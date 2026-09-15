#!/usr/bin/env python3
"""Small deterministic two-page digital/scanned continuation fixture and independent gold."""
import argparse
import hashlib
import json
import textwrap
from pathlib import Path

import fitz
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh fixture directory required; retain earlier originals')
a.output.mkdir(parents=True)
width, height = A4
xs = [32, 144, 232, 306, 562]
top, header_bottom = 672, 616
rows = [
    [('HT-SCAN-700', '900.00', '60', 'Valid invoice AND completed acceptance.'),
     ('HT-SCAN-701', '120.50', '30', 'Valid invoice AND written acceptance.')],
    [('HT-SCAN-702', '50.50', 'Deferred', 'No invoice: payment cannot be requested.'),
     ('HT-SCAN-703', '88.80', '15', 'Valid invoice AND completed acceptance.')],
]


def draw_page(c, page):
    c.setFont('Helvetica-Bold', 17)
    c.drawString(32, 791, 'Payment schedule for synthetic acceptance')
    c.setFont('Helvetica', 11)
    c.drawString(32, 766, 'Synthetic fixture only. No real contract or payment instruction.')
    c.drawString(32, 744, 'Table 1 - Payment schedule' + (' (continued)' if page else ''))
    c.drawString(32, 722, 'Every amount uses CNY 10,000. Deadlines are conditional, never absolute.')
    c.drawString(32, 700, 'The repeated header belongs to the same table; preserve each row identity.')
    bottom = header_bottom - 2 * 74
    for x in xs:
        c.line(x, bottom, x, top - 28 if x == xs[3] else top)
    for y in [top, header_bottom, header_bottom - 74, bottom]:
        c.line(xs[0], y, xs[-1], y)
    c.line(xs[2], top - 28, xs[-1], top - 28)
    c.setFont('Helvetica-Bold', 10)
    c.drawString(xs[0] + 6, top - 31, 'Contract')
    c.drawString(xs[1] + 6, top - 25, 'Amount')
    c.drawString(xs[1] + 6, top - 40, '(CNY 10,000)')
    c.drawCentredString((xs[2] + xs[-1]) / 2, top - 19, 'Payment terms')
    c.drawString(xs[2] + 5, top - 45, 'Due days')
    c.drawString(xs[3] + 6, top - 45, 'Prerequisites / exception')
    c.setFont('Helvetica', 10)
    for index, row in enumerate(rows[page]):
        y = header_bottom - index * 74 - 25
        for column, value in enumerate(row):
            lines = textwrap.wrap(value, 40 if column == 3 else 15)
            for line_index, line in enumerate(lines):
                c.drawString(xs[column] + 6, y - line_index * 16, line)
    c.setFont('Helvetica-Bold', 11)
    c.drawString(32, 436, 'No invoice: no payment obligation. Do not omit this condition.')
    c.setFont('Helvetica', 11)
    c.drawString(32, 413, 'Written acceptance requires continuous equipment testing for 72 hours.')
    c.drawString(32, 390, 'Warranty is 24 months for HT-SCAN-700; 12 months for HT-SCAN-701.')
    c.drawString(32, 367, 'Never attach the deferred HT-SCAN-702 exception to HT-SCAN-703.')
    c.setFont('Helvetica', 9)
    c.drawString(32, 39, 'Page ' + str(page + 1) + ' / 2 - ' + ('raster scanned continuation' if page else 'digital original'))


digital_source = a.output / 'digital-source.pdf'
c = canvas.Canvas(str(digital_source), pagesize=A4, invariant=1)
draw_page(c, 0)
c.showPage()
draw_page(c, 1)
c.save()
with fitz.open(digital_source) as document:
    scanned = document[1].get_pixmap(matrix=fitz.Matrix(2.5, 2.5), alpha=False)
    scanned.save(a.output / 'scanned-page-source.png')

original = a.output / 'scanned-cross-page-payment-schedule.pdf'
c = canvas.Canvas(str(original), pagesize=A4, invariant=1)
draw_page(c, 0)
c.showPage()
c.drawImage(ImageReader(str(a.output / 'scanned-page-source.png')), 0, 0, width=width, height=height)
c.save()
with fitz.open(original) as document:
    page_text = [page.get_text() for page in document]
    page_images = [len(page.get_images()) for page in document]
    assert page_text[0] and not page_text[1], 'Second page must actually require OCR'
    assert page_images == [0, 1], 'No hidden digital text under the raster scan'
    for index, page in enumerate(document):
        page.get_pixmap(matrix=fitz.Matrix(1.4, 1.4), alpha=False).save(a.output / f'page-{index + 1}-preview.png')

gold = {
    'status': 'FIXTURE_READY_NOT_PARSED',
    'path': str(original.resolve()),
    'sha256': hashlib.sha256(original.read_bytes()).hexdigest(),
    'bytes': original.stat().st_size,
    'pages': 2,
    'pageSizePoints': list(A4),
    'pageTableRegionPoints': [xs[0], height - top, xs[-1], height - (header_bottom - 148)],
    'pageKinds': ['digital', 'raster-only-scanned'],
    'pageExtractableTextCharacters': [len(text) for text in page_text],
    'pageImageCounts': page_images,
    'tableCaption': 'Table 1 - Payment schedule',
    'expectedHeader': ['Contract', 'Amount', 'CNY 10,000', 'Payment terms', 'Due days', 'Prerequisites / exception'],
    'expectedRows': [{'pageIndex': page, 'contract': row[0], 'amount': row[1], 'dueDays': row[2], 'condition': row[3]} for page in range(2) for row in rows[page]],
    'expectedCriticalText': ['No invoice: no payment obligation', '72 hours', '24 months', '12 months', 'written acceptance'],
    'mergeBoundary': 'One logical continued table across pages, repeated multi-level header (Contract/Amount rowspan2; Payment terms colspan2). Physical page/table blocks may remain separate, but page identity, unit, prerequisites and contract-specific rows must survive; no row-level mixing or manufactured due date for Deferred. This fixture checks two pages and one continuation, not arbitrary complex scans.',
    'builderSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'artifacts': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in a.output.iterdir() if path.is_file()},
}
(a.output / 'gold.json').write_text(json.dumps(gold, ensure_ascii=False, indent=2))
print(json.dumps({key: gold[key] for key in ['status', 'path', 'sha256', 'bytes', 'pageKinds', 'pageExtractableTextCharacters']}, ensure_ascii=False))
