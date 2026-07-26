import { request } from './http.ts';

export type SalesOrderStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface SalesOrderLine {
  lineNo: number;
  productId: number;
  quantity: string;
  unitPrice: string;
  amount: string;
  deliveredQty: string;
  remainingQty: string;
}

export interface SalesOrder {
  docNo: string;
  customerId: number;
  orderDate: string;
  remark: string | null;
  status: SalesOrderStatus;
  totalAmount: string;
  lines: SalesOrderLine[];
}

export interface SalesOrderPage {
  items: SalesOrder[];
  total: number;
  page: number;
  size: number;
}

export interface SalesOrderLineForm {
  productId: number | null;
  quantity: string;
  unitPrice: string;
}

export interface SalesOrderForm {
  customerId: number | null;
  orderDate: string;
  remark: string;
  lines: SalesOrderLineForm[];
}

export interface SalesOrderCreateResult {
  order: SalesOrder;
  warnings: string[];
}

type SalesOrderLineWire = Omit<
  SalesOrderLine,
  'quantity' | 'unitPrice' | 'amount' | 'deliveredQty' | 'remainingQty'
> & {
  quantity: string | number;
  unitPrice: string | number;
  amount: string | number;
  deliveredQty: string | number;
  remainingQty: string | number;
};

type SalesOrderWire = Omit<SalesOrder, 'totalAmount' | 'lines'> & {
  totalAmount: string | number;
  lines: SalesOrderLineWire[];
};

type SalesOrderPageWire = Omit<SalesOrderPage, 'items'> & {
  items: SalesOrderWire[];
};

type SalesOrderCreateResultWire = {
  order: SalesOrderWire;
  warnings?: string[] | null;
};

export const positiveDecimalPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

export const nonNegativeDecimalPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

function normalizeLine(line: SalesOrderLineWire): SalesOrderLine {
  return {
    ...line,
    quantity: String(line.quantity),
    unitPrice: String(line.unitPrice),
    amount: String(line.amount),
    deliveredQty: String(line.deliveredQty),
    remainingQty: String(line.remainingQty),
  };
}

function normalizeOrder(order: SalesOrderWire): SalesOrder {
  return {
    ...order,
    totalAmount: String(order.totalAmount),
    lines: order.lines.map(normalizeLine),
  };
}

function createBody(form: SalesOrderForm) {
  return {
    customerId: form.customerId,
    orderDate: form.orderDate,
    remark: form.remark,
    lines: form.lines.map(({ productId, quantity, unitPrice }) => ({
      productId,
      quantity,
      unitPrice,
    })),
  };
}

export function validateSalesOrderForm(form: SalesOrderForm): string | null {
  if (!Number.isInteger(form.customerId) || (form.customerId ?? 0) <= 0) {
    return '请选择客户';
  }
  if (!form.orderDate) {
    return '请选择下单日期';
  }
  if (form.lines.length === 0) {
    return '销售订单至少要有一行商品';
  }

  const productIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || (line.productId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择商品`;
    }
    if (productIds.has(line.productId as number)) {
      return '同一销售订单内商品不能重复';
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

export async function searchSalesOrders(
  customerId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<SalesOrderPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (customerId !== null) {
    params.set('customerId', String(customerId));
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<SalesOrderPageWire>(
    `/api/sales/orders?${params}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeOrder),
  };
}

export async function getSalesOrder(docNo: string): Promise<SalesOrder> {
  const result = await request<SalesOrderWire>(
    `/api/sales/orders/${encodeURIComponent(docNo)}`,
  );
  return normalizeOrder(result);
}

export async function createSalesOrder(
  form: SalesOrderForm,
): Promise<SalesOrderCreateResult> {
  const result = await request<SalesOrderCreateResultWire>(
    '/api/sales/orders',
    {
      method: 'POST',
      body: createBody(form),
    },
  );
  return {
    order: normalizeOrder(result.order),
    warnings: result.warnings ?? [],
  };
}

export async function approveSalesOrder(docNo: string): Promise<SalesOrder> {
  const result = await request<SalesOrderWire>(
    `/api/sales/orders/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeOrder(result);
}

export async function cancelSalesOrder(docNo: string): Promise<SalesOrder> {
  const result = await request<SalesOrderWire>(
    `/api/sales/orders/${encodeURIComponent(docNo)}/cancel`,
    { method: 'POST' },
  );
  return normalizeOrder(result);
}
