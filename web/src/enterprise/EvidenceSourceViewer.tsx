import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { fetchEnterpriseEvidenceSource, type EnterpriseEvidence, type EnterpriseEvidenceSource } from '../lib/api';
import { Icon, LoadingBlock } from './ui';

export function EvidenceSourceViewer({ evidence, onClose, onError }: { evidence: EnterpriseEvidence; onClose: () => void; onError: (message: string) => void }) {
  const [source, setSource] = useState<EnterpriseEvidenceSource | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let cancelled = false;
    fetchEnterpriseEvidenceSource(evidence.evidence_id).then(value => { if (!cancelled) setSource(value); }).catch(err => onError(String(err instanceof Error ? err.message : err))).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [evidence.evidence_id]);
  const isPDF = Boolean(source && (source.content_type?.includes('pdf') || source.object_key.toLowerCase().endsWith('.pdf')));

  return <div class="fixed inset-0 z-[90] flex items-center justify-center bg-slate-950/65 p-2 backdrop-blur-sm sm:p-5" onClick={onClose}>
    <div class="flex h-[96vh] w-full max-w-7xl flex-col overflow-hidden rounded-2xl border border-slate-700 bg-white shadow-2xl dark:bg-slate-900" onClick={event => event.stopPropagation()}>
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-4 py-3 dark:border-slate-800 sm:px-5"><h2 class="text-sm font-semibold">原文查看</h2><div class="flex items-center gap-2">{source && <a href={source.download_url} target="_blank" rel="noreferrer" class="inline-flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50/70 px-3 py-2 text-xs font-semibold text-blue-700 transition hover:border-blue-300 hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60"><Icon name="download" size={14}/>下载原文件</a>}<button onClick={onClose} class="flex h-9 w-9 items-center justify-center rounded-lg border border-slate-200 text-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800"><Icon name="close" size={16}/></button></div></div>
      <div class="min-h-0 flex-1 overflow-hidden bg-slate-100 dark:bg-slate-950">{loading ? <div class="mx-auto max-w-xl p-8"><LoadingBlock rows={6}/></div> : !source ? <div class="p-8 text-center text-sm text-rose-500">无法加载原文件。</div> : isPDF ? <PDFEvidencePage source={source}/> : <NonPDFSource source={source}/>}</div>
    </div>
  </div>;
}

function PDFEvidencePage({ source }: { source: EnterpriseEvidenceSource }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [pdf, setPDF] = useState<any>(null);
  const [pageCount, setPageCount] = useState(0);
  const [page, setPage] = useState(() => locationPage(source.location));
  const [renderSize, setRenderSize] = useState({ width: 0, height: 0 });
  const [error, setError] = useState('');
  const bbox = useMemo(() => numericArray(source.location?.bbox, 4), [source.location]);
  const pageSize = useMemo(() => numericArray(source.location?.page_size, 2), [source.location]);
  const bboxSpace = String(source.location?.bbox_space || 'normalized_1000');

  useEffect(() => {
    let cancelled = false;
    let loadingTask: any;
    void import('pdfjs-dist').then(pdfjs => {
      pdfjs.GlobalWorkerOptions.workerSrc = new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString();
      loadingTask = pdfjs.getDocument({ url: source.view_url, withCredentials: true });
      return loadingTask.promise;
    }).then(doc => { if (!cancelled) { setPDF(doc); setPageCount(doc.numPages); setPage(value => Math.min(Math.max(value, 1), doc.numPages)); } }).catch((err: unknown) => { if (!cancelled) setError(String(err)); });
    return () => { cancelled = true; void loadingTask?.destroy?.(); };
  }, [source.view_url]);

  useEffect(() => {
    if (!pdf || !canvasRef.current) return;
    let cancelled = false;
    let renderTask: any;
    void pdf.getPage(page).then((pdfPage: any) => {
      if (cancelled || !canvasRef.current) return;
      const base = pdfPage.getViewport({ scale: 1 });
      const containerWidth = Math.min(window.innerWidth - 70, 1080);
      const scale = Math.max(0.8, Math.min(2, containerWidth / base.width));
      const viewport = pdfPage.getViewport({ scale });
      const canvas = canvasRef.current;
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.floor(viewport.width * dpr);
      canvas.height = Math.floor(viewport.height * dpr);
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;
      const ctx = canvas.getContext('2d');
      if (!ctx) throw new Error('无法创建 PDF 画布');
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      setRenderSize({ width: viewport.width, height: viewport.height });
      renderTask = pdfPage.render({ canvasContext: ctx, viewport });
      return renderTask.promise;
    }).catch((err: unknown) => { if (!cancelled && String(err).indexOf('cancel') < 0) setError(String(err)); });
    return () => { cancelled = true; void renderTask?.cancel?.(); };
  }, [pdf, page]);

  const overlay = bbox && page === locationPage(source.location) && renderSize.width > 0 ? evidenceOverlay(bbox, bboxSpace, pageSize, renderSize) : null;

  return <div class="flex h-full flex-col">
    <div class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-white px-4 py-2.5 dark:border-slate-800 dark:bg-slate-900"><div class="text-xs text-slate-500">参考位置：第 {locationPage(source.location)} 页</div><div class="flex items-center gap-2"><button disabled={page <= 1} onClick={() => setPage(value => value - 1)} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs disabled:opacity-40 dark:border-slate-700">上一页</button><span class="min-w-20 text-center text-xs text-slate-500">{page} / {pageCount || '—'}</span><button disabled={!pageCount || page >= pageCount} onClick={() => setPage(value => value + 1)} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs disabled:opacity-40 dark:border-slate-700">下一页</button><button onClick={() => setPage(locationPage(source.location))} class="rounded-lg bg-blue-600 px-2.5 py-1.5 text-xs font-semibold text-white">回到参考位置</button></div></div>
    <div class="min-h-0 flex-1 overflow-auto p-4 sm:p-6"><div class="relative mx-auto w-fit bg-white shadow-xl"><canvas ref={canvasRef}/>{overlay && <div class="pointer-events-none absolute border-2 border-amber-500 bg-amber-300/25 shadow-[0_0_0_2px_rgba(255,255,255,.75)]" style={overlay}><div class="absolute -top-6 left-0 whitespace-nowrap rounded bg-amber-500 px-2 py-1 text-[10px] font-semibold text-white">证据位置</div></div>}</div>{error && <div class="mx-auto mt-4 max-w-2xl rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-600">{error}</div>}</div>
  </div>;
}

