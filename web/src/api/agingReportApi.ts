import { request } from './http.ts';

export type AgingKind = 'receivable' | 'payable';

export interface AgingBuckets {
  notDue: string;
  overdue1To30: string;
  overdue31To60: string;
  overdue61To90: string;
  overdue90Plus: string;
  totalOutstanding: string;
}

export interface AgingItem extends AgingBuckets {
  counterpartyId: number;
  counterpartyCode: string | null;
  counterpartyName: string | null;
}

export interface AgingReport {
  asOf: string;
  items: AgingItem[];
  total: number;
  page: number;
  size: number;
  grandTotal: AgingBuckets;
}

type DecimalWire = string | number | null | undefined;

type AgingBucketsWire = {
  notDue: DecimalWire;
  overdue1To30: DecimalWire;
  overdue31To60: DecimalWire;
  overdue61To90: DecimalWire;
  overdue90Plus: DecimalWire;
  totalOutstanding: DecimalWire;
};

type AgingItemWire = AgingBucketsWire & {
  counterpartyId: number;
  counterpartyCode: string | null;
  counterpartyName: string | null;
};

type AgingReportWire = {
  asOf: string;
  items: AgingItemWire[];
  total: number;
  page: number;
  size: number;
  grandTotal: AgingBucketsWire;
};

function asString(value: DecimalWire): string {
  return value == null ? '' : String(value);
}

function normalizeBuckets(value: AgingBucketsWire | null | undefined): AgingBuckets {
  return {
    notDue: asString(value?.notDue),
    overdue1To30: asString(value?.overdue1To30),
    overdue31To60: asString(value?.overdue31To60),
    overdue61To90: asString(value?.overdue61To90),
    overdue90Plus: asString(value?.overdue90Plus),
    totalOutstanding: asString(value?.totalOutstanding),
  };
}

function normalizeItem(item: AgingItemWire): AgingItem {
  return {
    ...normalizeBuckets(item),
    counterpartyId: item.counterpartyId,
    counterpartyCode: item.counterpartyCode ?? null,
    counterpartyName: item.counterpartyName ?? null,
  };
}

function normalizeReport(report: AgingReportWire): AgingReport {
  return {
    asOf: report.asOf ?? '',
    items: (report.items ?? []).map(normalizeItem),
    total: report.total,
    page: report.page,
    size: report.size,
    grandTotal: normalizeBuckets(report.grandTotal),
  };
}

function agingParams(
  asOf: string,
  counterpartyKey: 'customerId' | 'supplierId',
  counterpartyId: number | null,
  page: number,
  size: number,
): string {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (asOf.trim()) params.set('asOf', asOf.trim());
  if (counterpartyId !== null) params.set(counterpartyKey, String(counterpartyId));
  return params.toString();
}

export async function searchReceivableAging(
  asOf: string,
  customerId: number | null,
  page: number,
  size = 20,
): Promise<AgingReport> {
  const result = await request<AgingReportWire>(
    `/api/reports/receivable-aging?${agingParams(asOf, 'customerId', customerId, page, size)}`,
  );
  return normalizeReport(result);
}

export async function searchPayableAging(
  asOf: string,
  supplierId: number | null,
  page: number,
  size = 20,
): Promise<AgingReport> {
  const result = await request<AgingReportWire>(
    `/api/reports/payable-aging?${agingParams(asOf, 'supplierId', supplierId, page, size)}`,
  );
  return normalizeReport(result);
}
