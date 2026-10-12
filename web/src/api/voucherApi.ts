import { request } from './http.ts';
import { listLeafGlAccounts, type GlLeafAccount } from './fundAccountApi.ts';

export type { GlLeafAccount };
export { listLeafGlAccounts };

export type VoucherStatus = 'DRAFT' | 'APPROVED' | 'REVERSED' | 'CANCELLED';

export interface VoucherLine {
  lineNo: number;
  accountCode: string;
  debit: string;
  credit: string;
  summary: string | null;
}

export interface Voucher {
  docNo: string;
  period: string;
  voucherDate: string;
  word: string;
  totalAmount: string;
  summary: string | null;
  sourceDocNo: string | null;
  sourceDocType: string | null;
  status: VoucherStatus;
  lines: VoucherLine[];
}

export interface VoucherPage {
  items: Voucher[];
  total: number;
  page: number;
  size: number;
}

export interface VoucherLineForm {
  accountCode: string;
  debit: string;
  credit: string;
  summary: string;
}

export interface VoucherForm {
  voucherDate: string;
  summary: string;
  lines: VoucherLineForm[];
}

type DecimalWire = string | number;

type VoucherLineWire = Omit<VoucherLine, 'debit' | 'credit'> & {
  debit: DecimalWire;
  credit: DecimalWire;
};

type VoucherWire = Omit<Voucher, 'totalAmount' | 'lines'> & {
  totalAmount: DecimalWire;
  lines: VoucherLineWire[];
};

type VoucherPageWire = Omit<VoucherPage, 'items'> & {
  items: VoucherWire[];
};

export const nonNegativeAmountPattern = /^(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/;
export const positiveAmountPattern =
  /^(?!0(?:\.0{1,2})?$)(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/;

function asString(value: DecimalWire | null | undefined): string {
  return value == null ? '' : String(value);
}

function normalizeVoucher(voucher: VoucherWire): Voucher {
  return {
    ...voucher,
    totalAmount: asString(voucher.totalAmount),
    lines: voucher.lines.map((line) => ({
      ...line,
      debit: asString(line.debit),
      credit: asString(line.credit),
    })),
  };
}

export function emptyVoucherLine(): VoucherLineForm {
  return { accountCode: '', debit: '', credit: '', summary: '' };
}

export function emptyVoucherForm(): VoucherForm {
  return {
    voucherDate: '',
    summary: '',
    lines: [emptyVoucherLine(), emptyVoucherLine()],
  };
}

function amountOrZero(value: string): string {
  const trimmed = value.trim();
  return trimmed === '' ? '0' : trimmed;
}

function toMinorUnits(value: string): bigint {
  const normalized = amountOrZero(value);
  if (!nonNegativeAmountPattern.test(normalized)) return 0n;
  const [integer = '0', fraction = ''] = normalized.split('.');
  const normalizedInteger = integer.replace(/^0+(?=\d)/, '') || '0';
  return BigInt(normalizedInteger) * 100n + BigInt(fraction.padEnd(2, '0'));
}

function fromMinorUnits(value: bigint): string {
  const negative = value < 0n;
  const abs = negative ? -value : value;
  const integer = abs / 100n;
  const fraction = (abs % 100n).toString().padStart(2, '0');
  return `${negative ? '-' : ''}${integer.toString()}.${fraction}`;
}

function compareDecimalStrings(left: string, right: string): number {
  const leftUnits = toMinorUnits(left);
  const rightUnits = toMinorUnits(right);
  if (leftUnits === rightUnits) return 0;
  return leftUnits > rightUnits ? 1 : -1;
}

export function sumVoucherForm(form: VoucherForm): {
  totalDebit: string;
  totalCredit: string;
  difference: string;
  balanced: boolean;
} {
  let debitUnits = 0n;
  let creditUnits = 0n;
  for (const line of form.lines) {
    debitUnits += toMinorUnits(line.debit);
    creditUnits += toMinorUnits(line.credit);
  }
  const differenceUnits = debitUnits > creditUnits
    ? debitUnits - creditUnits
    : creditUnits - debitUnits;
  return {
    totalDebit: fromMinorUnits(debitUnits),
    totalCredit: fromMinorUnits(creditUnits),
    difference: fromMinorUnits(differenceUnits),
    balanced: debitUnits === creditUnits && debitUnits > 0n,
  };
}

function createBody(form: VoucherForm) {
  return {
    voucherDate: form.voucherDate.trim() || null,
    summary: form.summary.trim() || null,
    lines: form.lines.map((line) => ({
      accountCode: line.accountCode.trim(),
      debit: amountOrZero(line.debit),
      credit: amountOrZero(line.credit),
      summary: line.summary.trim() || null,
    })),
  };
}

function invalidAmountReason(value: string, fieldName: string): string | null {
  const trimmed = value.trim();
  if (trimmed === '') return null;
  if (trimmed.startsWith('-')) return `${fieldName}不能为负`;
  if (!nonNegativeAmountPattern.test(trimmed)) {
    if (/\.\d{3,}$/.test(trimmed)) return `${fieldName}小数最多 2 位`;
    return `${fieldName}格式不正确，整数最多 16 位、小数最多 2 位`;
  }
  return null;
}

export function validateVoucherForm(form: VoucherForm): string | null {
  if (!form.voucherDate.trim()) {
    return '请填写凭证日期';
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.voucherDate.trim())) {
    return '凭证日期格式须为 yyyy-MM-dd';
  }
  if (form.lines.length < 2) {
    return '凭证至少要有 2 行（一借一贷起）';
  }
  for (const [index, line] of form.lines.entries()) {
    const row = `第 ${index + 1} 行`;
    if (!line.accountCode.trim()) {
      return `${row}请选择科目`;
    }
    const debitError = invalidAmountReason(line.debit, `${row}借方金额`);
    if (debitError) return debitError;
    const creditError = invalidAmountReason(line.credit, `${row}贷方金额`);
    if (creditError) return creditError;
    const debitPositive = positiveAmountPattern.test(amountOrZero(line.debit));
    const creditPositive = positiveAmountPattern.test(amountOrZero(line.credit));
    if (debitPositive === creditPositive) {
      return `${row}必须恰好借或贷一方金额大于 0`;
    }
  }
  const totals = sumVoucherForm(form);
  if (!totals.balanced) {
    if (compareDecimalStrings(totals.totalDebit, totals.totalCredit) !== 0) {
      return `凭证借贷不平：Σ借=${totals.totalDebit}, Σ贷=${totals.totalCredit}`;
    }
    return '凭证总额必须大于 0';
  }
  return null;
}

export async function searchVouchers(
  period: string,
  status: string,
  page: number,
  size = 20,
): Promise<VoucherPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (period.trim()) params.set('period', period.trim());
  if (status) params.set('status', status);
  const result = await request<VoucherPageWire>(`/api/gl/vouchers?${params}`);
  return { ...result, items: result.items.map(normalizeVoucher) };
}

export async function getVoucher(docNo: string): Promise<Voucher> {
  const result = await request<VoucherWire>(
    `/api/gl/vouchers/${encodeURIComponent(docNo)}`,
  );
  return normalizeVoucher(result);
}

export async function createVoucher(form: VoucherForm): Promise<Voucher> {
  const result = await request<VoucherWire>('/api/gl/vouchers', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeVoucher(result);
}

export async function postVoucher(docNo: string): Promise<Voucher> {
  const result = await request<VoucherWire>(
    `/api/gl/vouchers/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeVoucher(result);
}
