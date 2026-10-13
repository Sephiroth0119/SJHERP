import { request } from './http.ts';

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'COST' | 'PROFIT_LOSS';
export type BalanceDir = 'DEBIT' | 'CREDIT';

export interface GlAccount {
  code: string;
  name: string;
  type: AccountType;
  typeLabel: string;
  balanceDir: BalanceDir;
  balanceDirLabel: string;
  parentCode: string | null;
  level: number;
  leaf: boolean;
  enabled: boolean;
  preset: boolean;
}

export interface GlAccountNode extends GlAccount {
  children: GlAccountNode[];
}

export interface GlAccountForm {
  code: string;
  name: string;
  type: AccountType;
  balanceDir: BalanceDir;
  parentCode: string;
  isLeaf: boolean;
}

const ACCOUNT_TYPES: readonly AccountType[] = [
  'ASSET', 'LIABILITY', 'EQUITY', 'COST', 'PROFIT_LOSS',
];
const BALANCE_DIRS: readonly BalanceDir[] = ['DEBIT', 'CREDIT'];

function isAccountType(value: string): value is AccountType {
  return (ACCOUNT_TYPES as readonly string[]).includes(value);
}

function isBalanceDir(value: string): value is BalanceDir {
  return (BALANCE_DIRS as readonly string[]).includes(value);
}

export function emptyAccountForm(): GlAccountForm {
  return {
    code: '',
    name: '',
    type: 'ASSET',
    balanceDir: 'DEBIT',
    parentCode: '',
    isLeaf: true,
  };
}

export function validateAccountForm(form: GlAccountForm): string | null {
  if (!form.code.trim()) return '请填写科目编码';
  if (form.code.trim().length > 32) return '科目编码不能超过 32 个字符';
  if (!form.name.trim()) return '请填写科目名称';
  if (form.name.trim().length > 100) return '科目名称不能超过 100 个字符';
  if (!isAccountType(form.type)) {
    return '科目类别仅支持 ASSET / LIABILITY / EQUITY / COST / PROFIT_LOSS';
  }
  if (!isBalanceDir(form.balanceDir)) {
    return '余额方向仅支持 DEBIT / CREDIT';
  }
  if (form.parentCode.trim() && !form.code.trim().startsWith(form.parentCode.trim())) {
    return `子科目编码必须以上级科目编码[${form.parentCode.trim()}] 为前缀`;
  }
  return null;
}

function createBody(form: GlAccountForm) {
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    type: form.type,
    balanceDir: form.balanceDir,
    parentCode: form.parentCode.trim() || null,
    isLeaf: form.isLeaf,
  };
}

export function buildAccountForest(accounts: GlAccount[]): GlAccountNode[] {
  const nodes = new Map<string, GlAccountNode>();
  for (const account of accounts) {
    nodes.set(account.code, { ...account, children: [] });
  }
  const roots: GlAccountNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.parentCode ? nodes.get(node.parentCode) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  const sortNodes = (items: GlAccountNode[]) => {
    items.sort((left, right) => left.code.localeCompare(right.code));
    items.forEach((item) => sortNodes(item.children));
  };
  sortNodes(roots);
  return roots;
}

export function flattenAccountForest(nodes: GlAccountNode[]): GlAccountNode[] {
  const result: GlAccountNode[] = [];
  const walk = (items: GlAccountNode[]) => {
    for (const item of items) {
      result.push(item);
      walk(item.children);
    }
  };
  walk(nodes);
  return result;
}

export async function listAccounts(type = '', leafOnly = false): Promise<GlAccount[]> {
  const params = new URLSearchParams();
  if (leafOnly) params.set('leafOnly', 'true');
  if (type.trim()) params.set('type', type.trim());
  const query = params.toString();
  return request<GlAccount[]>(query ? `/api/gl/accounts?${query}` : '/api/gl/accounts');
}

export async function getAccount(code: string): Promise<GlAccount> {
  return request<GlAccount>(`/api/gl/accounts/${encodeURIComponent(code)}`);
}

export async function createAccount(form: GlAccountForm): Promise<GlAccount> {
  return request<GlAccount>('/api/gl/accounts', {
    method: 'POST',
    body: createBody(form),
  });
}

export async function enableAccount(code: string): Promise<GlAccount> {
  return request<GlAccount>(`/api/gl/accounts/${encodeURIComponent(code)}/enable`, {
    method: 'POST',
  });
}

export async function disableAccount(code: string): Promise<GlAccount> {
  return request<GlAccount>(`/api/gl/accounts/${encodeURIComponent(code)}/disable`, {
    method: 'POST',
  });
}
