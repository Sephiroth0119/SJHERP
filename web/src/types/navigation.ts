export type ModuleKey =
  | 'agent' | 'purchase' | 'sales' | 'inventory' | 'production' | 'finance' | 'memory';

export interface ModuleNavItem {
  key: ModuleKey;
  label: string;
  description: string;
  /** A module is visible when the user has at least one of these permissions. */
  requiredPermissions?: readonly string[];
}

export const MODULE_NAV_ITEMS: readonly ModuleNavItem[] = [
  { key: 'agent', label: 'Agent 助手', description: '通过对话完成业务操作（主入口）' },
  {
    key: 'purchase', label: '采购', description: '采购订单 → 入库 → 应付 → 付款',
    requiredPermissions: ['purchase:order', 'purchase:receipt', 'purchase:invoice'],
  },
  {
    key: 'sales', label: '销售', description: '销售订单 → 出库 → 应收 → 收款',
    requiredPermissions: ['sales:order', 'sales:delivery', 'sales:invoice'],
  },
  {
    key: 'inventory', label: '库存', description: '库存台账、盘点、调拨、存货成本核算',
    requiredPermissions: ['inventory:adjust', 'inventory:count', 'inventory:transfer'],
  },
  {
    key: 'production', label: '生产', description: 'SOP/DP 计划、BOM、工单执行、工单成本',
    requiredPermissions: [
      'production:bom', 'production:routing', 'production:plan', 'production:mrp',
      'production:wo', 'production:material', 'production:report', 'production:cost',
    ],
  },
  {
    key: 'finance', label: '财务', description: '总账、应收应付、结算单、期间结账、报表',
    requiredPermissions: [
      'finance:account', 'finance:period', 'finance:period_reopen', 'finance:voucher',
      'finance:settlement', 'finance:payment_account', 'finance:report',
    ],
  },
  {
    key: 'memory', label: '记忆管理', description: '查看、编辑、失效并处理重复或冲突记忆',
    requiredPermissions: ['memory:manage'],
  },
];
