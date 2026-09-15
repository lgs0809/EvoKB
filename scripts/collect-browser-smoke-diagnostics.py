#!/usr/bin/env python3
"""Keep bounded CI logs before teardown, without exporting container env or credentials."""
import argparse
import json
from pathlib import Path
import re
import subprocess


def private_values(path):
    if not path.is_file():
        return []
    values = []
    for line in path.read_text().splitlines():
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        if re.search(r'PASSWORD|SECRET|API_KEY|TOKEN', key, re.I) and value.strip():
            values.append(value.strip().strip('\"\''))
    return sorted(set(values), key=len, reverse=True)


def redact(text, values):
    for value in values:
        text = text.replace(value, '[REDACTED]')
    return re.sub(
        r'(?im)(\b(?:authorization|cookie|set-cookie)\s*[:=]\s*)[^\r\n]+',
        r'\1[REDACTED]', text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    parser.add_argument('--env-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    values = private_values(args.env_file)
    compose = ['docker', 'compose', '-p', args.project, '--env-file', str(args.env_file),
               '-f', 'deploy/docker-compose.yml']
    commands = [('containers.json', ['ps', '-a', '--format', 'json']),
                ('services.log', ['logs', '--no-color', '--tail', '400'])]
    outcomes = []
    for filename, suffix in commands:
        try:
            result = subprocess.run(compose + suffix, capture_output=True, text=True,
                                    timeout=30)
            content = result.stdout + result.stderr
            outcomes.append({'file': filename, 'exitCode': result.returncode})
        except subprocess.TimeoutExpired as error:
            content = 'Diagnostic command exceeded 30 seconds.\n'
            for chunk in [error.stdout, error.stderr]:
                if chunk:
                    content += chunk.decode(errors='replace') if isinstance(chunk, bytes) else chunk
            outcomes.append({'file': filename, 'status': 'TIMED_OUT'})
        target = args.output / filename
        target.write_text(redact(content, values))
        target.chmod(0o600)
    (args.output / 'collection.json').write_text(json.dumps({
        'project': args.project, 'commands': outcomes,
        'scope': 'Compose status and last 400 log lines per service; no env/inspect export',
    }, indent=2) + '\n')
    print('Saved bounded, redacted deployment diagnostics.')


if __name__ == '__main__':
    main()
