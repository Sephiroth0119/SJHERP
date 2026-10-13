import { request } from './http.ts';

export interface BalanceSheetLine {
  name: string;
  amount: string;
}

export interface BalanceSheet {
  period: string;
  assetLines: BalanceSheetLine[];
  totalAssets: string;
  liabilityLines: BalanceSheetLine[];
  totalLiabilities: string;
  equityLines: BalanceSheetLine[];
  totalEquity: string;
  balanced: boolean;
}

export interface IncomeStatementLine {
  name: string;
  currentPeriod: string;
  yearToDate: string;
}

export interface IncomeStatement {
  period: string;
  lines: IncomeStatementLine[];
  netProfitCurrent: string;
  netProfitYtd: string;
}

type DecimalWire = string | number | null | undefined;

type BalanceSheetLineWire = { name: string; amount: DecimalWire };
type IncomeStatementLineWire = {
  name: string;
  currentPeriod: DecimalWire;
  yearToDate: DecimalWire;
};

type BalanceSheetWire = Omit<BalanceSheet, 'assetLines' | 'totalAssets' | 'liabilityLines' | 'totalLiabilities' | 'equityLines' | 'totalEquity'> & {
  assetLines: BalanceSheetLineWire[];
  totalAssets: DecimalWire;
  liabilityLines: BalanceSheetLineWire[];
  totalLiabilities: DecimalWire;
  equityLines: BalanceSheetLineWire[];
  totalEquity: DecimalWire;
};

type IncomeStatementWire = Omit<IncomeStatement, 'lines' | 'netProfitCurrent' | 'netProfitYtd'> & {
  lines: IncomeStatementLineWire[];
  netProfitCurrent: DecimalWire;
  netProfitYtd: DecimalWire;
};

function asString(value: DecimalWire): string {
  return value == null ? '' : String(value);
}

export function validateReportPeriod(period: string): string | null {
  const trimmed = period.trim();
  if (!trimmed) return '请填写账期';
  if (!/^\d{6}$/.test(trimmed)) return '账期格式须为 yyyyMM';
  const month = trimmed.slice(4, 6);
  if (month < '01' || month > '12') return '账期月份须为 01-12';
  return null;
}

function requirePeriod(period: string): string {
  const error = validateReportPeriod(period);
  if (error) throw new Error(error);
  return period.trim();
}

function normalizeBalanceSheet(value: BalanceSheetWire): BalanceSheet {
  return {
    period: value.period ?? '',
    assetLines: (value.assetLines ?? []).map((line) => ({ name: line.name, amount: asString(line.amount) })),
    totalAssets: asString(value.totalAssets),
    liabilityLines: (value.liabilityLines ?? []).map((line) => ({ name: line.name, amount: asString(line.amount) })),
    totalLiabilities: asString(value.totalLiabilities),
    equityLines: (value.equityLines ?? []).map((line) => ({ name: line.name, amount: asString(line.amount) })),
    totalEquity: asString(value.totalEquity),
    balanced: Boolean(value.balanced),
  };
}

function normalizeIncomeStatement(value: IncomeStatementWire): IncomeStatement {
  return {
    period: value.period ?? '',
    lines: (value.lines ?? []).map((line) => ({
      name: line.name,
      currentPeriod: asString(line.currentPeriod),
      yearToDate: asString(line.yearToDate),
    })),
    netProfitCurrent: asString(value.netProfitCurrent),
    netProfitYtd: asString(value.netProfitYtd),
  };
}

export async function getBalanceSheet(period: string): Promise<BalanceSheet> {
  const key = requirePeriod(period);
  const result = await request<BalanceSheetWire>(`/api/reports/balance-sheet?period=${encodeURIComponent(key)}`);
  return normalizeBalanceSheet(result);
}

export async function getIncomeStatement(period: string): Promise<IncomeStatement> {
  const key = requirePeriod(period);
  const result = await request<IncomeStatementWire>(`/api/reports/income-statement?period=${encodeURIComponent(key)}`);
  return normalizeIncomeStatement(result);
}
