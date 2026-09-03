import { request } from './http.ts';

export type SalesDeliveryStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface SalesDeliveryLine {
  lineNo: number;
  soLineNo: number;
  productId: number;
  quantity: string;
  cogsAmount: string | null;
}

export interface SalesDelivery {
  docNo: string;
  salesOrderNo: string;
  warehouseId: number;
  remark: string | null;
  status: SalesDeliveryStatus;
  totalCogs: string;
  lines: SalesDeliveryLine[];
}

export interface SalesDeliveryPage {
  items: SalesDelivery[];
  total: number;
  page: number;
  size: number;
}

export interface SalesDeliveryOrderLineOption {
  soLineNo: number;
  productId: number;
  quantity: string;
  unitPrice: string;
  amount: string;
  deliveredQty: string;
  remainingQty: string;
}

export interface SalesDeliveryOrderOption {
  docNo: string;
  customerId: number;
  orderDate: string;
  remark: string | null;
  status: 'APPROVED' | 'EXECUTING';
  lines: SalesDeliveryOrderLineOption[];
}

export interface SalesDeliveryOrderOptionPage {
  items: SalesDeliveryOrderOption[];
  total: number;
  page: number;
  size: number;
}

export interface SalesDeliveryLineForm {
  soLineNo: number;
  productId: number;
  quantity: string;
  remainingQty: string;
}

export interface SalesDeliveryForm {
  salesOrderNo: string;
  warehouseId: number | null;
  remark: string;
  lines: SalesDeliveryLineForm[];
}

type SalesDeliveryLineWire = Omit<
  SalesDeliveryLine,
  'quantity' | 'cogsAmount'
> & {
  quantity: string | number;
  cogsAmount: string | number | null;
};

type SalesDeliveryWire = Omit<SalesDelivery, 'totalCogs' | 'lines'> & {
  totalCogs: string | number;
  lines: SalesDeliveryLineWire[];
};

type SalesDeliveryPageWire = Omit<SalesDeliveryPage, 'items'> & {
  items: SalesDeliveryWire[];
};

type SalesDeliveryOrderLineOptionWire = Omit<
  SalesDeliveryOrderLineOption,
  | 'quantity'
  | 'unitPrice'
  | 'amount'
  | 'deliveredQty'
  | 'remainingQty'
> & {
  quantity: string | number;
  unitPrice: string | number;
  amount: string | number;
  deliveredQty: string | number;
  remainingQty: string | number;
};

type SalesDeliveryOrderOptionWire = Omit<
  SalesDeliveryOrderOption,
  'lines'
> & {
  lines: SalesDeliveryOrderLineOptionWire[];
};

type SalesDeliveryOrderOptionPageWire = Omit<
  SalesDeliveryOrderOptionPage,
  'items'
> & {
  items: SalesDeliveryOrderOptionWire[];
};

export const positiveDecimalPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

export const nonNegativeDecimalPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

function normalizeLine(line: SalesDeliveryLineWire): SalesDeliveryLine {
  return {
    ...line,
    quantity: String(line.quantity),
    cogsAmount:
      line.cogsAmount === null ? null : String(line.cogsAmount),
  };
}

function normalizeDelivery(delivery: SalesDeliveryWire): SalesDelivery {
  return {
    ...delivery,
    totalCogs: String(delivery.totalCogs),
    lines: delivery.lines.map(normalizeLine),
  };
}

function normalizeOrderOptionLine(
  line: SalesDeliveryOrderLineOptionWire,
): SalesDeliveryOrderLineOption {
  return {
    ...line,
    quantity: String(line.quantity),
    unitPrice: String(line.unitPrice),
    amount: String(line.amount),
    deliveredQty: String(line.deliveredQty),
    remainingQty: String(line.remainingQty),
  };
}

function normalizeOrderOption(
  order: SalesDeliveryOrderOptionWire,
): SalesDeliveryOrderOption {
  return {
    ...order,
    lines: order.lines.map(normalizeOrderOptionLine),
  };
}

function createBody(form: SalesDeliveryForm) {
  return {
    salesOrderNo: form.salesOrderNo,
    warehouseId: form.warehouseId,
    remark: form.remark,
    lines: form.lines.map(({ soLineNo, productId, quantity }) => ({
      soLineNo,
      productId,
      quantity,
    })),
  };
}

