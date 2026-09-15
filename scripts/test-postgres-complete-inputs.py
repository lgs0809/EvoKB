"""Frozen native Wiki/Graph complete-input authority regressions in retained isolated PG schemas."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from postgres_component_probe import run

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar-path', type=Path, required=True)
    parser.add_argument('--classes-path', type=Path,
                        help='Explicit frozen candidate overlay; no deployment claim')
    parser.add_argument('--candidate-migration', type=Path,
                        help='Explicit V22 snapshot only when the chosen immutable artifact predates it')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Fresh report required; retain original failures')
    migration = args.candidate_migration.read_bytes() if args.candidate_migration else b''
    with zipfile.ZipFile(args.jar_path) as archive:
        has_v22 = 'BOOT-INF/classes/db/migration/V22__complete_publication_inputs.sql' in archive.namelist()
    if bool(migration) == has_v22:
        raise ValueError('V22 must come from exactly one declared source: frozen artifact or explicit candidate snapshot')
    if migration and args.classes_path is None:
        raise ValueError('Candidate migration requires explicit candidate classes; never mix with an old deployed implementation')
    sources = [
        ('source-access', 'SourceAccessProbe.java', """
INSERT INTO document_unit(unit_id,version_id,unit_no) VALUES('unit-public','version-public',1),('unit-secret','version-secret',1);
INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle)
VALUES('summary-public','unit-public','version-public','component-only','component-only','PASS','PASS','ACTIVE'),
('summary-secret','unit-secret','version-secret','component-only','component-only','PASS','PASS','ACTIVE');
INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status)
VALUES('fact-public','summary-public','public','Synthetic public component fact','PASS');
"""),
        ('graph', 'PostgresGraphProbe.java', ''),
        ('task-inputs', 'TaskInputAccessProbe.java', ''),
    ]
    results = []
    for label, source, seed in sources:
        output = args.output.with_name(args.output.stem+'-'+label+args.output.suffix)
        code = run(output, source_name=source, prefix='evokb_complete_input_',
                   jar_path=args.jar_path, classes_path=args.classes_path,
                   extra_sql=migration.decode()+seed)
        result = json.loads(output.read_text())
        results.append(dict(label=label, resultPath=str(output), exitCode=code,
                            status=result['status'], checks=result.get('checks', {}),
                            schema=result['postgresSchema']))
        if code:
            break
    report = dict(status='PASS_NATIVE_COMPLETE_INPUT_AUTHORITY' if len(results)==3 and all(r['exitCode']==0 for r in results)
                  else 'FAIL_NATIVE_COMPLETE_INPUT_AUTHORITY', finishedAt=datetime.now(timezone.utc).isoformat(),
                  candidateV22Sha256=hashlib.sha256(migration).hexdigest() if migration else None,
                  candidateMigrationProvenance='EXPLICIT_FROZEN_SNAPSHOT' if migration else 'FROZEN_ARTIFACT',
                  results=results, deploymentClaim=False,
                  boundary='Actual native repositories/graph adapter in retained synthetic PG schemas. No model, MinIO/Milvus, production task/status, UI or deployment acceptance claim.')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(dict(status=report['status'], passed=sum(sum(bool(v) for v in r['checks'].values()) for r in results),
                          failed=[k for r in results for k,v in r['checks'].items() if not v]), ensure_ascii=False))
    raise SystemExit(0 if report['status'].startswith('PASS') else 1)


if __name__ == '__main__':
    main()
