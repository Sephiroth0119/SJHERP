import * as agingReportApi from '../api/agingReportApi.ts';
import * as statementApi from '../api/financialStatementApi.ts';
import * as glBalanceApi from '../api/glBalanceApi.ts';
import * as inventoryReportApi from '../api/inventoryReportApi.ts';
import type { SpreadsheetSheet } from './excelExport.ts';
import { type ReportKind } from './reportKinds.ts';

export const PAGE_SIZE = inventoryReportApi.REPORT_PAGE_SIZE;
export const EXPORT_PAGE_SIZE = inventoryReportApi.REPORT_EXPORT_PAGE_SIZE;

export interface ReportColumn {
  key: string;
  title: string;
}

export interface ReportChart {
  title: string;
  bars: { label: string; value: string }[];
  empty: string;
}

export interface ReportFilters {
  asOf: string;
  period: string;
  fromDate: string;
  toDate: string;
  keyword: string;
  includeZero: boolean;
  warehouseId: number | null;
  productId: number | null;
  customerId: number | null;
  supplierId: number | null;
  accountCode: string;
  status: string;
}

export interface ReportView {
  columns: ReportColumn[];
  rows: Record<string, string>[];
  total: number;
  page: number;
  size: number;
  paged: boolean;
  summary: string;
  chart: ReportChart | null;
  sheets: SpreadsheetSheet[];
  stamp: string;
}

export type LoadMode = 'page' | 'export';

const amount = (value: string) => (value === '' ? '—' : value);

