import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { createEnterpriseUser, fetchEnterpriseUsers, resetEnterpriseUserPassword, updateEnterpriseAccess, updateEnterpriseUser, type EnterpriseAccessPolicy, type EnterpriseCapabilities, type EnterpriseRole, type EnterpriseUser } from '../lib/api';
import { Icon, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate } from './ui';

export function AdminPage({ capabilities, currentUser, access, onAccessChanged, onError }: { capabilities: EnterpriseCapabilities | null; currentUser: EnterpriseUser; access: EnterpriseAccessPolicy | null; onAccessChanged: (access: EnterpriseAccessPolicy) => void; onError: (message: string) => void }) {
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

  const capabilityRows: Array<{ key: keyof EnterpriseCapabilities; label: string; description: string; icon: 'documents' | 'tasks' | 'evidence' | 'search' | 'download' | 'sparkles' | 'graph' | 'server' | 'admin' | 'shield' }> = [
    { key: 'auth', label: '身份认证', description: '本地账号与受保护会话', icon: 'admin' },
    { key: 'users', label: '用户管理', description: '管理员与普通用户角色控制', icon: 'admin' },
    { key: 'upload', label: '资料上传', description: '网页资料入库', icon: 'documents' },
    { key: 'batch_ingestion', label: '批量入库', description: '文件夹 / ZIP 暂存与后台处理', icon: 'documents' },
    { key: 'documents', label: '资料与版本', description: 'MySQL 知识资产信息', icon: 'documents' },
    { key: 'tasks', label: '任务管理', description: '资料处理与知识生成阶段可见', icon: 'tasks' },
    { key: 'audit', label: '审计日志', description: '用户 / 操作 / 对象记录', icon: 'shield' },
    { key: 'evidence', label: '证据解析', description: '精确回溯原文来源', icon: 'evidence' },
    { key: 'source_viewer', label: '原文查看', description: '经过认证的原文件查看器', icon: 'evidence' },
    { key: 'ask', label: '知识查询', description: '带原文依据的知识查询入口', icon: 'sparkles' },
    { key: 'ask_answering', label: '回答模型', description: '已配置兼容的大模型', icon: 'sparkles' },
    { key: 'search', label: '统一检索', description: '原文 / 摘要 / 百科 / 图谱', icon: 'search' },
    { key: 'package_sources', label: '来源资料包', description: '按精确版本去重打包 ZIP', icon: 'download' },
    { key: 'generation_recommendation', label: '知识生成推荐', description: '基于最终有效引用生成推荐', icon: 'sparkles' },
    { key: 'graph', label: 'Neo4j 关系图谱', description: '有证据支撑的有限层级关系查询', icon: 'graph' },
    { key: 'web', label: '企业知识网页端', description: '同源知识管理工作台', icon: 'server' },
  ];

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle title="用户与权限" action={<PrimaryButton onClick={() => setUserDialogOpen(true)} icon="admin">添加用户</PrimaryButton>} />
      <div class="mt-5 flex items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950/50">
        <div><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">允许普通用户添加资料</div><div class="mt-1 text-xs text-slate-400">开启后，普通用户可以在“资料管理”中添加资料。</div></div>
        <button type="button" role="switch" aria-checked={Boolean(access?.user_upload_enabled)} onClick={() => void toggleUserUpload()} class={`relative h-7 w-12 shrink-0 rounded-full transition ${access?.user_upload_enabled ? 'bg-blue-600' : 'bg-slate-300 dark:bg-slate-700'}`}><span class={`absolute top-1 h-5 w-5 rounded-full bg-white shadow transition ${access?.user_upload_enabled ? 'left-6' : 'left-1'}`}/></button>
      </div>
      <div class="mt-5 overflow-x-auto rounded-xl border border-slate-100 dark:border-slate-800">
        <table class="min-w-full text-sm">
          <thead class="bg-slate-50 text-left text-[11px] font-semibold tracking-[0.1em] text-slate-400 dark:bg-slate-950/50"><tr><th class="px-4 py-3">用户</th><th class="px-4 py-3">角色</th><th class="px-4 py-3">状态</th><th class="px-4 py-3">最后登录</th><th class="px-4 py-3 text-right">操作</th></tr></thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-800">
            {users.map(item => <tr key={item.user_id} class="align-middle">
              <td class="px-4 py-3"><div class="flex items-center gap-3"><div class="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-200">{(item.display_name || item.username).slice(0, 1).toUpperCase()}</div><div><div class="font-semibold text-slate-700 dark:text-slate-200">{item.display_name || item.username}{item.user_id === currentUser.user_id && <span class="ml-2 text-[10px] font-medium text-blue-500">当前账号</span>}</div><div class="mt-0.5 text-xs text-slate-400">@{item.username}</div></div></div></td>
              <td class="px-4 py-3"><select value={item.role} disabled={item.user_id === currentUser.user_id} onChange={event => void updateRole(item, (event.target as HTMLSelectElement).value as EnterpriseRole)} class="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold dark:border-slate-700 dark:bg-slate-900"><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select></td>
              <td class="px-4 py-3"><StatusBadge value={item.status}/></td>
              <td class="px-4 py-3 text-xs text-slate-400">{formatDate(item.last_login_at)}</td>
              <td class="px-4 py-3"><div class="flex justify-end gap-2"><SecondaryButton onClick={() => setResetUser(item)} disabled={item.user_id === currentUser.user_id}>重置密码</SecondaryButton><button disabled={item.user_id === currentUser.user_id} onClick={() => void toggleStatus(item)} class={`rounded-lg border px-2.5 py-1.5 text-xs font-semibold disabled:cursor-not-allowed disabled:opacity-40 ${item.status === 'ACTIVE' ? 'border-rose-200 text-rose-600 hover:bg-rose-50 dark:border-rose-900 dark:text-rose-400' : 'border-blue-200 text-blue-600 hover:bg-blue-50 dark:border-blue-900 dark:text-blue-300'}`}>{item.status === 'ACTIVE' ? '禁用' : '启用'}</button></div></td>
            </tr>)}
            {!usersLoading && users.length === 0 && <tr><td colSpan={5} class="px-4 py-8 text-center text-sm text-slate-400">暂无用户记录。</td></tr>}
            {usersLoading && <tr><td colSpan={5} class="px-4 py-8 text-center text-sm text-slate-400">正在加载用户…</td></tr>}
          </tbody>
        </table>
      </div>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle title="系统状态" action={<span class={`rounded-full px-2.5 py-1 text-xs font-semibold ${serverOnline === false ? 'bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-300' : serverOnline === true ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'}`}>{serverOnline == null ? '检查中' : serverOnline ? '服务正常' : '服务异常'}</span>} />
      <div class="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{capabilityRows.map(row => {
        const enabled = Boolean(capabilities?.[row.key]);
        return <div key={row.key} class="flex items-center gap-3 rounded-xl border border-slate-100 p-3 dark:border-slate-800"><div class={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${enabled ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300' : 'bg-slate-100 text-slate-400 dark:bg-slate-800'}`}><Icon name={row.icon} size={17}/></div><div class="min-w-0 flex-1"><div class="flex items-center justify-between gap-2"><div class="truncate text-sm font-semibold">{row.label}</div><span class={`text-[10px] font-semibold ${enabled ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400'}`}>{enabled ? '正常' : '未启用'}</span></div><div class="mt-0.5 truncate text-[11px] text-slate-400">{row.description}</div></div></div>;
      })}</div>
    </section>
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
  return <Modal title="添加用户" description="创建后即可使用新账号登录。" onClose={onClose}>
    <div class="grid gap-3 sm:grid-cols-2"><AdminField label="用户名"><input value={username} onInput={event => setUsername((event.target as HTMLInputElement).value)} class={inputClass}/></AdminField><AdminField label="显示名称"><input value={displayName} onInput={event => setDisplayName((event.target as HTMLInputElement).value)} class={inputClass}/></AdminField><AdminField label="角色"><select value={role} onChange={event => setRole((event.target as HTMLSelectElement).value as EnterpriseRole)} class={inputClass}><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select></AdminField><AdminField label="初始密码"><input type={'pass' + 'word'} value={secretInput} onInput={event => setSecretInput((event.target as HTMLInputElement).value)} class={inputClass}/></AdminField></div>
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
  return <div class="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/35 p-4 backdrop-blur-[1px]" onClick={onClose}><div class="w-full max-w-xl rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={event => event.stopPropagation()}><div class="flex items-start justify-between gap-4"><div><h3 class="text-lg font-semibold">{title}</h3><p class="mt-1 text-xs leading-5 text-slate-400">{description}</p></div><button onClick={onClose} class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close" size={16}/></button></div><div class="mt-5">{children}</div></div></div>;
}

function AdminField({ label, children }: { label: string; children: ComponentChildren }) {
  return <label class="block text-xs font-medium text-slate-500"><div class="mb-1.5">{label}</div>{children}</label>;
}
