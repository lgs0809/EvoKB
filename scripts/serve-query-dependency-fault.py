#!/usr/bin/env python3
"""Loopback-only deterministic 503 fixture; no upstream, model, credential or database writes."""
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise ValueError('Unprivileged loopback port required')
    args.output_dir.mkdir(parents=True, exist_ok=False)
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def handle_request(self):
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(length)
            health = self.command == 'GET' and self.path == '/health'
            status = 200 if health else 503
            observation = dict(actualOperationTime=datetime.now(timezone.utc).isoformat(),
                               method=self.command, path=self.path,
                               inputBytes=len(body), inputSha256=hashlib.sha256(body).hexdigest(),
                               httpStatus=status)
            with lock, (args.output_dir / 'requests.jsonl').open('a') as stream:
                stream.write(json.dumps(observation) + '\n')
            payload = json.dumps(dict(fixture='isolated-query-dependency-503') if health
                                 else dict(error=dict(message='isolated acceptance dependency unavailable'))).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(payload)

        do_GET = handle_request
        do_POST = handle_request

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    launch = dict(status='STARTED_ISOLATED_FAULT_FIXTURE', pid=os.getpid(),
                  actualOperationTime=datetime.now(timezone.utc).isoformat(),
                  baseUrl='http://127.0.0.1:' + str(args.port),
                  sourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  boundary='Every non-health request is explicitly synthetic HTTP503. No successful '
                           'model response, upstream call, source change or state write is provided. '
                           'Request bodies and headers are not logged; only sizes and SHA. '
                           'Stop only this recorded PID after restoring the normal model profile.')
    (args.output_dir / 'launch.json').write_text(json.dumps(launch, indent=2) + '\n')
    print(json.dumps(launch), flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
