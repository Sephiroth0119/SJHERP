import { request } from './http.ts';
import { validateReportPeriod } from './financialStatementApi.ts';

export interface AccountBalance {
  accountCode: string;
  totalDebit: string;
  totalCredit: string;
  netBalance: string;
}

export interface TrialBalance {
  period: string;
  balances: AccountBalance[];
  totalDebit: string;
  totalCredit: string;
}

type DecimalWire = string | number | null | undefined;

type AccountBalanceWire = {
  accountCode: string;
  totalDebit: DecimalWire;
  totalCredit: DecimalWire;
  netBalance: DecimalWire;
};

type TrialBalanceWire = {
  period: string;
  balances: AccountBalanceWire[];
  totalDebit: DecimalWire;
  totalCredit: DecimalWire;
};

function asString(value: DecimalWire): string {
  return value == null ? '' : String(value);
}

function requirePeriod(period: string): string {
  const error = validateReportPeriod(period);
  if (error) throw new Error(error);
  return period.trim();
}

function normalizeBalance(value: AccountBalanceWire): AccountBalance {
  return {
    accountCode: value.accountCode ?? '',
    totalDebit: asString(value.totalDebit),
    totalCredit: asString(value.totalCredit),
    netBalance: asString(value.netBalance),
  };
}

function normalizeTrial(value: TrialBalanceWire): TrialBalance {
  return {
    period: value.period ?? '',
    balances: (value.balances ?? []).map(normalizeBalance),
    totalDebit: asString(value.totalDebit),
    totalCredit: asString(value.totalCredit),
  };
}

export async function getTrialBalance(period: string): Promise<TrialBalance> {
  const key = requirePeriod(period);
  const result = await request<TrialBalanceWire>(
    `/api/gl/trial-balance?period=${encodeURIComponent(key)}`,
  );
  return normalizeTrial(result);
}

export async function getAccountBalance(accountCode: string, period: string): Promise<AccountBalance> {
  const key = requirePeriod(period);
  const code = accountCode.trim();
  if (!code) throw new Error('请填写科目编码');
  const params = new URLSearchParams({
    accountCode: code,
    period: key,
  });
  const result = await request<AccountBalanceWire>(`/api/gl/account-balance?${params}`);
  return normalizeBalance(result);
}
