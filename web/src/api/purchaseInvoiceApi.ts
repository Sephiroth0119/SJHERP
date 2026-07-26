import { request } from './http.ts';

export type PurchaseInvoiceStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface PurchaseInvoiceLine {
  lineNo: number;
  receiptLineNo: number;
  productId: number;
  quantity: string;
  amount: string;
}

export interface PurchaseInvoice {
  docNo: string;
  purchaseReceiptNo: string;
  supplierId: number;
  invoiceDate: string;
  supplierInvoiceNo: string | null;
  remark: string | null;
  status: PurchaseInvoiceStatus;
  totalAmount: string;
  lines: PurchaseInvoiceLine[];
}

export interface PurchaseInvoicePage {
  items: PurchaseInvoice[];
  total: number;
  page: number;
  size: number;
}

export interface PurchaseInvoiceReceiptLineOption {
  receiptLineNo: number;
  productId: number;
  quantity: string;
  unitCost: string;
  amount: string;
  invoicedQty: string;
  outstandingInvoiceableQty: string;
}

export interface PurchaseInvoiceReceiptOption {
  docNo: string;
  purchaseOrderNo: string;
  warehouseId: number;
  receiptDate: string;
  remark: string | null;
  status: 'COMPLETED';
  totalAmount: string;
  lines: PurchaseInvoiceReceiptLineOption[];
}

export interface PurchaseInvoiceReceiptOptionPage {
  items: PurchaseInvoiceReceiptOption[];
  total: number;
  page: number;
  size: number;
}

export interface PurchaseInvoiceLineForm {
  receiptLineNo: number;
  productId: number;
  quantity: string;
  amount: string;
  outstandingInvoiceableQty: string;
}

export interface PurchaseInvoiceForm {
  purchaseReceiptNo: string;
  invoiceDate: string;
  supplierInvoiceNo: string;
  remark: string;
  lines: PurchaseInvoiceLineForm[];
}

type PurchaseInvoiceLineWire = Omit<
  PurchaseInvoiceLine,
  'quantity' | 'amount'
> & {
  quantity: string | number;
  amount: string | number;
};

type PurchaseInvoiceWire = Omit<PurchaseInvoice, 'totalAmount' | 'lines'> & {
  totalAmount: string | number;
  lines: PurchaseInvoiceLineWire[];
};

type PurchaseInvoicePageWire = Omit<PurchaseInvoicePage, 'items'> & {
  items: PurchaseInvoiceWire[];
};

type PurchaseInvoiceReceiptLineOptionWire = Omit<
  PurchaseInvoiceReceiptLineOption,
  | 'quantity'
  | 'unitCost'
  | 'amount'
  | 'invoicedQty'
  | 'outstandingInvoiceableQty'
> & {
  quantity: string | number;
  unitCost: string | number;
  amount: string | number;
  invoicedQty: string | number;
  outstandingInvoiceableQty: string | number;
};

type PurchaseInvoiceReceiptOptionWire = Omit<
  PurchaseInvoiceReceiptOption,
  'totalAmount' | 'lines'
> & {
  totalAmount: string | number;
  lines: PurchaseInvoiceReceiptLineOptionWire[];
};

type PurchaseInvoiceReceiptOptionPageWire = Omit<
  PurchaseInvoiceReceiptOptionPage,
  'items'
> & {
  items: PurchaseInvoiceReceiptOptionWire[];
};

export const positiveQuantityPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

