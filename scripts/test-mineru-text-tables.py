#!/usr/bin/env python3
"""Native PDF component tests; no OCR/model inference or business task publication."""
import argparse
import ast
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'scripts/mineru-service/app.py'
LAUNCHER = ROOT / 'scripts/start-mineru-service.py'
MODULE = APP.with_name('pdf_text_tables.py')
ATTENTION = APP.with_name('native_attention.py')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--native-site', type=Path)
parser.add_argument('--fixture', type=Path)
parser.add_argument('--upstream-middle', type=Path)
parser.add_argument('--legacy-runtime-source', type=Path,
                    help='Optional retained real single-file parser for identity compatibility; CI uses an independent single-file fixture')
args = parser.parse_args()
if args.output.exists():
    raise SystemExit('Retain prior reports; use a fresh output')
if bool(args.fixture) != bool(args.upstream_middle):
    raise SystemExit('Original PDF and original upstream middle must be supplied together')
if args.fixture and not args.native_site:
    raise SystemExit('Original native conversion requires the explicitly provisioned native site')
sys.path.insert(0, str(MODULE.parent))
if args.native_site:
    sys.path.insert(0, str(args.native_site.resolve()))
import pdf_text_tables as native
import pdfplumber
from PIL import Image
from reportlab.pdfgen import canvas
from reportlab.platypus import Table, TableStyle


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_functions(path, names, **extra):
    tree = ast.parse(path.read_bytes())
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    if len(functions) != len(names):
        raise AssertionError('Actual source functions missing')
    namespace = dict(ast=ast, hashlib=hashlib, json=json, Path=Path, **extra)
    exec(compile(ast.Module(body=functions, type_ignores=[]), str(path), 'exec'), namespace)
    return namespace


