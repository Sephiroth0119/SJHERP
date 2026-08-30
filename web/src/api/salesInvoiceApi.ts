import { request } from './http.ts';

export type SalesInvoiceStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export interface SalesInvoiceLine {
  lineNo: number;
  deliveryLineNo: number;
  productId: number;
  quantity: string;
  unitPrice: string;
  amount: string;
}

export interface SalesInvoice {
  docNo: string;
  salesDeliveryNo: string;
  customerId: number;
  invoiceDate: string;
  dueDate: string | null;
  remark: string | null;
  status: SalesInvoiceStatus;
  totalAmount: string;
  lines: SalesInvoiceLine[];
}

export interface SalesInvoicePage {
  items: SalesInvoice[];
  total: number;
  page: number;
  size: number;
}

export interface SalesInvoiceDeliveryLineOption {
  deliveryLineNo: number;
  productId: number;
  quantity: string;
  invoicedQty: string;
  outstandingInvoiceableQty: string;
}

export interface SalesInvoiceDeliveryOption {
  docNo: string;
  salesOrderNo: string;
  warehouseId: number;
  remark: string | null;
  status: 'COMPLETED';
  lines: SalesInvoiceDeliveryLineOption[];
}

export interface SalesInvoiceDeliveryOptionPage {
  items: SalesInvoiceDeliveryOption[];
  total: number;
  page: number;
  size: number;
}

export interface SalesInvoiceLineForm {
  deliveryLineNo: number;
  productId: number;
  quantity: string;
  unitPrice: string;
  outstandingInvoiceableQty: string;
}

export interface SalesInvoiceForm {
  salesDeliveryNo: string;
  invoiceDate: string;
  dueDate: string;
  remark: string;
  lines: SalesInvoiceLineForm[];
}

type DecimalWire = string | number;
type SalesInvoiceLineWire = Omit<
  SalesInvoiceLine,
  'quantity' | 'unitPrice' | 'amount'
> & { quantity: DecimalWire; unitPrice: DecimalWire; amount: DecimalWire };
type SalesInvoiceWire = Omit<SalesInvoice, 'totalAmount' | 'lines'> & {
  totalAmount: DecimalWire;
  lines: SalesInvoiceLineWire[];
};
type SalesInvoicePageWire = Omit<SalesInvoicePage, 'items'> & {
  items: SalesInvoiceWire[];
};
type DeliveryLineWire = Omit<
  SalesInvoiceDeliveryLineOption,
  'quantity' | 'invoicedQty' | 'outstandingInvoiceableQty'
> & {
  quantity: DecimalWire;
  invoicedQty: DecimalWire;
  outstandingInvoiceableQty: DecimalWire;
};
type DeliveryWire = Omit<SalesInvoiceDeliveryOption, 'lines'> & {
  lines: DeliveryLineWire[];
};
type DeliveryPageWire = Omit<SalesInvoiceDeliveryOptionPage, 'items'> & {
  items: DeliveryWire[];
};

export const positiveQuantityPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;
export const nonNegativeUnitPricePattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

const normalizeLine = (line: SalesInvoiceLineWire): SalesInvoiceLine => ({
  ...line,
  quantity: String(line.quantity),
  unitPrice: String(line.unitPrice),
  amount: String(line.amount),
});

const normalizeInvoice = (invoice: SalesInvoiceWire): SalesInvoice => ({
  ...invoice,
  totalAmount: String(invoice.totalAmount),
  lines: invoice.lines.map(normalizeLine),
});

const normalizeDelivery = (delivery: DeliveryWire): SalesInvoiceDeliveryOption => ({
  ...delivery,
  lines: delivery.lines.map((line) => ({
    ...line,
    quantity: String(line.quantity),
    invoicedQty: String(line.invoicedQty),
    outstandingInvoiceableQty: String(line.outstandingInvoiceableQty),
  })),
});

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

const MAX_AMOUNT_CENTS = 999999999999999999n;

function amountInCents(quantity: string, unitPrice: string): bigint {
  const parse = (value: string) => {
    const [integer = '0', fraction = ''] = value.split('.');
    return { unscaled: BigInt(`${integer}${fraction}`), scale: fraction.length };
  };
  const left = parse(quantity);
  const right = parse(unitPrice);
  const raw = left.unscaled * right.unscaled;
  const scale = left.scale + right.scale;
  const cents = scale > 2
    ? (() => {
        const divisor = 10n ** BigInt(scale - 2);
        return (raw + divisor / 2n) / divisor;
      })()
    : raw * (10n ** BigInt(2 - scale));
  return cents;
}