function today(): string {
  const value = new Date();
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function currentPeriod(): string {
  const value = new Date();
  return `${value.getFullYear()}${String(value.getMonth() + 1).padStart(2, '0')}`;
}

function currentMonthRange(): { fromDate: string; toDate: string } {
  const value = new Date();
  const year = value.getFullYear();
  const monthIndex = value.getMonth();
  const month = String(monthIndex + 1).padStart(2, '0');
  const last = new Date(year, monthIndex + 1, 0).getDate();
  return {
    fromDate: `${year}-${month}-01`,
    toDate: `${year}-${month}-${String(last).padStart(2, '0')}`,
  };
}

export function emptyReportFilters(): ReportFilters {
  const month = currentMonthRange();
  return {
    asOf: today(),
    period: currentPeriod(),
    fromDate: month.fromDate,
    toDate: month.toDate,
    keyword: '',
    includeZero: true,
    warehouseId: null,
    productId: null,
    customerId: null,
    supplierId: null,
    accountCode: '',
    status: '',
  };
}

function sheetFrom(
  name: string,
  columns: ReportColumn[],
  rows: Record<string, string>[],
  extra: readonly (readonly string[])[] = [],
): SpreadsheetSheet {
  return {
    name,
    headers: columns.map((column) => column.title),
    rows: [
      ...extra,
      ...rows.map((row) => columns.map((column) => row[column.key] ?? '')),
    ],
  };
}

function agingColumns(counterparty: string): ReportColumn[] {
  return [
    { key: 'counterparty', title: counterparty },
    { key: 'notDue', title: '未到期' },
    { key: 'overdue1To30', title: '逾期 1-30' },
    { key: 'overdue31To60', title: '逾期 31-60' },
    { key: 'overdue61To90', title: '逾期 61-90' },
    { key: 'overdue90Plus', title: '逾期 90+' },
    { key: 'totalOutstanding', title: '合计' },
  ];
}

function agingRow(item: agingReportApi.AgingItem): Record<string, string> {
  const name = item.counterpartyName?.trim() ?? '';
  const code = item.counterpartyCode?.trim() ?? '';
  let counterparty = `对手方 #${item.counterpartyId}（名称不可用）`;
  if (name && code) counterparty = `${name}（${code}）`;
  else if (name) counterparty = name;
  else if (code) counterparty = code;
  return {
    counterparty,
    notDue: amount(item.notDue),
    overdue1To30: amount(item.overdue1To30),
    overdue31To60: amount(item.overdue31To60),
    overdue61To90: amount(item.overdue61To90),
    overdue90Plus: amount(item.overdue90Plus),
    totalOutstanding: amount(item.totalOutstanding),
  };
}

function agingChart(grandTotal: agingReportApi.AgingBuckets): ReportChart {
  return {
    title: '账龄分桶（当前筛选全集）',
    empty: '暂无账龄金额可绘图。',
    bars: [
      { label: '未到期', value: grandTotal.notDue },
      { label: '逾期 1-30', value: grandTotal.overdue1To30 },
      { label: '逾期 31-60', value: grandTotal.overdue31To60 },
      { label: '逾期 61-90', value: grandTotal.overdue61To90 },
      { label: '逾期 90+', value: grandTotal.overdue90Plus },
    ],
  };
}

async function loadAging(
  kind: 'receivable' | 'payable',
  filters: ReportFilters,
  page: number,
  mode: LoadMode,
): Promise<ReportView> {
  const counterparty = kind === 'receivable' ? '客户' : '供应商';
  const columns = agingColumns(counterparty);
  const fetchPage = (targetPage: number, size: number) => (
    kind === 'receivable'
      ? agingReportApi.searchReceivableAging(filters.asOf, filters.customerId, targetPage, size)
      : agingReportApi.searchPayableAging(filters.asOf, filters.supplierId, targetPage, size)
  );
  const collected = mode === 'export'
    ? await inventoryReportApi.collectAllPages(fetchPage, EXPORT_PAGE_SIZE)
    : { first: await fetchPage(page, PAGE_SIZE), items: [] as agingReportApi.AgingItem[] };
  const report = collected.first;
  const items = mode === 'export' ? collected.items : report.items;
  const rows = items.map(agingRow);
  const title = kind === 'receivable' ? '应收账龄' : '应付账龄';
  const grand = report.grandTotal;
  return {
    columns,
    rows,
    total: report.total,
    page: mode === 'export' ? 1 : report.page,
    size: mode === 'export' ? rows.length : report.size,
    paged: true,
    summary: `截止日 ${report.asOf || filters.asOf || '今天'} · 全量合计（当前筛选，非本页）：`
      + `未到期 ${amount(grand.notDue)} · 逾期 1-30 ${amount(grand.overdue1To30)} · `
      + `逾期 31-60 ${amount(grand.overdue31To60)} · 逾期 61-90 ${amount(grand.overdue61To90)} · `
      + `逾期 90+ ${amount(grand.overdue90Plus)} · 合计 ${amount(grand.totalOutstanding)}`,
    chart: agingChart(grand),
    sheets: [sheetFrom(title, columns, rows, [[
      '全量合计',
      amount(grand.notDue),
      amount(grand.overdue1To30),
      amount(grand.overdue31To60),
      amount(grand.overdue61To90),
      amount(grand.overdue90Plus),
      amount(grand.totalOutstanding),
    ]])],
    stamp: report.asOf || filters.asOf,
  };
}

function inventoryColumns(): ReportColumn[] {
  return [
    { key: 'warehouse', title: '仓库' },
    { key: 'product', title: '商品' },
    { key: 'quantity', title: '数量' },
    { key: 'costAmount', title: '结存金额' },
    { key: 'unitCost', title: '加权单价' },
  ];
}

function movementColumns(): ReportColumn[] {
  return [
    { key: 'warehouse', title: '仓库' },
    { key: 'product', title: '商品' },
    { key: 'openingQuantity', title: '期初数量' },
    { key: 'openingAmount', title: '期初金额' },
    { key: 'inQuantity', title: '收入数量' },
    { key: 'inAmount', title: '收入金额' },
    { key: 'outQuantity', title: '发出数量' },
    { key: 'outAmount', title: '发出金额' },
    { key: 'endingQuantity', title: '期末数量' },
    { key: 'endingAmount', title: '期末金额' },
  ];
}

function purchaseColumns(): ReportColumn[] {
  return [
    { key: 'receiptNo', title: '入库单号' },
    { key: 'receiptDate', title: '收货日' },
    { key: 'purchaseOrderNo', title: '采购订单' },
    { key: 'supplier', title: '供应商' },
    { key: 'warehouse', title: '仓库' },
    { key: 'product', title: '商品' },
    { key: 'quantity', title: '数量' },
    { key: 'unitCost', title: '收货单价' },
    { key: 'amount', title: '入库金额' },
    { key: 'status', title: '状态' },
  ];
}

function salesColumns(): ReportColumn[] {
  return [
    { key: 'deliveryNo', title: '出库单号' },
    { key: 'deliveryDate', title: '出库日' },
    { key: 'salesOrderNo', title: '销售订单' },
    { key: 'customer', title: '客户' },
    { key: 'warehouse', title: '仓库' },
    { key: 'product', title: '商品' },
    { key: 'quantity', title: '数量' },
    { key: 'unitPrice', title: '售价' },
    { key: 'salesAmount', title: '销售额' },
    { key: 'cogsAmount', title: 'COGS' },
    { key: 'grossProfit', title: '毛利' },
    { key: 'status', title: '状态' },
  ];
}

function named(code: string, name: string, fallback: string): string {
  if (name && code) return `${name}（${code}）`;
  if (name) return name;
  if (code) return code;
  return fallback;
}

async function loadInventoryBalance(
  filters: ReportFilters,
  page: number,
  mode: LoadMode,
): Promise<ReportView> {
  const columns = inventoryColumns();
  const fetchPage = (targetPage: number, size: number) => inventoryReportApi.searchInventoryBalance(
    filters.warehouseId,
    filters.productId,
    filters.keyword,
    filters.includeZero,
    targetPage,
    size,
  );
  const collected = mode === 'export'
    ? await inventoryReportApi.collectAllPages(fetchPage, EXPORT_PAGE_SIZE)
    : { first: await fetchPage(page, PAGE_SIZE), items: [] as inventoryReportApi.InventoryBalanceRow[] };
  const report = collected.first;
  const items = mode === 'export' ? collected.items : report.items;
  const rows = items.map((item) => ({
    warehouse: named(item.warehouseCode, item.warehouseName, `#${item.warehouseId}`),
    product: named(item.productCode, item.productName, `#${item.productId}`),
    quantity: amount(item.quantity),
    costAmount: amount(item.costAmount),
    unitCost: amount(item.unitCost),
  }));
  return {
    columns,
    rows,
    total: report.total,
    page: mode === 'export' ? 1 : report.page,
    size: mode === 'export' ? rows.length : report.size,
    paged: true,
    summary: `库存总值 ${amount(report.totalCostAmount)}（当前筛选全集，非本页）`,
    chart: null,
    sheets: [sheetFrom('库存余额表', columns, rows)],
    stamp: today(),
  };
}

async function loadStockMovement(
  filters: ReportFilters,
  page: number,
  mode: LoadMode,
): Promise<ReportView> {
  const columns = movementColumns();
  const fetchPage = (targetPage: number, size: number) => inventoryReportApi.searchStockMovement(
    filters.fromDate,
    filters.toDate,
    filters.warehouseId,
    filters.productId,
    filters.keyword,
    targetPage,
    size,
  );
  const collected = mode === 'export'
    ? await inventoryReportApi.collectAllPages(fetchPage, EXPORT_PAGE_SIZE)
    : { first: await fetchPage(page, PAGE_SIZE), items: [] as inventoryReportApi.StockMovementRow[] };
  const report = collected.first;
  const items = mode === 'export' ? collected.items : report.items;
  const rows = items.map((item) => ({
    warehouse: named(item.warehouseCode, item.warehouseName, `#${item.warehouseId}`),
    product: named(item.productCode, item.productName, `#${item.productId}`),
    openingQuantity: amount(item.openingQuantity),
    openingAmount: amount(item.openingAmount),
    inQuantity: amount(item.inQuantity),
    inAmount: amount(item.inAmount),
    outQuantity: amount(item.outQuantity),
    outAmount: amount(item.outAmount),
    endingQuantity: amount(item.endingQuantity),
    endingAmount: amount(item.endingAmount),
  }));
  return {
    columns,
    rows,
    total: report.total,
    page: mode === 'export' ? 1 : report.page,
    size: mode === 'export' ? rows.length : report.size,
    paged: true,
    summary: `${filters.fromDate} ~ ${filters.toDate} · `
      + `期初 ${amount(report.totalOpeningAmount)} · 收 ${amount(report.totalInAmount)} · `
      + `发 ${amount(report.totalOutAmount)} · 期末 ${amount(report.totalEndingAmount)}`,
    chart: {
      title: '收发存金额（当前筛选全集）',
      empty: '暂无收发存金额可绘图。',
      bars: [
        { label: '期初', value: report.totalOpeningAmount },
        { label: '收入', value: report.totalInAmount },
        { label: '发出', value: report.totalOutAmount },
        { label: '期末', value: report.totalEndingAmount },
      ],
    },
    sheets: [sheetFrom('收发存汇总', columns, rows)],
    stamp: `${filters.fromDate}_${filters.toDate}`,
  };
}

async function loadPurchaseDetail(
  filters: ReportFilters,
  page: number,
  mode: LoadMode,
): Promise<ReportView> {
  const columns = purchaseColumns();
  const fetchPage = (targetPage: number, size: number) => inventoryReportApi.searchPurchaseDetail(
    filters.fromDate,
    filters.toDate,
    filters.supplierId,
    filters.productId,
    filters.warehouseId,
    filters.status,
    targetPage,
    size,
  );
  const collected = mode === 'export'
    ? await inventoryReportApi.collectAllPages(fetchPage, EXPORT_PAGE_SIZE)
    : { first: await fetchPage(page, PAGE_SIZE), items: [] as inventoryReportApi.PurchaseDetailRow[] };
  const report = collected.first;
  const items = mode === 'export' ? collected.items : report.items;
  const rows = items.map((item) => ({
    receiptNo: item.receiptNo,
    receiptDate: item.receiptDate,
    purchaseOrderNo: item.purchaseOrderNo,
    supplier: named(item.supplierCode, item.supplierName, item.supplierId ? `#${item.supplierId}` : '—'),
    warehouse: named(item.warehouseCode, item.warehouseName, `#${item.warehouseId}`),
    product: named(item.productCode, item.productName, `#${item.productId}`),
    quantity: amount(item.quantity),
    unitCost: amount(item.unitCost),
    amount: amount(item.amount),
    status: item.status || '—',
  }));
  return {
    columns,
    rows,
    total: report.total,
    page: mode === 'export' ? 1 : report.page,
    size: mode === 'export' ? rows.length : report.size,
    paged: true,
    summary: `总进货额 ${amount(report.totalAmount)}（当前筛选全集，非本页）`,
    chart: null,
    sheets: [sheetFrom('采购入库明细', columns, rows)],
    stamp: `${filters.fromDate}_${filters.toDate}`,
  };
}

async function loadSalesDetail(
  filters: ReportFilters,
  page: number,
  mode: LoadMode,
): Promise<ReportView> {
  const columns = salesColumns();
  const fetchPage = (targetPage: number, size: number) => inventoryReportApi.searchSalesDetail(
    filters.fromDate,
    filters.toDate,
    filters.customerId,
    filters.productId,
    filters.warehouseId,
    filters.status,
    targetPage,
    size,
  );
  const collected = mode === 'export'
    ? await inventoryReportApi.collectAllPages(fetchPage, EXPORT_PAGE_SIZE)
    : { first: await fetchPage(page, PAGE_SIZE), items: [] as inventoryReportApi.SalesDetailRow[] };
  const report = collected.first;
  const items = mode === 'export' ? collected.items : report.items;
  const rows = items.map((item) => ({
    deliveryNo: item.deliveryNo,
    deliveryDate: item.deliveryDate,
    salesOrderNo: item.salesOrderNo,
    customer: named(item.customerCode, item.customerName, item.customerId ? `#${item.customerId}` : '—'),
    warehouse: named(item.warehouseCode, item.warehouseName, `#${item.warehouseId}`),
    product: named(item.productCode, item.productName, `#${item.productId}`),
    quantity: amount(item.quantity),
    unitPrice: amount(item.unitPrice),
    salesAmount: amount(item.salesAmount),
    cogsAmount: amount(item.cogsAmount),
    grossProfit: amount(item.grossProfit),
    status: item.status || '—',
  }));
  return {
    columns,
    rows,
    total: report.total,
    page: mode === 'export' ? 1 : report.page,
    size: mode === 'export' ? rows.length : report.size,
    paged: true,
    summary: `销售额 ${amount(report.totalSalesAmount)} · COGS ${amount(report.totalCogsAmount)} · 毛利 ${amount(report.totalGrossProfit)}`,
    chart: {
      title: '销售金额结构（当前筛选全集）',
      empty: '暂无销售金额可绘图。',
      bars: [
        { label: '销售额', value: report.totalSalesAmount },
        { label: 'COGS', value: report.totalCogsAmount },
        { label: '毛利', value: report.totalGrossProfit },
      ],
    },
    sheets: [sheetFrom('销售出库明细', columns, rows)],
    stamp: `${filters.fromDate}_${filters.toDate}`,
  };
}

function trialColumns(): ReportColumn[] {
  return [
    { key: 'accountCode', title: '科目编码' },
    { key: 'totalDebit', title: '借方发生额' },
    { key: 'totalCredit', title: '贷方发生额' },
    { key: 'netBalance', title: '净额' },
  ];
}

async function loadTrialBalance(filters: ReportFilters): Promise<ReportView> {
  const report = await glBalanceApi.getTrialBalance(filters.period);
  const columns = trialColumns();
  const rows = report.balances.map((item) => ({
    accountCode: item.accountCode,
    totalDebit: amount(item.totalDebit),
    totalCredit: amount(item.totalCredit),
    netBalance: amount(item.netBalance),
  }));
  return {
    columns,
    rows,
    total: rows.length,
    page: 1,
    size: rows.length,
    paged: false,
    summary: `账期 ${report.period} · Σ借 ${amount(report.totalDebit)} · Σ贷 ${amount(report.totalCredit)}`,
    chart: {
      title: '试算借贷合计',
      empty: '暂无试算发生额可绘图。',
      bars: [
        { label: 'Σ借', value: report.totalDebit },
        { label: 'Σ贷', value: report.totalCredit },
      ],
    },
    sheets: [sheetFrom('试算平衡', columns, rows)],
    stamp: report.period,
  };
}

async function loadAccountBalance(filters: ReportFilters): Promise<ReportView> {
  const item = await glBalanceApi.getAccountBalance(filters.accountCode, filters.period);
  const columns = trialColumns();
  const rows = [{
    accountCode: item.accountCode,
    totalDebit: amount(item.totalDebit),
    totalCredit: amount(item.totalCredit),
    netBalance: amount(item.netBalance),
  }];
  return {
    columns,
    rows,
    total: 1,
    page: 1,
    size: 1,
    paged: false,
    summary: `科目 ${item.accountCode} · 账期 ${filters.period.trim()} · 净额 ${amount(item.netBalance)}`,
    chart: {
      title: '科目借贷发生额',
      empty: '该科目本期无发生额。',
      bars: [
        { label: '借方', value: item.totalDebit },
        { label: '贷方', value: item.totalCredit },
      ],
    },
    sheets: [sheetFrom('科目余额', columns, rows)],
    stamp: `${item.accountCode}-${filters.period.trim()}`,
  };
}

async function loadBalanceSheet(filters: ReportFilters): Promise<ReportView> {
  const sheet = await statementApi.getBalanceSheet(filters.period);
  const columns: ReportColumn[] = [
    { key: 'section', title: '类别' },
    { key: 'name', title: '项目' },
    { key: 'amount', title: '金额' },
  ];
  const rows: Record<string, string>[] = [
    ...sheet.assetLines.map((line) => ({ section: '资产', name: line.name, amount: amount(line.amount) })),
    { section: '资产', name: '资产总计', amount: amount(sheet.totalAssets) },
    ...sheet.liabilityLines.map((line) => ({ section: '负债', name: line.name, amount: amount(line.amount) })),
    { section: '负债', name: '负债合计', amount: amount(sheet.totalLiabilities) },
    ...sheet.equityLines.map((line) => ({ section: '所有者权益', name: line.name, amount: amount(line.amount) })),
    { section: '所有者权益', name: '权益合计', amount: amount(sheet.totalEquity) },
  ];
  return {
    columns,
    rows,
    total: rows.length,
    page: 1,
    size: rows.length,
    paged: false,
    summary: `账期 ${sheet.period} · ${sheet.balanced ? '已平衡（资产 = 负债 + 权益）' : '未平衡，请核对未分类科目或凭证'}`,
    chart: {
      title: '资产负债结构',
      empty: '暂无资产负债表金额可绘图。',
      bars: [
        { label: '资产总计', value: sheet.totalAssets },
        { label: '负债合计', value: sheet.totalLiabilities },
        { label: '权益合计', value: sheet.totalEquity },
      ],
    },
    sheets: [sheetFrom('资产负债表', columns, rows)],
    stamp: sheet.period,
  };
}

async function loadIncomeStatement(filters: ReportFilters): Promise<ReportView> {
  const statement = await statementApi.getIncomeStatement(filters.period);
  const columns: ReportColumn[] = [
    { key: 'name', title: '项目' },
    { key: 'currentPeriod', title: '本期' },
    { key: 'yearToDate', title: '本年累计' },
  ];
  const rows = statement.lines.map((line) => ({
    name: line.name,
    currentPeriod: amount(line.currentPeriod),
    yearToDate: amount(line.yearToDate),
  }));
  return {
    columns,
    rows,
    total: rows.length,
    page: 1,
    size: rows.length,
    paged: false,
    summary: `账期 ${statement.period} · 本期净利润 ${amount(statement.netProfitCurrent)} · 本年累计净利润 ${amount(statement.netProfitYtd)}`,
    chart: {
      title: '净利润本期 vs 本年累计',
      empty: '暂无利润金额可绘图。',
      bars: [
        { label: '本期净利润', value: statement.netProfitCurrent },
        { label: '本年累计净利润', value: statement.netProfitYtd },
      ],
    },
    sheets: [sheetFrom('利润表', columns, rows)],
    stamp: statement.period,
  };
}

export async function loadReport(
  kind: ReportKind,
  filters: ReportFilters,
  page: number,
  mode: LoadMode = 'page',
): Promise<ReportView> {
  switch (kind) {
    case 'inventory-balance':
      return loadInventoryBalance(filters, page, mode);
    case 'stock-movement':
      return loadStockMovement(filters, page, mode);
    case 'purchase-detail':
      return loadPurchaseDetail(filters, page, mode);
    case 'sales-detail':
      return loadSalesDetail(filters, page, mode);
    case 'receivable-aging':
      return loadAging('receivable', filters, page, mode);
    case 'payable-aging':
      return loadAging('payable', filters, page, mode);
    case 'trial-balance':
      return loadTrialBalance(filters);
    case 'account-balance':
      return loadAccountBalance(filters);
    case 'balance-sheet':
      return loadBalanceSheet(filters);
    case 'income-statement':
      return loadIncomeStatement(filters);
    default: {
      const exhaustive: never = kind;
      throw new Error(`未知报表类型: ${exhaustive}`);
    }
  }
}
