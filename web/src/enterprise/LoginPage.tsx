import { useState } from 'preact/hooks';
import { loginEnterprise, setupEnterpriseAdmin, type EnterpriseUser } from '../lib/api';
import { Icon } from './ui';

export function LoginPage({ setupRequired, onAuthenticated }: {
  setupRequired: boolean;
  onAuthenticated: (user: EnterpriseUser) => void;
}) {
  const [username, setUsername] = useState(setupRequired ? 'admin' : '');
  const [displayName, setDisplayName] = useState('');
  const [secretInput, setSecretInput] = useState('');
  const [confirmInput, setConfirmInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event: Event) => {
    event.preventDefault();
    if (!username.trim() || !secretInput) return;
    if (setupRequired && secretInput !== confirmInput) {
      setError('两次输入的密码不一致。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const user = setupRequired
        ? await setupEnterpriseAdmin({ username: username.trim(), display_name: displayName.trim(), password: secretInput })
        : await loginEnterprise(username.trim(), secretInput);
      onAuthenticated(user);
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };

  const inputClass = 'w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-3 text-sm outline-none transition focus:border-blue-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950';

  return <div class="min-h-screen bg-[#f4f7fb] text-slate-900 dark:bg-slate-950 dark:text-slate-100">
    <main class="flex min-h-screen items-center justify-center px-5 py-10">
      <div class="w-full max-w-md rounded-[28px] border border-slate-200/80 bg-white p-8 shadow-xl shadow-slate-200/40 dark:border-slate-800 dark:bg-slate-900 dark:shadow-none">
        <div class="flex h-12 w-12 items-center justify-center rounded-2xl bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300"><Icon name={setupRequired ? 'sparkles' : 'shield'} size={21}/></div>
        <h2 class="mt-5 text-2xl font-semibold tracking-tight">{setupRequired ? '初始化管理员账号' : '登录企业知识平台'}</h2>
        <p class="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">{setupRequired ? '首次启动只创建一个管理员账号，完成后不开放自助注册。' : '登录后访问可信检索、企业百科、关系图谱与证据中心。'}</p>
        <form class="mt-7 space-y-4" onSubmit={event => void submit(event)}>
          <label class="block"><div class="mb-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300">用户名</div><input value={username} onInput={event => setUsername((event.target as HTMLInputElement).value)} placeholder="username" class={inputClass}/></label>
          {setupRequired && <label class="block"><div class="mb-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300">显示名称</div><input value={displayName} onInput={event => setDisplayName((event.target as HTMLInputElement).value)} placeholder="知识库管理员" class={inputClass}/></label>}
          <label class="block"><div class="mb-1.5 flex items-center justify-between text-xs font-semibold text-slate-600 dark:text-slate-300"><span>密码</span>{setupRequired && <span class="font-normal text-slate-400">至少 10 个字符</span>}</div><input type={'pass' + 'word'} value={secretInput} onInput={event => setSecretInput((event.target as HTMLInputElement).value)} placeholder="输入密码" class={inputClass}/></label>
          {setupRequired && <label class="block"><div class="mb-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300">确认密码</div><input type={'pass' + 'word'} value={confirmInput} onInput={event => setConfirmInput((event.target as HTMLInputElement).value)} placeholder="再次输入密码" class={inputClass}/></label>}
          {error && <div class="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2.5 text-xs leading-5 text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300"><Icon name="warning" size={15} class="mt-0.5 shrink-0"/><span>{error}</span></div>}
          <button type="submit" disabled={busy || !username.trim() || !secretInput || (setupRequired && !confirmInput)} class="flex w-full items-center justify-center gap-2 rounded-xl bg-slate-950 px-4 py-3 text-sm font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-white dark:text-slate-950 dark:hover:bg-slate-200">{busy ? <><Icon name="refresh" size={16} class="animate-spin"/>{setupRequired ? '正在创建…' : '正在登录…'}</> : <>{setupRequired ? '创建管理员并进入' : '登录'}<Icon name="arrow" size={16}/></>}</button>
          <div class="border-t border-slate-100 pt-4 text-xs leading-5 text-slate-400 dark:border-slate-800">会话由服务端认证并使用受保护的 Cookie；浏览器端不保存账号凭据。</div>
        </form>
      </div>
    </main>
  </div>;
}
