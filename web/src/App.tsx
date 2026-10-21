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
import { ReportWorkbench } from './components/ReportWorkbench';
import { HelpWorkbench } from './components/HelpWorkbench';
import { OnboardingWizard } from './components/OnboardingWizard';
import {
  readOnboardingStatus,
  writeOnboardingStatus,
  type OnboardingStatus,
} from './onboarding/onboarding';
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
import { hashForRoute, parseWorkbenchRoute, resolveWorkbenchRoute, type WorkbenchRoute } from './security/workbenchRoute';
import { COMPACT_SHELL_MEDIA, useMediaQuery } from './layout/useMediaQuery';

export function App() {
  // 有 token 即先按已登录渲染（展示信息取本地缓存），后台 me 校验兜底
  const [user, setUser] = useState<AuthUser | null>(() => (getToken() ? getStoredUser() : null));
  const [route, setRoute] = useState<WorkbenchRoute>(() =>
    parseWorkbenchRoute(typeof window === 'undefined' ? '' : window.location.hash));
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
  const compactShell = useMediaQuery(COMPACT_SHELL_MEDIA);
  const [navOpen, setNavOpen] = useState(false);
  const [forceWizard, setForceWizard] = useState(false);
  const [onboardingStatus, setOnboardingStatus] = useState<OnboardingStatus>('pending');
  const selectModule = useCallback((module: ModuleKey) => {
    const next = resolveWorkbenchRoute({ module }, permissions);
    setRoute(next);
    window.history.replaceState(null, '', hashForRoute(next));
    setNavOpen(false);
  }, [permissions]);
  const selectTab = useCallback((tab: string) => {
    const next = resolveWorkbenchRoute({ module: route.module, tab }, permissions);
    setRoute(next);
    window.history.replaceState(null, '', hashForRoute(next));
  }, [permissions, route.module]);

  useEffect(() => {
    if (!user) return;
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, [user]);

  useEffect(() => {
    if (user && !getToken()) setUser(null);
  }, [nowMs, user]);

  useEffect(() => {
    const onHashChange = () => setRoute(parseWorkbenchRoute(window.location.hash));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    if (!compactShell) setNavOpen(false);
  }, [compactShell]);

  useEffect(() => {
    if (user) {
      setOnboardingStatus(readOnboardingStatus(user.username));
      return;
    }
    setForceWizard(false);
    setOnboardingStatus('pending');
  }, [user]);

  useEffect(() => {
    if (!navOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNavOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navOpen]);

  useEffect(() => {
    if (!user) return;
    const requested = parseWorkbenchRoute(window.location.hash);
    const guarded = resolveWorkbenchRoute(requested, permissions);
    const same = guarded.module === route.module
      && guarded.tab === route.tab
      && guarded.docNo === route.docNo;
    if (!same) setRoute(guarded);
    const expected = hashForRoute(guarded);
    if (window.location.hash === '' || window.location.hash !== expected) {
      window.history.replaceState(null, '', expected);
    }
  }, [permissions, route.docNo, route.module, route.tab, user]);

  if (!user) {
    return <LoginPage onLogin={setUser} />;
  }

  const guardedRoute = resolveWorkbenchRoute(route, permissions);
  const guardedModule = guardedRoute.module;
  const tokenWarning = isTokenExpiringSoon(getToken() ?? '', nowMs);
  const wizardOpen = forceWizard || onboardingStatus === 'pending';

  const closeWizard = (status: Exclude<OnboardingStatus, 'pending'>) => {
    writeOnboardingStatus(user.username, status);
    setOnboardingStatus(status);
    setForceWizard(false);
  };

  const openHash = (hash: string) => {
    const next = resolveWorkbenchRoute(parseWorkbenchRoute(hash), permissions);
    setRoute(next);
    window.history.replaceState(null, '', hashForRoute(next));
  };

  return (
    <div className={navOpen ? 'app-layout app-nav-open' : 'app-layout'}>
      {compactShell && navOpen && (
        <button
          type="button"
          className="app-nav-backdrop"
          aria-label="关闭导航"
          onClick={() => setNavOpen(false)}
        />
      )}
      <Sidebar active={guardedModule} onSelect={selectModule} permissions={permissions} />
      <main className="app-main">
        <header className="app-topbar">
          {compactShell && (
            <button
              type="button"
              className="app-nav-toggle"
              aria-expanded={navOpen}
              aria-controls="app-sidebar"
              onClick={() => setNavOpen((open) => !open)}
            >
              菜单
            </button>
          )}
          <div className="app-topbar-end">
            <NotificationBell />
            {tokenWarning && <span className="app-token-warning">登录会话即将过期，请重新登录</span>}
            <span className="app-topbar-user" title={`角色：${formatRoles(user.roles)}`}>
              {user.displayName}
              <span className="app-topbar-roles">{formatRoles(user.roles)}</span>
            </span>
            <button type="button" className="app-topbar-help" onClick={() => setForceWizard(true)}>
              新手引导
            </button>
            <button type="button" className="app-topbar-logout" onClick={handleLogout}>
              退出
            </button>
          </div>
        </header>
        {guardedModule === 'sales' ? (
          <SalesWorkbench
            permissions={permissions}
            tab={guardedRoute.tab}
            docNo={guardedRoute.docNo}
            onTabChange={selectTab}
          />
        ) : guardedModule === 'purchase' ? (
          <PurchaseWorkbench
            permissions={permissions}
            tab={guardedRoute.tab}
            docNo={guardedRoute.docNo}
            onTabChange={selectTab}
          />
        ) : guardedModule === 'inventory' ? (
          <InventoryWorkbench
            permissions={permissions}
            tab={guardedRoute.tab}
            docNo={guardedRoute.docNo}
            onTabChange={selectTab}
          />
        ) : guardedModule === 'finance' ? (
          <FinanceWorkbench
            permissions={permissions}
            tab={guardedRoute.tab}
            docNo={guardedRoute.docNo}
            onTabChange={selectTab}
          />
        ) : guardedModule === 'production' ? (
          <ProductionWorkbench
            permissions={permissions}
            tab={guardedRoute.tab}
            docNo={guardedRoute.docNo}
            onTabChange={selectTab}
          />
        ) : guardedModule === 'reports' ? (
          <ReportWorkbench
            permissions={permissions}
            tab={guardedRoute.tab}
            onTabChange={selectTab}
          />
        ) : guardedModule === 'help' ? (
          <HelpWorkbench
            onRestartOnboarding={() => setForceWizard(true)}
            onOpenHash={openHash}
          />
        ) : guardedModule === 'memory' && canManageMemory ? (
          <MemoryGovernancePage />
        ) : (
          <ChatPanel permissions={permissions} />
        )}
      </main>
      <OnboardingWizard
        open={wizardOpen}
        permissions={permissions}
        onDismiss={() => closeWizard('dismissed')}
        onComplete={() => closeWizard('completed')}
        onOpenHelp={() => {
          closeWizard('completed');
          selectModule('help');
        }}
        onOpenFirstOrder={(docNo) => {
          closeWizard('completed');
          const next = resolveWorkbenchRoute(
            { module: 'purchase', tab: 'order', docNo },
            permissions,
          );
          setRoute(next);
          window.history.replaceState(null, '', hashForRoute(next));
        }}
      />
    </div>
  );
}

interface ModuleWorkbenchProps {
  permissions: string[];
  tab?: string;
  docNo?: string;
  onTabChange: (tab: string) => void;
}

function SalesWorkbench({ permissions, tab, docNo, onTabChange }: ModuleWorkbenchProps) {
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
      <button type="button" role="tab" aria-selected={activeTab === 'customer'} className={activeTab === 'customer' ? 'memory-tab-active' : ''} onClick={() => onTabChange('customer')}>客户档案</button>
      {canUseSalesOrders && <button type="button" role="tab" aria-selected={activeTab === 'order'} className={activeTab === 'order' ? 'memory-tab-active' : ''} onClick={() => onTabChange('order')}>销售订单</button>}
      {canUseSalesDeliveries && <button type="button" role="tab" aria-selected={activeTab === 'delivery'} className={activeTab === 'delivery' ? 'memory-tab-active' : ''} onClick={() => onTabChange('delivery')}>销售出库</button>}
      {canUseSalesInvoices && <button type="button" role="tab" aria-selected={activeTab === 'invoice'} className={activeTab === 'invoice' ? 'memory-tab-active' : ''} onClick={() => onTabChange('invoice')}>销售发票</button>}
      {canUseCollections && <button type="button" role="tab" aria-selected={activeTab === 'collection'} className={activeTab === 'collection' ? 'memory-tab-active' : ''} onClick={() => onTabChange('collection')}>收款</button>}
    </div>
    {activeTab === 'order'
      ? <SalesOrderWorkbench focusDocNo={docNo} />
      : activeTab === 'delivery'
        ? <SalesDeliveryWorkbench focusDocNo={docNo} />
        : activeTab === 'invoice'
          ? <SalesInvoiceWorkbench focusDocNo={docNo} />
        : activeTab === 'collection'
          ? <CollectionWorkbench focusDocNo={docNo} />
      : <CustomerWorkbench permissions={permissions} />}
  </>;
}

function PurchaseWorkbench({ permissions, tab, docNo, onTabChange }: ModuleWorkbenchProps) {
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
      <button type="button" role="tab" aria-selected={activeTab === 'supplier'} className={activeTab === 'supplier' ? 'memory-tab-active' : ''} onClick={() => onTabChange('supplier')}>供应商档案</button>
      {canUseOrders && <button type="button" role="tab" aria-selected={activeTab === 'order'} className={activeTab === 'order' ? 'memory-tab-active' : ''} onClick={() => onTabChange('order')}>采购订单</button>}
      {canUseReceipts && <button type="button" role="tab" aria-selected={activeTab === 'receipt'} className={activeTab === 'receipt' ? 'memory-tab-active' : ''} onClick={() => onTabChange('receipt')}>采购入库</button>}
      {canUseInvoices && <button type="button" role="tab" aria-selected={activeTab === 'invoice'} className={activeTab === 'invoice' ? 'memory-tab-active' : ''} onClick={() => onTabChange('invoice')}>采购发票</button>}
      {canUsePayments && <button type="button" role="tab" aria-selected={activeTab === 'payment'} className={activeTab === 'payment' ? 'memory-tab-active' : ''} onClick={() => onTabChange('payment')}>付款</button>}
    </div>
    {activeTab === 'order'
      ? <PurchaseOrderWorkbench focusDocNo={docNo} />
      : activeTab === 'receipt'
        ? <PurchaseReceiptWorkbench focusDocNo={docNo} />
        : activeTab === 'invoice'
          ? <PurchaseInvoiceWorkbench focusDocNo={docNo} />
        : activeTab === 'payment'
          ? <PaymentWorkbench focusDocNo={docNo} />
      : <SupplierWorkbench permissions={permissions} />}
  </>;
}

function InventoryWorkbench({ permissions, tab, docNo, onTabChange }: ModuleWorkbenchProps) {
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
      <button type="button" role="tab" aria-selected={activeTab === 'warehouse'} className={activeTab === 'warehouse' ? 'memory-tab-active' : ''} onClick={() => onTabChange('warehouse')}>仓库档案</button>
      <button type="button" role="tab" aria-selected={activeTab === 'product'} className={activeTab === 'product' ? 'memory-tab-active' : ''} onClick={() => onTabChange('product')}>商品档案</button>
      {canUseCount && <button type="button" role="tab" aria-selected={activeTab === 'count'} className={activeTab === 'count' ? 'memory-tab-active' : ''} onClick={() => onTabChange('count')}>盘点</button>}
      {canUseTransfer && <button type="button" role="tab" aria-selected={activeTab === 'transfer'} className={activeTab === 'transfer' ? 'memory-tab-active' : ''} onClick={() => onTabChange('transfer')}>调拨</button>}
      <button type="button" role="tab" aria-selected={activeTab === 'ledger'} className={activeTab === 'ledger' ? 'memory-tab-active' : ''} onClick={() => onTabChange('ledger')}>台账</button>
    </div>
    {activeTab === 'count'
      ? <StockCountWorkbench focusDocNo={docNo} />
      : activeTab === 'transfer'
        ? <TransferWorkbench focusDocNo={docNo} />
        : activeTab === 'ledger'
          ? <InventoryLedgerWorkbench canAdjust={canAdjust} />
          : activeTab === 'product'
            ? <ProductWorkbench permissions={permissions} />
            : <WarehouseWorkbench permissions={permissions} />}
  </>;
}

function FinanceWorkbench({ permissions, tab, docNo, onTabChange }: ModuleWorkbenchProps) {
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
        onClick={() => onTabChange('fund')}>资金账户</button>
      {canManageAccounts && <button type="button" role="tab" aria-selected={activeTab === 'account'}
        className={activeTab === 'account' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('account')}>科目</button>}
      {canUseVouchers && <button type="button" role="tab" aria-selected={activeTab === 'voucher'}
        className={activeTab === 'voucher' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('voucher')}>凭证</button>}
      {canManagePeriods && <button type="button" role="tab" aria-selected={activeTab === 'period'}
        className={activeTab === 'period' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('period')}>账期关账</button>}
      <button type="button" role="tab" aria-selected={activeTab === 'payable'}
        className={activeTab === 'payable' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('payable')}>应付台账</button>
      {canViewAging && <button type="button" role="tab" aria-selected={activeTab === 'payableAging'}
        className={activeTab === 'payableAging' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('payableAging')}>应付账龄</button>}
      {canUseReceivables && <button type="button" role="tab" aria-selected={activeTab === 'receivable'}
        className={activeTab === 'receivable' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('receivable')}>应收台账</button>}
      {canViewAging && <button type="button" role="tab" aria-selected={activeTab === 'receivableAging'}
        className={activeTab === 'receivableAging' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('receivableAging')}>应收账龄</button>}
      {canViewReports && <button type="button" role="tab" aria-selected={activeTab === 'balanceSheet'}
        className={activeTab === 'balanceSheet' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('balanceSheet')}>资产负债表</button>}
      {canViewReports && <button type="button" role="tab" aria-selected={activeTab === 'incomeStatement'}
        className={activeTab === 'incomeStatement' ? 'memory-tab-active' : ''}
        onClick={() => onTabChange('incomeStatement')}>利润表</button>}
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
              ? <VoucherWorkbench canWrite={canUseVouchers} focusDocNo={docNo} />
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
