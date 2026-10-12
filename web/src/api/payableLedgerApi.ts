import { request } from './http.ts';

export type PayableStatus = 'OPEN' | 'PARTIAL' | 'SETTLED' | 'REVERSED';

export interface Payable {
  id: number;
  supplierId: number;
  amount: string;
  sourceDocNo: string;
  dueDate: string | null;
  status: PayableStatus;
  settledAmount: string;
  outstandingAmount: string;
}

export interface PayablePage {
  items: Payable[];
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

type PayableWire = Omit<Payable, 'amount' | 'settledAmount' | 'outstandingAmount'> & {
  amount: DecimalWire;
  settledAmount: DecimalWire;
  outstandingAmount: DecimalWire;
};

type PayablePageWire = Omit<PayablePage, 'items'> & {
  items: PayableWire[];
};

type SettlementWire = Omit<SettlementRecord, 'amount'> & {
  amount: DecimalWire;
};

function asString(value: DecimalWire | null | undefined): string {
  return value == null ? '' : String(value);
}

function normalizePayable(payable: PayableWire): Payable {
  return {
    ...payable,
    amount: asString(payable.amount),
    settledAmount: asString(payable.settledAmount),
    outstandingAmount: asString(payable.outstandingAmount),
  };
}

function normalizeSettlement(record: SettlementWire): SettlementRecord {
  return { ...record, amount: asString(record.amount) };
}

export async function searchPayables(
  supplierId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<PayablePage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (supplierId !== null) params.set('supplierId', String(supplierId));
  if (status) params.set('status', status);
  const result = await request<PayablePageWire>(`/api/payables?${params}`);
  return { ...result, items: result.items.map(normalizePayable) };
}

export async function searchPayableSettlements(targetId: number): Promise<SettlementRecord[]> {
  const result = await request<{ items: SettlementWire[] }>(
    `/api/settlements?type=PAYABLE&targetId=${targetId}`,
  );
  return result.items.map(normalizeSettlement);
}
