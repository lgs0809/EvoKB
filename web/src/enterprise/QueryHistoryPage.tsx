import { useEffect, useMemo, useState } from 'preact/hooks';
import {
  createEnterpriseQueryGroup,
  deleteEnterpriseQueryGroup,
  deleteEnterpriseQueryRecord,
  dissolveEnterpriseQueryGroup,
  fetchEnterpriseQueryGroups,
  fetchEnterpriseQueryHistory,
  moveEnterpriseQueryRecord,
  packageEnterpriseQueryGroup,
  packageEnterpriseQueryRecord,
  renameEnterpriseQueryGroup,
  renameEnterpriseQueryRecord,
  type EnterpriseQueryGroup,
  type EnterpriseQueryHistoryRecord,
  type EnterpriseQueryPackage,
} from '../lib/api';
import { EmptyState, Icon, LoadingBlock, SectionTitle, StatusBadge, formatDate } from './ui';

export function QueryHistoryPage({ onOpenSession, onError }: {
  onOpenSession: (sessionId: string) => void;
  onError: (message: string) => void;
}) {
  const [records, setRecords] = useState<EnterpriseQueryHistoryRecord[]>([]);
  const [groups, setGroups] = useState<EnterpriseQueryGroup[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [search, setSearch] = useState('');
  const [renameTarget, setRenameTarget] = useState<{ kind: 'record' | 'group'; id: string; value: string } | null>(null);
  const [createGroupOpen, setCreateGroupOpen] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const [recordData, groupData] = await Promise.all([
        fetchEnterpriseQueryHistory(100),
        fetchEnterpriseQueryGroups(),
      ]);
      setRecords(recordData.records || []);
      setGroups(groupData.groups || []);
      setSelected(current => new Set([...current].filter(id => (recordData.records || []).some(record => record.session_id === id))));
      onError('');
    } catch (error) {
      onError(String(error instanceof Error ? error.message : error));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return records;
    return records.filter(record => [record.name, record.question, record.answer, record.group_name || ''].some(value => value.toLowerCase().includes(q)));
  }, [records, search]);

  const run = async (key: string, action: () => Promise<void>) => {
    setBusy(key);
    try {
      await action();
      await load();
      onError('');
    } catch (error) {
      onError(String(error instanceof Error ? error.message : error));
    } finally {
      setBusy('');
    }
  };

  const downloadPackage = (pack?: EnterpriseQueryPackage) => {
    if (!pack?.download_url) return;
    const anchor = document.createElement('a');
    anchor.href = pack.download_url;
    anchor.target = '_blank';
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  };

  const packageRecord = async (record: EnterpriseQueryHistoryRecord, regenerate = false) => {
    setBusy(`package-record:${record.session_id}`);
    try {
      const pack = await packageEnterpriseQueryRecord(record.session_id, regenerate);
      downloadPackage(pack);
      await load();
      onError('');
    } catch (error) {
      onError(String(error instanceof Error ? error.message : error));
    } finally {
      setBusy('');
    }
  };

  const packageGroup = async (group: EnterpriseQueryGroup, regenerate = false) => {
    setBusy(`package-group:${group.group_id}`);
    try {
      const pack = await packageEnterpriseQueryGroup(group.group_id, regenerate);
      downloadPackage(pack);
      await load();
      onError('');
    } catch (error) {
      onError(String(error instanceof Error ? error.message : error));
    } finally {
      setBusy('');
    }
  };

  const toggle = (id: string) => {
    setSelected(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const deleteRecord = (record: EnterpriseQueryHistoryRecord) => {
    if (!window.confirm(`删除查询记录“${record.name}”？\n\n记录将从页面隐藏，数据库中的查询事实保留；已经生成的 ZIP 资料包会立即从对象存储中清理。`)) return;
    void run(`delete-record:${record.session_id}`, () => deleteEnterpriseQueryRecord(record.session_id));
  };

  const dissolveGroup = (group: EnterpriseQueryGroup) => {
    if (!window.confirm(`解散查询组“${group.name}”？\n\n只解除分组，组内 ${group.records.length} 条查询记录都会保留；组级 ZIP 资料包会被清理。`)) return;
    void run(`dissolve-group:${group.group_id}`, () => dissolveEnterpriseQueryGroup(group.group_id));
  };

  const deleteGroup = (group: EnterpriseQueryGroup) => {
    if (!window.confirm(`删除查询组“${group.name}”以及组内 ${group.records.length} 条查询记录？\n\n这是组级删除：组和查询记录会逻辑删除，数据库事实保留用于恢复；相关 ZIP 资料包会从对象存储中真实删除。`)) return;
    void run(`delete-group:${group.group_id}`, () => deleteEnterpriseQueryGroup(group.group_id));
  };

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <SectionTitle title="查询记录" />
          <p class="mt-2 max-w-3xl text-sm leading-6 text-slate-500 dark:text-slate-400">保留每次知识查询的结果与引用资料。可以重新打开结果、下载当次资料包、批量组成查询组，或直接把单条记录移动到其他组。</p>
        </div>
        <button onClick={() => void load()} disabled={loading} class="inline-flex shrink-0 items-center justify-center gap-1.5 self-start rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-500 transition hover:bg-slate-50 hover:text-slate-700 disabled:opacity-50 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200"><Icon name="refresh" size={14} class={loading ? 'animate-spin' : ''}/><span>刷新</span></button>
      </div>
      <div class="mt-5 relative max-w-xl">
        <Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
        <input value={search} onInput={event => setSearch((event.target as HTMLInputElement).value)} placeholder="搜索查询名称、问题、答案或查询组" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950" />
      </div>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div class="flex items-center justify-between gap-3"><SectionTitle title="查询组" /><span class="text-xs text-slate-400">{groups.length} 组</span></div>
      {loading ? <div class="mt-4"><LoadingBlock rows={3}/></div> : groups.length === 0 ? <div class="mt-4"><EmptyState icon="layers" title="暂无查询组" description="勾选至少两条查询记录后，可以把它们设为一个查询组。" /></div> : <div class="mt-4 grid gap-3 xl:grid-cols-2">{groups.map(group => <div key={group.group_id} class="rounded-2xl border border-slate-200 p-4 dark:border-slate-700">
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0"><div class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{group.name}</div><div class="mt-1 text-xs text-slate-400">{group.records.length} 次查询 · 更新于 {formatDate(group.updated_at)}</div></div>
          <button onClick={() => setRenameTarget({ kind: 'group', id: group.group_id, value: group.name })} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-500 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">重命名</button>
        </div>
        <div class="mt-3 flex flex-wrap gap-1.5">{group.records.slice(0, 5).map(record => <button key={record.session_id} onClick={() => onOpenSession(record.session_id)} class="max-w-full truncate rounded-lg bg-slate-100 px-2.5 py-1.5 text-xs text-slate-600 hover:bg-blue-50 hover:text-blue-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-blue-950/30">{record.name}</button>)}{group.records.length > 5 && <span class="rounded-lg bg-slate-100 px-2.5 py-1.5 text-xs text-slate-400 dark:bg-slate-800">+{group.records.length - 5}</span>}</div>
        <div class="mt-4 flex flex-wrap gap-2">
          <PackageButton pack={group.latest_package} disabled={busy === `package-group:${group.group_id}` || group.records.length === 0} onExisting={() => downloadPackage(group.latest_package)} onGenerate={regenerate => void packageGroup(group, regenerate)} />
          <button onClick={() => dissolveGroup(group)} disabled={Boolean(busy)} class="rounded-lg border border-amber-200 px-3 py-2 text-xs font-semibold text-amber-700 hover:bg-amber-50 disabled:opacity-50 dark:border-amber-900/60 dark:text-amber-300 dark:hover:bg-amber-950/20">解散组</button>
          <button onClick={() => deleteGroup(group)} disabled={Boolean(busy)} class="rounded-lg border border-rose-200 px-3 py-2 text-xs font-semibold text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:border-rose-900/60 dark:text-rose-300 dark:hover:bg-rose-950/20">删除组</button>
        </div>
      </div>)}</div>}
    </section>

    <section class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div class="flex flex-col gap-3 border-b border-slate-100 px-5 py-4 dark:border-slate-800 sm:flex-row sm:items-center sm:justify-between">
        <div class="flex items-center gap-3"><SectionTitle title="全部查询记录" /><span class="text-xs text-slate-400">{filtered.length} 条</span></div>
        {selected.size > 0 && <div class="flex flex-wrap items-center gap-2 rounded-xl bg-blue-50 px-3 py-2 dark:bg-blue-950/30">
          <span class="text-xs font-medium text-blue-700 dark:text-blue-300">已选择 {selected.size} 条</span>
          <button onClick={() => setSelected(new Set())} class="rounded-lg px-2.5 py-1.5 text-xs font-medium text-slate-500 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-slate-900/50">取消选择</button>
          <button onClick={() => setCreateGroupOpen(true)} disabled={selected.size < 2} class="rounded-lg bg-blue-600 px-3.5 py-1.5 text-xs font-semibold text-white shadow-sm shadow-blue-600/15 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-blue-500">将 {selected.size} 条设为一组</button>
        </div>}
      </div>
      {loading ? <div class="p-5"><LoadingBlock rows={7}/></div> : filtered.length === 0 ? <div class="p-5"><EmptyState icon="history" title="暂无查询记录" description="完成一次知识查询后，结果会自动保存在这里。" /></div> : <div class="divide-y divide-slate-100 dark:divide-slate-800">{filtered.map(record => <div key={record.session_id} class="p-4 sm:p-5">
        <div class="flex items-start gap-3">
          <input type="checkbox" checked={selected.has(record.session_id)} onChange={() => toggle(record.session_id)} aria-label={`选择 ${record.name}`} class="mt-1 h-4 w-4 rounded border-slate-300" />
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2"><StatusBadge value={record.status}/>{record.group_name && <span class="rounded-full bg-violet-50 px-2 py-1 text-[11px] font-semibold text-violet-600 dark:bg-violet-950/30 dark:text-violet-300">{record.group_name}</span>}<span class="text-[11px] text-slate-400">{record.citation_count} 条引用</span></div>
            <div class="mt-2 text-sm font-semibold text-slate-800 dark:text-slate-100">{record.name}</div>
            {record.name !== record.question && <div class="mt-1 line-clamp-2 text-xs leading-5 text-slate-400">原问题：{record.question}</div>}
            {record.answer && <div class="mt-2 line-clamp-2 text-xs leading-5 text-slate-500 dark:text-slate-400">{record.answer}</div>}
            <div class="mt-3 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
              <div class="flex flex-wrap gap-2">
                <button onClick={() => onOpenSession(record.session_id)} class="rounded-lg bg-blue-50 px-3 py-2 text-xs font-semibold text-blue-700 hover:bg-blue-100 dark:bg-blue-950/30 dark:text-blue-300">打开结果</button>
                <button onClick={() => setRenameTarget({ kind: 'record', id: record.session_id, value: record.name })} class="rounded-lg border border-slate-200 px-3 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">重命名</button>
                <PackageButton pack={record.latest_package} disabled={busy === `package-record:${record.session_id}` || record.citation_count === 0} onExisting={() => downloadPackage(record.latest_package)} onGenerate={regenerate => void packageRecord(record, regenerate)} />
                <button onClick={() => deleteRecord(record)} disabled={Boolean(busy)} class="rounded-lg border border-rose-200 px-3 py-2 text-xs font-medium text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:border-rose-900/60 dark:text-rose-300 dark:hover:bg-rose-950/20">删除记录</button>
              </div>
              <div class="flex items-center gap-2"><span class="text-xs text-slate-400">查询组</span><select value={record.group_id || ''} disabled={Boolean(busy)} onChange={event => { const groupId = (event.target as HTMLSelectElement).value || undefined; void run(`move:${record.session_id}`, () => moveEnterpriseQueryRecord(record.session_id, groupId).then(() => undefined)); }} class="max-w-56 rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-xs text-slate-600 outline-none focus:border-blue-400 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-300"><option value="">未分组</option>{groups.map(group => <option key={group.group_id} value={group.group_id}>{group.name}</option>)}</select></div>
            </div>
            <div class="mt-2 text-[11px] text-slate-400">{formatDate(record.created_at)}</div>
          </div>
        </div>
      </div>)}</div>}
    </section>

    {renameTarget && <TextDialog title={renameTarget.kind === 'record' ? '重命名查询记录' : '重命名查询组'} initialValue={renameTarget.value} confirmText="保存" onClose={() => setRenameTarget(null)} onConfirm={value => {
      const target = renameTarget;
      setRenameTarget(null);
      void run(`rename:${target.id}`, async () => {
        if (target.kind === 'record') await renameEnterpriseQueryRecord(target.id, value);
        else await renameEnterpriseQueryGroup(target.id, value);
      });
    }}/>}
    {createGroupOpen && <TextDialog title={`将 ${selected.size} 条查询设为一组`} initialValue="" placeholder="例如：万科治理结构核查" confirmText="创建查询组" hint="已属于其他组的记录会直接移动到新组，不需要先解散原组。" onClose={() => setCreateGroupOpen(false)} onConfirm={value => {
      const ids = [...selected];
      setCreateGroupOpen(false);
      void run('create-group', async () => { await createEnterpriseQueryGroup(value, ids); setSelected(new Set()); });
    }}/>}
  </div>;
}

function PackageButton({ pack, disabled, onExisting, onGenerate }: {
  pack?: EnterpriseQueryPackage;
  disabled?: boolean;
  onExisting: () => void;
  onGenerate: (regenerate: boolean) => void;
}) {
  if (pack?.status === 'AVAILABLE' && pack.download_url) return <div class="flex gap-1.5"><button disabled={disabled} onClick={onExisting} class="rounded-lg border border-emerald-200 px-3 py-2 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:opacity-50 dark:border-emerald-900/60 dark:text-emerald-300 dark:hover:bg-emerald-950/20">下载资料包</button><button disabled={disabled} title="重新打包" onClick={() => onGenerate(true)} class="rounded-lg border border-slate-200 px-2.5 py-2 text-xs text-slate-500 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"><Icon name="refresh" size={13}/></button></div>;
  const label = pack?.status === 'MISSING' ? '文件丢失，重新生成' : pack?.status === 'DELETED' ? '已清理，重新生成' : '打包并下载';
  return <button disabled={disabled} onClick={() => onGenerate(Boolean(pack))} class="rounded-lg border border-emerald-200 px-3 py-2 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-emerald-900/60 dark:text-emerald-300 dark:hover:bg-emerald-950/20">{label}</button>;
}

function TextDialog({ title, initialValue, placeholder, confirmText, hint, onClose, onConfirm }: {
  title: string;
  initialValue: string;
  placeholder?: string;
  confirmText: string;
  hint?: string;
  onClose: () => void;
  onConfirm: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);
  return <div class="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm" onMouseDown={event => { if (event.currentTarget === event.target) onClose(); }}>
    <div class="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900">
      <h3 class="text-base font-semibold">{title}</h3>
      {hint && <p class="mt-2 text-xs leading-5 text-slate-400">{hint}</p>}
      <input autoFocus value={value} onInput={event => setValue((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter' && value.trim()) onConfirm(value.trim()); }} placeholder={placeholder} class="mt-4 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950" />
      <div class="mt-5 flex justify-end gap-2"><button onClick={onClose} class="rounded-xl border border-slate-200 px-4 py-2 text-sm text-slate-500 dark:border-slate-700">取消</button><button disabled={!value.trim()} onClick={() => onConfirm(value.trim())} class="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40 dark:bg-blue-500">{confirmText}</button></div>
    </div>
  </div>;
}