function NonPDFSource({ source }: { source: EnterpriseEvidenceSource }) {
  return <div class="flex h-full items-center justify-center p-6"><div class="max-w-lg rounded-2xl border border-slate-200 bg-white p-6 text-center shadow-sm dark:border-slate-800 dark:bg-slate-900"><div class="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-50 text-blue-600 dark:bg-blue-950/40"><Icon name="file" size={24}/></div><h3 class="mt-4 text-lg font-semibold">查看原文件</h3><div class="mt-5 flex justify-center gap-2"><a href={source.view_url} target="_blank" rel="noreferrer" class="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-blue-600/15 transition hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400">打开</a><a href={source.download_url} target="_blank" rel="noreferrer" class="rounded-xl border border-blue-200 bg-blue-50/70 px-4 py-2.5 text-sm font-semibold text-blue-700 transition hover:border-blue-300 hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60">下载</a></div></div></div>;
}

function evidenceOverlay(bbox: number[], bboxSpace: string, pageSize: number[] | null, renderSize: { width: number; height: number }) {
  const normalized1000 = bboxSpace === 'normalized_1000' || !bboxSpace;
  const sourceWidth = normalized1000 ? 1000 : (pageSize?.[0] || 1000);
  const sourceHeight = normalized1000 ? 1000 : (pageSize?.[1] || 1000);
  const left = clamp(bbox[0] / sourceWidth * renderSize.width, 0, renderSize.width);
  const top = clamp(bbox[1] / sourceHeight * renderSize.height, 0, renderSize.height);
  const right = clamp(bbox[2] / sourceWidth * renderSize.width, left, renderSize.width);
  const bottom = clamp(bbox[3] / sourceHeight * renderSize.height, top, renderSize.height);
  return { left: `${left}px`, top: `${top}px`, width: `${Math.max(2, right - left)}px`, height: `${Math.max(2, bottom - top)}px` };
}
function clamp(value: number, min: number, max: number) { return Math.min(max, Math.max(min, value)); }
function locationPage(location: Record<string, unknown>) { const value = Number(location?.page_start ?? location?.page ?? 1); return Number.isFinite(value) && value >= 1 ? Math.floor(value) : 1; }
function numericArray(value: unknown, minLength: number): number[] | null { if (!Array.isArray(value) || value.length < minLength) return null; const out = value.map(Number); return out.every(Number.isFinite) && out.every(item => item >= 0) ? out : null; }
