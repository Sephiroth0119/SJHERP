import { request } from './http.ts';

export type FundAccountType = 'CASH' | 'BANK' | 'OTHER';
export type FundAccountStatus = 'ENABLED' | 'DISABLED';

export interface FundAccount {
  id: number;
  code: string;
  name: string;
  accountType: FundAccountType;
  accountTypeLabel: string;
  glAccountCode: string;
  bankName: string | null;
  accountNo: string | null;
  status: FundAccountStatus;
  createdBy: string | null;
  createdAt: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface FundAccountPage {
  items: FundAccount[];
  total: number;
  page: number;
  size: number;
}

export interface FundAccountForm {
  code: string;
  name: string;
  accountType: FundAccountType;
  glAccountCode: string;
  bankName: string;
  accountNo: string;
}

export interface GlLeafAccount {
  code: string;
  name: string;
  type: string;
  typeLabel: string;
  balanceDir: string;
  balanceDirLabel: string;
  parentCode: string | null;
  level: number;
  leaf: boolean;
  enabled: boolean;
  preset: boolean;
}

export const emptyFundAccountForm = (): FundAccountForm => ({
  code: '',
  name: '',
  accountType: 'BANK',
  glAccountCode: '',
  bankName: '',
  accountNo: '',
});

export function toFundAccountForm(account: FundAccount): FundAccountForm {
  return {
    code: account.code,
    name: account.name,
    accountType: account.accountType,
    glAccountCode: account.glAccountCode,
    bankName: account.bankName ?? '',
    accountNo: account.accountNo ?? '',
  };
}

function createBody(form: FundAccountForm, requireCode: boolean) {
  const code = form.code.trim();
  return {
    code: requireCode || code ? code : null,
    name: form.name.trim(),
    accountType: form.accountType,
    glAccountCode: form.glAccountCode.trim(),
    bankName: form.bankName.trim() || null,
    accountNo: form.accountNo.trim() || null,
  };
}

export function validateFundAccountForm(form: FundAccountForm, requireCode: boolean): string | null {
  if (requireCode && !form.code.trim()) {
    return '更新资金账户时编码必填';
  }
  if (form.code.trim().length > 50) {
    return '资金账户编码不能超过 50 个字符';
  }
  if (!form.name.trim()) {
    return '请填写资金账户名称';
  }
  if (form.name.trim().length > 200) {
    return '资金账户名称不能超过 200 个字符';
  }
  if (form.accountType !== 'CASH' && form.accountType !== 'BANK' && form.accountType !== 'OTHER') {
    return '账户类别仅支持 CASH / BANK / OTHER';
  }
  if (!form.glAccountCode.trim()) {
    return '请选择映射的 GL 科目（须为启用末级）';
  }
  if (form.glAccountCode.trim().length > 32) {
    return 'GL 科目编码不能超过 32 个字符';
  }
  if (form.bankName.trim().length > 200) {
    return '开户行不能超过 200 个字符';
  }
  if (form.accountNo.trim().length > 64) {
    return '银行账号不能超过 64 个字符';
  }
  return null;
}

export async function searchFundAccounts(
  keyword: string,
  status: string,
  page: number,
  size = 20,
): Promise<FundAccountPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (keyword.trim()) params.set('keyword', keyword.trim());
  if (status) params.set('status', status);
  return request<FundAccountPage>(`/api/fund/accounts?${params}`);
}

export async function getFundAccount(id: number): Promise<FundAccount> {
  return request<FundAccount>(`/api/fund/accounts/${id}`);
}

export async function createFundAccount(form: FundAccountForm): Promise<FundAccount> {
  return request<FundAccount>('/api/fund/accounts', {
    method: 'POST',
    body: createBody(form, false),
  });
}

export async function updateFundAccount(id: number, form: FundAccountForm): Promise<FundAccount> {
  return request<FundAccount>(`/api/fund/accounts/${id}`, {
    method: 'PUT',
    body: createBody(form, true),
  });
}

export async function enableFundAccount(id: number): Promise<FundAccount> {
  return request<FundAccount>(`/api/fund/accounts/${id}/enable`, { method: 'POST' });
}

export async function disableFundAccount(id: number): Promise<FundAccount> {
  return request<FundAccount>(`/api/fund/accounts/${id}/disable`, { method: 'POST' });
}

export async function listLeafGlAccounts(): Promise<GlLeafAccount[]> {
  return request<GlLeafAccount[]>('/api/gl/accounts?leafOnly=true');
}
