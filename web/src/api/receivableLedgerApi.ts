import { request } from './http.ts';

export type ReceivableStatus = 'OPEN' | 'PARTIAL' | 'SETTLED' | 'REVERSED';

export interface Receivable {
  id: number;
  customerId: number;
  amount: string;
  settledAmount: string;
  openAmount: string;
  sourceDocNo: string;
  dueDate: string | null;
  status: ReceivableStatus;
}

export interface ReceivablePage {
  items: Receivable[];
  total: number;
  page: number;
  size: number;
}

export interface SettlementRecord {
  id: number;
  type: string;
  targetId: number;
  targetSourceDocNo: string | null;
  amount: string;
  settlementDate: string | null;
  paymentDocNo: string | null;
  createdBy: string | null;
  createdAt: string | null;
}

type DecimalWire = string | number;

type ReceivableWire = Omit<Receivable, 'amount' | 'settledAmount' | 'openAmount'> & {
  amount: DecimalWire;
  settledAmount: DecimalWire;
  openAmount: DecimalWire;
};

type ReceivablePageWire = Omit<ReceivablePage, 'items'> & {
  items: ReceivableWire[];
};

type SettlementWire = Omit<SettlementRecord, 'amount'> & {
  amount: DecimalWire;
};

function asString(value: DecimalWire | null | undefined): string {
  return value == null ? '' : String(value);
}

function normalizeReceivable(receivable: ReceivableWire): Receivable {
  return {
    ...receivable,
    amount: asString(receivable.amount),
    settledAmount: asString(receivable.settledAmount),
    openAmount: asString(receivable.openAmount),
  };
}

function normalizeSettlement(record: SettlementWire): SettlementRecord {
  return { ...record, amount: asString(record.amount) };
}

export async function searchReceivables(
  customerId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<ReceivablePage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (customerId !== null) params.set('customerId', String(customerId));
  if (status) params.set('status', status);
  const result = await request<ReceivablePageWire>(`/api/receivables?${params}`);
  return { ...result, items: result.items.map(normalizeReceivable) };
}

export async function getReceivable(id: number): Promise<Receivable> {
  const result = await request<ReceivableWire>(`/api/receivables/${id}`);
  return normalizeReceivable(result);
}

export async function searchReceivableSettlements(targetId: number): Promise<SettlementRecord[]> {
  const result = await request<{ items: SettlementWire[] }>(
    `/api/settlements?type=RECEIVABLE&targetId=${targetId}`,
  );
  return result.items.map(normalizeSettlement);
}
