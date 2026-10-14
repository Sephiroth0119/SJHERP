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
import { MemoryGovernancePage } from './components/memory/MemoryGovernancePage';
import { ProductionWorkbench } from './components/ProductionWorkbench';
import { CustomerWorkbench } from './components/CustomerWorkbench';
import { NotificationBell } from './components/NotificationBell';
import { SupplierWorkbench, WarehouseWorkbench } from './components/MasterDataWorkbench';
import { ProductWorkbench } from './components/ProductWorkbench';
import { CollectionWorkbench } from './components/CollectionWorkbench';
import { PayableAgingWorkbench, ReceivableAgingWorkbench } from './components/AgingReportWorkbench';
import { BalanceSheetWorkbench, IncomeStatementWorkbench } from './components/FinancialStatementWorkbench';
import { FundAccountWorkbench } from './components/FundAccountWorkbench';
import { GlAccountWorkbench } from './components/GlAccountWorkbench';
import { PeriodCloseWorkbench } from './components/PeriodCloseWorkbench';
import { InventoryLedgerWorkbench } from './components/InventoryLedgerWorkbench';
import { PayableLedgerWorkbench } from './components/PayableLedgerWorkbench';
import { PaymentWorkbench } from './components/PaymentWorkbench';
import { ReceivableLedgerWorkbench } from './components/ReceivableLedgerWorkbench';
import { VoucherWorkbench } from './components/VoucherWorkbench';
import { PurchaseInvoiceWorkbench } from './components/PurchaseInvoiceWorkbench';
import { StockCountWorkbench } from './components/StockCountWorkbench';
import { TransferWorkbench } from './components/TransferWorkbench';
import { PurchaseOrderWorkbench } from './components/PurchaseOrderWorkbench';
import { PurchaseReceiptWorkbench } from './components/PurchaseReceiptWorkbench';
import { SalesDeliveryWorkbench } from './components/SalesDeliveryWorkbench';
import { SalesInvoiceWorkbench } from './components/SalesInvoiceWorkbench';
import { SalesOrderWorkbench } from './components/SalesOrderWorkbench';
import { type ModuleKey } from './types/navigation';
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
        ) : guardedModule === 'finance' ? (
          <FinanceWorkbench permissions={permissions} />
        ) : guardedModule === 'production' ? (
          <ProductionWorkbench permissions={permissions} />
        ) : guardedModule === 'memory' && canManageMemory ? (
          <MemoryGovernancePage />
        ) : (
          <ChatPanel />
        )}
      </main>
    </div>
  );
}

function SalesWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<'customer' | 'order' | 'delivery' | 'invoice' | 'collection'>('customer');
  const canUseSalesOrders = permissions.includes('sales:order');
  const canUseSalesDeliveries = permissions.includes('sales:delivery');
  const canUseSalesInvoices = permissions.includes('sales:invoice');
  const canUseCollections = permissions.includes('finance:settlement');
  const activeTab = tab === 'order' && canUseSalesOrders
    ? 'order'
    : tab === 'delivery' && canUseSalesDeliveries
      ? 'delivery'
      : tab === 'invoice' && canUseSalesInvoices
        ? 'invoice'
      : tab === 'collection' && canUseCollections
        ? 'collection'
      : 'customer';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="销售工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'customer'} className={activeTab === 'customer' ? 'memory-tab-active' : ''} onClick={() => setTab('customer')}>客户档案</button>
      {canUseSalesOrders && <button type="button" role="tab" aria-selected={activeTab === 'order'} className={activeTab === 'order' ? 'memory-tab-active' : ''} onClick={() => setTab('order')}>销售订单</button>}
      {canUseSalesDeliveries && <button type="button" role="tab" aria-selected={activeTab === 'delivery'} className={activeTab === 'delivery' ? 'memory-tab-active' : ''} onClick={() => setTab('delivery')}>销售出库</button>}
      {canUseSalesInvoices && <button type="button" role="tab" aria-selected={activeTab === 'invoice'} className={activeTab === 'invoice' ? 'memory-tab-active' : ''} onClick={() => setTab('invoice')}>销售发票</button>}
      {canUseCollections && <button type="button" role="tab" aria-selected={activeTab === 'collection'} className={activeTab === 'collection' ? 'memory-tab-active' : ''} onClick={() => setTab('collection')}>收款</button>}
    </div>
    {activeTab === 'order'
      ? <SalesOrderWorkbench />
      : activeTab === 'delivery'
        ? <SalesDeliveryWorkbench />
        : activeTab === 'invoice'
          ? <SalesInvoiceWorkbench />
        : activeTab === 'collection'
          ? <CollectionWorkbench />
      : <CustomerWorkbench permissions={permissions} />}
  </>;
}

function PurchaseWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<
    'supplier' | 'order' | 'receipt' | 'invoice' | 'payment'
  >('supplier');
  const canUseOrders = permissions.includes('purchase:order');
  const canUseReceipts = permissions.includes('purchase:receipt');
  const canUseInvoices = permissions.includes('purchase:invoice');
  const canUsePayments = permissions.includes('finance:settlement');
  const activeTab = tab === 'order' && canUseOrders
    ? 'order'
    : tab === 'receipt' && canUseReceipts
      ? 'receipt'
      : tab === 'invoice' && canUseInvoices
        ? 'invoice'
      : tab === 'payment' && canUsePayments
        ? 'payment'
      : 'supplier';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="采购工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'supplier'} className={activeTab === 'supplier' ? 'memory-tab-active' : ''} onClick={() => setTab('supplier')}>供应商档案</button>
      {canUseOrders && <button type="button" role="tab" aria-selected={activeTab === 'order'} className={activeTab === 'order' ? 'memory-tab-active' : ''} onClick={() => setTab('order')}>采购订单</button>}
      {canUseReceipts && <button type="button" role="tab" aria-selected={activeTab === 'receipt'} className={activeTab === 'receipt' ? 'memory-tab-active' : ''} onClick={() => setTab('receipt')}>采购入库</button>}
      {canUseInvoices && <button type="button" role="tab" aria-selected={activeTab === 'invoice'} className={activeTab === 'invoice' ? 'memory-tab-active' : ''} onClick={() => setTab('invoice')}>采购发票</button>}
      {canUsePayments && <button type="button" role="tab" aria-selected={activeTab === 'payment'} className={activeTab === 'payment' ? 'memory-tab-active' : ''} onClick={() => setTab('payment')}>付款</button>}
    </div>
    {activeTab === 'order'
      ? <PurchaseOrderWorkbench />
      : activeTab === 'receipt'
        ? <PurchaseReceiptWorkbench />
        : activeTab === 'invoice'
          ? <PurchaseInvoiceWorkbench />
        : activeTab === 'payment'
          ? <PaymentWorkbench />
      : <SupplierWorkbench permissions={permissions} />}
  </>;
}

function InventoryWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<'warehouse' | 'product' | 'count' | 'transfer' | 'ledger'>('warehouse');
  const canUseCount = permissions.includes('inventory:count');
  const canUseTransfer = permissions.includes('inventory:transfer');
  const canAdjust = permissions.includes('inventory:adjust');
  const activeTab = tab === 'count' && canUseCount
    ? 'count'
    : tab === 'transfer' && canUseTransfer
      ? 'transfer'
      : tab === 'ledger'
        ? 'ledger'
        : tab === 'product'
          ? 'product'
          : 'warehouse';

  return <>
    <div className="memory-tabs inventory-workbench-tabs" role="tablist" aria-label="库存工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'warehouse'} className={activeTab === 'warehouse' ? 'memory-tab-active' : ''} onClick={() => setTab('warehouse')}>仓库档案</button>
      <button type="button" role="tab" aria-selected={activeTab === 'product'} className={activeTab === 'product' ? 'memory-tab-active' : ''} onClick={() => setTab('product')}>商品档案</button>
      {canUseCount && <button type="button" role="tab" aria-selected={activeTab === 'count'} className={activeTab === 'count' ? 'memory-tab-active' : ''} onClick={() => setTab('count')}>盘点</button>}
      {canUseTransfer && <button type="button" role="tab" aria-selected={activeTab === 'transfer'} className={activeTab === 'transfer' ? 'memory-tab-active' : ''} onClick={() => setTab('transfer')}>调拨</button>}
      <button type="button" role="tab" aria-selected={activeTab === 'ledger'} className={activeTab === 'ledger' ? 'memory-tab-active' : ''} onClick={() => setTab('ledger')}>台账</button>
    </div>
    {activeTab === 'count'
      ? <StockCountWorkbench />
      : activeTab === 'transfer'
        ? <TransferWorkbench />
        : activeTab === 'ledger'
          ? <InventoryLedgerWorkbench canAdjust={canAdjust} />
          : activeTab === 'product'
            ? <ProductWorkbench permissions={permissions} />
            : <WarehouseWorkbench permissions={permissions} />}
  </>;
}

function FinanceWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<
    | 'fund' | 'account' | 'voucher' | 'period'
    | 'payable' | 'receivable' | 'receivableAging' | 'payableAging'
    | 'balanceSheet' | 'incomeStatement'
  >('fund');
  const canWriteFunds = permissions.includes('finance:payment_account');
  const canViewSettlements = permissions.includes('finance:settlement');
  const canViewAging = permissions.includes('finance:settlement');
  const canUseReceivables = permissions.includes('sales:invoice');
  const canUseVouchers = permissions.includes('finance:voucher');
  const canManageAccounts = permissions.includes('finance:account');
  const canManagePeriods = permissions.includes('finance:period');
  const canReopenPeriods = permissions.includes('finance:period_reopen');
  const canViewReports = permissions.includes('finance:report');
  const activeTab = tab === 'receivable' && canUseReceivables
    ? 'receivable'
    : tab === 'voucher' && canUseVouchers
      ? 'voucher'
      : tab === 'account' && canManageAccounts
        ? 'account'
        : tab === 'period' && canManagePeriods
          ? 'period'
      : tab === 'receivableAging' && canViewAging
        ? 'receivableAging'
        : tab === 'payableAging' && canViewAging
          ? 'payableAging'
          : tab === 'balanceSheet' && canViewReports
            ? 'balanceSheet'
            : tab === 'incomeStatement' && canViewReports
              ? 'incomeStatement'
          : tab === 'payable'
            ? 'payable'
            : 'fund';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="财务工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'fund'}
        className={activeTab === 'fund' ? 'memory-tab-active' : ''}
        onClick={() => setTab('fund')}>资金账户</button>
      {canManageAccounts && <button type="button" role="tab" aria-selected={activeTab === 'account'}
        className={activeTab === 'account' ? 'memory-tab-active' : ''}
        onClick={() => setTab('account')}>科目</button>}
      {canUseVouchers && <button type="button" role="tab" aria-selected={activeTab === 'voucher'}
        className={activeTab === 'voucher' ? 'memory-tab-active' : ''}
        onClick={() => setTab('voucher')}>凭证</button>}
      {canManagePeriods && <button type="button" role="tab" aria-selected={activeTab === 'period'}
        className={activeTab === 'period' ? 'memory-tab-active' : ''}
        onClick={() => setTab('period')}>账期关账</button>}
      <button type="button" role="tab" aria-selected={activeTab === 'payable'}
        className={activeTab === 'payable' ? 'memory-tab-active' : ''}
        onClick={() => setTab('payable')}>应付台账</button>
      {canViewAging && <button type="button" role="tab" aria-selected={activeTab === 'payableAging'}
        className={activeTab === 'payableAging' ? 'memory-tab-active' : ''}
        onClick={() => setTab('payableAging')}>应付账龄</button>}
      {canUseReceivables && <button type="button" role="tab" aria-selected={activeTab === 'receivable'}
        className={activeTab === 'receivable' ? 'memory-tab-active' : ''}
        onClick={() => setTab('receivable')}>应收台账</button>}
      {canViewAging && <button type="button" role="tab" aria-selected={activeTab === 'receivableAging'}
        className={activeTab === 'receivableAging' ? 'memory-tab-active' : ''}
        onClick={() => setTab('receivableAging')}>应收账龄</button>}
      {canViewReports && <button type="button" role="tab" aria-selected={activeTab === 'balanceSheet'}
        className={activeTab === 'balanceSheet' ? 'memory-tab-active' : ''}
        onClick={() => setTab('balanceSheet')}>资产负债表</button>}
      {canViewReports && <button type="button" role="tab" aria-selected={activeTab === 'incomeStatement'}
        className={activeTab === 'incomeStatement' ? 'memory-tab-active' : ''}
        onClick={() => setTab('incomeStatement')}>利润表</button>}
    </div>
    {activeTab === 'payable'
      ? <PayableLedgerWorkbench canViewSettlements={canViewSettlements} />
      : activeTab === 'receivable'
        ? <ReceivableLedgerWorkbench canViewSettlements={canViewSettlements} />
        : activeTab === 'payableAging'
          ? <PayableAgingWorkbench />
          : activeTab === 'receivableAging'
            ? <ReceivableAgingWorkbench />
            : activeTab === 'voucher'
              ? <VoucherWorkbench canWrite={canUseVouchers} />
              : activeTab === 'account'
                ? <GlAccountWorkbench canWrite={canManageAccounts} />
                : activeTab === 'period'
                  ? <PeriodCloseWorkbench canClose={canManagePeriods} canReopen={canReopenPeriods} />
                  : activeTab === 'balanceSheet'
                    ? <BalanceSheetWorkbench />
                    : activeTab === 'incomeStatement'
                      ? <IncomeStatementWorkbench />
              : <FundAccountWorkbench canWrite={canWriteFunds} />}
  </>;
}
