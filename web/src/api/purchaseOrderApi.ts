import { request } from './http.ts';

export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface PurchaseOrderLine {
  lineNo: number;
  productId: number;
  quantity: string;
  unitPrice: string;
  amount: string;
  receivedQty: string;
  outstandingQty: string;
}

export interface PurchaseOrder {
  docNo: string;
  supplierId: number;
  orderDate: string;
  remark: string | null;
  status: PurchaseOrderStatus;
  totalAmount: string;
  lines: PurchaseOrderLine[];
}

export interface PurchaseOrderPage {
  items: PurchaseOrder[];
  total: number;
  page: number;
  size: number;
}

export interface PurchaseOrderLineForm {
  productId: number | null;
  quantity: string;
  unitPrice: string;
}

export interface PurchaseOrderForm {
  supplierId: number | null;
  orderDate: string;
  remark: string;
  lines: PurchaseOrderLineForm[];
}

type PurchaseOrderLineWire = Omit<
  PurchaseOrderLine,
  'quantity' | 'unitPrice' | 'amount' | 'receivedQty' | 'outstandingQty'
> & {
  quantity: string | number;
  unitPrice: string | number;
  amount: string | number;
  receivedQty: string | number;
  outstandingQty: string | number;
};

type PurchaseOrderWire = Omit<PurchaseOrder, 'totalAmount' | 'lines'> & {
  totalAmount: string | number;
  lines: PurchaseOrderLineWire[];
};

type PurchaseOrderPageWire = Omit<PurchaseOrderPage, 'items'> & {
  items: PurchaseOrderWire[];
};

export const positiveDecimalPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

export const nonNegativeDecimalPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

function normalizeLine(line: PurchaseOrderLineWire): PurchaseOrderLine {
  return {
    ...line,
    quantity: String(line.quantity),
    unitPrice: String(line.unitPrice),
    amount: String(line.amount),
    receivedQty: String(line.receivedQty),
    outstandingQty: String(line.outstandingQty),
  };
}

function normalizeOrder(order: PurchaseOrderWire): PurchaseOrder {
  return {
    ...order,
    totalAmount: String(order.totalAmount),
    lines: order.lines.map(normalizeLine),
  };
}

function createBody(form: PurchaseOrderForm) {
  return {
    supplierId: form.supplierId,
    orderDate: form.orderDate,
    remark: form.remark,
    lines: form.lines.map(({ productId, quantity, unitPrice }) => ({
      productId,
      quantity,
      unitPrice,
    })),
  };
}

export function validatePurchaseOrderForm(form: PurchaseOrderForm): string | null {
  if (!Number.isInteger(form.supplierId) || (form.supplierId ?? 0) <= 0) {
    return '请选择供应商';
  }
  if (!form.orderDate) {
    return '请选择下单日期';
  }
  if (form.lines.length === 0) {
    return '采购订单至少要有一行商品';
  }

  const productIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || (line.productId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择商品`;
    }
    if (productIds.has(line.productId as number)) {
      return '同一采购订单内商品不能重复';
    }
    productIds.add(line.productId as number);
    if (!positiveDecimalPattern.test(line.quantity)) {
      return `第 ${index + 1} 行数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!nonNegativeDecimalPattern.test(line.unitPrice)) {
      return `第 ${index + 1} 行单价必须为非负数，整数最多 12 位、小数最多 6 位`;
    }
  }
  return null;
}

export async function searchPurchaseOrders(
  supplierId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<PurchaseOrderPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (supplierId !== null) {
    params.set('supplierId', String(supplierId));
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<PurchaseOrderPageWire>(
    `/api/purchase/orders?${params}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeOrder),
  };
}

export async function getPurchaseOrder(docNo: string): Promise<PurchaseOrder> {
  const result = await request<PurchaseOrderWire>(
    `/api/purchase/orders/${encodeURIComponent(docNo)}`,
  );
  return normalizeOrder(result);
}

export async function createPurchaseOrder(
  form: PurchaseOrderForm,
): Promise<PurchaseOrder> {
  const result = await request<PurchaseOrderWire>('/api/purchase/orders', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeOrder(result);
}

export async function approvePurchaseOrder(
  docNo: string,
): Promise<PurchaseOrder> {
  const result = await request<PurchaseOrderWire>(
    `/api/purchase/orders/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeOrder(result);
}

export async function closePurchaseOrder(
  docNo: string,
): Promise<PurchaseOrder> {
  const result = await request<PurchaseOrderWire>(
    `/api/purchase/orders/${encodeURIComponent(docNo)}/close`,
    { method: 'POST' },
  );
  return normalizeOrder(result);
}
