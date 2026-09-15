"""Prefer source-bound vector/text tables; retain MinerU for scans and uncertain geometry.

No OCR, inference, contract vocabulary or answer/oracle is used here. pdfplumber owns
line/cell discovery and text extraction; this module verifies its complete cell tiling
and source glyph preservation before selecting its output for a matching native table.
"""
from bisect import bisect_right
from collections import Counter
from copy import deepcopy
from hashlib import sha256
from html import escape
from importlib.metadata import PackageNotFoundError, version
import math

POLICY = 'native-vector-text-tables-v1'
DEPENDENCIES = {'pdfplumber': '0.11.9', 'pdfminer.six': '20251230'}


class NativeTableSelectionError(ValueError):
    code = 'QUALITY_REVIEW_REQUIRED'


def dependency_identity():
    try:
        actual = {name: version(name) for name in DEPENDENCIES}
    except PackageNotFoundError as missing:
        error = NativeTableSelectionError('Pinned native PDF text-table dependency is unavailable')
        error.code = 'DEPENDENCY_NOT_AVAILABLE'
        raise error from missing
    if actual != DEPENDENCIES:
        error = NativeTableSelectionError('Native PDF text-table dependency differs from pinned profile')
        error.code = 'PARSER_PROFILE_INVALID'
        raise error
    return actual


def _box(value):
    if not isinstance(value, (tuple, list)) or len(value) != 4:
        raise NativeTableSelectionError('Table coordinate must have four source positions')
    try:
        result = tuple(float(x) for x in value)
    except (TypeError, ValueError) as invalid:
        raise NativeTableSelectionError('Table source positions are not numeric') from invalid
    if not all(math.isfinite(x) for x in result) or result[2] <= result[0] or result[3] <= result[1]:
        raise NativeTableSelectionError('Table source positions are invalid')
    return result


def _intersection(a, b):
    return max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))


def _size(value):
    try:
        size = tuple(float(x) for x in value)
    except (TypeError, ValueError) as invalid:
        raise NativeTableSelectionError('Source page size is not numeric') from invalid
    if len(size) != 2 or any(not math.isfinite(x) or x <= 0 for x in size):
        raise NativeTableSelectionError('Source page size is invalid')
    return size


def _iou(a, b):
    intersection = _intersection(a, b)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
    return intersection / union if union else 0


def _glyphs(text):
    # Formatting whitespace may change; punctuation, digits, units and glyphs may not.
    return Counter(x for x in text if not x.isspace())


def _source_table(page, table):
    rows = table.rows
    values = table.extract(expand_ligatures=False)
    if len(rows) < 2 or not values or len(values) != len(rows) or len(values[0]) < 2:
        return None
    bounds = _box(table.bbox)
    if any(_intersection(bounds, _box((image['x0'], image['top'], image['x1'], image['bottom']))) > 0 for image in page.images):
        return None  # A text layer cannot certify raster content in any part of this table.
    cells = {}
    for row, texts in zip(rows, values):
        if len(row.cells) != len(texts):
            raise NativeTableSelectionError('Native table row and cell text disagree')
        for source, text in zip(row.cells, texts):
            if source is None:
                if text is not None:
                    raise NativeTableSelectionError('Text has no native cell geometry')
                continue
            rect = _box(source)
            value = '' if text is None else str(text)
            if rect in cells and cells[rect] != value:
                raise NativeTableSelectionError('Duplicate native cell has conflicting text')
            cells[rect] = value
    if not cells or not any(_glyphs(text) for text in cells.values()):
        return None
    xs = sorted({round(x, 6) for rect in cells for x in (rect[0], rect[2])})
    ys = sorted({round(y, 6) for rect in cells for y in (rect[1], rect[3])})
    if len(xs) < 3 or len(ys) < 3:
        return None
    if (xs[0], ys[0], xs[-1], ys[-1]) != tuple(round(x, 6) for x in bounds):
        raise NativeTableSelectionError('Native cells do not cover their table bounds')
    occupied, anchors = {}, {}
    for rect, text in cells.items():
        x0, y0, x1, y1 = (round(x, 6) for x in rect)
        left, right, top, bottom = xs.index(x0), xs.index(x1), ys.index(y0), ys.index(y1)
        anchors[(top, left)] = (rect, text, bottom - top, right - left)
        for row in range(top, bottom):
            for column in range(left, right):
                if (row, column) in occupied:
                    raise NativeTableSelectionError('Native table cells overlap')
                occupied[(row, column)] = rect
    if len(occupied) != (len(xs) - 1) * (len(ys) - 1):
        raise NativeTableSelectionError('Native table geometry has an uncovered cell')
    source_text = {rect: [] for rect in cells}
    for char in page.chars:
        center_x = (char['x0'] + char['x1']) / 2
        center_y = (char['top'] + char['bottom']) / 2
        if not (bounds[0] <= center_x < bounds[2] and bounds[1] <= center_y < bounds[3]):
            continue
        rect = occupied.get((bisect_right(ys, center_y) - 1, bisect_right(xs, center_x) - 1))
        if rect is None:
            raise NativeTableSelectionError('A table glyph has no unique native cell')
        if _glyphs(char['text']) and (not char.get('upright', True)
                or char['x0'] < rect[0] - .5 or char['top'] < rect[1] - .5
                or char['x1'] > rect[2] + .5 or char['bottom'] > rect[3] + .5):
            raise NativeTableSelectionError('A table glyph crosses its source cell boundary or is rotated')
        source_text[rect].append(char['text'])
    for rect, text in cells.items():
        if _glyphs(''.join(source_text[rect])) != _glyphs(text):
            raise NativeTableSelectionError('Native cell text does not preserve its source glyphs')
    html, evidence = ['<table>'], []
    for row in range(len(ys) - 1):
        html.append('<tr>')
        for column in range(len(xs) - 1):
            anchor = anchors.get((row, column))
            if anchor is None:
                continue
            rect, text, rowspan, colspan = anchor
            attributes = (f' rowspan="{rowspan}"' if rowspan > 1 else '') + (f' colspan="{colspan}"' if colspan > 1 else '')
            html.append(f'<td{attributes}>{escape(" ".join(text.split()))}</td>')
            evidence.append({'bbox': list(rect), 'row': row, 'column': column, 'rowspan': rowspan, 'colspan': colspan,
                             'textSha256': sha256(text.encode()).hexdigest(),
                             'sourceGlyphSha256': sha256(''.join(source_text[rect]).encode()).hexdigest()})
        html.append('</tr>')
    html.append('</table>')
    return {'bbox': list(bounds), 'html': ''.join(html), 'cells': evidence}


