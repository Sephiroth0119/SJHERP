import { request } from './http.ts';

export type StockCountStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface StockCountLine {
  lineNo: number;
  productId: number;
  snapshotQty: string;
  countedQty: string | null;
  diffQty: string | null;
  enteredUnitCost: string | null;
}

export interface StockCount {
  docNo: string;
  warehouseId: number;
  remark: string | null;
  status: StockCountStatus;
  lines: StockCountLine[];
}

export interface StockCountPage {
  items: StockCount[];
  total: number;
  page: number;
  size: number;
}

export interface StockCountLineForm {
  productId: number;
  enteredUnitCost: string;
}

export interface StockCountForm {
  warehouseId: number | null;
  remark: string;
  lines: StockCountLineForm[];
}

type DecimalWire = string | number | null;

type StockCountLineWire = Omit<
  StockCountLine,
  'snapshotQty' | 'countedQty' | 'diffQty' | 'enteredUnitCost'
> & {
  snapshotQty: DecimalWire;
  countedQty: DecimalWire;
  diffQty: DecimalWire;
  enteredUnitCost: DecimalWire;
};

type StockCountWire = Omit<StockCount, 'lines'> & {
  lines: StockCountLineWire[];
};

type StockCountPageWire = Omit<StockCountPage, 'items'> & {
  items: StockCountWire[];
};

/** 实盘数量：≥ 0，整数最多 12 位，小数最多 6 位（含 0，盘亏到零合法）。 */
export const nonNegativeQuantityPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

/** 零库存盘盈单价：≥ 0，整数最多 12 位，小数最多 6 位。 */
export const nonNegativeUnitCostPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

function asString(value: DecimalWire | undefined): string {
  return value == null ? '' : String(value);
}

function asNullableString(value: DecimalWire | undefined): string | null {
  return value == null ? null : String(value);
}

function normalizeLine(line: StockCountLineWire): StockCountLine {
  return {
    ...line,
    snapshotQty: asString(line.snapshotQty),
    countedQty: asNullableString(line.countedQty),
    diffQty: asNullableString(line.diffQty),
    enteredUnitCost: asNullableString(line.enteredUnitCost),
  };
}

function normalizeStockCount(document: StockCountWire): StockCount {
  return {
    ...document,
    lines: (document.lines ?? []).map(normalizeLine),
  };
}

function createBody(form: StockCountForm) {
  return {
    warehouseId: form.warehouseId,
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => {
      const entered = line.enteredUnitCost.trim();
      return entered
        ? { productId: line.productId, enteredUnitCost: entered }
        : { productId: line.productId };
    }),
  };
}

export function validateStockCountForm(form: StockCountForm): string | null {
  if (!form.warehouseId || form.warehouseId <= 0) {
    return '请选择盘点仓库';
  }
  if (form.lines.length === 0) {
    return '盘点单至少要有一行';
  }
  const productIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || line.productId <= 0) {
      return `第 ${index + 1} 行商品 id 不可用`;
    }
    if (productIds.has(line.productId)) {
      return '同一盘点单内商品不能重复';
    }
    productIds.add(line.productId);
    const entered = line.enteredUnitCost.trim();
    if (entered && !nonNegativeUnitCostPattern.test(entered)) {
      return `第 ${index + 1} 行盘盈单价必须 ≥ 0，整数最多 12 位、小数最多 6 位`;
    }
  }
  return null;
}

export function validateCountedQty(countedQty: string): string | null {
  if (!nonNegativeQuantityPattern.test(countedQty.trim())) {
    return '实盘数量必须 ≥ 0，整数最多 12 位、小数最多 6 位';
  }
  return null;
}

export async function searchStockCounts(
  warehouseId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<StockCountPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (warehouseId !== null) params.set('warehouseId', String(warehouseId));
  if (status) params.set('status', status);
  const result = await request<StockCountPageWire>(`/api/inventory/stock-counts?${params}`);
  return { ...result, items: result.items.map(normalizeStockCount) };
}

export async function getStockCount(docNo: string): Promise<StockCount> {
  const result = await request<StockCountWire>(
    `/api/inventory/stock-counts/${encodeURIComponent(docNo)}`,
  );
  return normalizeStockCount(result);
}

export async function createStockCount(form: StockCountForm): Promise<StockCount> {
  const result = await request<StockCountWire>('/api/inventory/stock-counts', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeStockCount(result);
}

export async function enterStockCountLine(
  docNo: string,
  lineNo: number,
  countedQty: string,
): Promise<StockCount> {
  const result = await request<StockCountWire>(
    `/api/inventory/stock-counts/${encodeURIComponent(docNo)}/lines/count`,
    { method: 'POST', body: { lineNo, countedQty } },
  );
  return normalizeStockCount(result);
}

export async function approveStockCount(docNo: string): Promise<StockCount> {
  const result = await request<StockCountWire>(
    `/api/inventory/stock-counts/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeStockCount(result);
}

export async function postStockCount(docNo: string): Promise<StockCount> {
  const result = await request<StockCountWire>(
    `/api/inventory/stock-counts/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeStockCount(result);
}

export async function reverseStockCount(docNo: string): Promise<StockCount> {
  const result = await request<StockCountWire>(
    `/api/inventory/stock-counts/${encodeURIComponent(docNo)}/reverse`,
    { method: 'POST' },
  );
  return normalizeStockCount(result);
}
