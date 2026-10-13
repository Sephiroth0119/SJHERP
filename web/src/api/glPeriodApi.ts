import { request } from './http.ts';

export type PeriodStatus = 'OPEN' | 'CLOSED';

export interface AccountingPeriod {
  period: string;
  year: number;
  month: number;
  status: PeriodStatus;
  statusLabel: string;
  closedBy: string | null;
  closedAt: string | null;
}

export interface ClosingPreviewLine {
  accountCode: string;
  accountName: string;
  debit: string;
  credit: string;
}

export interface PeriodCloseReadiness {
  period: string;
  status: PeriodStatus;
  closeable: boolean;
  alreadyClosed: boolean;
  consistencyErrors: string[];
  consistencyWarnings: string[];
  closingPreviewLines: ClosingPreviewLine[];
  totalRevenue: string;
  totalExpense: string;
  netProfit: string;
  trialBalanceDebit: string;
  trialBalanceCredit: string;
}

export interface PeriodCloseResult {
  period: string;
  closingVoucherDocNo: string | null;
  totalRevenue: string;
  totalExpense: string;
  netProfit: string;
  trialBalanceDebit: string;
  trialBalanceCredit: string;
  closedBy: string | null;
  closedAt: string | null;
}

type DecimalWire = string | number | null | undefined;

type ClosingPreviewLineWire = {
  accountCode: string;
  accountName: string;
  debit: DecimalWire;
  credit: DecimalWire;
};

type PeriodCloseReadinessWire = Omit<PeriodCloseReadiness, 'closingPreviewLines' | 'totalRevenue' | 'totalExpense' | 'netProfit' | 'trialBalanceDebit' | 'trialBalanceCredit'> & {
  closingPreviewLines: ClosingPreviewLineWire[];
  totalRevenue: DecimalWire;
  totalExpense: DecimalWire;
  netProfit: DecimalWire;
  trialBalanceDebit: DecimalWire;
  trialBalanceCredit: DecimalWire;
};

type PeriodCloseResultWire = Omit<PeriodCloseResult, 'totalRevenue' | 'totalExpense' | 'netProfit' | 'trialBalanceDebit' | 'trialBalanceCredit'> & {
  totalRevenue: DecimalWire;
  totalExpense: DecimalWire;
  netProfit: DecimalWire;
  trialBalanceDebit: DecimalWire;
  trialBalanceCredit: DecimalWire;
};

function asString(value: DecimalWire): string {
  return value == null ? '' : String(value);
}

export function validatePeriodKey(period: string): string | null {
  const trimmed = period.trim();
  if (!trimmed) return '请填写账期';
  if (!/^\d{6}$/.test(trimmed)) return '账期格式须为 yyyyMM';
  const month = trimmed.slice(4, 6);
  if (month < '01' || month > '12') return '账期月份须为 01-12';
  return null;
}

function normalizePreviewLine(line: ClosingPreviewLineWire): ClosingPreviewLine {
  return {
    accountCode: line.accountCode,
    accountName: line.accountName,
    debit: asString(line.debit),
    credit: asString(line.credit),
  };
}

function normalizeReadiness(value: PeriodCloseReadinessWire): PeriodCloseReadiness {
  return {
    period: value.period,
    status: value.status,
    closeable: value.closeable,
    alreadyClosed: value.alreadyClosed,
    consistencyErrors: value.consistencyErrors ?? [],
    consistencyWarnings: value.consistencyWarnings ?? [],
    closingPreviewLines: (value.closingPreviewLines ?? []).map(normalizePreviewLine),
    totalRevenue: asString(value.totalRevenue),
    totalExpense: asString(value.totalExpense),
    netProfit: asString(value.netProfit),
    trialBalanceDebit: asString(value.trialBalanceDebit),
    trialBalanceCredit: asString(value.trialBalanceCredit),
  };
}

function normalizeCloseResult(value: PeriodCloseResultWire): PeriodCloseResult {
  return {
    period: value.period,
    closingVoucherDocNo: value.closingVoucherDocNo ?? null,
    totalRevenue: asString(value.totalRevenue),
    totalExpense: asString(value.totalExpense),
    netProfit: asString(value.netProfit),
    trialBalanceDebit: asString(value.trialBalanceDebit),
    trialBalanceCredit: asString(value.trialBalanceCredit),
    closedBy: value.closedBy ?? null,
    closedAt: value.closedAt ?? null,
  };
}

export async function listPeriods(): Promise<AccountingPeriod[]> {
  return request<AccountingPeriod[]>('/api/gl/periods');
}

export async function getPeriod(period: string): Promise<AccountingPeriod> {
  return request<AccountingPeriod>(`/api/gl/periods/${encodeURIComponent(period)}`);
}

export async function openPeriod(period: string): Promise<AccountingPeriod> {
  return request<AccountingPeriod>('/api/gl/periods', {
    method: 'POST',
    body: { period: period.trim() },
  });
}

export async function precheckPeriodClose(period: string): Promise<PeriodCloseReadiness> {
  const result = await request<PeriodCloseReadinessWire>(
    `/api/gl/periods/${encodeURIComponent(period)}/close-precheck`,
  );
  return normalizeReadiness(result);
}

export async function closePeriod(period: string): Promise<PeriodCloseResult> {
  const result = await request<PeriodCloseResultWire>(
    `/api/gl/periods/${encodeURIComponent(period)}/close`,
    { method: 'POST' },
  );
  return normalizeCloseResult(result);
}

export async function reopenPeriod(period: string): Promise<AccountingPeriod> {
  return request<AccountingPeriod>(
    `/api/gl/periods/${encodeURIComponent(period)}/reopen`,
    { method: 'POST' },
  );
}
