import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { createEnterpriseUser, fetchEnterpriseUsers, resetEnterpriseUserPassword, updateEnterpriseAccess, updateEnterpriseUser, type EnterpriseAccessPolicy, type EnterpriseCapabilities, type EnterpriseOverview, type EnterpriseRole, type EnterpriseUser } from '../lib/api';
import { Icon, MetricCard, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate, formatNumber, pct } from './ui';

export function AdminPage({ capabilities, overview, currentUser, access, onAccessChanged, onError }: { capabilities: EnterpriseCapabilities | null; overview: EnterpriseOverview | null; currentUser: EnterpriseUser; access: EnterpriseAccessPolicy | null; onAccessChanged: (access: EnterpriseAccessPolicy) => void; onError: (message: string) => void }) {
  const [serverOnline, setServerOnline] = useState<boolean | null>(null);
  const [users, setUsers] = useState<EnterpriseUser[]>([]);
  const [userDialogOpen, setUserDialogOpen] = useState(false);
  const [resetUser, setResetUser] = useState<EnterpriseUser | null>(null);
  const [usersLoading, setUsersLoading] = useState(false);
  const loadUsers = async () => {
    setUsersLoading(true);
    try {
      const data = await fetchEnterpriseUsers();
      setUsers(data.users || []);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setUsersLoading(false);
    }
  };

  useEffect(() => {
    fetch('/health').then(response => setServerOnline(response.ok)).catch(() => setServerOnline(false));
    void loadUsers();
  }, []);

  const updateRole = async (target: EnterpriseUser, role: EnterpriseRole) => {
    try {
      await updateEnterpriseUser(target.user_id, { role });
      await loadUsers();
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const toggleStatus = async (target: EnterpriseUser) => {
    try {
      await updateEnterpriseUser(target.user_id, { status: target.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' });
      await loadUsers();
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const toggleUserUpload = async () => {
    try {
      const updated = await updateEnterpriseAccess(!access?.user_upload_enabled);
      onAccessChanged(updated);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const capabilityRows: Array<{ key: keyof EnterpriseCapabilities; label: string; description: string; icon: 'documents' | 'tasks' | 'evidence' | 'search' | 'download' | 'sparkles' | 'graph' | 'server' | 'admin' }> = [
    { key: 'auth', label: 'Authentication', description: 'Local account + protected session', icon: 'admin' },
    { key: 'users', label: 'User Management', description: 'ADMIN / USER role control', icon: 'admin' },
    { key: 'upload', label: 'Document Upload', description: 'MinerU-backed web ingestion', icon: 'documents' },
    { key: 'documents', label: 'Document / Version', description: 'MySQL knowledge asset metadata', icon: 'documents' },
    { key: 'tasks', label: 'Compile Task', description: 'Pipeline execution visibility', icon: 'tasks' },
    { key: 'evidence', label: 'Evidence Resolver', description: 'Exact Raw provenance resolution', icon: 'evidence' },
    { key: 'search', label: 'Unified Retrieval', description: 'Raw / Summary / Wiki / Graph', icon: 'search' },
    { key: 'package_sources', label: 'Source Package', description: 'Version-deduplicated ZIP delivery', icon: 'download' },
    { key: 'promotion', label: 'Distillation Promotion', description: 'Final effective-hit recommendation', icon: 'sparkles' },
    { key: 'graph', label: 'Neo4j Graph', description: 'Bounded evidence-backed traversal', icon: 'graph' },
    { key: 'web', label: 'Enterprise Web', description: 'Same-origin Preact workbench', icon: 'server' },
  ];

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="Knowledge Server" value={serverOnline == null ? 'CHECK' : serverOnline ? 'ONLINE' : 'OFFLINE'} hint="GET /health" icon="server" tone={serverOnline === false ? 'amber' : 'emerald'}/>
      <MetricCard label="Active Version" value={formatNumber(overview?.active_versions)} hint={`${formatNumber(overview?.failed_versions)} failed versions`} icon="documents" tone="blue"/>
      <MetricCard label="Grounding Pass" value={overview ? pct(overview.grounded_summaries, overview.active_summaries) : '—'} hint="ACTIVE Summary" icon="shield" tone="emerald"/>
      <MetricCard label="Coverage Pass" value={overview ? pct(overview.covered_summaries, overview.active_summaries) : '—'} hint="ACTIVE Summary" icon="activity" tone="violet"/>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle eyebrow="Access control" title="用户与权限" description="系统不开放自助注册。ADMIN 管理账号；USER 的上传入库能力由下面的全局开关控制。" action={<PrimaryButton onClick={() => setUserDialogOpen(true)} icon="admin">添加用户</PrimaryButton>} />
      <div class="mt-5 flex items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950/50">
        <div><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">允许 USER 添加文件 / 入库</div><div class="mt-1 text-xs leading-5 text-slate-400">关闭时只有 ADMIN 可上传；打开后所有 ACTIVE USER 都可以使用 Documents 页的“添加文件”，后端权限立即生效。</div></div>
        <button type="button" role="switch" aria-checked={Boolean(access?.user_upload_enabled)} onClick={() => void toggleUserUpload()} class={`relative h-7 w-12 shrink-0 rounded-full transition ${access?.user_upload_enabled ? 'bg-blue-600' : 'bg-slate-300 dark:bg-slate-700'}`}><span class={`absolute top-1 h-5 w-5 rounded-full bg-white shadow transition ${access?.user_upload_enabled ? 'left-6' : 'left-1'}`}/></button>
      </div>
      <div class="mt-5 overflow-x-auto rounded-xl border border-slate-100 dark:border-slate-800">
        <table class="min-w-full text-sm">
          <thead class="bg-slate-50 text-left text-[11px] font-semibold uppercase tracking-[0.1em] text-slate-400 dark:bg-slate-950/50"><tr><th class="px-4 py-3">User</th><th class="px-4 py-3">Role</th><th class="px-4 py-3">Status</th><th class="px-4 py-3">Last login</th><th class="px-4 py-3 text-right">Actions</th></tr></thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-800">
            {users.map(item => <tr key={item.user_id} class="align-middle">
              <td class="px-4 py-3"><div class="flex items-center gap-3"><div class="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-200">{(item.display_name || item.username).slice(0, 1).toUpperCase()}</div><div><div class="font-semibold text-slate-700 dark:text-slate-200">{item.display_name || item.username}{item.user_id === currentUser.user_id && <span class="ml-2 text-[10px] font-medium text-blue-500">当前账号</span>}</div><div class="mt-0.5 text-xs text-slate-400">@{item.username}</div></div></div></td>
              <td class="px-4 py-3"><select value={item.role} disabled={item.user_id === currentUser.user_id} onChange={event => void updateRole(item, (event.target as HTMLSelectElement).value as EnterpriseRole)} class="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold dark:border-slate-700 dark:bg-slate-900"><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></td>
              <td class="px-4 py-3"><StatusBadge value={item.status}/></td>
              <td class="px-4 py-3 text-xs text-slate-400">{formatDate(item.last_login_at)}</td>
              <td class="px-4 py-3"><div class="flex justify-end gap-2"><SecondaryButton onClick={() => setResetUser(item)} disabled={item.user_id === currentUser.user_id}>重置密码</SecondaryButton><button disabled={item.user_id === currentUser.user_id} onClick={() => void toggleStatus(item)} class={`rounded-lg border px-2.5 py-1.5 text-xs font-semibold disabled:cursor-not-allowed disabled:opacity-40 ${item.status === 'ACTIVE' ? 'border-rose-200 text-rose-600 hover:bg-rose-50 dark:border-rose-900 dark:text-rose-400' : 'border-emerald-200 text-emerald-600 hover:bg-emerald-50 dark:border-emerald-900 dark:text-emerald-400'}`}>{item.status === 'ACTIVE' ? '禁用' : '启用'}</button></div></td>
            </tr>)}
            {!usersLoading && users.length === 0 && <tr><td colSpan={5} class="px-4 py-8 text-center text-sm text-slate-400">暂无用户记录。</td></tr>}
            {usersLoading && <tr><td colSpan={5} class="px-4 py-8 text-center text-sm text-slate-400">正在加载用户…</td></tr>}
          </tbody>
        </table>
      </div>
    </section>

    <section class="grid gap-6 xl:grid-cols-[1.05fr_.95fr]">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Runtime capabilities" title="已接入能力" description="这里展示 Server 当前实际装配的 adapter，不展示 API Key、密码、DSN 或任何敏感环境变量。" />
        <div class="mt-5 grid gap-3 sm:grid-cols-2">{capabilityRows.map(row => {
          const enabled = Boolean(capabilities?.[row.key]);
          return <div key={row.key} class="flex items-center gap-3 rounded-xl border border-slate-100 p-3 dark:border-slate-800"><div class={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${enabled ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300' : 'bg-slate-100 text-slate-400 dark:bg-slate-800'}`}><Icon name={row.icon} size={17}/></div><div class="min-w-0 flex-1"><div class="flex items-center justify-between gap-2"><div class="truncate text-sm font-semibold">{row.label}</div><span class={`text-[10px] font-semibold uppercase tracking-wide ${enabled ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400'}`}>{enabled ? 'wired' : 'off'}</span></div><div class="mt-0.5 truncate text-[11px] text-slate-400">{row.description}</div></div></div>;
        })}</div>
      </div>

      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Trust contract" title="企业知识质量门" description="这些是系统行为约束，不是装饰性的“AI 可信度分数”。" />
        <div class="mt-5 space-y-3">
          <TrustRule title="Raw Retrieval 是第一硬 Gate" detail="BM25 + Vector + Metadata Filter 先保证原始资料可找回；不会为了高阶知识牺牲 Raw。" />
          <TrustRule title="Grounding 防编错，Coverage 防漏" detail="Summary / Wiki / Graph 都要求 Precision 与 Recall 两类质量门，而不是只检查生成内容看起来合理。" />
          <TrustRule title="Final Candidate 必须 Evidence-resolvable" detail="任何高阶候选如果不能回到 ACTIVE Raw Evidence，会在最终交付前被过滤。" />
          <TrustRule title="版本失效不改业务事实有效期" detail="V1 文件被 V2 替换只撤销旧 Evidence Support，不会错误地修改 Relation Fact 的 valid_to。" />
        </div>
      </div>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle eyebrow="Storage responsibilities" title="企业数据平面" description="四类存储各自负责清晰职责，上层通过 adapter 组合，不强塞回一个单体 Store。" />
      <div class="mt-5 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StorageCard title="MySQL" subtitle="事实与生命周期" items={['Document / Version', 'Evidence metadata', 'Summary / Wiki facts', 'Promotion & Task']}/>
        <StorageCard title="MinIO" subtitle="原始与派生对象" items={['Original files', 'Parsed artifacts', 'Summary / Wiki files', 'Source packages']}/>
        <StorageCard title="OpenSearch" subtitle="检索候选" items={['Raw BM25', 'Raw Vector', 'Summary / Wiki index', 'Metadata filter']}/>
        <StorageCard title="Neo4j" subtitle="关系与多跳" items={['Canonical Entity', 'Relation Fact', 'Evidence Support', 'Bounded traversal']}/>
      </div>
    </section>

    <section class="rounded-2xl border border-blue-200 bg-blue-50 p-5 text-sm leading-7 text-blue-900 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-200"><div class="flex items-start gap-3"><Icon name="shield" size={20} class="mt-1 shrink-0"/><div><div class="font-semibold">安全边界</div><p class="mt-1 text-blue-800/80 dark:text-blue-300/80">正常用户通过 HttpOnly + SameSite Session Cookie 登录；ADMIN/USER 权限由服务端强制。原 Bearer service token 仅作为兼容的自动化管理入口。系统状态页面不会回显模型 Key、数据库密码、MinIO Secret 或 Neo4j 凭据。</p></div></div></section>
    {userDialogOpen && <CreateUserDialog onClose={() => setUserDialogOpen(false)} onCreated={async () => { setUserDialogOpen(false); await loadUsers(); }} onError={onError}/>}
    {resetUser && <ResetUserDialog user={resetUser} onClose={() => setResetUser(null)} onReset={async value => { await resetEnterpriseUserPassword(resetUser.user_id, value); setResetUser(null); await loadUsers(); }} onError={onError}/>}
  </div>;
}

function CreateUserDialog({ onClose, onCreated, onError }: { onClose: () => void; onCreated: () => Promise<void> | void; onError: (message: string) => void }) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<EnterpriseRole>('USER');
  const [secretInput, setSecretInput] = useState('');
  const [busy, setBusy] = useState(false);
  const inputClass = 'w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950';
  const submit = async () => {
    setBusy(true);
    try {
      await createEnterpriseUser({ username: username.trim(), display_name: displayName.trim(), role, password: secretInput });
      onError('');
      await onCreated();
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };
  return <Modal title="添加企业用户" description="创建后即可使用登录页进入知识平台；系统不开放自助注册。" onClose={onClose}>
    <div class="grid gap-3 sm:grid-cols-2"><AdminField label="用户名"><input value={username} onInput={event => setUsername((event.target as HTMLInputElement).value)} class={inputClass}/></AdminField><AdminField label="显示名称"><input value={displayName} onInput={event => setDisplayName((event.target as HTMLInputElement).value)} class={inputClass}/></AdminField><AdminField label="角色"><select value={role} onChange={event => setRole((event.target as HTMLSelectElement).value as EnterpriseRole)} class={inputClass}><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></AdminField><AdminField label="初始密码"><input type={'pass' + 'word'} value={secretInput} onInput={event => setSecretInput((event.target as HTMLInputElement).value)} class={inputClass}/></AdminField></div>
    <div class="mt-5 flex justify-end gap-2"><SecondaryButton onClick={onClose}>取消</SecondaryButton><PrimaryButton onClick={() => void submit()} disabled={busy || username.trim().length < 3 || secretInput.length < 10} icon="admin">{busy ? '创建中…' : '创建用户'}</PrimaryButton></div>
  </Modal>;
}

function ResetUserDialog({ user, onClose, onReset, onError }: { user: EnterpriseUser; onClose: () => void; onReset: (value: string) => Promise<void>; onError: (message: string) => void }) {
  const [secretInput, setSecretInput] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      await onReset(secretInput);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };
  return <Modal title={`重置 ${user.display_name || user.username} 的密码`} description="重置后该用户现有会话会立即失效，需要使用新密码重新登录。" onClose={onClose}>
    <label class="block text-xs font-medium text-slate-500">新密码<input type={'pass' + 'word'} value={secretInput} onInput={event => setSecretInput((event.target as HTMLInputElement).value)} class="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></label>
    <div class="mt-5 flex justify-end gap-2"><SecondaryButton onClick={onClose}>取消</SecondaryButton><PrimaryButton onClick={() => void submit()} disabled={busy || secretInput.length < 10} icon="shield">{busy ? '处理中…' : '确认重置'}</PrimaryButton></div>
  </Modal>;
}

function Modal({ title, description, onClose, children }: { title: string; description: string; onClose: () => void; children: ComponentChildren }) {
  return <div class="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/35 p-4 backdrop-blur-[1px]" onClick={onClose}><div class="w-full max-w-xl rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={event => event.stopPropagation()}><div class="flex items-start justify-between gap-4"><div><div class="text-xs font-semibold uppercase tracking-[0.14em] text-blue-600 dark:text-blue-400">Access control</div><h3 class="mt-1 text-lg font-semibold">{title}</h3><p class="mt-1 text-xs leading-5 text-slate-400">{description}</p></div><button onClick={onClose} class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close" size={16}/></button></div><div class="mt-5">{children}</div></div></div>;
}

function AdminField({ label, children }: { label: string; children: ComponentChildren }) {
  return <label class="block text-xs font-medium text-slate-500"><div class="mb-1.5">{label}</div>{children}</label>;
}

function TrustRule({ title, detail }: { title: string; detail: string }) {
  return <div class="flex gap-3 rounded-xl bg-slate-50 p-3 dark:bg-slate-950/50"><div class="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 dark:bg-emerald-950/50 dark:text-emerald-300"><Icon name="check" size={14}/></div><div><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">{title}</div><div class="mt-1 text-xs leading-5 text-slate-400">{detail}</div></div></div>;
}

function StorageCard({ title, subtitle, items }: { title: string; subtitle: string; items: string[] }) {
  return <div class="rounded-2xl border border-slate-200 p-4 dark:border-slate-800"><div class="flex items-center gap-3"><div class="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name="database" size={17}/></div><div><div class="text-sm font-semibold">{title}</div><div class="text-[11px] text-slate-400">{subtitle}</div></div></div><ul class="mt-4 space-y-2">{items.map(item => <li key={item} class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400"><span class="h-1.5 w-1.5 rounded-full bg-slate-300 dark:bg-slate-600"/>{item}</li>)}</ul></div>;
}