def read_native_tables(original):
    import pdfplumber
    dependencies = dependency_identity()
    pages = []
    with pdfplumber.open(original) as pdf:
        for page in pdf.pages:
            rejected = []
            # Native coordinates of rotated pages need a separate certified transform.
            if page.rotation or not page.chars:
                tables = []
            else:
                tables = []
                for source in page.find_tables():
                    try:
                        table = _source_table(page, source)
                        if table is not None:
                            tables.append(table)
                    except NativeTableSelectionError as invalid:
                        rejected.append({'bbox': list(source.bbox), 'reason': str(invalid)})
            pages.append({'size': [page.width, page.height], 'tables': tables, 'rejectedTables': rejected})
    return pages, dependencies


def _table_spans(block):
    return [span for child in block.get('blocks', []) if child.get('type') == 'table_body'
            for line in child.get('lines', []) for span in line.get('spans', []) if span.get('type') == 'table']


def select_native_tables(middle, native_pages, source_sha256):
    result = deepcopy(middle)
    observations = []
    actual_pages = result.get('pdf_info', [])
    if sorted(page.get('page_idx', -1) for page in actual_pages) != list(range(len(native_pages))):
        raise NativeTableSelectionError('Native table selection source pages do not match parser pages')
    for page in actual_pages:
        index = page['page_idx']
        source = native_pages[index]
        parser_size, source_size = _size(page.get('page_size', [])), _size(source['size'])
        if any(abs(a - b) > 1 for a, b in zip(parser_size, source_size)):
            raise NativeTableSelectionError('Native table selection page positions are incompatible')
        used = set()
        for block in page.get('para_blocks', []):
            if block.get('type') != 'table':
                continue
            bbox = _box(block.get('bbox'))
            matches = [(n, table) for n, table in enumerate(source['tables']) if _iou(bbox, _box(table['bbox'])) >= .90]
            if any(_iou(bbox, _box(rejected['bbox'])) >= .90 for rejected in source.get('rejectedTables', [])):
                raise NativeTableSelectionError('Aligned native text table cannot be certified')
            if len(matches) > 1:
                raise NativeTableSelectionError('More than one native table matches the parser region')
            if not matches:
                continue
            number, table = matches[0]
            if number in used:
                raise NativeTableSelectionError('Multiple parser tables reference one native source table')
            spans = _table_spans(block)
            if len(spans) != 1:
                raise NativeTableSelectionError('Native table target does not have one structural body')
            provenance = {'policy': POLICY, 'method': 'pdfplumber-native-vector-text', 'sourceSha256': source_sha256,
                          'pageIndex': index, 'sourceBBox': table['bbox'], 'cells': table['cells'],
                          'parserBBox': list(bbox), 'sourcePageSize': source['size'],
                          'nativeHtmlSha256': sha256(table['html'].encode()).hexdigest(),
                          'upstreamHtmlSha256': sha256(spans[0].get('html', '').encode()).hexdigest()}
            spans[0]['html'] = table['html']
            spans[0]['text_table_provenance'] = provenance
            # The parser's two structural views must describe the same selected source.
            previous_matches = [previous for previous in page.get('preproc_blocks', [])
                                if previous.get('type') == 'table' and _iou(_box(previous.get('bbox')), bbox) >= .90]
            if len(previous_matches) > 1:
                raise NativeTableSelectionError('Preprocessing table target is ambiguous')
            for previous in previous_matches:
                previous_spans = _table_spans(previous)
                if len(previous_spans) != 1:
                    raise NativeTableSelectionError('Preprocessing table target is ambiguous')
                previous_spans[0]['html'] = table['html']
                previous_spans[0]['text_table_provenance'] = provenance
            observations.append(provenance)
            used.add(number)
    return result, observations


def attach_content_provenance(content, observations):
    for observation in observations:
        matches = []
        for block in content:
            if block.get('type') != 'table' or block.get('page_idx') != observation['pageIndex']:
                continue
            if sha256(block.get('table_body', '').encode()).hexdigest() != observation['nativeHtmlSha256']:
                continue
            size = observation['sourcePageSize']
            bbox = block.get('bbox')
            if isinstance(bbox, list) and len(bbox) == 4:
                source_bbox = [bbox[0] * size[0] / 1000, bbox[1] * size[1] / 1000,
                               bbox[2] * size[0] / 1000, bbox[3] * size[1] / 1000]
                if _iou(_box(source_bbox), _box(observation['parserBBox'])) >= .90:
                    matches.append(block)
        if len(matches) != 1:
            raise NativeTableSelectionError('Derived content table does not uniquely bind selected native input')
        matches[0]['text_table_provenance'] = observation
    return content
