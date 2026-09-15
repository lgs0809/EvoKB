#!/usr/bin/env python3
"""Run frozen evolution mechanisms against the deployed artifact and real isolated PostgreSQL."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from postgres_component_probe import ROOT, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--case', action='append', default=[])
    args = parser.parse_args()
    manifest_bytes = args.manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    cases = [case for case in manifest['cases'] if not args.case or case['caseId'] in args.case]
    if len(manifest['cases']) != 30 or Counter(case['split'] for case in manifest['cases']) != {'development': 19, 'holdout': 11}:
        raise ValueError('Require the original thirty-case split')
    if not cases or set(args.case) - {case['caseId'] for case in cases}:
        raise ValueError('Unknown frozen case selection')
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=False)
    process = json.loads((ROOT / '.tmp/target/process.json').read_text())
    jar = Path(process['artifact'])
    if hashlib.sha256(jar.read_bytes()).hexdigest() != process['jarSha256']:
        raise ValueError('Deployed immutable artifact changed')
    sources = {name: hashlib.sha256((ROOT / 'scripts' / name).read_bytes()).hexdigest()
               for name in ['EvolutionThirtyProbe.java', 'SelectiveGenerationProbe.java', 'postgres_component_probe.py']}
    report = dict(status='RUNNING_NATIVE_EVOLUTION_MATRIX', startedAt=datetime.now(timezone.utc).isoformat(),
                  manifestSha256=hashlib.sha256(manifest_bytes).hexdigest(), readerArtifact=process,
                  helperSourceSha256=sources, declaredCases=[case['caseId'] for case in cases], attempts=[],
                  modelCalls=0, businessSqlMutations=0, plannedCases=len(cases),
                  boundary=manifest['scope'])

    def persist():
        report['counts'] = dict(Counter(attempt['outcome'] for attempt in report['attempts']))
        report['notStartedCases'] = len(cases) - len(report['attempts'])
        (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    persist()
    for case in cases:
        if args.manifest.read_bytes() != manifest_bytes or any(hashlib.sha256((ROOT / 'scripts' / name).read_bytes()).hexdigest() != sha for name, sha in sources.items()):
            report['failure'] = 'Frozen case or compiled helper bytes changed; no further admission'; break
        destination = output / (case['caseId'] + '.json')
        attempt = dict(caseId=case['caseId'], split=case['split'], outcome='IN_FLIGHT', startedAt=datetime.now(timezone.utc).isoformat())
        report['attempts'].append(attempt); persist()
        try:
            rc = run(destination, source_name='EvolutionThirtyProbe.java', additional_sources=('SelectiveGenerationProbe.java',),
                     prefix='evokb_evolution30_', jar_path=jar,
                     extra_sql="INSERT INTO enterprise_user(user_id,username,password_hash,display_name,role,security_clearance,status) VALUES('quota-user','quota-component-fixture','synthetic-not-a-login-credential','Synthetic component','USER',10,'ACTIVE');\n",
                     runtime_args=(json.dumps(case, ensure_ascii=False, separators=(',', ':')),), timeout=180)
            result = json.loads(destination.read_text())
            attempt.update(outcome='PASS' if rc == 0 and result['status'] == 'PASS_NATIVE_EVOLUTION_CASE' else 'FAIL',
                           report=str(destination), postgresSchema=result['postgresSchema'], checks=result['checks'])
        except Exception as failure:
            attempt.update(outcome='ERROR', failureType=type(failure).__name__, failure=str(failure))
        attempt['finishedAt'] = datetime.now(timezone.utc).isoformat(); persist()
    report['status'] = ('PASS_NATIVE_EVOLUTION_MATRIX' if len(report['attempts']) == len(cases)
                        and all(attempt['outcome'] == 'PASS' for attempt in report['attempts']) else 'FAIL_OR_PARTIAL_NATIVE_EVOLUTION_MATRIX')
    report['runtimeUnchangedDuringMatrix'] = json.loads((ROOT / '.tmp/target/process.json').read_text()) == process
    report['finishedAt'] = datetime.now(timezone.utc).isoformat(); persist()
    print(json.dumps({key: report[key] for key in ['status', 'counts', 'notStartedCases', 'runtimeUnchangedDuringMatrix']}, ensure_ascii=False))
    raise SystemExit(int(report['status'] != 'PASS_NATIVE_EVOLUTION_MATRIX' or not report['runtimeUnchangedDuringMatrix']))


if __name__ == '__main__':
    main()
