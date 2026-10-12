import { request } from './http.ts';

export type CollectionStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export type ReceivableStatus = 'OPEN' | 'PARTIAL' | 'SETTLED' | 'REVERSED';

export interface CollectionLine {
  lineNo: number;
  receivableId: number;
  allocatedAmount: string;
}

export interface Collection {
  docNo: string;
  customerId: number;
  paymentAccountId: number;
  receiptDate: string;
  remark: string | null;
  status: CollectionStatus;
  totalAmount: string;
  lines: CollectionLine[];
}

export interface CollectionPage {
  items: Collection[];
  total: number;
  page: number;
  size: number;
}

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

export interface FundAccount {
  id: number;
  code: string;
  name: string;
  accountType: string;
  status: string;
}

export interface FundAccountPage {
  items: FundAccount[];
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

export interface CollectionLineForm {
  receivableId: number;
  allocatedAmount: string;
  openAmount: string;
}

export interface CollectionForm {
  customerId: number | null;
  paymentAccountId: number | null;
  receiptDate: string;
  remark: string;
  lines: CollectionLineForm[];
}

type DecimalWire = string | number;

type CollectionLineWire = Omit<CollectionLine, 'allocatedAmount'> & {
  allocatedAmount: DecimalWire;
};

type CollectionWire = Omit<Collection, 'totalAmount' | 'lines'> & {
  totalAmount: DecimalWire;
  lines: CollectionLineWire[];
};

type CollectionPageWire = Omit<CollectionPage, 'items'> & {
  items: CollectionWire[];
};

type ReceivableWire = Omit<
  Receivable,
  'amount' | 'settledAmount' | 'openAmount'
> & {
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

export const positiveAllocatedAmountPattern =
  /^(?!0(?:\.0{1,2})?$)(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/;

function asString(value: DecimalWire | null | undefined): string {
  return value == null ? '' : String(value);
}

function normalizeCollection(collection: CollectionWire): Collection {
  return {
    ...collection,
    totalAmount: asString(collection.totalAmount),
    lines: collection.lines.map((line) => ({
      ...line,
      allocatedAmount: asString(line.allocatedAmount),
    })),
  };
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

function createBody(form: CollectionForm) {
  return {
    customerId: form.customerId,
    paymentAccountId: form.paymentAccountId,
    receiptDate: form.receiptDate || null,
    remark: form.remark.trim() || null,
    lines: form.lines.map(({ receivableId, allocatedAmount }) => ({
      receivableId,
      allocatedAmount,
    })),
  };
}

function compareDecimalStrings(left: string, right: string): number {
  const [leftInteger = '0', leftFraction = ''] = left.split('.');
  const [rightInteger = '0', rightFraction = ''] = right.split('.');
  const normalizedLeftInteger = leftInteger.replace(/^0+(?=\d)/, '') || '0';
  const normalizedRightInteger = rightInteger.replace(/^0+(?=\d)/, '') || '0';
  if (normalizedLeftInteger.length !== normalizedRightInteger.length) {
    return normalizedLeftInteger.length > normalizedRightInteger.length ? 1 : -1;
  }
  if (normalizedLeftInteger !== normalizedRightInteger) {
    return normalizedLeftInteger > normalizedRightInteger ? 1 : -1;
  }
  const scale = Math.max(leftFraction.length, rightFraction.length);
  const normalizedLeftFraction = leftFraction.padEnd(scale, '0');
  const normalizedRightFraction = rightFraction.padEnd(scale, '0');
  if (normalizedLeftFraction === normalizedRightFraction) return 0;
  return normalizedLeftFraction > normalizedRightFraction ? 1 : -1;
}

export function validateCollectionForm(form: CollectionForm): string | null {
  if (!form.customerId || form.customerId <= 0) {
    return '请选择客户';
  }
  if (!form.paymentAccountId || form.paymentAccountId <= 0) {
    return '请选择收入的资金账户';
  }
  if (form.lines.length === 0) {
    return '收款单至少要有一行分摊';
  }
  const receivableIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.receivableId) || line.receivableId <= 0) {
      return `第 ${index + 1} 行应收 id 不可用`;
    }
    if (receivableIds.has(line.receivableId)) {
      return '同一收款单内应收不能重复';
    }
    receivableIds.add(line.receivableId);
    if (!positiveAllocatedAmountPattern.test(line.allocatedAmount)) {
      return `第 ${index + 1} 行分摊金额必须大于 0，整数最多 16 位、小数最多 2 位`;
    }
    if (!positiveAllocatedAmountPattern.test(line.openAmount)) {
      return `第 ${index + 1} 行未核销余额不可用，请重新选择应收`;
    }
    if (compareDecimalStrings(line.allocatedAmount, line.openAmount) > 0) {
      return `第 ${index + 1} 行分摊金额超过未核销余额 ${line.openAmount}`;
    }
  }
  return null;
}

export async function searchCollections(
  customerId: number | null,
  paymentAccountId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<CollectionPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (customerId !== null) params.set('customerId', String(customerId));
  if (paymentAccountId !== null) {
    params.set('paymentAccountId', String(paymentAccountId));
  }
  if (status) params.set('status', status);
  const result = await request<CollectionPageWire>(`/api/collections?${params}`);
  return { ...result, items: result.items.map(normalizeCollection) };
}

export async function getCollection(docNo: string): Promise<Collection> {
  const result = await request<CollectionWire>(
    `/api/collections/${encodeURIComponent(docNo)}`,
  );
  return normalizeCollection(result);
}

export async function createCollection(form: CollectionForm): Promise<Collection> {
  const result = await request<CollectionWire>('/api/collections', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeCollection(result);
}

export async function approveCollection(docNo: string): Promise<Collection> {
  const result = await request<CollectionWire>(
    `/api/collections/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeCollection(result);
}

export async function postCollection(docNo: string): Promise<Collection> {
  const result = await request<CollectionWire>(
    `/api/collections/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeCollection(result);
}

export async function reverseCollection(docNo: string): Promise<Collection> {
  const result = await request<CollectionWire>(
    `/api/collections/${encodeURIComponent(docNo)}/reverse`,
    { method: 'POST' },
  );
  return normalizeCollection(result);
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

export async function searchFundAccounts(
  status: string,
  page: number,
  size = 20,
): Promise<FundAccountPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (status) params.set('status', status);
  return request<FundAccountPage>(`/api/fund/accounts?${params}`);
}

export async function getFundAccount(id: number): Promise<FundAccount> {
  return request<FundAccount>(`/api/fund/accounts/${id}`);
}

export async function searchReceivableSettlements(
  receivableId: number,
): Promise<SettlementRecord[]> {
  const result = await request<{ items: SettlementWire[] }>(
    `/api/settlements?type=RECEIVABLE&targetId=${receivableId}`,
  );
  return result.items.map(normalizeSettlement);
}
