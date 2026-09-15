#!/usr/bin/env python3
"""Read normal compilation output without changing tasks, usage, publication or approvals."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    version = str(uuid.UUID(args.version))
    output = args.output.resolve()
    paths = [output, output.with_suffix('.sql'), output.with_suffix('.log')]
    if any(p.exists() for p in paths):
        raise ValueError('Use a fresh output stem and preserve every earlier observation')
    output.parent.mkdir(parents=True, exist_ok=True)
    sql = f"""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
  'tasks',(SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY created_at),'[]'::jsonb)
    FROM compile_task t WHERE version_id='{version}' OR EXISTS(
      SELECT 1 FROM kb_knowledge_entry e WHERE t.knowledge_base_id=e.knowledge_base_id
        AND t.subject_id='entry/'||e.entry_id||'/revision/'||e.revision::text
        AND (EXISTS(SELECT 1 FROM kb_entry_source s
          WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id
            AND s.source_version_id='{version}')
          OR EXISTS(SELECT 1 FROM kb_entry_pending_source p
            WHERE p.knowledge_base_id=e.knowledge_base_id AND p.entry_id=e.entry_id
              AND p.source_version_id='{version}')))),
  'entries',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.entry_id),'[]'::jsonb)
    FROM kb_knowledge_entry e WHERE EXISTS(SELECT 1 FROM kb_entry_source s
      WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id
        AND s.source_version_id='{version}')),
  'entrySources',(SELECT coalesce(jsonb_agg(to_jsonb(s)),'[]'::jsonb)
    FROM kb_entry_source s WHERE s.source_version_id='{version}'),
  'inputs',(SELECT coalesce(jsonb_agg(to_jsonb(b)),'[]'::jsonb)
    FROM kb_entry_build_input b WHERE b.source_version_id='{version}'),
  'pending',(SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]'::jsonb)
    FROM kb_entry_pending_source p WHERE p.source_version_id='{version}'),
  'summaries',(SELECT coalesce(jsonb_agg(to_jsonb(s)),'[]'::jsonb)
    FROM summary s WHERE s.version_id='{version}'),
  'current',(SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]'::jsonb)
    FROM kb_asset_current c WHERE EXISTS(SELECT 1 FROM kb_asset_source s
      WHERE s.asset_id=c.asset_id AND s.asset_version=c.asset_version
        AND s.source_version_id='{version}'))
);
COMMIT;
"""
    output.with_suffix('.sql').write_text(sql)
    result = subprocess.run(
        ['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
         '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
        input=sql, text=True, capture_output=True, timeout=30)
    output.with_suffix('.log').write_text(result.stdout + result.stderr)
    rows = [line for line in result.stdout.splitlines() if line.startswith('{')]
    data = json.loads(rows[0]) if result.returncode == 0 and len(rows) == 1 else None
    report = dict(
        status='OBSERVED_NATIVE_STATE' if data is not None else 'FAIL_NATIVE_INSPECTION',
        actualOperationTime=datetime.now(timezone.utc).isoformat(), sourceVersion=version,
        process=json.loads((ROOT / '.tmp/target/process.json').read_text()), database=data,
        sqlSha256=hashlib.sha256(sql.encode()).hexdigest(), exitCode=result.returncode,
        scope='Read-only actual local task, entry, dependency and publication observation. '
              'The reader artifact is not necessarily the generation artifact; bind execution '
              'start time before using it as generation proof. This is not an acceptance PASS.')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], sourceVersion=version,
                         tasks=[dict(type=t['task_type'], status=t['status'], attempt=t['attempt'])
                                for t in (data or {}).get('tasks', [])],
                         entries=len((data or {}).get('entries', [])))))
    raise SystemExit(result.returncode or int(data is None))


if __name__ == '__main__':
    main()
