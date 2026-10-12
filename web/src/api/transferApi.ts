import { request } from './http.ts';

export type TransferStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface TransferLine {
  lineNo: number;
  productId: number;
  quantity: string;
}

export interface Transfer {
  docNo: string;
  fromWarehouseId: number;
  toWarehouseId: number;
  remark: string | null;
  status: TransferStatus;
  lines: TransferLine[];
}

export interface TransferPage {
  items: Transfer[];
  total: number;
  page: number;
  size: number;
}

export interface TransferLineForm {
  productId: number;
  quantity: string;
}

export interface TransferForm {
  fromWarehouseId: number | null;
  toWarehouseId: number | null;
  remark: string;
  lines: TransferLineForm[];
}

type DecimalWire = string | number;

type TransferLineWire = Omit<TransferLine, 'quantity'> & {
  quantity: DecimalWire;
};

type TransferWire = Omit<Transfer, 'lines'> & {
  lines: TransferLineWire[];
};

type TransferPageWire = Omit<TransferPage, 'items'> & {
  items: TransferWire[];
};

/** 调拨数量：> 0，整数最多 12 位，小数最多 6 位。 */
export const positiveQuantityPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

function asString(value: DecimalWire | null | undefined): string {
  return value == null ? '' : String(value);
}

function normalizeLine(line: TransferLineWire): TransferLine {
  return { ...line, quantity: asString(line.quantity) };
}

function normalizeTransfer(document: TransferWire): Transfer {
  return {
    ...document,
    lines: (document.lines ?? []).map(normalizeLine),
  };
}

function createBody(form: TransferForm) {
  return {
    fromWarehouseId: form.fromWarehouseId,
    toWarehouseId: form.toWarehouseId,
    remark: form.remark.trim() || null,
    lines: form.lines.map(({ productId, quantity }) => ({ productId, quantity })),
  };
}

export function validateTransferForm(form: TransferForm): string | null {
  if (!form.fromWarehouseId || form.fromWarehouseId <= 0) {
    return '请选择调出仓库';
  }
  if (!form.toWarehouseId || form.toWarehouseId <= 0) {
    return '请选择调入仓库';
  }
  if (form.fromWarehouseId === form.toWarehouseId) {
    return '调出仓库与调入仓库必须不同';
  }
  if (form.lines.length === 0) {
    return '调拨单至少要有一行';
  }
  const productIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || line.productId <= 0) {
      return `第 ${index + 1} 行商品 id 不可用`;
    }
    if (productIds.has(line.productId)) {
      return '同一调拨单内商品不能重复';
    }
    productIds.add(line.productId);
    if (!positiveQuantityPattern.test(line.quantity)) {
      return `第 ${index + 1} 行调拨数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
  }
  return null;
}

export async function searchTransfers(
  warehouseId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<TransferPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (warehouseId !== null) params.set('warehouseId', String(warehouseId));
  if (status) params.set('status', status);
  const result = await request<TransferPageWire>(`/api/inventory/transfers?${params}`);
  return { ...result, items: result.items.map(normalizeTransfer) };
}

export async function getTransfer(docNo: string): Promise<Transfer> {
  const result = await request<TransferWire>(
    `/api/inventory/transfers/${encodeURIComponent(docNo)}`,
  );
  return normalizeTransfer(result);
}

export async function createTransfer(form: TransferForm): Promise<Transfer> {
  const result = await request<TransferWire>('/api/inventory/transfers', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeTransfer(result);
}

export async function approveTransfer(docNo: string): Promise<Transfer> {
  const result = await request<TransferWire>(
    `/api/inventory/transfers/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeTransfer(result);
}

export async function postTransfer(docNo: string): Promise<Transfer> {
  const result = await request<TransferWire>(
    `/api/inventory/transfers/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeTransfer(result);
}

export async function reverseTransfer(docNo: string): Promise<Transfer> {
  const result = await request<TransferWire>(
    `/api/inventory/transfers/${encodeURIComponent(docNo)}/reverse`,
    { method: 'POST' },
  );
  return normalizeTransfer(result);
}
