import { request } from './http.ts';

export const REPORT_PAGE_SIZE = 20;
export const REPORT_EXPORT_PAGE_SIZE = 200;

export interface InventoryBalanceRow {
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  productId: string;
  productCode: string;
  productName: string;
  quantity: string;
  costAmount: string;
  unitCost: string;
}

export interface InventoryBalanceReport {
  items: InventoryBalanceRow[];
  total: number;
  page: number;
  size: number;
  totalCostAmount: string;
}

export interface StockMovementRow {
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  productId: string;
  productCode: string;
  productName: string;
  openingQuantity: string;
  openingAmount: string;
  inQuantity: string;
  inAmount: string;
  outQuantity: string;
  outAmount: string;
  endingQuantity: string;
  endingAmount: string;
}

export interface StockMovementReport {
  items: StockMovementRow[];
  total: number;
  page: number;
  size: number;
  totalOpeningAmount: string;
  totalInAmount: string;
  totalOutAmount: string;
  totalEndingAmount: string;
}

export interface PurchaseDetailRow {
  receiptNo: string;
  receiptDate: string;
  purchaseOrderNo: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  lineNo: string;
  productId: string;
  productCode: string;
  productName: string;
  quantity: string;
  unitCost: string;
  amount: string;
  status: string;
}

export interface PurchaseDetailReport {
  items: PurchaseDetailRow[];
  total: number;
  page: number;
  size: number;
  totalAmount: string;
}

export interface SalesDetailRow {
  deliveryNo: string;
  deliveryDate: string;
  salesOrderNo: string;
  customerId: string;
  customerCode: string;
  customerName: string;
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  lineNo: string;
  productId: string;
  productCode: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  salesAmount: string;
  cogsAmount: string;
  grossProfit: string;
  status: string;
}

export interface SalesDetailReport {
  items: SalesDetailRow[];
  total: number;
  page: number;
  size: number;
  totalSalesAmount: string;
  totalCogsAmount: string;
  totalGrossProfit: string;
}

type DecimalWire = string | number | null | undefined;

function asString(value: DecimalWire): string {
  return value == null ? '' : String(value);
}

function optionalId(value: number | null): string {
  return value === null ? '' : String(value);
}

function appendOptional(
  params: URLSearchParams,
  key: string,
  value: string | number | null | undefined,
): void {
  if (value === null || value === undefined) return;
  const text = typeof value === 'number' ? String(value) : value.trim();
  if (text) params.set(key, text);
}

function pageParams(
  page: number,
  size: number,
  extras: Record<string, string | number | boolean | null | undefined>,
): string {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  for (const [key, value] of Object.entries(extras)) {
    if (typeof value === 'boolean') {
      params.set(key, value ? 'true' : 'false');
      continue;
    }
    appendOptional(params, key, value);
  }
  return params.toString();
}

function requireRange(fromDate: string, toDate: string): { fromDate: string; toDate: string } {
  const from = fromDate.trim();
  const to = toDate.trim();
  if (!from) throw new Error('请填写起始日期');
  if (!to) throw new Error('请填写结束日期');
  if (from > to) throw new Error(`起始日期不能晚于结束日期: ${from} > ${to}`);
  return { fromDate: from, toDate: to };
}

function normalizeInventoryRow(row: Record<string, DecimalWire>): InventoryBalanceRow {
  return {
    warehouseId: asString(row.warehouseId),
    warehouseCode: asString(row.warehouseCode),
    warehouseName: asString(row.warehouseName),
    productId: asString(row.productId),
    productCode: asString(row.productCode),
    productName: asString(row.productName),
    quantity: asString(row.quantity),
    costAmount: asString(row.costAmount),
    unitCost: asString(row.unitCost),
  };
}

function normalizeMovementRow(row: Record<string, DecimalWire>): StockMovementRow {
  return {
    warehouseId: asString(row.warehouseId),
    warehouseCode: asString(row.warehouseCode),
    warehouseName: asString(row.warehouseName),
    productId: asString(row.productId),
    productCode: asString(row.productCode),
    productName: asString(row.productName),
    openingQuantity: asString(row.openingQuantity),
    openingAmount: asString(row.openingAmount),
    inQuantity: asString(row.inQuantity),
    inAmount: asString(row.inAmount),
    outQuantity: asString(row.outQuantity),
    outAmount: asString(row.outAmount),
    endingQuantity: asString(row.endingQuantity),
    endingAmount: asString(row.endingAmount),
  };
}

function normalizePurchaseRow(row: Record<string, DecimalWire>): PurchaseDetailRow {
  return {
    receiptNo: asString(row.receiptNo),
    receiptDate: asString(row.receiptDate),
    purchaseOrderNo: asString(row.purchaseOrderNo),
    supplierId: asString(row.supplierId),
    supplierCode: asString(row.supplierCode),
    supplierName: asString(row.supplierName),
    warehouseId: asString(row.warehouseId),
    warehouseCode: asString(row.warehouseCode),
    warehouseName: asString(row.warehouseName),
    lineNo: asString(row.lineNo),
    productId: asString(row.productId),
    productCode: asString(row.productCode),
    productName: asString(row.productName),
    quantity: asString(row.quantity),
    unitCost: asString(row.unitCost),
    amount: asString(row.amount),
    status: asString(row.status),
  };
}