class TextCollector(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.cells, self.parts = [], None

    def handle_starttag(self, tag, attrs):
        if tag == 'td':
            self.parts = []
            self.cells.append((dict(attrs), self.parts))

    def handle_data(self, data):
        if self.parts is not None:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == 'td':
            self.parts = None


def table_pdf(path, data=None, spans=(), raster=False, rotate=False):
    data = data if data is not None else [['Unit <A&>', 'Signed amount'], ['-5.30 CNY10,000', '']]
    c = canvas.Canvas(str(path), pagesize=(400, 400))
    if rotate:
        c.setPageRotation(90)
    table = Table(data, colWidths=[100] * len(data[0]), rowHeights=[35] * len(data))
    table.setStyle(TableStyle([('GRID', (0, 0), (-1, -1), 1, 'black'),
                               ('FONTNAME', (0, 0), (-1, -1), 'Helvetica'),
                               *[('SPAN', first, last) for first, last in spans]]))
    _, height = table.wrap(360, 360)
    table.drawOn(c, 20, 350 - height)
    if raster:
        image = Image.new('RGB', (10, 10), 'white')
        from reportlab.lib.utils import ImageReader
        c.drawImage(ImageReader(image), 22, 350 - height + 2, width=10, height=10)
    c.save()


def parser_table(bbox, body='<table><tr><td>upstream-only</td></tr></table>'):
    return dict(type='table', bbox=bbox, blocks=[dict(type='table_body',
                lines=[dict(spans=[dict(type='table', html=body)])])])


def middle(pages):
    return {'pdf_info': [dict(page_idx=i, page_size=p['size'],
                             para_blocks=[parser_table(t['bbox']) for t in p['tables']],
                             preproc_blocks=[parser_table(t['bbox']) for t in p['tables']])
                         for i, p in enumerate(pages)]}


class NativeTextTableTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='evokb-native-table-unit-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name)
        self.pdf = self.workspace / 'source.pdf'
        table_pdf(self.pdf)
        self.pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual([len(p['tables']) for p in self.pages], [1])

    def test_real_vector_cells_preserve_sign_units_escaping_and_blank_cell(self):
        table = self.pages[0]['tables'][0]
        parsed = TextCollector()
        parsed.feed(table['html'])
        self.assertEqual([''.join(parts) for _, parts in parsed.cells],
                         ['Unit <A&>', 'Signed amount', '-5.30 CNY10,000', ''])
        self.assertIn('&lt;A&amp;&gt;', table['html'])
        self.assertEqual(len(table['cells']), 4)

    def test_real_native_row_and_column_spans_come_from_pdf_geometry(self):
        table_pdf(self.pdf, [['First', 'Pair', ''], ['', 'B', 'C'], ['D', 'E', 'F']],
                  spans=[((0, 0), (0, 1)), ((1, 0), (2, 0))])
        pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual(len(pages[0]['tables']), 1)
        table = pages[0]['tables'][0]
        self.assertEqual([(c['rowspan'], c['colspan']) for c in table['cells'][:2]], [(2, 1), (1, 2)])
        self.assertIn('rowspan="2"', table['html'])
        self.assertIn('colspan="2"', table['html'])

    def test_real_raster_overlap_is_not_replaced_by_text_layer(self):
        table_pdf(self.pdf, raster=True)
        pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual(pages[0]['tables'], [])
        original = middle(pages)
        original['pdf_info'][0]['para_blocks'] = [parser_table([20, 50, 220, 120])]
        selected, observations = native.select_native_tables(original, pages, digest(self.pdf.read_bytes()))
        self.assertEqual(selected, original)
        self.assertEqual(observations, [])

    def test_real_blank_vector_grid_is_not_text_certified(self):
        table_pdf(self.pdf, [['', ''], ['', '']])
        pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual(pages[0]['tables'], [])

    def test_real_rotated_page_retains_native_parser(self):
        table_pdf(self.pdf, rotate=True)
        pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual(pages[0]['tables'], [])

    def test_real_chart_and_text_are_not_fabricated_as_tables(self):
        c = canvas.Canvas(str(self.pdf), pagesize=(400, 400))
        c.line(30, 30, 30, 180)
        c.line(30, 30, 250, 30)
        c.line(30, 40, 80, 90)
        c.line(80, 90, 140, 60)
        c.drawString(30, 200, 'Trend: 20, 30, -10')
        c.save()
        pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual(pages[0]['tables'], [])

    def test_true_image_only_page_retains_native_output_and_page_index(self):
        c = canvas.Canvas(str(self.pdf), pagesize=(400, 400))
        from reportlab.lib.utils import ImageReader
        c.drawImage(ImageReader(Image.new('RGB', (50, 50), 'white')), 20, 20, width=300, height=300)
        c.save()
        pages, _ = native.read_native_tables(self.pdf)
        self.assertEqual(pages[0]['tables'], [])
        original = middle(pages)
        original['pdf_info'][0]['para_blocks'] = [parser_table([20, 80, 320, 380], '<table>native-scan</table>')]
        selected, observations = native.select_native_tables(original, pages, digest(self.pdf.read_bytes()))
        self.assertEqual(selected, original)
        self.assertEqual(observations, [])

    def test_source_glyph_loss_is_rejected_even_with_valid_table_geometry(self):
        with pdfplumber.open(self.pdf) as pdf:
            table = pdf.pages[0].find_tables()[0]
            values = table.extract(expand_ligatures=False)
            values[1][0] = values[1][0].replace('-', '')
            with patch.object(table, 'extract', return_value=values):
                with self.assertRaisesRegex(native.NativeTableSelectionError, 'source glyphs'):
                    native._source_table(pdf.pages[0], table)

    def test_incomplete_and_overlapping_cell_geometry_are_rejected(self):
        with pdfplumber.open(self.pdf) as pdf:
            table = pdf.pages[0].find_tables()[0]
            values = table.extract(expand_ligatures=False)
            incomplete = SimpleNamespace(bbox=table.bbox, rows=[SimpleNamespace(cells=r.cells[:]) for r in table.rows],
                                         extract=lambda **kw: deepcopy(values))
            incomplete.rows[1].cells[1] = None
            values[1][1] = None
            with self.assertRaisesRegex(native.NativeTableSelectionError, 'uncovered cell'):
                native._source_table(pdf.pages[0], incomplete)
            overlap = SimpleNamespace(bbox=table.bbox, rows=[SimpleNamespace(cells=r.cells[:]) for r in table.rows],
                                      extract=lambda **kw: [['Unit <A&>', 'Signed amount'], ['-5.30 CNY10,000', '']])
            a = overlap.rows[0].cells[0]
            overlap.rows[0].cells[0] = (a[0], a[1], a[2] + 20, a[3])
            with self.assertRaisesRegex(native.NativeTableSelectionError, 'overlap'):
                native._source_table(pdf.pages[0], overlap)

    def test_glyph_crossing_cell_boundary_or_rotated_glyph_is_not_certified(self):
        with pdfplumber.open(self.pdf) as pdf:
            page = pdf.pages[0]
            table = page.find_tables()[0]
            for change in [dict(x0=19), dict(upright=False)]:
                chars = deepcopy(page.chars)
                chars[0].update(change)
                source = SimpleNamespace(images=page.images, chars=chars)
                with self.assertRaisesRegex(native.NativeTableSelectionError, 'cell boundary or is rotated'):
                    native._source_table(source, table)

    def test_selection_is_source_bound_and_keeps_raw_input_unchanged(self):
        original = middle(self.pages)
        before = deepcopy(original)
        source = digest(self.pdf.read_bytes())
        selected, observations = native.select_native_tables(original, self.pages, source)
        self.assertEqual(original, before)
        self.assertEqual(len(observations), 1)
        proof = observations[0]
        self.assertEqual(proof['sourceSha256'], source)
        self.assertEqual(proof['upstreamHtmlSha256'], digest('<table><tr><td>upstream-only</td></tr></table>'.encode()))
        for view in ['para_blocks', 'preproc_blocks']:
            span = native._table_spans(selected['pdf_info'][0][view][0])[0]
            self.assertEqual(span['text_table_provenance'], proof)
            self.assertEqual(digest(span['html'].encode()), proof['nativeHtmlSha256'])

    def test_missing_duplicate_pages_and_incompatible_coordinates_fail_closed(self):
        original = middle(self.pages)
        for pages in [[], [deepcopy(original['pdf_info'][0]), deepcopy(original['pdf_info'][0])]]:
            with self.assertRaises(native.NativeTableSelectionError):
                native.select_native_tables({'pdf_info': pages}, self.pages, 'source')
        for size in [[500, 400], [float('nan'), 400], [0, 400], ['invalid-size', 400]]:
            invalid = deepcopy(original)
            invalid['pdf_info'][0]['page_size'] = size
            with self.assertRaises(native.NativeTableSelectionError):
                native.select_native_tables(invalid, self.pages, 'source')

    def test_ambiguous_native_match_reused_region_or_rejected_source_fail_closed(self):
        original = middle(self.pages)
        ambiguous = deepcopy(self.pages)
        ambiguous[0]['tables'].append(deepcopy(ambiguous[0]['tables'][0]))
        with self.assertRaisesRegex(native.NativeTableSelectionError, 'More than one'):
            native.select_native_tables(original, ambiguous, 'source')
        duplicate = deepcopy(original)
        duplicate['pdf_info'][0]['para_blocks'].append(deepcopy(duplicate['pdf_info'][0]['para_blocks'][0]))
        with self.assertRaisesRegex(native.NativeTableSelectionError, 'Multiple parser'):
            native.select_native_tables(duplicate, self.pages, 'source')
        rejected = deepcopy(self.pages)
        rejected[0]['rejectedTables'].append({'bbox': rejected[0]['tables'][0]['bbox'], 'reason': 'missing source glyph'})
        with self.assertRaisesRegex(native.NativeTableSelectionError, 'cannot be certified'):
            native.select_native_tables(original, rejected, 'source')

    def test_wrong_structural_body_and_ambiguous_preprocessing_view_fail_closed(self):
        original = middle(self.pages)
        original['pdf_info'][0]['para_blocks'][0]['blocks'] = []
        with self.assertRaisesRegex(native.NativeTableSelectionError, 'one structural body'):
            native.select_native_tables(original, self.pages, 'source')
        original = middle(self.pages)
        original['pdf_info'][0]['preproc_blocks'].append(deepcopy(original['pdf_info'][0]['preproc_blocks'][0]))
        with self.assertRaisesRegex(native.NativeTableSelectionError, 'Preprocessing'):
            native.select_native_tables(original, self.pages, 'source')

    def test_chart_blocks_and_unmatched_table_regions_remain_native(self):
        original = middle(self.pages)
        block = original['pdf_info'][0]['para_blocks'][0]
        block['type'] = 'chart'
        unchanged, observations = native.select_native_tables(original, self.pages, 'source')
        self.assertEqual(unchanged, original)
        self.assertEqual(observations, [])
        block['type'], block['bbox'] = 'table', [20, 200, 220, 270]
        unchanged, observations = native.select_native_tables(original, self.pages, 'source')
        self.assertEqual(unchanged, original)
        self.assertEqual(observations, [])

    def test_content_provenance_requires_unique_page_body_and_geometry(self):
        _, observations = native.select_native_tables(middle(self.pages), self.pages, 'source')
        table = self.pages[0]['tables'][0]
        content = [dict(type='table', page_idx=0, table_body=table['html'],
                        bbox=[x * 1000 / 400 for x in table['bbox']])]
        attached = native.attach_content_provenance(deepcopy(content), observations)
        self.assertEqual(attached[0]['text_table_provenance'], observations[0])
        for invalid in [[], content + deepcopy(content), [{**content[0], 'page_idx': 1}],
                        [{**content[0], 'table_body': '<table>changed</table>'}],
                        [{**content[0], 'bbox': [0, 0, 5, 5]}]]:
            with self.assertRaises(native.NativeTableSelectionError):
                native.attach_content_provenance(deepcopy(invalid), observations)

    def test_wrong_pinned_dependency_cannot_silently_replace_parser(self):
        with patch.object(native, 'version', return_value='different'):
            with self.assertRaises(native.NativeTableSelectionError) as rejected:
                native.dependency_identity()
        self.assertEqual(rejected.exception.code, 'PARSER_PROFILE_INVALID')


class FrozenRuntimeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='evokb-parser-bundle-unit-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name)
        self.source = self.workspace / 'source/app.py'
        self.source.parent.mkdir()
        self.source.write_bytes(APP.read_bytes())
        self.source.with_name(MODULE.name).write_bytes(MODULE.read_bytes())
        self.source.with_name(ATTENTION.name).write_bytes(ATTENTION.read_bytes())
        self.launcher = source_functions(LAUNCHER, {'runtime_sources', 'freeze_runtime'})

    def freeze(self):
        source, modules, bundle = self.launcher['runtime_sources'](self.source)
        runtime = self.launcher['freeze_runtime'](self.workspace, source, modules, bundle)
        return runtime, bundle

    def read_runtime(self, runtime):
        return source_functions(APP, {'runtime_bundle'}, __file__=str(runtime / 'app.py'),
                                sha256=lambda path: digest(path.read_bytes()))['runtime_bundle']()

    def test_new_app_and_helper_are_both_frozen_and_verified(self):
        runtime, bundle = self.freeze()
        self.assertEqual(self.read_runtime(runtime), bundle)
        self.assertEqual(bundle['modules'], {MODULE.name: digest(MODULE.read_bytes()), ATTENTION.name: digest(ATTENTION.read_bytes())})
        self.assertNotEqual(bundle['bundleSha256'], bundle['appSha256'])
        self.assertEqual(runtime, self.launcher['freeze_runtime'](self.workspace, *self.launcher['runtime_sources'](self.source)))

    def test_frozen_helper_tampering_is_rejected_without_overwriting_old_bytes(self):
        runtime, _ = self.freeze()
        module = runtime / MODULE.name
        module.write_bytes(b'changed module')
        with self.assertRaisesRegex(ValueError, 'module identity'):
            self.read_runtime(runtime)
        with self.assertRaisesRegex(SystemExit, 'content differs'):
            self.freeze()
        self.assertEqual(module.read_bytes(), b'changed module')

    def test_frozen_app_or_bundle_manifest_tampering_is_rejected(self):
        runtime, bundle = self.freeze()
        manifest = runtime / 'runtime-bundle.json'
        manifest.write_text(json.dumps({**bundle, 'modules': {}}))
        with self.assertRaisesRegex(ValueError, 'module set'):
            self.read_runtime(runtime)
        manifest.write_text(json.dumps(bundle))
        (runtime / 'app.py').write_bytes(b'changed app')
        with self.assertRaisesRegex(ValueError, 'app identity'):
            self.read_runtime(runtime)

    def test_module_change_creates_new_bundle_and_preserves_old_release(self):
        old, old_bundle = self.freeze()
        before = {p.name: p.read_bytes() for p in old.iterdir()}
        self.source.with_name(MODULE.name).write_bytes(MODULE.read_bytes() + b'\n# independent change\n')
        new, new_bundle = self.freeze()
        self.assertNotEqual(new, old)
        self.assertEqual(old_bundle['appSha256'], new_bundle['appSha256'])
        self.assertEqual({p.name: p.read_bytes() for p in old.iterdir()}, before)
        self.assertEqual(self.read_runtime(new), new_bundle)

    def test_actual_module_import_cannot_be_shadowed_by_native_site(self):
        runtime, bundle = self.freeze()
        namespace = source_functions(APP, {'native_text_tables_module'},
                                     __file__=str(runtime / 'app.py'), RUNTIME_BUNDLE=bundle,
                                     sha256=lambda path: digest(path.read_bytes()),
                                     ParserFailure=lambda code, message: ValueError(code + ': ' + message))
        imported = SimpleNamespace(__file__=str(runtime / MODULE.name))
        with patch.dict(sys.modules, pdf_text_tables=imported):
            self.assertIs(namespace['native_text_tables_module'](), imported)
        imported.__file__ = str(self.source.with_name(MODULE.name))
        with patch.dict(sys.modules, pdf_text_tables=imported):
            with self.assertRaisesRegex(ValueError, 'PARSER_PROFILE_INVALID'):
                namespace['native_text_tables_module']()

    def test_native_attention_import_cannot_be_shadowed_by_dependency_site(self):
        runtime,bundle=self.freeze()
        namespace=source_functions(APP,{'native_attention_module'},__file__=str(runtime/'app.py'),RUNTIME_BUNDLE=bundle,
                                   sha256=lambda path:digest(path.read_bytes()),
                                   ParserFailure=lambda code,message:ValueError(code+': '+message))
        imported=SimpleNamespace(__file__=str(runtime/ATTENTION.name))
        with patch.dict(sys.modules,native_attention=imported):
            self.assertIs(namespace['native_attention_module'](),imported)
        imported.__file__=str(self.source.with_name(ATTENTION.name))
        with patch.dict(sys.modules,native_attention=imported):
            with self.assertRaisesRegex(ValueError,'PARSER_PROFILE_INVALID'):namespace['native_attention_module']()

    def test_ready_source_contract_exposes_same_bundle_without_creating_worker(self):
        runtime, bundle = self.freeze()
        namespace = source_functions(APP, {'ready'}, app=SimpleNamespace(get=lambda path: lambda function: function),
                                     VERSION='3.4.5', PROTOCOL='evokb-mineru-isolated-v1',
                                     STAGE_SECONDS=600, CPU_THREADS=4, SITE='synthetic-native-site', os=os,
                                     MANAGER=SimpleNamespace(worker=None), RUNTIME_BUNDLE=bundle,
                                     __file__=str(runtime / 'app.py'), sha256=lambda path: digest(path.read_bytes()))
        with patch.dict(os.environ, EKB_MINERU_HYBRID_MANIFEST='synthetic-preserved-manifest'):
            ready = namespace['ready']()
        self.assertEqual(ready['parserSourceSha256'], bundle['appSha256'])
        self.assertEqual(ready['parserRuntimeBundleSha256'], bundle['bundleSha256'])
        self.assertEqual(ready['parserRuntimeModulesSha256'], bundle['modules'])
        self.assertEqual((ready['stageSeconds'], ready['cpuThreads'], ready['workerPid']), (600, 4, None))

    def test_requirement_locks_match_actual_module_dependency_identity(self):
        locks = dict(line.strip().split('==', 1) for line in
                     (ROOT / 'deploy/mineru/requirements-hybrid.txt').read_text().splitlines()
                     if line.strip() and not line.lstrip().startswith('#'))
        self.assertEqual({name: locks[name] for name in native.DEPENDENCIES}, native.dependency_identity())

    def test_missing_new_helper_is_a_clear_prelaunch_failure(self):
        self.source.with_name(MODULE.name).unlink()
        with self.assertRaisesRegex(SystemExit, 'module missing'):
            self.launcher['runtime_sources'](self.source)
        with self.assertRaisesRegex(ValueError, 'module is missing'):
            self.read_runtime(self.source.parent)

    def test_explicit_old_single_file_runtime_retains_its_original_identity(self):
        old = (args.legacy_runtime_source.read_bytes() if args.legacy_runtime_source
               else b'from pathlib import Path\nVERSION = "independent-single-file-fixture"\n')
        self.source.write_bytes(old)
        runtime, bundle = self.freeze()
        self.assertEqual(bundle['bundleSha256'], digest(old))
        self.assertEqual(bundle['modules'], {})
        self.assertEqual((runtime / 'app.py').read_bytes(), old)
        self.assertFalse((runtime / 'runtime-bundle.json').exists())

    def test_default_source_without_manifest_and_unrelated_foreign_imports(self):
        self.assertEqual(self.read_runtime(self.source.parent)['modules'], {MODULE.name: digest(MODULE.read_bytes()), ATTENTION.name: digest(ATTENTION.read_bytes())})
        self.source.write_text('from foreign_dependency import method\n')
        _, modules, _ = self.launcher['runtime_sources'](self.source)
        self.assertEqual(modules, {})


class PipelineMetadataTests(unittest.TestCase):
    """Actual pure metadata function; synthetic declared packages, no inference/readiness claim."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='evokb-parser-identity-unit-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name)
        self.manifest = self.workspace / 'manifest.json'
        self.declaration = dict(parserVersion='3.4.5', backend='hybrid-engine', effort='medium',
            parseMethod='auto', language='ch', modelRevision=digest(b'fixture native model'),
            files=[dict(path='/synthetic/a/config.json', sha256=digest(b'a'), size=1),
                   dict(path='/synthetic/b/config.json', sha256=digest(b'b'), size=1)],
            repoRevisions={'synthetic-layout': 'fixture-revision'})
        self.manifest.write_text(json.dumps(self.declaration))
        self.bundle = dict(bundleSha256=digest(b'fixture actual bundle'),
            appSha256=digest(APP.read_bytes()), modules={MODULE.name: digest(MODULE.read_bytes()), ATTENTION.name: digest(ATTENTION.read_bytes())})
        self.packages = {'mineru': '3.4.5', 'pillow': '11.3.0', 'torch': '2.8.0',
            'transformers': 'fixture', 'torchvision': 'fixture', 'pypdfium2': 'fixture',
            'python-docx': 'fixture', 'openpyxl': 'fixture', 'python-pptx': 'fixture'}
        class MetadataMissing(Exception):
            pass
        metadata = SimpleNamespace(version=lambda name: self.packages[name],
            PackageNotFoundError=MetadataMissing)
        class Failure(Exception):
            def __init__(self, code, message):
                super().__init__(message)
                self.code = code
        self.failure = Failure
        self.functions = source_functions(APP, {'parser_pipeline'}, os=os, VERSION='3.4.5',
            CPU_THREADS=4, RUNTIME_BUNDLE=self.bundle, ParserFailure=Failure,
            native_text_tables_module=lambda: native,
            importlib=SimpleNamespace(metadata=metadata))
        self.environment = patch.dict(os.environ,
            EKB_MINERU_HYBRID_MANIFEST=str(self.manifest), MINERU_DEVICE_MODE='mps')
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def pipeline(self, suffix='.pdf'):
        return self.functions['parser_pipeline'](suffix)

    def test_full_manifest_repeated_basenames_are_distinct_valid_assets(self):
        observed = self.pipeline()
        expected = digest(json.dumps(self.declaration, sort_keys=True, separators=(',', ':')).encode())
        self.assertEqual(observed['components']['model.manifest.sha256'], expected)
        self.assertNotIn('/synthetic', json.dumps(observed))

    def test_policy_bundle_and_actual_dependency_changes_change_identity(self):
        before = self.pipeline()
        self.bundle['bundleSha256'] = digest(b'different immutable bundle')
        self.assertNotEqual(before['sha256'], self.pipeline()['sha256'])
        self.bundle['bundleSha256'] = before['components']['runtime.sha256']
        self.packages['torch'] = 'different real declaration'
        self.assertNotEqual(before['sha256'], self.pipeline()['sha256'])

    def test_manifest_missing_or_invalid_never_becomes_certified_fallback(self):
        os.environ.pop('EKB_MINERU_HYBRID_MANIFEST')
        with self.assertRaises(self.failure) as failure:
            self.pipeline()
        self.assertEqual(failure.exception.code, 'DEPENDENCY_NOT_AVAILABLE')
        os.environ['EKB_MINERU_HYBRID_MANIFEST'] = str(self.manifest)
        self.declaration['modelRevision'] = 'unknown'
        self.manifest.write_text(json.dumps(self.declaration))
        with self.assertRaises(self.failure):
            self.pipeline()
        self.declaration['modelRevision'] = digest(b'fixture native model')
        self.declaration['files'][0]['sha256'] = 'unknown'
        self.manifest.write_text(json.dumps(self.declaration))
        with self.assertRaises(self.failure):
            self.pipeline()

    def test_frozen_profile_and_installed_mineru_version_must_match(self):
        self.declaration['parseMethod'] = 'different'
        self.manifest.write_text(json.dumps(self.declaration))
        with self.assertRaises(self.failure):
            self.pipeline()
        self.declaration['parseMethod'] = 'auto'
        self.manifest.write_text(json.dumps(self.declaration))
        self.packages['mineru'] = 'different'
        with self.assertRaises(self.failure):
            self.pipeline()

    def test_native_office_identity_does_not_require_pdf_model_manifest(self):
        os.environ.pop('EKB_MINERU_HYBRID_MANIFEST')
        for suffix in ['.docx', '.xlsx', '.pptx']:
            observed = self.pipeline(suffix)
            self.assertEqual(observed['components']['parser.format'], suffix)
            self.assertNotIn('model.manifest.sha256', observed['components'])

    def test_sorted_utf8_length_frames_recompute_exact_pipeline_hash(self):
        observed = self.pipeline()
        def frame(value):
            data = value.encode('utf-8')
            return str(len(data)).encode('ascii') + b':' + data
        expected = frame('raw-parser-pipeline-v1')
        for key in sorted(observed['components']):
            expected += frame(key) + frame(observed['components'][key])
        self.assertEqual(observed['sha256'], digest(expected))
        self.assertEqual(observed['sha256'], self.pipeline()['sha256'])


class OriginalNativeConversionTests(unittest.TestCase):
    def test_actual_original_output_selects_only_native_page_and_all_views_match(self):
        from mineru.backend.vlm.vlm_middle_json_mkcontent import union_make
        from mineru.utils.enum_class import MakeMode
        pdf_bytes, upstream_bytes = args.fixture.read_bytes(), args.upstream_middle.read_bytes()
        pages, _ = native.read_native_tables(args.fixture)
        original = json.loads(upstream_bytes)
        selected, observations = native.select_native_tables(original, pages, digest(pdf_bytes))
        self.assertEqual([len(p['tables']) for p in pages], [1, 0])
        self.assertEqual(len(observations), 1)
        self.assertEqual(observations[0]['pageIndex'], 0)
        self.assertEqual(selected['pdf_info'][1], original['pdf_info'][1])
        content = native.attach_content_provenance(union_make(selected['pdf_info'], MakeMode.CONTENT_LIST, 'images'), observations)
        markdown = union_make(selected['pdf_info'], MakeMode.MM_MD, 'images')
        expected = pages[0]['tables'][0]['html']
        self.assertIn(expected, markdown)
        table = next(b for b in content if b.get('text_table_provenance'))
        self.assertEqual(table['table_body'], expected)
        self.assertEqual(table['text_table_provenance'], observations[0])
        self.assertEqual(args.fixture.read_bytes(), pdf_bytes)
        self.assertEqual(args.upstream_middle.read_bytes(), upstream_bytes)


suite = unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(NativeTextTableTests),
                           unittest.defaultTestLoader.loadTestsFromTestCase(FrozenRuntimeTests),
                           unittest.defaultTestLoader.loadTestsFromTestCase(PipelineMetadataTests)])
if args.fixture:
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(OriginalNativeConversionTests))
stream = io.StringIO()
result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
report = dict(status='PASS_NATIVE_TEXT_TABLE_COMPONENT' if result.wasSuccessful() else 'FAIL_NATIVE_TEXT_TABLE_COMPONENT',
              operationTime=datetime.now(timezone.utc).isoformat(), tests=result.testsRun,
              failures=len(result.failures), errors=len(result.errors), skipped=len(result.skipped),
              dependencies=native.dependency_identity(), originalNativeConversionEnabled=bool(args.fixture),
              legacyIdentityInput='retained-real-runtime' if args.legacy_runtime_source else 'independent-single-file-fixture',
              sourceHashes={str(p.relative_to(ROOT)): digest(p.read_bytes()) for p in
                            [Path(__file__), APP, LAUNCHER, MODULE, ROOT / 'deploy/mineru/requirements-hybrid.txt']},
              scope='Actual PDF text/vector/tablefinder geometry and frozen runtime bytes; optional official conversion of preserved upstream output. No new MinerU inference or business generation acceptance.')
if args.fixture:
    report.update(originalInputSha256=digest(args.fixture.read_bytes()),
                  upstreamMiddleSha256=digest(args.upstream_middle.read_bytes()))
if args.legacy_runtime_source:
    report['legacyRuntimeSourceSha256'] = digest(args.legacy_runtime_source.read_bytes())
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.with_suffix('.log').write_text(stream.getvalue())
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False))
raise SystemExit(0 if result.wasSuccessful() else 1)