export function validateSalesInvoiceForm(form: SalesInvoiceForm): string | null {
  if (!form.salesDeliveryNo.trim()) {
    return '请选择已过账且仍可开票的销售出库单';
  }
  if (!form.invoiceDate) return '请选择发票日期';
  if (form.remark.length > 255) return '发票说明最多 255 个字符';
  if (form.lines.length === 0) return '销售发票至少要有一行未开完商品';

  const deliveryLineNos = new Set<number>();
  let totalAmountCents = 0n;
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.deliveryLineNo) || line.deliveryLineNo <= 0) {
      return `第 ${index + 1} 行销售出库单行号不可用`;
    }
    if (deliveryLineNos.has(line.deliveryLineNo)) {
      return '同一销售发票内销售出库行不能重复';
    }
    deliveryLineNos.add(line.deliveryLineNo);
    if (!positiveQuantityPattern.test(line.quantity)) {
      return `第 ${index + 1} 行开票数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!nonNegativeUnitPricePattern.test(line.unitPrice)) {
      return `第 ${index + 1} 行开票单价必须为非负数，整数最多 12 位、小数最多 6 位`;
    }
    const lineAmountCents = amountInCents(line.quantity, line.unitPrice);
    if (lineAmountCents > MAX_AMOUNT_CENTS) {
      return `第 ${index + 1} 行金额超过系统可保存范围`;
    }
    totalAmountCents += lineAmountCents;
    if (totalAmountCents > MAX_AMOUNT_CENTS) {
      return '销售发票总金额超过系统可保存范围';
    }
    if (!positiveQuantityPattern.test(line.outstandingInvoiceableQty)) {
      return `第 ${index + 1} 行剩余可开票量不可用，请重新选择销售出库单`;
    }
    if (compareDecimalStrings(line.quantity, line.outstandingInvoiceableQty) > 0) {
      return `第 ${index + 1} 行开票数量超过剩余可开票量 ${line.outstandingInvoiceableQty}`;
    }
  }
  return null;
}

export async function searchSalesInvoices(
  customerId: number | null,
  salesDeliveryNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<SalesInvoicePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (customerId !== null) params.set('customerId', String(customerId));
  if (salesDeliveryNo.trim()) params.set('salesDeliveryNo', salesDeliveryNo.trim());
  if (status) params.set('status', status);
  const result = await request<SalesInvoicePageWire>(`/api/sales/invoices?${params}`);
  return { ...result, items: result.items.map(normalizeInvoice) };
}

export async function getSalesInvoice(docNo: string): Promise<SalesInvoice> {
  return normalizeInvoice(await request<SalesInvoiceWire>(
    `/api/sales/invoices/${encodeURIComponent(docNo)}`,
  ));
}

export async function searchSalesInvoiceDeliveryOptions(
  page: number,
  size = 20,
): Promise<SalesInvoiceDeliveryOptionPage> {
  const result = await request<DeliveryPageWire>(
    `/api/sales/invoices/delivery-options?page=${page}&size=${size}`,
  );
  return { ...result, items: result.items.map(normalizeDelivery) };
}

export async function getSalesInvoiceDeliveryOption(
  docNo: string,
): Promise<SalesInvoiceDeliveryOption> {
  return normalizeDelivery(await request<DeliveryWire>(
    `/api/sales/invoices/delivery-options/${encodeURIComponent(docNo)}`,
  ));
}

export async function createSalesInvoice(form: SalesInvoiceForm): Promise<SalesInvoice> {
  const body = {
    salesDeliveryNo: form.salesDeliveryNo,
    invoiceDate: form.invoiceDate,
    dueDate: form.dueDate || null,
    remark: form.remark,
    lines: form.lines.map(({ deliveryLineNo, productId, quantity, unitPrice }) => ({
      deliveryLineNo,
      productId,
      quantity,
      unitPrice,
    })),
  };
  return normalizeInvoice(await request<SalesInvoiceWire>('/api/sales/invoices', {
    method: 'POST', body,
  }));
}

const transition = async (docNo: string, action: 'approve' | 'post' | 'cancel') =>
  normalizeInvoice(await request<SalesInvoiceWire>(
    `/api/sales/invoices/${encodeURIComponent(docNo)}/${action}`,
    { method: 'POST' },
  ));

export const approveSalesInvoice = (docNo: string) => transition(docNo, 'approve');
export const postSalesInvoice = (docNo: string) => transition(docNo, 'post');
export const cancelSalesInvoice = (docNo: string) => transition(docNo, 'cancel');