function normalizeSalesRow(row: Record<string, DecimalWire>): SalesDetailRow {
  return {
    deliveryNo: asString(row.deliveryNo),
    deliveryDate: asString(row.deliveryDate),
    salesOrderNo: asString(row.salesOrderNo),
    customerId: asString(row.customerId),
    customerCode: asString(row.customerCode),
    customerName: asString(row.customerName),
    warehouseId: asString(row.warehouseId),
    warehouseCode: asString(row.warehouseCode),
    warehouseName: asString(row.warehouseName),
    lineNo: asString(row.lineNo),
    productId: asString(row.productId),
    productCode: asString(row.productCode),
    productName: asString(row.productName),
    quantity: asString(row.quantity),
    unitPrice: asString(row.unitPrice),
    salesAmount: asString(row.salesAmount),
    cogsAmount: asString(row.cogsAmount),
    grossProfit: asString(row.grossProfit),
    status: asString(row.status),
  };
}

export async function searchInventoryBalance(
  warehouseId: number | null,
  productId: number | null,
  keyword: string,
  includeZero: boolean,
  page: number,
  size = REPORT_PAGE_SIZE,
): Promise<InventoryBalanceReport> {
  const result = await request<{
    items: Record<string, DecimalWire>[];
    total: number;
    page: number;
    size: number;
    totalCostAmount: DecimalWire;
  }>(`/api/reports/inventory-balance?${pageParams(page, size, {
    warehouseId: optionalId(warehouseId),
    productId: optionalId(productId),
    keyword,
    includeZero,
  })}`);
  return {
    items: (result.items ?? []).map(normalizeInventoryRow),
    total: result.total,
    page: result.page,
    size: result.size,
    totalCostAmount: asString(result.totalCostAmount),
  };
}

export async function searchStockMovement(
  fromDate: string,
  toDate: string,
  warehouseId: number | null,
  productId: number | null,
  keyword: string,
  page: number,
  size = REPORT_PAGE_SIZE,
): Promise<StockMovementReport> {
  const range = requireRange(fromDate, toDate);
  const result = await request<{
    items: Record<string, DecimalWire>[];
    total: number;
    page: number;
    size: number;
    totalOpeningAmount: DecimalWire;
    totalInAmount: DecimalWire;
    totalOutAmount: DecimalWire;
    totalEndingAmount: DecimalWire;
  }>(`/api/reports/stock-movement-summary?${pageParams(page, size, {
    fromDate: range.fromDate,
    toDate: range.toDate,
    warehouseId: optionalId(warehouseId),
    productId: optionalId(productId),
    keyword,
  })}`);
  return {
    items: (result.items ?? []).map(normalizeMovementRow),
    total: result.total,
    page: result.page,
    size: result.size,
    totalOpeningAmount: asString(result.totalOpeningAmount),
    totalInAmount: asString(result.totalInAmount),
    totalOutAmount: asString(result.totalOutAmount),
    totalEndingAmount: asString(result.totalEndingAmount),
  };
}

export async function searchPurchaseDetail(
  fromDate: string,
  toDate: string,
  supplierId: number | null,
  productId: number | null,
  warehouseId: number | null,
  status: string,
  page: number,
  size = REPORT_PAGE_SIZE,
): Promise<PurchaseDetailReport> {
  if (fromDate.trim() && toDate.trim() && fromDate.trim() > toDate.trim()) {
    throw new Error(`起始日期不能晚于结束日期: ${fromDate.trim()} > ${toDate.trim()}`);
  }
  const result = await request<{
    items: Record<string, DecimalWire>[];
    total: number;
    page: number;
    size: number;
    totalAmount: DecimalWire;
  }>(`/api/reports/purchase-detail?${pageParams(page, size, {
    fromDate,
    toDate,
    supplierId: optionalId(supplierId),
    productId: optionalId(productId),
    warehouseId: optionalId(warehouseId),
    status,
  })}`);
  return {
    items: (result.items ?? []).map(normalizePurchaseRow),
    total: result.total,
    page: result.page,
    size: result.size,
    totalAmount: asString(result.totalAmount),
  };
}

export async function searchSalesDetail(
  fromDate: string,
  toDate: string,
  customerId: number | null,
  productId: number | null,
  warehouseId: number | null,
  status: string,
  page: number,
  size = REPORT_PAGE_SIZE,
): Promise<SalesDetailReport> {
  if (fromDate.trim() && toDate.trim() && fromDate.trim() > toDate.trim()) {
    throw new Error(`起始日期不能晚于结束日期: ${fromDate.trim()} > ${toDate.trim()}`);
  }
  const result = await request<{
    items: Record<string, DecimalWire>[];
    total: number;
    page: number;
    size: number;
    totalSalesAmount: DecimalWire;
    totalCogsAmount: DecimalWire;
    totalGrossProfit: DecimalWire;
  }>(`/api/reports/sales-detail?${pageParams(page, size, {
    fromDate,
    toDate,
    customerId: optionalId(customerId),
    productId: optionalId(productId),
    warehouseId: optionalId(warehouseId),
    status,
  })}`);
  return {
    items: (result.items ?? []).map(normalizeSalesRow),
    total: result.total,
    page: result.page,
    size: result.size,
    totalSalesAmount: asString(result.totalSalesAmount),
    totalCogsAmount: asString(result.totalCogsAmount),
    totalGrossProfit: asString(result.totalGrossProfit),
  };
}

export async function collectAllPages<T extends { items: unknown[]; total: number }>(
  fetchPage: (page: number, size: number) => Promise<T>,
  size = REPORT_EXPORT_PAGE_SIZE,
): Promise<{ first: T; items: T['items'] }> {
  const first = await fetchPage(1, size);
  const items = [...first.items] as T['items'];
  const pageCount = size <= 0 ? 1 : Math.trunc((first.total + size - 1) / size);
  for (let page = 2; page <= pageCount; page += 1) {
    const next = await fetchPage(page, size);
    (items as unknown[]).push(...next.items);
  }
  return { first, items };
}
