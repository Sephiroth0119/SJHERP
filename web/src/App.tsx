/**
 * 应用根组件：登录态守卫（M2-T05）+ 左侧业务入口菜单 + 主区域（默认 Agent 聊天）。
 *
 * 登录态：token 与用户信息存 localStorage；启动时若有 token 则后台调 /api/auth/me
 * 校验并刷新展示信息（token 过期/用户被停用 → 全局 401 拦截自动登出）。
 */
import { useCallback, useEffect, useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { ChatPanel } from './components/chat/ChatPanel';
import { LoginPage } from './components/LoginPage';
import { ModulePlaceholder } from './components/ModulePlaceholder';
import { MemoryGovernancePage } from './components/memory/MemoryGovernancePage';
import { NotificationBell } from './components/NotificationBell';
import { MODULE_NAV_ITEMS, type ModuleKey } from './types/navigation';
import {
  clearAuth,
  getStoredUser,
  getToken,
  setStoredUser,
  setUnauthorizedHandler,
  type AuthUser,
} from './api/http';
import { fetchMe, formatRoles } from './api/authApi';
import { CHAT_SESSION_STORAGE_KEY } from './api/chatApi';
import { canAccessModule } from './security/access';
import { isTokenExpiringSoon } from './security/token';
import { guardModule, moduleFromHash, routeForModule } from './security/routeGuard';

export function App() {
  // 有 token 即先按已登录渲染（展示信息取本地缓存），后台 me 校验兜底
  const [user, setUser] = useState<AuthUser | null>(() => (getToken() ? getStoredUser() : null));
  const [activeModule, setActiveModule] = useState<ModuleKey>(() =>
    moduleFromHash(typeof window === 'undefined' ? '' : window.location.hash));
  const [nowMs, setNowMs] = useState(() => Date.now());

  // 401 全局拦截：任何接口返回未登录/过期 → 清登录态（http.ts 已清）并切回登录页
  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null));
    return () => setUnauthorizedHandler(null);
  }, []);

  // 启动校验 token 并刷新用户展示信息（角色变更/改名即时生效）
  useEffect(() => {
    if (!getToken()) return;
    fetchMe()
      .then((me) => {
        const fresh: AuthUser = {
          username: me.username,
          displayName: me.displayName,
          roles: me.roles,
          permissions: me.permissions ?? [],
        };
        setStoredUser(fresh);
        setUser(fresh);
      })
      .catch(() => {
        // 401 已由全局拦截处理（登出）；网络异常保持现状，等首个业务请求兜底
      });
  }, []);

  /** 退出登录：清 token/用户信息与当前聊天会话 id（避免下个登录者串会话） */
  const handleLogout = useCallback(() => {
    clearAuth();
    localStorage.removeItem(CHAT_SESSION_STORAGE_KEY);
    setUser(null);
  }, []);

  const permissions = user?.permissions ?? [];
  const canManageMemory = canAccessModule('memory', permissions);

  useEffect(() => {
    if (!user) return;
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, [user]);

  useEffect(() => {
    if (user && !getToken()) setUser(null);
  }, [nowMs, user]);

  useEffect(() => {
    const onHashChange = () => setActiveModule(moduleFromHash(window.location.hash));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    if (!user) return;
    const requested = moduleFromHash(window.location.hash);
    const guarded = guardModule(requested, permissions);
    if (guarded !== activeModule) setActiveModule(guarded);
    if (requested !== guarded || window.location.hash === '') {
      window.history.replaceState(null, '', routeForModule(guarded));
    }
  }, [activeModule, permissions, user]);

  if (!user) {
    return <LoginPage onLogin={setUser} />;
  }

  const guardedModule = guardModule(activeModule, permissions);
  const activeItem = MODULE_NAV_ITEMS.find((item) => item.key === guardedModule);
  const selectModule = useCallback((module: ModuleKey) => {
    const guarded = guardModule(module, permissions);
    setActiveModule(guarded);
    window.history.replaceState(null, '', routeForModule(guarded));
  }, [permissions]);
  const tokenWarning = isTokenExpiringSoon(getToken() ?? '', nowMs);

  return (
    <div className="app-layout">
      <Sidebar active={guardedModule} onSelect={selectModule} permissions={permissions} />
      <main className="app-main">
        <header className="app-topbar">
          <NotificationBell />
          {tokenWarning && <span className="app-token-warning">登录会话即将过期，请重新登录</span>}
          <span className="app-topbar-user" title={`角色：${formatRoles(user.roles)}`}>
            {user.displayName}
            <span className="app-topbar-roles">{formatRoles(user.roles)}</span>
          </span>
          <button type="button" className="app-topbar-logout" onClick={handleLogout}>
            退出
          </button>
        </header>
        {guardedModule === 'memory' && canManageMemory ? (
          <MemoryGovernancePage />
        ) : guardedModule === 'agent' || !activeItem || guardedModule === 'memory' ? (
          <ChatPanel />
        ) : (
          <ModulePlaceholder module={activeItem} />
        )}
      </main>
    </div>
  );
}