export const nonNegativeAmountPattern =
  /^(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/;

function normalizeLine(line: PurchaseInvoiceLineWire): PurchaseInvoiceLine {
  return {
    ...line,
    quantity: String(line.quantity),
    amount: String(line.amount),
  };
}

function normalizeInvoice(invoice: PurchaseInvoiceWire): PurchaseInvoice {
  return {
    ...invoice,
    totalAmount: String(invoice.totalAmount),
    lines: invoice.lines.map(normalizeLine),
  };
}

function normalizeReceiptOptionLine(
  line: PurchaseInvoiceReceiptLineOptionWire,
): PurchaseInvoiceReceiptLineOption {
  return {
    ...line,
    quantity: String(line.quantity),
    unitCost: String(line.unitCost),
    amount: String(line.amount),
    invoicedQty: String(line.invoicedQty),
    outstandingInvoiceableQty: String(line.outstandingInvoiceableQty),
  };
}

function normalizeReceiptOption(
  receipt: PurchaseInvoiceReceiptOptionWire,
): PurchaseInvoiceReceiptOption {
  return {
    ...receipt,
    totalAmount: String(receipt.totalAmount),
    lines: receipt.lines.map(normalizeReceiptOptionLine),
  };
}

function createBody(form: PurchaseInvoiceForm) {
  return {
    purchaseReceiptNo: form.purchaseReceiptNo,
    invoiceDate: form.invoiceDate,
    supplierInvoiceNo: form.supplierInvoiceNo,
    remark: form.remark,
    lines: form.lines.map(({ receiptLineNo, quantity, amount }) => ({
      receiptLineNo,
      quantity,
      amount,
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

export function validatePurchaseInvoiceForm(
  form: PurchaseInvoiceForm,
): string | null {
  if (!form.purchaseReceiptNo.trim()) {
    return '请选择已过账且仍可开票的采购入库单';
  }
  if (!form.invoiceDate) {
    return '请选择发票日期';
  }
  if (form.supplierInvoiceNo.length > 64) {
    return '供应商发票号最多 64 个字符';
  }
  if (form.lines.length === 0) {
    return '采购发票至少要有一行未开完商品';
  }

  const receiptLineNos = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.receiptLineNo) || line.receiptLineNo <= 0) {
      return `第 ${index + 1} 行采购入库单行号不可用`;
    }
    if (receiptLineNos.has(line.receiptLineNo)) {
      return '同一采购发票内采购入库行不能重复';
    }
    receiptLineNos.add(line.receiptLineNo);
    if (!positiveQuantityPattern.test(line.quantity)) {
      return `第 ${index + 1} 行开票数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!nonNegativeAmountPattern.test(line.amount)) {
      return `第 ${index + 1} 行开票金额必须为非负数，整数最多 16 位、小数最多 2 位`;
    }
    if (!positiveQuantityPattern.test(line.outstandingInvoiceableQty)) {
      return `第 ${index + 1} 行剩余可开票量不可用，请重新选择采购入库单`;
    }
    if (
      compareDecimalStrings(line.quantity, line.outstandingInvoiceableQty) > 0
    ) {
      return `第 ${index + 1} 行开票数量超过剩余可开票量 ${line.outstandingInvoiceableQty}`;
    }
  }
  return null;
}

export async function searchPurchaseInvoices(
  supplierId: number | null,
  purchaseReceiptNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<PurchaseInvoicePage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (supplierId !== null) {
    params.set('supplierId', String(supplierId));
  }
  if (purchaseReceiptNo.trim()) {
    params.set('purchaseReceiptNo', purchaseReceiptNo.trim());
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<PurchaseInvoicePageWire>(
    `/api/purchase/invoices?${params}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeInvoice),
  };
}

export async function getPurchaseInvoice(
  docNo: string,
): Promise<PurchaseInvoice> {
  const result = await request<PurchaseInvoiceWire>(
    `/api/purchase/invoices/${encodeURIComponent(docNo)}`,
  );
  return normalizeInvoice(result);
}

export async function searchPurchaseInvoiceReceiptOptions(
  page: number,
  size = 20,
): Promise<PurchaseInvoiceReceiptOptionPage> {
  const result = await request<PurchaseInvoiceReceiptOptionPageWire>(
    `/api/purchase/invoices/receipt-options?page=${page}&size=${size}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeReceiptOption),
  };
}

export async function getPurchaseInvoiceReceiptOption(
  docNo: string,
): Promise<PurchaseInvoiceReceiptOption> {
  const result = await request<PurchaseInvoiceReceiptOptionWire>(
    `/api/purchase/invoices/receipt-options/${encodeURIComponent(docNo)}`,
  );
  return normalizeReceiptOption(result);
}

export async function createPurchaseInvoice(
  form: PurchaseInvoiceForm,
): Promise<PurchaseInvoice> {
  const result = await request<PurchaseInvoiceWire>('/api/purchase/invoices', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeInvoice(result);
}

export async function approvePurchaseInvoice(
  docNo: string,
): Promise<PurchaseInvoice> {
  const result = await request<PurchaseInvoiceWire>(
    `/api/purchase/invoices/${encodeURIComponent(docNo)}/approve`,
    { method: 'POST' },
  );
  return normalizeInvoice(result);
}

export async function postPurchaseInvoice(
  docNo: string,
): Promise<PurchaseInvoice> {
  const result = await request<PurchaseInvoiceWire>(
    `/api/purchase/invoices/${encodeURIComponent(docNo)}/post`,
    { method: 'POST' },
  );
  return normalizeInvoice(result);
}
