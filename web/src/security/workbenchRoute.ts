import { MODULE_NAV_ITEMS, type ModuleKey } from '../types/navigation.ts';
import { canAccessModule, hasPermission } from './access.ts';
import { DOCUMENT_TYPE_DEFS, type DocumentType } from './documentRef.ts';
import { guardModule } from './routeGuard.ts';

export interface WorkbenchRoute {
  module: ModuleKey;
  tab?: string;
  docNo?: string;
}

const MODULE_TAB_PERMISSION: Record<string, Record<string, string>> = {
  purchase: {
    order: 'purchase:order',
    receipt: 'purchase:receipt',
    invoice: 'purchase:invoice',
    payment: 'finance:settlement',
  },
  sales: {
    order: 'sales:order',
    delivery: 'sales:delivery',
    invoice: 'sales:invoice',
    collection: 'finance:settlement',
  },
  inventory: {
    count: 'inventory:count',
    transfer: 'inventory:transfer',
  },
  production: {
    mrp: 'production:mrp',
    wo: 'production:wo',
    issue: 'production:material',
    return: 'production:material',
    report: 'production:report',
    cost: 'production:cost',
  },
  finance: {
    account: 'finance:account',
    voucher: 'finance:voucher',
    period: 'finance:period',
    receivable: 'sales:invoice',
    receivableAging: 'finance:settlement',
    payableAging: 'finance:settlement',
    balanceSheet: 'finance:report',
    incomeStatement: 'finance:report',
  },
  reports: {
    'purchase-detail': 'purchase:order',
    'sales-detail': 'sales:order',
    'receivable-aging': 'finance:settlement',
    'payable-aging': 'finance:settlement',
    'trial-balance': 'finance:voucher',
    'account-balance': 'finance:voucher',
    'balance-sheet': 'finance:report',
    'income-statement': 'finance:report',
  },
};

export function parseWorkbenchRoute(hash: string): WorkbenchRoute {
  const raw = hash.replace(/^#\/?/, '');
  const parts = raw.split('/').filter((part) => part.length > 0);
  const moduleKey = parts[0];
  const module = MODULE_NAV_ITEMS.some((item) => item.key === moduleKey)
    ? moduleKey as ModuleKey
    : 'agent';
  const tab = parts[1];
  const docNo = parts.length >= 3 ? decodeURIComponent(parts.slice(2).join('/')) : undefined;
  return {
    module,
    tab: tab || undefined,
    docNo: docNo || undefined,
  };
}

export function hashForRoute(route: WorkbenchRoute): string {
  if (route.module === 'agent' && !route.tab) return '#/agent';
  let path = `#/${route.module}`;
  if (route.tab) {
    path += `/${route.tab}`;
    if (route.docNo) path += `/${encodeURIComponent(route.docNo)}`;
  }
  return path;
}

/**
 * 将 URL 路由收敛到当前权限可打开的模块/页签。
 * 无模块权限回退 Agent；有模块但无页签权限则丢掉 tab/docNo（fail-closed 不露单号跳转）。
 */
export function resolveWorkbenchRoute(
  route: WorkbenchRoute,
  permissions: readonly string[],
): WorkbenchRoute {
  const module = guardModule(route.module, permissions);
  if (module !== route.module) {
    return { module };
  }
  if (!route.tab) {
    return { module };
  }
  const required = MODULE_TAB_PERMISSION[module]?.[route.tab];
  if (required && !hasPermission(permissions, required)) {
    return { module };
  }
  if (!canAccessModule(module, permissions) && module !== 'agent') {
    return { module: 'agent' };
  }
  return {
    module,
    tab: route.tab,
    docNo: route.docNo,
  };
}

export function documentTypeForTab(module: ModuleKey, tab: string): DocumentType | null {
  for (const [type, def] of Object.entries(DOCUMENT_TYPE_DEFS)) {
    if (def.module === module && def.tab === tab) {
      return type as DocumentType;
    }
  }
  return null;
}
