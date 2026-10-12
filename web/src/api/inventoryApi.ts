import { request } from './http.ts';

export type AdjustmentType = 'OPENING' | 'COST_ADJUST';

export interface InventoryBalance {
  warehouseId: number;
  warehouseCode: string;
  warehouseName: string;
  productId: number;
  productCode: string;
  productName: string;
  quantity: string;
  costAmount: string;
  unitCost: string | null;
}

export interface InventoryBalancePage {
  items: InventoryBalance[];
  total: number;
  page: number;
  size: number;
}

export interface InventoryTransaction {
  id: number;
  txnType: string;
  quantity: string;
  unitCost: string | null;
  totalCost: string;
  balanceQuantityAfter: string;
  balanceAmountAfter: string;
  srcDocType: string | null;
  srcDocNo: string | null;
  srcLineNo: number | null;
  operator: string | null;
  createdAt: string;
}

export interface InventoryTransactionPage {
  items: InventoryTransaction[];
  total: number;
  page: number;
  size: number;
}

export interface InventoryAdjustment {
  transactionId: number;
  warehouseId: number;
  productId: number;
  txnType: string;
  quantity: string;
  unitCost: string | null;
  totalCost: string;
  balanceQuantityAfter: string;
  balanceAmountAfter: string;
  srcDocType: string | null;
  srcDocNo: string | null;
  idempotencyKey: string | null;
}

export interface InventoryAdjustmentForm {
  type: AdjustmentType;
  warehouseId: number | null;
  productId: number | null;
  quantity: string;
  unitCost: string;
  adjustAmount: string;
}

type DecimalWire = string | number | null;

type InventoryBalanceWire = Omit<InventoryBalance, 'quantity' | 'costAmount' | 'unitCost'> & {
  quantity: DecimalWire;
  costAmount: DecimalWire;
  unitCost: DecimalWire;
};

type InventoryBalancePageWire = Omit<InventoryBalancePage, 'items'> & {
  items: InventoryBalanceWire[];
};

type InventoryTransactionWire = Omit<
  InventoryTransaction,
  'quantity' | 'unitCost' | 'totalCost' | 'balanceQuantityAfter' | 'balanceAmountAfter'
> & {
  quantity: DecimalWire;
  unitCost: DecimalWire;
  totalCost: DecimalWire;
  balanceQuantityAfter: DecimalWire;
  balanceAmountAfter: DecimalWire;
};

type InventoryTransactionPageWire = Omit<InventoryTransactionPage, 'items'> & {
  items: InventoryTransactionWire[];
};

type InventoryAdjustmentWire = Omit<
  InventoryAdjustment,
  'quantity' | 'unitCost' | 'totalCost' | 'balanceQuantityAfter' | 'balanceAmountAfter'
> & {
  quantity: DecimalWire;
  unitCost: DecimalWire;
  totalCost: DecimalWire;
  balanceQuantityAfter: DecimalWire;
  balanceAmountAfter: DecimalWire;
};

/** 期初数量：> 0，整数最多 12 位，小数最多 6 位。 */
export const positiveQuantityPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

/** 期初单价：≥ 0，整数最多 12 位，小数最多 6 位。 */
export const nonNegativeUnitCostPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

/** 成本调整额：非 0，可负，整数最多 16 位，小数最多 2 位。 */
export const nonzeroAdjustAmountPattern =
  /^(?!-?0(?:\.0{1,2})?$)-?(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/;

function asString(value: DecimalWire | undefined): string {
  return value == null ? '' : String(value);
}

function asNullableString(value: DecimalWire | undefined): string | null {
  return value == null ? null : String(value);
}

function normalizeBalance(row: InventoryBalanceWire): InventoryBalance {
  return {
    ...row,
    quantity: asString(row.quantity),
    costAmount: asString(row.costAmount),
    unitCost: asNullableString(row.unitCost),
  };
}

function normalizeTransaction(row: InventoryTransactionWire): InventoryTransaction {
  return {
    ...row,
    quantity: asString(row.quantity),
    unitCost: asNullableString(row.unitCost),
    totalCost: asString(row.totalCost),
    balanceQuantityAfter: asString(row.balanceQuantityAfter),
    balanceAmountAfter: asString(row.balanceAmountAfter),
  };
}

function normalizeAdjustment(row: InventoryAdjustmentWire): InventoryAdjustment {
  return {
    ...row,
    quantity: asString(row.quantity),
    unitCost: asNullableString(row.unitCost),
    totalCost: asString(row.totalCost),
    balanceQuantityAfter: asString(row.balanceQuantityAfter),
    balanceAmountAfter: asString(row.balanceAmountAfter),
  };
}

function createAdjustmentBody(form: InventoryAdjustmentForm) {
  if (form.type === 'OPENING') {
    return {
      type: 'OPENING',
      warehouseId: form.warehouseId,
      productId: form.productId,
      quantity: form.quantity,
      unitCost: form.unitCost,
    };
  }
  return {
    type: 'COST_ADJUST',
    warehouseId: form.warehouseId,
    productId: form.productId,
    adjustAmount: form.adjustAmount,
  };
}

export function validateAdjustmentForm(form: InventoryAdjustmentForm): string | null {
  if (form.type !== 'OPENING' && form.type !== 'COST_ADJUST') {
    return '调整类型仅支持 OPENING / COST_ADJUST';
  }
  if (!form.warehouseId || form.warehouseId <= 0) {
    return '请选择仓库';
  }
  if (!form.productId || form.productId <= 0) {
    return '请选择商品';
  }
  if (form.type === 'OPENING') {
    if (!positiveQuantityPattern.test(form.quantity)) {
      return '期初数量必须大于 0，整数最多 12 位、小数最多 6 位';
    }
    if (!nonNegativeUnitCostPattern.test(form.unitCost)) {
      return '期初单价必须 ≥ 0，整数最多 12 位、小数最多 6 位';
    }
    return null;
  }
  if (!nonzeroAdjustAmountPattern.test(form.adjustAmount)) {
    return '成本调整额不能为 0，可负，整数最多 16 位、小数最多 2 位';
  }
  return null;
}

export async function searchBalances(
  warehouseId: number | null,
  productId: number | null,
  keyword: string,
  page: number,
  size = 20,
): Promise<InventoryBalancePage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (warehouseId !== null) params.set('warehouseId', String(warehouseId));
  if (productId !== null) params.set('productId', String(productId));
  if (keyword.trim()) params.set('keyword', keyword.trim());
  const result = await request<InventoryBalancePageWire>(`/api/inventory/balances?${params}`);
  return { ...result, items: result.items.map(normalizeBalance) };
}

export async function searchTransactions(
  warehouseId: number,
  productId: number,
  page: number,
  size = 20,
): Promise<InventoryTransactionPage> {
  const params = new URLSearchParams({
    warehouseId: String(warehouseId),
    productId: String(productId),
    page: String(page),
    size: String(size),
  });
  const result = await request<InventoryTransactionPageWire>(
    `/api/inventory/transactions?${params}`,
  );
  return { ...result, items: result.items.map(normalizeTransaction) };
}

export async function createAdjustment(
  form: InventoryAdjustmentForm,
): Promise<InventoryAdjustment> {
  const result = await request<InventoryAdjustmentWire>('/api/inventory/adjustments', {
    method: 'POST',
    body: createAdjustmentBody(form),
  });
  return normalizeAdjustment(result);
}
