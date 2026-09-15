"""Keep a real small business DOCX, and a clearly labeled >64MiB transport copy.
The copy contains an unreferenced, typed deterministic ZIP part. It tests transport,
not the complexity of Word content; both have identical business XML/table/image.
"""
import argparse,hashlib,json,shutil,uuid,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--original',type=Path,required=True);p.add_argument('--gold',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh fixture directory required')
a.output.mkdir();suffix=uuid.uuid4().hex[:10];small=a.output/f'原件分片业务验收-{suffix}.docx';large=a.output/f'原件四分片传输验收-{suffix}.docx';shutil.copyfile(a.original,small)
with zipfile.ZipFile(small) as src,zipfile.ZipFile(large,'w') as dst:
 for member in src.infolist():
  data=src.read(member.filename)
  if member.filename=='[Content_Types].xml':
   root=ET.fromstring(data);ns='http://schemas.openxmlformats.org/package/2006/content-types';ET.SubElement(root,'{'+ns+'}Default',Extension='bin',ContentType='application/octet-stream');data=ET.tostring(root,encoding='utf-8',xml_declaration=True)
  dst.writestr(member,data)
 fixture=zipfile.ZipInfo('evokb-transport-fixture/deterministic-padding.bin');fixture.compress_type=zipfile.ZIP_STORED
 with dst.open(fixture,'w',force_zip64=True) as out:
  block=bytes(range(256))*4096
  for _ in range(64):out.write(block)
for path in [small,large]:
 digest=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(65536),b''):digest.update(block)
 record=dict(filename=path.name,path=str(path.resolve()),sizeBytes=path.stat().st_size,sha256=digest.hexdigest(),expectedParts=(path.stat().st_size+16777215)//16777216)
 (a.output/(path.name+'.identity.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2))
shutil.copyfile(a.gold,a.output/'business-gold.json');(a.output/'fixture-boundary.json').write_text(json.dumps(dict(boundary='Real DOCX business source; >64MiB copy has explicitly labeled unreferenced typed ZIP transport padding. No fabricated model/parse result. Large file verifies byte transport/resume only; business content is compared to independent gold.',sourceOriginal=str(a.original),sourceSha256=hashlib.sha256(a.original.read_bytes()).hexdigest(),businessXmlUnchanged=True),ensure_ascii=False,indent=2))
print(json.dumps(dict(status='READY_SYNTHETIC_LOCAL_BUSINESS_AND_TRANSPORT_FIXTURES',smallBytes=small.stat().st_size,largeBytes=large.stat().st_size,largeParts=5),ensure_ascii=False))
