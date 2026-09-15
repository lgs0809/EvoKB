"""Read-only artifact/local assets/PG audit; no model requests or business writes."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--verification', type=Path, required=True)
p.add_argument('--actual-encoding-response', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh result required')
a.output.parent.mkdir(parents=True, exist_ok=True)
verification = json.loads(a.verification.read_text())
jar = R / '.tmp/target/artifacts' / verification['jarSha256'] / 'evokb.jar'
sha = lambda data: hashlib.sha256(data).hexdigest()
if sha(jar.read_bytes()) != verification['jarSha256'] or not verification.get('sourcesStableDuringVerification'):
    raise SystemExit('Stable verified immutable artifact required')
settings = dict(line.split('=', 1) for line in (R / '.env.target').read_text().splitlines()
                if line and not line.startswith('#'))
names = ['java/ekb-bootstrap/src/main/java/cn/lgs/knowledgebase/EnterpriseConfiguration.java',
         'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/FrozenRawEncodingConfiguration.java',
         'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/LocalEmbeddingTokenizer.java',
         'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw/RawEncodingProfile.java',
         'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw/RawVectorEncoder.java',
         'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/JdbcRawVectorCache.java',
         'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/JdbcRawPublicationWriter.java',
         'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/OpenAiCompatibleModelServingAdapter.java']
sources = {name: sha((R / name).read_bytes()) for name in names}
source_checks = {name: verification['backendSources'].get(name) == digest for name, digest in sources.items()}
class_sha = {}
with zipfile.ZipFile(jar) as archive:
    for name in archive.namelist():
        if name.startswith('BOOT-INF/lib/ekb-') and name.endswith('.jar'):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as module:
                for cls in module.namelist():
                    if cls.endswith('.class') and any(Path(n).stem in cls for n in names):
                        class_sha[name + '!/' + cls] = sha(module.read(cls))
        elif name == 'BOOT-INF/classes/cn/lgs/knowledgebase/EnterpriseConfiguration.class':
            class_sha[name] = sha(archive.read(name))
manifest_path = Path(settings['EKB_EMBED_TOKENIZER_MANIFEST'])
manifest = json.loads(manifest_path.read_text())
assets = {name: sha((manifest_path.parent / name).read_bytes()) for name in manifest['files']}
sql = "BEGIN READ ONLY; SELECT row_to_json(p) FROM (SELECT profile_id,embedding_base_url,embedding_model,embedding_dimensions,embedding_batch_size,embedding_timeout_seconds FROM model_configuration_profile WHERE active) p; COMMIT;"
a.output.with_suffix('.sql').write_text(sql)
pg = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U', 'evokb_target',
                     '-d', 'evokb_target', '-At', '-v', 'ON_ERROR_STOP=1'], input=sql,
                    text=True, capture_output=True, timeout=10)
a.output.with_suffix('.log').write_text(pg.stdout + pg.stderr)
pg.check_returncode()
effective = [json.loads(line) for line in pg.stdout.splitlines() if line.startswith('{')]
recorded = json.loads(a.actual_encoding_response.read_text())
atomic = recorded.get('atomicEncodingIdentity') or recorded['response']['encoding_identity']
frozen = (R / names[1]).read_text()
dto = (R / names[-1]).read_text()
report = {'status': 'READ_ONLY_AUDIT_REQUIRES_REVIEW', 'observedAt': datetime.now(timezone.utc).isoformat(),
          'jarSha256': verification['jarSha256'], 'verificationSha256': sha(a.verification.read_bytes()),
          'productionSourceSha256': sources, 'productionSourcesMatchVerifiedArtifactManifest': source_checks,
          'immutableClassSha256': class_sha, 'tokenizerManifestPath': str(manifest_path),
          'tokenizerManifestSha256': sha(manifest_path.read_bytes()), 'sixAssetHashes': assets,
          'sixAssetHashesMatchManifest': assets == manifest['files'],
          'localModelRevision': manifest['modelRevision'], 'localTokenizerRevision': manifest['tokenizerRevision'],
          'effectiveEmbeddingConfigurationNoCredentials': effective,
          'separatePreviouslyRecordedProfile': {key: atomic[key] for key in ('schema', 'profile_sha256', 'profile')},
          'separateEncodingProofSha256': sha(a.actual_encoding_response.read_bytes()),
          'sourceInspectionHintsNotRuntimeProof': {'frozenConfigurationContainsAtomicFields': 'encoding_identity' in frozen or 'pipeline_sha256' in frozen,
                                                 'embeddingResponseOnlyData': 'record EmbeddingResponse(List<EmbeddingItem> data)' in dto},
          'actualNewModelCallsByAudit': 0, 'businessMutations': 0,
          'boundary': 'Read-only immutable artifact and current matching source/PG/env/local assets. A previous response is explicitly separate, not runtime recertification of cache hits. Field inspection hints require source review and never prove absence of all future indirect wiring.'}
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({key: report[key] for key in ('status', 'jarSha256', 'productionSourcesMatchVerifiedArtifactManifest', 'sixAssetHashesMatchManifest', 'actualNewModelCallsByAudit')}, ensure_ascii=False))