function compareDecimalStrings(left: string, right: string): number {
  const [leftInteger = '0', leftFraction = ''] = left.split('.');
  const [rightInteger = '0', rightFraction = ''] = right.split('.');
  const normalizedLeftInteger = leftInteger.replace(/^0+(?=\d)/, '');
  const normalizedRightInteger = rightInteger.replace(/^0+(?=\d)/, '');
  if (normalizedLeftInteger.length !== normalizedRightInteger.length) {
    return normalizedLeftInteger.length > normalizedRightInteger.length ? 1 : -1;
  }
  if (normalizedLeftInteger !== normalizedRightInteger) {
    return normalizedLeftInteger > normalizedRightInteger ? 1 : -1;
  }
  const scale = Math.max(leftFraction.length, rightFraction.length);
  const normalizedLeftFraction = leftFraction.padEnd(scale, '0');
  const normalizedRightFraction = rightFraction.padEnd(scale, '0');
  if (normalizedLeftFraction === normalizedRightFraction) return 0;
  return normalizedLeftFraction > normalizedRightFraction ? 1 : -1;
}

export function validateSalesDeliveryForm(
  form: SalesDeliveryForm,
): string | null {
  if (!form.salesOrderNo.trim()) {
    return '请选择已审核或执行中的销售订单';
  }
  if (!Number.isInteger(form.warehouseId) || (form.warehouseId ?? 0) <= 0) {
    return '请选择启用中的出库仓库';
  }
  if (form.lines.length === 0) {
    return '销售出库单至少要有一行未发商品';
  }

  const soLineNos = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.soLineNo) || line.soLineNo <= 0) {
      return `第 ${index + 1} 行销售订单行号不可用`;
    }
    if (!Number.isInteger(line.productId) || line.productId <= 0) {
      return `第 ${index + 1} 行商品不可用`;
    }
    if (soLineNos.has(line.soLineNo)) {
      return '同一销售出库单内销售订单行不能重复';
    }
    soLineNos.add(line.soLineNo);
    if (!positiveDecimalPattern.test(line.quantity)) {
      return `第 ${index + 1} 行发货数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!nonNegativeDecimalPattern.test(line.remainingQty)) {
      return `第 ${index + 1} 行订单剩余可发量不可用，请重新选择销售订单`;
    }
    if (compareDecimalStrings(line.quantity, line.remainingQty) > 0) {
      return `第 ${index + 1} 行发货数量超过剩余可发量 ${line.remainingQty}`;
    }
  }
  return null;
}

export async function searchSalesDeliveries(
  warehouseId: number | null,
  salesOrderNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<SalesDeliveryPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (warehouseId !== null) {
    params.set('warehouseId', String(warehouseId));
  }
  if (salesOrderNo.trim()) {
    params.set('salesOrderNo', salesOrderNo.trim());
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<SalesDeliveryPageWire>(
    `/api/sales/deliveries?${params}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeDelivery),
  };
}

export async function getSalesDelivery(
  docNo: string,
): Promise<SalesDelivery> {
  const result = await request<SalesDeliveryWire>(
    `/api/sales/deliveries/${encodeURIComponent(docNo)}`,
  );
  return normalizeDelivery(result);
}

export async function searchSalesDeliveryOrderOptions(
  page: number,
  size = 20,
): Promise<SalesDeliveryOrderOptionPage> {
  const result = await request<SalesDeliveryOrderOptionPageWire>(
    `/api/sales/deliveries/order-options?page=${page}&size=${size}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeOrderOption),
  };
}

export async function getSalesDeliveryOrderOption(
  docNo: string,
): Promise<SalesDeliveryOrderOption> {
  const result = await request<SalesDeliveryOrderOptionWire>(
    `/api/sales/deliveries/order-options/${encodeURIComponent(docNo)}`,
  );
  return normalizeOrderOption(result);
}

export async function createSalesDelivery(
  form: SalesDeliveryForm,
): Promise<SalesDelivery> {
  const result = await request<SalesDeliveryWire>('/api/sales/deliveries', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeDelivery(result);
}

export async function approveSalesDelivery(
  docNo: string,
): Promise<SalesDelivery> {
  const result = await request<SalesDeliveryWire>(
    `/api/sales/deliveries/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeDelivery(result);
}

export async function postSalesDelivery(
  docNo: string,
): Promise<SalesDelivery> {
  const result = await request<SalesDeliveryWire>(
    `/api/sales/deliveries/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeDelivery(result);
}

export async function cancelSalesDelivery(
  docNo: string,
): Promise<SalesDelivery> {
  const result = await request<SalesDeliveryWire>(
    `/api/sales/deliveries/${encodeURIComponent(docNo)}/cancel`,
    { method: 'POST' },
  );
  return normalizeDelivery(result);
}
