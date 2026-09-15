"""Copy read-back actual CUA resources into a retained isolated component schema; no actual mutations."""
import argparse,json
from pathlib import Path
from postgres_component_probe import run
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--classes',type=Path,required=True);p.add_argument('--jar-path',type=Path,required=True);p.add_argument('--file-id',required=True);p.add_argument('--original',type=Path,required=True);a=p.parse_args()
if not __import__('re').fullmatch('[0-9a-f-]{36}',a.file_id):raise SystemExit('Expected actual UUID file id')
f=a.file_id
extra=f'''
-- Explicit isolated clone of the recorded FAILED-after-publication CUA task. No normal DB writes.
INSERT INTO enterprise_user SELECT u.* FROM public.enterprise_user u JOIN public.ingestion_batch b ON b.created_by=u.user_id JOIN public.ingestion_file f USING(batch_id) WHERE f.file_id='{f}';
INSERT INTO document SELECT d.* FROM public.document d JOIN public.ingestion_file f USING(document_id) WHERE f.file_id='{f}';
INSERT INTO document_version SELECT v.* FROM public.document_version v JOIN public.ingestion_file f USING(version_id) WHERE f.file_id='{f}';
INSERT INTO document_metadata SELECT m.* FROM public.document_metadata m JOIN public.ingestion_file f USING(version_id) WHERE f.file_id='{f}';
INSERT INTO ingestion_batch SELECT b.* FROM public.ingestion_batch b JOIN public.ingestion_file f USING(batch_id) WHERE f.file_id='{f}';
INSERT INTO ingestion_file SELECT (jsonb_populate_record(NULL::ingestion_file,to_jsonb(f)||jsonb_build_object('upload_session_id',null))).* FROM public.ingestion_file f WHERE file_id='{f}';
INSERT INTO kb_upload_session SELECT s.* FROM public.kb_upload_session s JOIN public.ingestion_file f ON f.upload_session_id=s.session_id WHERE f.file_id='{f}';
UPDATE ingestion_file f SET upload_session_id=s.session_id FROM kb_upload_session s WHERE s.file_id=f.file_id;
INSERT INTO raw_publication SELECT p.* FROM public.raw_publication p JOIN public.ingestion_file f USING(version_id) WHERE f.file_id='{f}';
INSERT INTO raw_publication_member SELECT p.* FROM public.raw_publication_member p JOIN public.ingestion_file f USING(version_id) WHERE f.file_id='{f}';
INSERT INTO raw_ingestion_failure SELECT r.* FROM public.raw_ingestion_failure r WHERE r.file_id='{f}';
'''
raise SystemExit(run(a.output,source_name='RawCompletedInputGuardProbe.java',prefix='raw_closure_',extra_sql=extra,
                     runtime_args=(a.file_id,str(a.original.resolve())),timeout=40,jar_path=a.jar_path,classes_path=a.classes,source_migrations=False))
