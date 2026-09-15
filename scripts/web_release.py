"""Publish verified web assets atomically, retaining hashed chunks used by already-open tabs."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def build(root: Path, private: Path, log: Path):
    root, private, log = root.resolve(), private.resolve(), log.resolve()
    if log.exists():
        raise ValueError('Use a fresh build log; retain every failed attempt')
    private.mkdir(parents=True, exist_ok=True)
    private.chmod(0o700)
    candidate = private / ('web-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    sources = source_manifest(root)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('xb') as stream:
        subprocess.run(['npm', 'run', 'lint'], cwd=root / 'web', stdout=stream, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['npm', 'run', 'build', '--', '--outDir', str(candidate)], cwd=root / 'web',
                       stdout=stream, stderr=subprocess.STDOUT, check=True)
    if not (candidate / 'index.html').is_file():
        raise ValueError('The build did not produce an entry page')
    if sources != source_manifest(root):
        raise ValueError('Frontend build inputs changed during verification; candidate retained without publication')
    published = root / 'web/dist'
    published.mkdir(parents=True, exist_ok=True)
    for source in candidate.iterdir():
        if source.name == 'index.html':
            continue
        destination = published / source.name
        if source.is_dir():
            shutil.copytree(source, destination, dirs_exist_ok=True)
        else:
            shutil.copy2(source, destination)
    pending = published / '.index.html.pending'
    shutil.copy2(candidate / 'index.html', pending)
    os.replace(pending, published / 'index.html')
    record = {
        'status': 'PASS_FRONTEND_LINT_BUILD_AND_PUBLICATION',
        'actualOperationTime': datetime.now(timezone.utc).isoformat(),
        'buildLogSha256': hashlib.sha256(log.read_bytes()).hexdigest(), 'candidate': str(candidate),
        'files': {str(p.relative_to(candidate)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in sorted(candidate.rglob('*')) if p.is_file()},
        'sources': sources,
        'boundary': 'Assets copied before the entry page. Prior hashed assets retained. No backend restart.'}
    (private / 'web-release.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({k: v for k, v in record.items() if k not in ['files', 'sources']}))
    return record


def source_manifest(root: Path):
    files = [p for p in (root / 'web/src').rglob('*') if p.is_file()]
    files += [p for p in (root / 'web/public').rglob('*') if p.is_file()]
    files += [p for p in (root / 'web').iterdir() if p.is_file()
              and (p.name in {'package.json','package-lock.json','index.html','vite.config.ts','eslint.config.js'}
                   or p.name.startswith('tsconfig'))]
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}
