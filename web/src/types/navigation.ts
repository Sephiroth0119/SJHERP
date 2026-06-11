/**
 * 左侧业务入口的导航定义。
 * 业务范围对应 CLAUDE.md：进（采购）/ 销（销售）/ 存（库存）/ 产（生产）/ 财（财务）。
 */

/** 业务模块标识 */
export type ModuleKey =
  | 'agent'
  | 'purchase'
  | 'sales'
  | 'inventory'
  | 'production'
  | 'finance';

export interface ModuleNavItem {
  key: ModuleKey;
  /** 菜单标题（中文） */
  label: string;
  /** 简短说明，用于占位页 */
  description: string;
}

/** 导航菜单：Agent 助手置顶（主入口），其余为传统业务入口 */
export const MODULE_NAV_ITEMS: ModuleNavItem[] = [
  { key: 'agent', label: 'Agent 助手', description: '通过对话完成所有业务操作（主入口）' },
  { key: 'purchase', label: '采购', description: '采购订单 → 入库 → 应付 → 付款' },
  { key: 'sales', label: '销售', description: '销售订单 → 出库 → 应收 → 收款' },
  { key: 'inventory', label: '库存', description: '库存台账、盘点、调拨、存货成本核算' },
  { key: 'production', label: '生产', description: 'SOP/DP 计划、BOM、工单执行、工单成本' },
  { key: 'finance', label: '财务', description: '总账、应收应付、结算单、期间结账、报表' },
];
