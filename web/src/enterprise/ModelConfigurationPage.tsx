import { DialogFrame } from './DialogFrame';
import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import {
  activateEnterpriseModelProfile,
  copyEnterpriseModelProfile,
  createEnterpriseModelProfile,
  deleteEnterpriseModelProfile,
  fetchEnterpriseModelProfiles,
  updateEnterpriseModelProfile,
  type EnterpriseModelProfile,
  type EnterpriseModelProfileUpdate,
} from '../lib/api';
import { Icon, PrimaryButton, SecondaryButton, SectionTitle, formatDate, type IconName } from './ui';

const inputClass = 'mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none transition focus:border-blue-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950 dark:focus:border-blue-500';

export function ModelConfigurationPage({ onError }: { onError: (message: string) => void }) {
  const [profiles, setProfiles] = useState<EnterpriseModelProfile[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [form, setForm] = useState<EnterpriseModelProfileUpdate | null>(null);
  const [busy, setBusy] = useState('');
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [createName, setCreateName] = useState('新配置');
  const [deleteOpen, setDeleteOpen] = useState(false);
  const selected = useMemo(() => profiles.find(item => item.profile_id === selectedId) || profiles[0] || null, [profiles, selectedId]);

  const load = async (preferredId?: string) => {
    setLoading(true);
    try {
      const value = await fetchEnterpriseModelProfiles();
      setProfiles(value.profiles || []);
      const nextId = preferredId && value.profiles.some(item => item.profile_id === preferredId)
        ? preferredId
        : value.active_profile_id || value.profiles[0]?.profile_id || '';
      setSelectedId(nextId);
      const next = value.profiles.find(item => item.profile_id === nextId) || value.profiles[0];
      setForm(next ? fromProfile(next) : null);
      onError('');
    } catch (err) { onError(toMessage(err)); }
    finally { setLoading(false); }
  };

  useEffect(() => { void load(); }, []);
  useEffect(() => { if (selected) setForm(fromProfile(selected)); }, [selectedId]);

  const set = <K extends keyof EnterpriseModelProfileUpdate>(key: K, next: EnterpriseModelProfileUpdate[K]) => {
    setForm(current => current ? { ...current, [key]: next } : current);
  };

  const save = async () => {
    if (!selected || !form) return;
    setBusy('save');
    try { await updateEnterpriseModelProfile(selected.profile_id, form); await load(selected.profile_id); }
    catch (err) { onError(toMessage(err)); }
    finally { setBusy(''); }
  };

  const create = async () => {
    const name = createName.trim();
    if (!name) return;
    setBusy('create');
    try {
      const next = await createEnterpriseModelProfile(name);
      setCreateOpen(false);
      setCreateName('新配置');
      await load(next.profile_id);
    }
    catch (err) { onError(toMessage(err)); }
    finally { setBusy(''); }
  };

  const copy = async () => {
    if (!selected) return;
    setBusy('copy');
    try { const next = await copyEnterpriseModelProfile(selected.profile_id); await load(next.profile_id); }
    catch (err) { onError(toMessage(err)); }
    finally { setBusy(''); }
  };

  const activate = async () => {
    if (!selected) return;
    setBusy('activate');
    try { await activateEnterpriseModelProfile(selected.profile_id); await load(selected.profile_id); }
    catch (err) { onError(toMessage(err)); }
    finally { setBusy(''); }
  };

  const remove = async () => {
    if (!selected || selected.active) return;
    setBusy('delete');
    try {
      await deleteEnterpriseModelProfile(selected.profile_id);
      setDeleteOpen(false);
      await load();
    }
    catch (err) { onError(toMessage(err)); }
    finally { setBusy(''); }
  };

  if (loading) return <div class="rounded-2xl border border-slate-200 bg-white p-8 text-sm text-slate-400 dark:border-slate-800 dark:bg-slate-900">正在读取模型配置…</div>;

  return <div class="space-y-5">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle title="模型配置" description="维护多套完整配置并选择当前使用组。切换后后续 Chat、Embedding、Rerank 请求立即使用新配置。" action={<PrimaryButton onClick={() => { setCreateName('新配置'); setCreateOpen(true); }} disabled={Boolean(busy)} icon="models">新建配置组</PrimaryButton>} />
    </section>

    <div class="grid gap-5 xl:grid-cols-[300px_minmax(0,1fr)]">
      <aside class="self-start rounded-2xl border border-slate-200/80 bg-white p-3 shadow-sm dark:border-slate-800 dark:bg-slate-900 xl:sticky xl:top-24">
        <div class="px-2 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-400">配置组</div>
        <div class="space-y-1.5">{profiles.map(profile => <button key={profile.profile_id} onClick={() => setSelectedId(profile.profile_id)} class={`w-full rounded-xl border px-3 py-3 text-left transition ${selectedId === profile.profile_id ? 'border-blue-300 bg-blue-50 dark:border-blue-800 dark:bg-blue-950/30' : 'border-transparent hover:bg-slate-50 dark:hover:bg-slate-950/50'}`}>
          <div class="flex items-center justify-between gap-2"><span class="truncate text-sm font-semibold">{profile.name}</span>{profile.active && <span class="shrink-0 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-semibold text-emerald-600 dark:bg-emerald-950/50 dark:text-emerald-300">使用中</span>}</div>
          <div class="mt-1.5 truncate text-[11px] text-slate-400">{profile.large_model || profile.small_model || '未配置模型'}</div>
          <div class="mt-1 text-[10px] text-slate-400">更新 {formatDate(profile.updated_at)}</div>
        </button>)}</div>
      </aside>

      {selected && form && <section class="space-y-5">
        <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
          <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
            <Field label="配置组名称" className="max-w-lg"><input value={form.name} onInput={event => set('name', inputValue(event))} class={inputClass} /></Field>
            <div class="flex flex-wrap gap-2">
              <SecondaryButton onClick={() => void copy()} disabled={Boolean(busy)} icon="copy">复制配置</SecondaryButton>
              {!selected.active && <SecondaryButton onClick={() => setDeleteOpen(true)} disabled={Boolean(busy)} icon="trash">删除</SecondaryButton>}
              {!selected.active && <PrimaryButton onClick={() => void activate()} disabled={Boolean(busy)} icon="check">{busy === 'activate' ? '切换中…' : '设为当前使用'}</PrimaryButton>}
              <PrimaryButton onClick={() => void save()} disabled={Boolean(busy)} icon="check">{busy === 'save' ? '保存中…' : selected.active ? '保存并立即生效' : '保存配置'}</PrimaryButton>
            </div>
          </div>
        </section>

        <ModelCard icon="chat" title="LLM / Chat" description="查询理解、判断、回答，以及 Summary / Wiki / Graph 生成。">
          <div class="grid gap-4 lg:grid-cols-2">
            <Field label="API Base URL" span><input value={form.chat_base_url} onInput={event => set('chat_base_url', inputValue(event))} class={inputClass} /></Field>
            <KeyField label="API Key" saved={selected.chat_api_key_masked} onChange={next => set('chat_api_key', next)} />
            <Field label="请求超时（秒）"><NumberInput value={form.chat_timeout_seconds} onChange={next => set('chat_timeout_seconds', next)} /></Field>
            <Field label="Small Model"><input value={form.small_model} onInput={event => set('small_model', inputValue(event))} class={inputClass} /></Field>
            <Field label="Large Model"><input value={form.large_model} onInput={event => set('large_model', inputValue(event))} class={inputClass} /></Field>
          </div>
        </ModelCard>

        <ModelCard icon="embedding" title="Embedding" description="文档向量化与查询向量化。">
          <div class="mb-4 flex gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300"><Icon name="warning" size={16} class="mt-0.5 shrink-0"/><span>更换模型或向量维度后，需要确认 Milvus 向量集合维度一致；维度变化时应重建派生索引。</span></div>
          <div class="grid gap-4 lg:grid-cols-2">
            <Field label="API Base URL" span><input value={form.embedding_base_url} onInput={event => set('embedding_base_url', inputValue(event))} class={inputClass} /></Field>
            <KeyField label="API Key" saved={selected.embedding_api_key_masked} onChange={next => set('embedding_api_key', next)} />
            <Field label="Model"><input value={form.embedding_model} onInput={event => set('embedding_model', inputValue(event))} class={inputClass} /></Field>
            <Field label="向量维度"><NumberInput value={form.embedding_dimensions} onChange={next => set('embedding_dimensions', next)} /></Field>
            <Field label="Batch Size"><NumberInput value={form.embedding_batch_size} onChange={next => set('embedding_batch_size', next)} /></Field>
            <Field label="请求超时（秒）"><NumberInput value={form.embedding_timeout_seconds} onChange={next => set('embedding_timeout_seconds', next)} /></Field>
          </div>
        </ModelCard>

        <ModelCard icon="rerank" title="Rerank" description="对多路召回候选进行相关性重排。">
          <div class="grid gap-4 lg:grid-cols-2">
            <Field label="API Base URL" span><input value={form.rerank_base_url} onInput={event => set('rerank_base_url', inputValue(event))} class={inputClass} /></Field>
            <KeyField label="API Key" saved={selected.rerank_api_key_masked} onChange={next => set('rerank_api_key', next)} />
            <Field label="Model"><input value={form.rerank_model} onInput={event => set('rerank_model', inputValue(event))} class={inputClass} /></Field>
            <Field label="请求超时（秒）"><NumberInput value={form.rerank_timeout_seconds} onChange={next => set('rerank_timeout_seconds', next)} /></Field>
          </div>
        </ModelCard>
      </section>}
    </div>

    {createOpen && <TextModal
      title="新建模型配置组"
      description="创建后会复制当前使用中的完整模型配置，你可以再修改 URL、模型名和密钥。"
      value={createName}
      confirmText={busy === 'create' ? '创建中…' : '创建配置组'}
      disabled={busy === 'create'}
      onChange={setCreateName}
      onClose={() => { if (!busy) setCreateOpen(false); }}
      onConfirm={() => void create()}
    />}

    {deleteOpen && selected && <ConfirmModal
      title="删除模型配置组"
      description={`确定删除“${selected.name}”吗？当前正在使用的配置组不能删除。`}
      confirmText={busy === 'delete' ? '删除中…' : '删除配置组'}
      disabled={busy === 'delete'}
      onClose={() => { if (!busy) setDeleteOpen(false); }}
      onConfirm={() => void remove()}
    />}
  </div>;
}

function ModelCard({ icon, title, description, children }: { icon: IconName; title: string; description: string; children: ComponentChildren }) {
  return <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6"><div class="flex items-start gap-3"><div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300"><Icon name={icon} size={19}/></div><div><h2 class="text-base font-semibold text-slate-900 dark:text-white">{title}</h2><p class="mt-1 text-xs leading-5 text-slate-400">{description}</p></div></div><div class="mt-5">{children}</div></section>;
}

function Field({ label, span, className = '', children }: { label: string; span?: boolean; className?: string; children: ComponentChildren }) {
  return <label class={`block text-xs font-medium text-slate-500 ${span ? 'lg:col-span-2' : ''} ${className}`}><span>{label}</span>{children}</label>;
}

function KeyField({ label, saved, onChange }: { label: string; saved: string; onChange: (value: string) => void }) {
  return <label class="block text-xs font-medium text-slate-500"><span>{label}</span><div class="mt-1.5 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 dark:border-slate-700 dark:bg-slate-950"><div class="font-mono text-xs text-slate-500">已保存：{saved || '未配置'}</div><input onInput={event => onChange(inputValue(event))} class="mt-2 w-full border-0 bg-transparent p-0 text-sm outline-none" placeholder="输入新值可替换；留空保留当前值" autoComplete="off" /></div></label>;
}

function NumberInput({ value, onChange }: { value: number; onChange: (value: number) => void }) {
  return <input type="number" min="1" value={value} onInput={event => onChange(Math.max(1, Number((event.target as HTMLInputElement).value) || 1))} class={inputClass} />;
}

function TextModal({ title, description, value, confirmText, disabled, onChange, onClose, onConfirm }: { title: string; description: string; value: string; confirmText: string; disabled?: boolean; onChange: (value: string) => void; onClose: () => void; onConfirm: () => void }) {
  return <DialogFrame title={title} onClose={onClose}>
    <div class="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900">
      <div class="flex items-start gap-3"><div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300"><Icon name="models" size={18}/></div><div><h3 class="text-base font-semibold text-slate-900 dark:text-white">{title}</h3><p class="mt-1 text-xs leading-5 text-slate-400">{description}</p></div></div>
      <label class="mt-5 block text-xs font-medium text-slate-500">配置组名称<input autoFocus value={value} maxLength={128} onInput={event => onChange(inputValue(event))} onKeyDown={event => { if (event.key === 'Enter' && value.trim() && !disabled) onConfirm(); if (event.key === 'Escape') onClose(); }} class={inputClass}/></label>
      <div class="mt-5 flex justify-end gap-2"><button onClick={onClose} disabled={disabled} class="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-500 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">取消</button><button onClick={onConfirm} disabled={disabled || !value.trim()} class="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-blue-600/15 disabled:opacity-40 dark:bg-blue-500">{confirmText}</button></div>
    </div>
  </DialogFrame>;
}

function ConfirmModal({ title, description, confirmText, disabled, onClose, onConfirm }: { title: string; description: string; confirmText: string; disabled?: boolean; onClose: () => void; onConfirm: () => void }) {
  return <DialogFrame title={title} onClose={onClose}>
    <div class="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900">
      <div class="flex items-start gap-3"><div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-300"><Icon name="trash" size={18}/></div><div><h3 class="text-base font-semibold text-slate-900 dark:text-white">{title}</h3><p class="mt-1 text-xs leading-5 text-slate-500 dark:text-slate-400">{description}</p></div></div>
      <div class="mt-5 flex justify-end gap-2"><button onClick={onClose} disabled={disabled} class="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-500 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">取消</button><button onClick={onConfirm} disabled={disabled} class="rounded-xl bg-rose-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-rose-600/15 disabled:opacity-40">{confirmText}</button></div>
    </div>
  </DialogFrame>;
}

function fromProfile(value: EnterpriseModelProfile): EnterpriseModelProfileUpdate {
  return {
    name: value.name,
    chat_base_url: value.chat_base_url,
    chat_api_key: '',
    small_model: value.small_model,
    large_model: value.large_model,
    chat_timeout_seconds: value.chat_timeout_seconds,
    embedding_base_url: value.embedding_base_url,
    embedding_api_key: '',
    embedding_model: value.embedding_model,
    embedding_dimensions: value.embedding_dimensions,
    embedding_batch_size: value.embedding_batch_size,
    embedding_timeout_seconds: value.embedding_timeout_seconds,
    rerank_base_url: value.rerank_base_url,
    rerank_api_key: '',
    rerank_model: value.rerank_model,
    rerank_timeout_seconds: value.rerank_timeout_seconds,
  };
}

function inputValue(event: Event): string { return (event.target as HTMLInputElement).value; }
function toMessage(err: unknown): string { return String(err instanceof Error ? err.message : err); }
