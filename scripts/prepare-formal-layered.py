#!/usr/bin/env python3
"""Prepare the formal layered arm through the normal durable compilation API."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import time
from uuid import UUID


from formal_target import FormalTarget, retrieval_service_snapshot

TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    FormalTarget.add_selection(parser)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--observe-seconds", type=int, default=3600)
    args = parser.parse_args()
    target = FormalTarget.selected(args)
    args.output.mkdir(parents=True, exist_ok=False)
    manifest_bytes = args.manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    expected = [case["caseId"] for case in manifest["cases"]
                if case["split"] == "holdout" and case["category"] == "complex"]
    deadline = time.monotonic() + args.observe_seconds
    report = {"status": "WAITING_FOR_RAW_BASELINE", "tasks": {}, "observed": {}}

    def save():
        staged = args.output / "summary.json.tmp"
        staged.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        staged.replace(args.output / "summary.json")

    def pause():
        if time.monotonic() >= deadline:
            raise TimeoutError("Observation deadline reached. Durable jobs keep their normal retry schedule.")
        time.sleep(5)

    save()
    try:
        while True:
            baseline = json.loads(args.baseline.read_text())
            if baseline["status"] != "RUNNING_ACTUAL_FORMAL_QUERIES":
                break
            pause()
        snapshot = baseline["sourceSnapshot"]
        if (baseline["condition"] != "baseline" or baseline["status"] != "COMPLETED_ACTUAL_FORMAL_QUERIES"
                or baseline["declaredCases"] != expected or baseline["unstartedAttempts"]
                or baseline["manifestSha256"] != hashlib.sha256(manifest_bytes).hexdigest()
                or snapshot["derived"] or snapshot["graph"] or snapshot.get("summaries")
                or len(baseline["attempts"]) != baseline["plannedAttempts"]):
            raise ValueError("Complete frozen Raw-only holdout receipt required")
        def require_same_runtime():
            if target.process() != baseline["process"]:
                raise ValueError("Use the same deployed artifact and process for both arms")
            if retrieval_service_snapshot() != baseline["runtimeSnapshot"]["retrievalService"]:
                raise ValueError("Retrieval process changed since the Raw arm")

        require_same_runtime()
        pg = target.pg
        api, _ = target.client(target.accounts()["admin"])
        all_versions = ",".join("'" + str(UUID(row["version_id"])) + "'" for row in snapshot["sources"])
        cutoff = datetime.fromisoformat(baseline["finishedAt"]).isoformat()
        raw_sql = f"""SELECT jsonb_build_object('groundedSummaries',count(*)) FROM summary
  WHERE version_id IN ({all_versions}) AND grounding_status='PASS' AND coverage_status='PASS'
    AND created_at <= TIMESTAMPTZ '{cutoff}';"""
        (args.output / "raw-no-summary.sql").write_text(raw_sql + "\n")
        report["rawNoSummary"] = pg(raw_sql)
        if report["rawNoSummary"]["groundedSummaries"]:
            raise ValueError("A historical Summary exists for the frozen Raw sources")
        # Every case's scope includes one of these sources. Each Concept uses one
        # source to keep it visible under the unchanged case-specific filters.
        sources = [row for row in snapshot["sources"] if row["lifecycle"] == "ACTIVE"
                   and re.fullmatch(r"EKB-F(?:08|09|10)-(?:采购合同|补充协议)\.md", row["original_filename"])]
        if len(sources) != 6:
            raise ValueError("Expected the six immutable active formal sources")
        report.update(status="COMPILING", baselineSha256=hashlib.sha256(args.baseline.read_bytes()).hexdigest())
        for source in sources:
            require_same_runtime()
            version = str(UUID(source["version_id"]))
            task = report["tasks"][version] = {"filename": source["original_filename"]}
            code, body = api("/compilation/summary", {"version_id": version})
            if code != 202:
                raise ValueError(f"Normal Summary command failed with HTTP {code}")
            task["summary"] = str(UUID(body.get("task_id", body.get("taskId"))))
            save()

        while True:
            require_same_runtime()
            ids = [task["summary"] for task in report["tasks"].values()]
            versions = ",".join("'" + str(UUID(source["version_id"])) + "'" for source in sources)
            literals = ",".join("'" + task_id + "'" for task_id in ids)
            sql = f"""SELECT jsonb_build_object(
  'tasks',(SELECT coalesce(jsonb_object_agg(task_id,jsonb_build_object(
     'status',status,'attempt',attempt,'errorCode',error_code)),'{{}}') FROM compile_task WHERE task_id IN ({literals})),
  'summaries',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',summary_id,'version',version_id,'hash',content_hash)
     ORDER BY summary_id),'[]') FROM summary WHERE version_id IN ({versions})
     AND lifecycle='ACTIVE' AND grounding_status='PASS' AND coverage_status='PASS'),
  'conceptTasks',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',t.task_id,'version',t.version_id,
     'summary',s.summary_id,'status',t.status,'attempt',t.attempt,'errorCode',t.error_code) ORDER BY t.task_id),'[]')
     FROM compile_task t JOIN summary s USING(version_id) JOIN document_metadata m USING(version_id)
     WHERE t.task_type='CONCEPT' AND t.version_id IN ({versions})
       AND s.lifecycle='ACTIVE' AND s.grounding_status='PASS' AND s.coverage_status='PASS'
       AND t.subject_id=concat('summary/',s.summary_id,'/acl/',m.acl_version,'/content/',s.content_hash)),
  'conceptSources',(SELECT coalesce(jsonb_agg(DISTINCT a.source_version_id),'[]') FROM kb_asset_current c
     JOIN kb_derived_asset b USING(asset_id,asset_version) JOIN kb_asset_source a USING(asset_id,asset_version)
     WHERE b.stage='CONCEPT' AND a.source_version_id IN ({versions})
     AND 1=(SELECT count(*) FROM kb_asset_dependency d WHERE d.asset_id=c.asset_id AND d.asset_version=c.asset_version)));
"""
            (args.output / "readback.sql").write_text(sql)
            observed = report["observed"] = pg(sql)
            for version, task in report["tasks"].items():
                # The normal Summary worker has already queued the Concept for each grounded
                # revision. Follow that durable handoff instead of submitting a parallel command.
                task["concepts"] = [row for row in observed["conceptTasks"] if row["version"] == version]
            save()
            if any(row["status"] in TERMINAL - {"SUCCEEDED"} for row in observed["tasks"].values()):
                raise ValueError("A durable compilation failed. Preserve the first result and its task identity.")
            completed_concepts = {row["version"] for row in observed["conceptTasks"] if row["status"] == "SUCCEEDED"}
            if (set(observed["conceptSources"]) == completed_concepts == set(report["tasks"])
                    and len(observed["tasks"]) == len(report["tasks"])
                    and all(row["status"] == "SUCCEEDED" for row in observed["tasks"].values())
                    and all(row["status"] in TERMINAL for row in observed["conceptTasks"])):
                break
            pause()
        report["status"] = "PASS_FORMAL_LAYERED_PUBLICATION"
    except Exception as error:
        report.update(status="INCOMPLETE_FORMAL_LAYERED_PUBLICATION", error=str(error))
        raise
    finally:
        save()
    print(report["status"], flush=True)


if __name__ == "__main__":
    main()
