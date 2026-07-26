import { request } from './http.ts';

export type PurchaseReceiptStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface PurchaseReceiptLine {
  lineNo: number;
  poLineNo: number;
  productId: number;
  quantity: string;
  unitCost: string;
  amount: string;
}

export interface PurchaseReceipt {
  docNo: string;
  purchaseOrderNo: string;
  warehouseId: number;
  receiptDate: string;
  remark: string | null;
  status: PurchaseReceiptStatus;
  totalAmount: string;
  lines: PurchaseReceiptLine[];
}

export interface PurchaseReceiptPage {
  items: PurchaseReceipt[];
  total: number;
  page: number;
  size: number;
}

export interface PurchaseReceiptOrderLineOption {
  poLineNo: number;
  productId: number;
  quantity: string;
  unitPrice: string;
  receivedQty: string;
  outstandingQty: string;
}

export interface PurchaseReceiptOrderOption {
  docNo: string;
  supplierId: number;
  orderDate: string;
  remark: string | null;
  status: 'APPROVED';
  totalAmount: string;
  lines: PurchaseReceiptOrderLineOption[];
}

export interface PurchaseReceiptOrderOptionPage {
  items: PurchaseReceiptOrderOption[];
  total: number;
  page: number;
  size: number;
}

export interface PurchaseReceiptLineForm {
  poLineNo: number;
  productId: number;
  quantity: string;
  unitCost: string;
  outstandingQty: string;
}

export interface PurchaseReceiptForm {
  purchaseOrderNo: string;
  warehouseId: number | null;
  receiptDate: string;
  remark: string;
  lines: PurchaseReceiptLineForm[];
}

type PurchaseReceiptLineWire = Omit<
  PurchaseReceiptLine,
  'quantity' | 'unitCost' | 'amount'
> & {
  quantity: string | number;
  unitCost: string | number;
  amount: string | number;
};

type PurchaseReceiptWire = Omit<PurchaseReceipt, 'totalAmount' | 'lines'> & {
  totalAmount: string | number;
  lines: PurchaseReceiptLineWire[];
};

type PurchaseReceiptPageWire = Omit<PurchaseReceiptPage, 'items'> & {
  items: PurchaseReceiptWire[];
};

type PurchaseReceiptOrderLineOptionWire = Omit<
  PurchaseReceiptOrderLineOption,
  'quantity' | 'unitPrice' | 'receivedQty' | 'outstandingQty'
> & {
  quantity: string | number;
  unitPrice: string | number;
  receivedQty: string | number;
  outstandingQty: string | number;
};

type PurchaseReceiptOrderOptionWire = Omit<
  PurchaseReceiptOrderOption,
  'totalAmount' | 'lines'
> & {
  totalAmount: string | number;
  lines: PurchaseReceiptOrderLineOptionWire[];
};

type PurchaseReceiptOrderOptionPageWire = Omit<
  PurchaseReceiptOrderOptionPage,
  'items'
> & {
  items: PurchaseReceiptOrderOptionWire[];
};

export const positiveDecimalPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

export const nonNegativeDecimalPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

function normalizeLine(line: PurchaseReceiptLineWire): PurchaseReceiptLine {
  return {
    ...line,
    quantity: String(line.quantity),
    unitCost: String(line.unitCost),
    amount: String(line.amount),
  };
}

function normalizeReceipt(receipt: PurchaseReceiptWire): PurchaseReceipt {
  return {
    ...receipt,
    totalAmount: String(receipt.totalAmount),
    lines: receipt.lines.map(normalizeLine),
  };
}

function normalizeOrderOptionLine(
  line: PurchaseReceiptOrderLineOptionWire,
): PurchaseReceiptOrderLineOption {
  return {
    ...line,
    quantity: String(line.quantity),
    unitPrice: String(line.unitPrice),
    receivedQty: String(line.receivedQty),
    outstandingQty: String(line.outstandingQty),
  };
}

function normalizeOrderOption(
  order: PurchaseReceiptOrderOptionWire,
): PurchaseReceiptOrderOption {
  return {
    ...order,
    totalAmount: String(order.totalAmount),
    lines: order.lines.map(normalizeOrderOptionLine),
  };
}

