export type ReportKind =
  | 'inventory-balance'
  | 'stock-movement'
  | 'purchase-detail'
  | 'sales-detail'
  | 'receivable-aging'
  | 'payable-aging'
  | 'trial-balance'
  | 'account-balance'
  | 'balance-sheet'
  | 'income-statement';

export const REPORT_KINDS: readonly ReportKind[] = [
  'inventory-balance',
  'stock-movement',
  'purchase-detail',
  'sales-detail',
  'receivable-aging',
  'payable-aging',
  'trial-balance',
  'account-balance',
  'balance-sheet',
  'income-statement',
];

const REPORT_PERMISSION: Record<ReportKind, string | null> = {
  'inventory-balance': null,
  'stock-movement': null,
  'purchase-detail': 'purchase:order',
  'sales-detail': 'sales:order',
  'receivable-aging': 'finance:settlement',
  'payable-aging': 'finance:settlement',
  'trial-balance': 'finance:voucher',
  'account-balance': 'finance:voucher',
  'balance-sheet': 'finance:report',
  'income-statement': 'finance:report',
};

const REPORT_LABEL: Record<ReportKind, string> = {
  'inventory-balance': '库存余额表',
  'stock-movement': '收发存汇总',
  'purchase-detail': '采购入库明细',
  'sales-detail': '销售出库明细',
  'receivable-aging': '应收账龄',
  'payable-aging': '应付账龄',
  'trial-balance': '试算平衡',
  'account-balance': '科目余额',
  'balance-sheet': '资产负债表',
  'income-statement': '利润表',
};

export function isReportKind(value: string | undefined): value is ReportKind {
  return value !== undefined && (REPORT_KINDS as readonly string[]).includes(value);
}

export function reportPermission(kind: ReportKind): string | null {
  return REPORT_PERMISSION[kind];
}

export function reportLabel(kind: ReportKind): string {
  return REPORT_LABEL[kind];
}

export function canViewReport(kind: ReportKind, permissions: readonly string[]): boolean {
  const required = REPORT_PERMISSION[kind];
  return required === null || permissions.includes(required);
}

export function visibleReportKinds(permissions: readonly string[]): ReportKind[] {
  return REPORT_KINDS.filter((kind) => canViewReport(kind, permissions));
}

export function resolveReportKind(
  tab: string | undefined,
  permissions: readonly string[],
): ReportKind | null {
  const visible = visibleReportKinds(permissions);
  if (visible.length === 0) return null;
  if (isReportKind(tab) && canViewReport(tab, permissions)) return tab;
  return visible[0] ?? null;
}
