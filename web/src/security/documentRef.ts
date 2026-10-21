import type { ModuleKey } from '../types/navigation.ts';
import { hasPermission } from './access.ts';

/**
 * 已登记的单据类型 → 工作台跳转与权限（M7-T04）。
 * 未知 type fail-closed：不渲染卡片、不跳转、不补拉详情。
 */
export const DOCUMENT_TYPE_DEFS = {
  PURCHASE_ORDER: {
    label: '采购订单',
    permission: 'purchase:order',
    module: 'purchase',
    tab: 'order',
  },
  PURCHASE_RECEIPT: {
    label: '采购入库单',
    permission: 'purchase:receipt',
    module: 'purchase',
    tab: 'receipt',
  },
  PURCHASE_INVOICE: {
    label: '采购发票',
    permission: 'purchase:invoice',
    module: 'purchase',
    tab: 'invoice',
  },
  SALES_ORDER: {
    label: '销售订单',
    permission: 'sales:order',
    module: 'sales',
    tab: 'order',
  },
  SALES_DELIVERY: {
    label: '销售出库单',
    permission: 'sales:delivery',
    module: 'sales',
    tab: 'delivery',
  },
  SALES_INVOICE: {
    label: '销售发票',
    permission: 'sales:invoice',
    module: 'sales',
    tab: 'invoice',
  },
  COLLECTION_RECEIPT: {
    label: '收款单',
    permission: 'finance:settlement',
    module: 'sales',
    tab: 'collection',
  },
  PAYMENT_DISBURSEMENT: {
    label: '付款单',
    permission: 'finance:settlement',
    module: 'purchase',
    tab: 'payment',
  },
  STOCK_COUNT: {
    label: '盘点单',
    permission: 'inventory:count',
    module: 'inventory',
    tab: 'count',
  },
  TRANSFER: {
    label: '调拨单',
    permission: 'inventory:transfer',
    module: 'inventory',
    tab: 'transfer',
  },
  WORK_ORDER: {
    label: '生产工单',
    permission: 'production:wo',
    module: 'production',
    tab: 'wo',
  },
  MATERIAL_ISSUE: {
    label: '领料单',
    permission: 'production:material',
    module: 'production',
    tab: 'issue',
  },
  MATERIAL_RETURN: {
    label: '退料单',
    permission: 'production:material',
    module: 'production',
    tab: 'return',
  },
  PRODUCTION_REPORT: {
    label: '报工单',
    permission: 'production:report',
    module: 'production',
    tab: 'report',
  },
  COST_SETTLEMENT: {
    label: '成本结转单',
    permission: 'production:cost',
    module: 'production',
    tab: 'cost',
  },
  VOUCHER: {
    label: '记账凭证',
    permission: 'finance:voucher',
    module: 'finance',
    tab: 'voucher',
  },
} as const;

export type DocumentType = keyof typeof DOCUMENT_TYPE_DEFS;

export interface ResolvedDocumentRef {
  type: DocumentType;
  docNo: string;
  title: string;
  permission: string;
  module: ModuleKey;
  tab: string;
}

export function isDocumentType(value: string): value is DocumentType {
  return Object.prototype.hasOwnProperty.call(DOCUMENT_TYPE_DEFS, value);
}

/** 解析协议 documents[] 的一项；字段非法则返回 null（fail-closed）。 */
export function parseDocumentRef(raw: unknown): ResolvedDocumentRef | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const candidate = raw as Record<string, unknown>;
  if (typeof candidate.type !== 'string' || typeof candidate.docNo !== 'string') return null;
  const type = candidate.type.trim();
  const docNo = candidate.docNo.trim();
  if (!isDocumentType(type) || docNo === '') return null;
  const def = DOCUMENT_TYPE_DEFS[type];
  const title = typeof candidate.title === 'string' && candidate.title.trim() !== ''
    ? candidate.title.trim()
    : def.label;
  return {
    type,
    docNo,
    title,
    permission: def.permission,
    module: def.module,
    tab: def.tab,
  };
}

export function visibleDocumentRefs(
  refs: readonly unknown[] | undefined,
  permissions: readonly string[],
): ResolvedDocumentRef[] {
  if (!refs || refs.length === 0) return [];
  const seen = new Set<string>();
  const result: ResolvedDocumentRef[] = [];
  for (const raw of refs) {
    const parsed = parseDocumentRef(raw);
    if (!parsed) continue;
    if (!hasPermission(permissions, parsed.permission)) continue;
    const key = `${parsed.type}:${parsed.docNo}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(parsed);
  }
  return result;
}

export function hashForDocument(ref: Pick<ResolvedDocumentRef, 'module' | 'tab' | 'docNo'>): string {
  return `#/${ref.module}/${ref.tab}/${encodeURIComponent(ref.docNo)}`;
}