function createBody(form: PurchaseReceiptForm) {
  return {
    purchaseOrderNo: form.purchaseOrderNo,
    warehouseId: form.warehouseId,
    receiptDate: form.receiptDate,
    remark: form.remark,
    lines: form.lines.map(({ poLineNo, quantity, unitCost }) => ({
      poLineNo,
      quantity,
      unitCost,
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

export function validatePurchaseReceiptForm(
  form: PurchaseReceiptForm,
): string | null {
  if (!form.purchaseOrderNo.trim()) {
    return '请选择已审核的采购订单';
  }
  if (!Number.isInteger(form.warehouseId) || (form.warehouseId ?? 0) <= 0) {
    return '请选择启用中的收货仓库';
  }
  if (!form.receiptDate) {
    return '请选择收货日期';
  }
  if (form.lines.length === 0) {
    return '采购入库单至少要有一行未收商品';
  }

  const poLineNos = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.poLineNo) || line.poLineNo <= 0) {
      return `第 ${index + 1} 行采购订单行号不可用`;
    }
    if (poLineNos.has(line.poLineNo)) {
      return '同一采购入库单内采购订单行不能重复';
    }
    poLineNos.add(line.poLineNo);
    if (!positiveDecimalPattern.test(line.quantity)) {
      return `第 ${index + 1} 行收货数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!nonNegativeDecimalPattern.test(line.unitCost)) {
      return `第 ${index + 1} 行收货单价必须为非负数，整数最多 12 位、小数最多 6 位`;
    }
    if (!nonNegativeDecimalPattern.test(line.outstandingQty)) {
      return `第 ${index + 1} 行订单未收量不可用，请重新选择采购订单`;
    }
    if (compareDecimalStrings(line.quantity, line.outstandingQty) > 0) {
      return `第 ${index + 1} 行收货数量超过未收量 ${line.outstandingQty}`;
    }
  }
  return null;
}

export async function searchPurchaseReceipts(
  warehouseId: number | null,
  purchaseOrderNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<PurchaseReceiptPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (warehouseId !== null) {
    params.set('warehouseId', String(warehouseId));
  }
  if (purchaseOrderNo.trim()) {
    params.set('purchaseOrderNo', purchaseOrderNo.trim());
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<PurchaseReceiptPageWire>(
    `/api/purchase/receipts?${params}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeReceipt),
  };
}

export async function getPurchaseReceipt(
  docNo: string,
): Promise<PurchaseReceipt> {
  const result = await request<PurchaseReceiptWire>(
    `/api/purchase/receipts/${encodeURIComponent(docNo)}`,
  );
  return normalizeReceipt(result);
}

export async function searchPurchaseReceiptOrderOptions(
  page: number,
  size = 20,
): Promise<PurchaseReceiptOrderOptionPage> {
  const result = await request<PurchaseReceiptOrderOptionPageWire>(
    `/api/purchase/receipts/order-options?page=${page}&size=${size}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeOrderOption),
  };
}

export async function getPurchaseReceiptOrderOption(
  docNo: string,
): Promise<PurchaseReceiptOrderOption> {
  const result = await request<PurchaseReceiptOrderOptionWire>(
    `/api/purchase/receipts/order-options/${encodeURIComponent(docNo)}`,
  );
  return normalizeOrderOption(result);
}

export async function createPurchaseReceipt(
  form: PurchaseReceiptForm,
): Promise<PurchaseReceipt> {
  const result = await request<PurchaseReceiptWire>('/api/purchase/receipts', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeReceipt(result);
}

export async function approvePurchaseReceipt(
  docNo: string,
): Promise<PurchaseReceipt> {
  const result = await request<PurchaseReceiptWire>(
    `/api/purchase/receipts/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeReceipt(result);
}

export async function postPurchaseReceipt(
  docNo: string,
): Promise<PurchaseReceipt> {
  const result = await request<PurchaseReceiptWire>(
    `/api/purchase/receipts/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeReceipt(result);
}
