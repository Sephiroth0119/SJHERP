import { request } from './http.ts';

export type PaymentStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export type PayableStatus = 'OPEN' | 'PARTIAL' | 'SETTLED' | 'REVERSED';

export interface PaymentLine {
  lineNo: number;
  payableId: number;
  allocatedAmount: string;
}

export interface Payment {
  docNo: string;
  supplierId: number;
  paymentAccountId: number;
  paymentDate: string;
  remark: string | null;
  status: PaymentStatus;
  totalAmount: string;
  lines: PaymentLine[];
}

export interface PaymentPage {
  items: Payment[];
  total: number;
  page: number;
  size: number;
}

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

export interface PaymentLineForm {
  payableId: number;
  allocatedAmount: string;
  outstandingAmount: string;
}

export interface PaymentForm {
  supplierId: number | null;
  paymentAccountId: number | null;
  paymentDate: string;
  remark: string;
  lines: PaymentLineForm[];
}

type DecimalWire = string | number;

type PaymentLineWire = Omit<PaymentLine, 'allocatedAmount'> & {
  allocatedAmount: DecimalWire;
};

type PaymentWire = Omit<Payment, 'totalAmount' | 'lines'> & {
  totalAmount: DecimalWire;
  lines: PaymentLineWire[];
};

type PaymentPageWire = Omit<PaymentPage, 'items'> & {
  items: PaymentWire[];
};

type PayableWire = Omit<
  Payable,
  'amount' | 'settledAmount' | 'outstandingAmount'
> & {
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

export const positiveAllocatedAmountPattern =
  /^(?!0(?:\.0{1,2})?$)(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/;

function asString(value: DecimalWire | null | undefined): string {
  return value == null ? '' : String(value);
}

function normalizePayment(payment: PaymentWire): Payment {
  return {
    ...payment,
    totalAmount: asString(payment.totalAmount),
    lines: payment.lines.map((line) => ({
      ...line,
      allocatedAmount: asString(line.allocatedAmount),
    })),
  };
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

function createBody(form: PaymentForm) {
  return {
    supplierId: form.supplierId,
    paymentAccountId: form.paymentAccountId,
    paymentDate: form.paymentDate || null,
    remark: form.remark.trim() || null,
    lines: form.lines.map(({ payableId, allocatedAmount }) => ({
      payableId,
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

export function validatePaymentForm(form: PaymentForm): string | null {
  if (!form.supplierId || form.supplierId <= 0) {
    return '请选择供应商';
  }
  if (!form.paymentAccountId || form.paymentAccountId <= 0) {
    return '请选择付出的资金账户';
  }
  if (form.lines.length === 0) {
    return '付款单至少要有一行分摊';
  }
  const payableIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.payableId) || line.payableId <= 0) {
      return `第 ${index + 1} 行应付 id 不可用`;
    }
    if (payableIds.has(line.payableId)) {
      return '同一付款单内应付不能重复';
    }
    payableIds.add(line.payableId);
    if (!positiveAllocatedAmountPattern.test(line.allocatedAmount)) {
      return `第 ${index + 1} 行分摊金额必须大于 0，整数最多 16 位、小数最多 2 位`;
    }
    if (!positiveAllocatedAmountPattern.test(line.outstandingAmount)) {
      return `第 ${index + 1} 行未核销余额不可用，请重新选择应付`;
    }
    if (compareDecimalStrings(line.allocatedAmount, line.outstandingAmount) > 0) {
      return `第 ${index + 1} 行分摊金额超过未核销余额 ${line.outstandingAmount}`;
    }
  }
  return null;
}

export async function searchPayments(
  supplierId: number | null,
  paymentAccountId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<PaymentPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (supplierId !== null) params.set('supplierId', String(supplierId));
  if (paymentAccountId !== null) {
    params.set('paymentAccountId', String(paymentAccountId));
  }
  if (status) params.set('status', status);
  const result = await request<PaymentPageWire>(`/api/payments?${params}`);
  return { ...result, items: result.items.map(normalizePayment) };
}

export async function getPayment(docNo: string): Promise<Payment> {
  const result = await request<PaymentWire>(
    `/api/payments/${encodeURIComponent(docNo)}`,
  );
  return normalizePayment(result);
}

export async function createPayment(form: PaymentForm): Promise<Payment> {
  const result = await request<PaymentWire>('/api/payments', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizePayment(result);
}

export async function approvePayment(docNo: string): Promise<Payment> {
  const result = await request<PaymentWire>(
    `/api/payments/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizePayment(result);
}

export async function postPayment(docNo: string): Promise<Payment> {
  const result = await request<PaymentWire>(
    `/api/payments/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizePayment(result);
}

export async function reversePayment(docNo: string): Promise<Payment> {
  const result = await request<PaymentWire>(
    `/api/payments/${encodeURIComponent(docNo)}/reverse`,
    { method: 'POST' },
  );
  return normalizePayment(result);
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

export async function searchPayableSettlements(
  payableId: number,
): Promise<SettlementRecord[]> {
  const result = await request<{ items: SettlementWire[] }>(
    `/api/settlements?type=PAYABLE&targetId=${payableId}`,
  );
  return result.items.map(normalizeSettlement);
}
