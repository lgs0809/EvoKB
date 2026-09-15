"""Loopback runtime binding shared by the formal seed, compilation and query tools."""
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
POSTGRES = 'evokb-target-postgres-1'
ISOLATED_TARGETS = {
    'holdout': ('evokb_formal_holdout', 18483, 'formal-target', 11),
    'holdout-replay': ('evokb_formal_holdout_replay', 18485, 'formal-target-replay', 12),
}


class FormalTarget:
    def __init__(self, isolated=False, name=None):
        name = name or ('holdout' if isolated else 'managed')
        if name == 'managed':
            self.database, self.port, directory, self.redis_database = 'evokb_target', 18481, 'target', 0
        else:
            self.database, self.port, directory, self.redis_database = ISOLATED_TARGETS[name]
        self.base = f'http://127.0.0.1:{self.port}'
        self.private = ROOT / '.tmp' / directory
        self.bucket = self.database.replace('_', '-')
        self.stream = f'{self.bucket.replace("evokb-", "evokb:", 1)}:raw:v1'
        self.session_cookie = 'ekb_' + self.database.removeprefix('evokb_')

    @staticmethod
    def add_selection(parser):
        group = parser.add_mutually_exclusive_group()
        group.add_argument('--isolated', action='store_true', help='Use the original local holdout target')
        group.add_argument('--target', choices=ISOLATED_TARGETS, help='Select a named local holdout target')

    @classmethod
    def selected(cls, args):
        return cls(isolated=args.isolated, name=args.target)

    def process(self):
        process = json.loads((self.private / 'process.json').read_text())
        birth = subprocess.run(['ps', '-p', str(process['pid']), '-o', 'lstart='],
                               text=True, capture_output=True, timeout=5).stdout.strip()
        if not birth or birth != process['processBirth'] or process['url'] != self.base:
            raise ValueError('The selected formal deployment is no longer running')
        if self.redis_database and process.get('database') != self.database:
            raise ValueError('The formal process receipt does not match the selected database')
        if self.redis_database and any(process.get(key) != expected for key, expected in {
                'redisDatabase': self.redis_database, 'collection': self.database, 'bucket': self.bucket,
        }.items()):
            raise ValueError('The formal process receipt does not match the selected dependency namespace')
        return process

    def accounts(self):
        return json.loads((self.private / 'permission-accounts.json').read_text())

    def client(self, account):
        spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.client(account, base=self.base)

    def psql(self, sql):
        return subprocess.run(['docker', 'exec', '-i', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
                               POSTGRES, 'psql', '-XAt',
                               '-U', 'evokb_target', '-d', self.database, '-v', 'ON_ERROR_STOP=1'],
                              input=sql, text=True, capture_output=True, timeout=30)

    def pg(self, sql):
        result = self.psql(sql)
        result.check_returncode()
        return json.loads(result.stdout)


def retrieval_service_snapshot():
    """Bind a campaign to the actual local image/runtime without loading a model."""
    result = subprocess.run(['docker', 'inspect', 'embedding-model'],
                            text=True, capture_output=True, timeout=30, check=True)
    rows = json.loads(result.stdout)
    if len(rows) != 1 or not rows[0]['State']['Running']:
        raise ValueError('The ordinary local retrieval container must be running')
    container = rows[0]
    allowlist = {'EMBEDDING_MODEL_ID', 'RERANK_MODEL_ID', 'EMBEDDING_DIMENSIONS',
                 'MODEL_DEVICE', 'INFERENCE_CPU_THREADS', 'INFERENCE_INTEROP_THREADS',
                 'INFERENCE_CPU_ATTENTION', 'HF_HUB_OFFLINE', 'TRANSFORMERS_OFFLINE'}
    settings = {key: value for entry in container['Config'].get('Env', [])
                for key, _, value in [entry.partition('=')] if key in allowlist}
    if (settings.get('EMBEDDING_MODEL_ID') != 'Qwen/Qwen3-VL-Embedding-2B'
            or settings.get('RERANK_MODEL_ID') != 'Qwen/Qwen3-VL-Reranker-2B'):
        raise ValueError('Use the approved Qwen models in the existing retrieval container')
    return dict(containerId=container['Id'], imageId=container['Image'],
                startedAt=container['State']['StartedAt'], restartCount=container['RestartCount'],
                settings=settings,
                boundary='Read-only Docker identity; no loading, embedding or reranking. '
                         'Container image and allowlisted execution settings are frozen. '
                         'This does not retroactively certify individual historical vectors.')
