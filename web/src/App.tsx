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
import { CustomerWorkbench } from './components/CustomerWorkbench';
import { NotificationBell } from './components/NotificationBell';
import { SupplierWorkbench, WarehouseWorkbench } from './components/MasterDataWorkbench';
import { ProductWorkbench } from './components/ProductWorkbench';
import { PurchaseInvoiceWorkbench } from './components/PurchaseInvoiceWorkbench';
import { PurchaseOrderWorkbench } from './components/PurchaseOrderWorkbench';
import { PurchaseReceiptWorkbench } from './components/PurchaseReceiptWorkbench';
import { SalesDeliveryWorkbench } from './components/SalesDeliveryWorkbench';
import { SalesInvoiceWorkbench } from './components/SalesInvoiceWorkbench';
import { SalesOrderWorkbench } from './components/SalesOrderWorkbench';
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
    setUser(null);
  }, []);

  const permissions = user?.permissions ?? [];
  const canManageMemory = canAccessModule('memory', permissions);
  const selectModule = useCallback((module: ModuleKey) => {
    const guarded = guardModule(module, permissions);
    setActiveModule(guarded);
    window.history.replaceState(null, '', routeForModule(guarded));
  }, [permissions]);

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
        {guardedModule === 'sales' ? (
          <SalesWorkbench permissions={permissions} />
        ) : guardedModule === 'purchase' ? (
          <PurchaseWorkbench permissions={permissions} />
        ) : guardedModule === 'inventory' ? (
          <InventoryWorkbench permissions={permissions} />
        ) : guardedModule === 'memory' && canManageMemory ? (
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

function SalesWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<'customer' | 'order' | 'delivery' | 'invoice'>('customer');
  const canUseSalesOrders = permissions.includes('sales:order');
  const canUseSalesDeliveries = permissions.includes('sales:delivery');
  const canUseSalesInvoices = permissions.includes('sales:invoice');
  const activeTab = tab === 'order' && canUseSalesOrders
    ? 'order'
    : tab === 'delivery' && canUseSalesDeliveries
      ? 'delivery'
      : tab === 'invoice' && canUseSalesInvoices
        ? 'invoice'
      : 'customer';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="销售工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'customer'} className={activeTab === 'customer' ? 'memory-tab-active' : ''} onClick={() => setTab('customer')}>客户档案</button>
      {canUseSalesOrders && <button type="button" role="tab" aria-selected={activeTab === 'order'} className={activeTab === 'order' ? 'memory-tab-active' : ''} onClick={() => setTab('order')}>销售订单</button>}
      {canUseSalesDeliveries && <button type="button" role="tab" aria-selected={activeTab === 'delivery'} className={activeTab === 'delivery' ? 'memory-tab-active' : ''} onClick={() => setTab('delivery')}>销售出库</button>}
      {canUseSalesInvoices && <button type="button" role="tab" aria-selected={activeTab === 'invoice'} className={activeTab === 'invoice' ? 'memory-tab-active' : ''} onClick={() => setTab('invoice')}>销售发票</button>}
    </div>
    {activeTab === 'order'
      ? <SalesOrderWorkbench />
      : activeTab === 'delivery'
        ? <SalesDeliveryWorkbench />
        : activeTab === 'invoice'
          ? <SalesInvoiceWorkbench />
      : <CustomerWorkbench permissions={permissions} />}
  </>;
}

function PurchaseWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<
    'supplier' | 'order' | 'receipt' | 'invoice'
  >('supplier');
  const canUseOrders = permissions.includes('purchase:order');
  const canUseReceipts = permissions.includes('purchase:receipt');
  const canUseInvoices = permissions.includes('purchase:invoice');
  const activeTab = tab === 'order' && canUseOrders
    ? 'order'
    : tab === 'receipt' && canUseReceipts
      ? 'receipt'
      : tab === 'invoice' && canUseInvoices
        ? 'invoice'
      : 'supplier';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="采购工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'supplier'} className={activeTab === 'supplier' ? 'memory-tab-active' : ''} onClick={() => setTab('supplier')}>供应商档案</button>
      {canUseOrders && <button type="button" role="tab" aria-selected={activeTab === 'order'} className={activeTab === 'order' ? 'memory-tab-active' : ''} onClick={() => setTab('order')}>采购订单</button>}
      {canUseReceipts && <button type="button" role="tab" aria-selected={activeTab === 'receipt'} className={activeTab === 'receipt' ? 'memory-tab-active' : ''} onClick={() => setTab('receipt')}>采购入库</button>}
      {canUseInvoices && <button type="button" role="tab" aria-selected={activeTab === 'invoice'} className={activeTab === 'invoice' ? 'memory-tab-active' : ''} onClick={() => setTab('invoice')}>采购发票</button>}
    </div>
    {activeTab === 'order'
      ? <PurchaseOrderWorkbench />
      : activeTab === 'receipt'
        ? <PurchaseReceiptWorkbench />
        : activeTab === 'invoice'
          ? <PurchaseInvoiceWorkbench />
      : <SupplierWorkbench permissions={permissions} />}
  </>;
}

function InventoryWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<'warehouse' | 'product'>('warehouse');
  return <>
    <div className="memory-tabs inventory-workbench-tabs" role="tablist" aria-label="库存基础档案">
      <button type="button" role="tab" aria-selected={tab === 'warehouse'} className={tab === 'warehouse' ? 'memory-tab-active' : ''} onClick={() => setTab('warehouse')}>仓库档案</button>
      <button type="button" role="tab" aria-selected={tab === 'product'} className={tab === 'product' ? 'memory-tab-active' : ''} onClick={() => setTab('product')}>商品档案</button>
    </div>
    {tab === 'warehouse' ? <WarehouseWorkbench permissions={permissions} /> : <ProductWorkbench permissions={permissions} />}
  </>;
}
