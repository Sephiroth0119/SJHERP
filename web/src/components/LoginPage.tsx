/**
 * 登录页（M2-T05）：用户名 + 密码 → POST /api/auth/login。
 * 成功后 token 与用户信息已由 authApi 写入 localStorage，回调通知 App 进入主界面；
 * 失败（密码错误 401 / 账号停用 / 网络异常）就地展示中文错误文案。
 */
import { useState, type FormEvent } from 'react';
import { login } from '../api/authApi';
import { ApiError, type AuthUser } from '../api/http';

interface LoginPageProps {
  onLogin: (user: AuthUser) => void;
}

export function LoginPage({ onLogin }: LoginPageProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      const user = await login(username.trim(), password);
      onLogin(user);
    } catch (e) {
      setPending(false);
      setError(e instanceof ApiError ? e.message : '登录失败，请稍后重试');
    }
  };

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-brand">
          <span className="login-brand-name">SJHERP</span>
          <span className="login-brand-sub">Agent 原生 ERP</span>
        </div>
        <label className="login-field">
          <span>用户名</span>
          <input
            type="text"
            value={username}
            autoComplete="username"
            autoFocus
            onChange={(e) => setUsername(e.target.value)}
          />
        </label>
        <label className="login-field">
          <span>密码</span>
          <input
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <div className="login-error">{error}</div>}
        <button
          type="submit"
          className="login-submit"
          disabled={pending || username.trim() === '' || password === ''}
        >
          {pending ? '登录中…' : '登录'}
        </button>
      </form>
    </div>
  );
}
