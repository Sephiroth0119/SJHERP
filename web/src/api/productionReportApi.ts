import { request } from './http.ts';
import {
  formatQuantityDisplay,
  positiveDecimalPattern,
} from './productionBomApi.ts';
import {
  type MaterialDocumentStatus,
  nonNegativeDecimalPattern,
} from './materialIssueApi.ts';

type DecimalWire = string | number;

export type ProductionReportAction = 'approve' | 'post' | 'cancel';

export interface ProductionReportLine {
  lineNo: number;
  operationSeqNo: number | null;
  operationName: string | null;
  workCenter: string | null;
  reportedHours: string;
  reportedQty: string | null;
  unitId: number;
}

export interface ProductionReport {
  docNo: string;
  workOrderDocNo: string;
  warehouseId: number;
  productId: number;
  completedQty: string;
  scrapQty: string;
  unitId: number;
  inboundCost: string | null;
  status: MaterialDocumentStatus;
  remark: string | null;
  lines: ProductionReportLine[];
}

export interface ProductionReportPage {
  items: ProductionReport[];
  total: number;
  page: number;
  size: number;
}

export interface ProductionReportLineForm {
  operationSeqNo: string;
  operationName: string;
  workCenter: string;
  reportedHours: string;
  reportedQty: string;
  unitId: number | null;
}

export interface ProductionReportForm {
  workOrderDocNo: string;
  warehouseId: number | null;
  productId: number | null;
  completedQty: string;
  scrapQty: string;
  unitId: number | null;
  remark: string;
  lines: ProductionReportLineForm[];
}

type ProductionReportLineWire = Omit<
  ProductionReportLine,
  'reportedHours' | 'reportedQty' | 'operationSeqNo' | 'operationName' | 'workCenter'
> & {
  reportedHours: DecimalWire;
  reportedQty?: DecimalWire | null;
  operationSeqNo?: number | null;
  operationName?: string | null;
  workCenter?: string | null;
};

type ProductionReportWire = Omit<
  ProductionReport,
  'completedQty' | 'scrapQty' | 'inboundCost' | 'status' | 'remark' | 'lines'
> & {
  completedQty: DecimalWire;
  scrapQty: DecimalWire;
  inboundCost?: DecimalWire | null;
  status: string;
  remark?: string | null;
  lines?: ProductionReportLineWire[];
};

type ProductionReportPageWire = Omit<ProductionReportPage, 'items'> & {
  items: ProductionReportWire[];
};

export { formatQuantityDisplay, positiveDecimalPattern, nonNegativeDecimalPattern };

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

const sequencePattern = /^[1-9]\d{0,8}$/;

function normalizeStatus(value: string): MaterialDocumentStatus {
  switch (value) {
    case 'DRAFT':
    case 'APPROVED':
    case 'EXECUTING':
    case 'COMPLETED':
    case 'CANCELLED':
    case 'REVERSED':
      return value;
    default:
      return 'DRAFT';
  }
}

function normalizeLine(line: ProductionReportLineWire): ProductionReportLine {
  return {
    lineNo: line.lineNo,
    operationSeqNo: line.operationSeqNo ?? null,
    operationName: line.operationName ?? null,
    workCenter: line.workCenter ?? null,
    reportedHours: asString(line.reportedHours),
    reportedQty: line.reportedQty == null ? null : asString(line.reportedQty),
    unitId: line.unitId,
  };
}

function normalizeReport(item: ProductionReportWire): ProductionReport {
  return {
    docNo: item.docNo,
    workOrderDocNo: item.workOrderDocNo,
    warehouseId: item.warehouseId,
    productId: item.productId,
    completedQty: asString(item.completedQty),
    scrapQty: asString(item.scrapQty),
    unitId: item.unitId,
    inboundCost: item.inboundCost == null ? null : asString(item.inboundCost),
    status: normalizeStatus(item.status),
    remark: item.remark ?? null,
    lines: (item.lines ?? []).map(normalizeLine),
  };
}

export function emptyProductionReportLine(): ProductionReportLineForm {
  return {
    operationSeqNo: '',
    operationName: '',
    workCenter: '',
    reportedHours: '1',
    reportedQty: '',
    unitId: null,
  };
}

export function emptyProductionReportForm(): ProductionReportForm {
  return {
    workOrderDocNo: '',
    warehouseId: null,
    productId: null,
    completedQty: '1',
    scrapQty: '0',
    unitId: null,
    remark: '',
    lines: [emptyProductionReportLine()],
  };
}

function parseOptionalSequence(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed === '') {
    return null;
  }
  if (!sequencePattern.test(trimmed)) {
    return null;
  }
  return Number.parseInt(trimmed, 10);
}

function createBody(form: ProductionReportForm) {
  return {
    workOrderDocNo: form.workOrderDocNo.trim(),
    warehouseId: form.warehouseId,
    productId: form.productId,
    completedQty: form.completedQty,
    scrapQty: form.scrapQty.trim() === '' ? null : form.scrapQty,
    unitId: form.unitId,
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => ({
      operationSeqNo: parseOptionalSequence(line.operationSeqNo),
      operationName: line.operationName.trim() || null,
      workCenter: line.workCenter.trim() || null,
      reportedHours: line.reportedHours,
      reportedQty: line.reportedQty.trim() === '' ? null : line.reportedQty,
      unitId: line.unitId,
    })),
  };
}

export function validateProductionReportForm(form: ProductionReportForm): string | null {
  if (form.workOrderDocNo.trim() === '') {
    return '请选择已开工的工单';
  }
  if (!Number.isInteger(form.warehouseId) || (form.warehouseId ?? 0) <= 0) {
    return '请选择完工入库仓库';
  }
  if (!Number.isInteger(form.productId) || (form.productId ?? 0) <= 0) {
    return '请选择生产商品（须与工单一致）';
  }
  if (!positiveDecimalPattern.test(form.completedQty.trim())) {
    return '完工数量必须大于 0，整数最多 12 位、小数最多 6 位';
  }
  if (form.scrapQty.trim() !== '' && !nonNegativeDecimalPattern.test(form.scrapQty.trim())) {
    return '报废数量须为非负数，整数最多 12 位、小数最多 6 位，或留空';
  }
  if (!Number.isInteger(form.unitId) || (form.unitId ?? 0) <= 0) {
    return '请选择计量单位';
  }
  if (form.lines.length === 0) {
    return '报工单至少要有一行工时记录';
  }
  for (const [index, line] of form.lines.entries()) {
    if (line.operationSeqNo.trim() !== '' && parseOptionalSequence(line.operationSeqNo) === null) {
      return `第 ${index + 1} 行工序序号须为正整数，或留空`;
    }
    if (!positiveDecimalPattern.test(line.reportedHours.trim())) {
      return `第 ${index + 1} 行报工工时必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (line.reportedQty.trim() !== '' && !positiveDecimalPattern.test(line.reportedQty.trim())) {
      return `第 ${index + 1} 行报工数量必须大于 0，整数最多 12 位、小数最多 6 位，或留空`;
    }
    if (!Number.isInteger(line.unitId) || (line.unitId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择工时计量单位`;
    }
  }
  return null;
}

export function availableProductionReportActions(status: MaterialDocumentStatus): ProductionReportAction[] {
  switch (status) {
    case 'DRAFT':
      return ['approve', 'cancel'];
    case 'APPROVED':
      return ['post'];
    case 'EXECUTING':
    case 'COMPLETED':
    case 'CANCELLED':
    case 'REVERSED':
      return [];
    default: {
      const exhaustive: never = status;
      return exhaustive;
    }
  }
}

export async function searchProductionReports(
  workOrderDocNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<ProductionReportPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (workOrderDocNo.trim()) {
    params.set('workOrderDocNo', workOrderDocNo.trim());
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<ProductionReportPageWire>(`/api/production/reports?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeReport),
  };
}

export async function getProductionReport(docNo: string): Promise<ProductionReport> {
  const result = await request<ProductionReportWire>(
    `/api/production/reports/${encodeURIComponent(docNo)}`,
  );
  return normalizeReport(result);
}

export async function createProductionReport(form: ProductionReportForm): Promise<ProductionReport> {
  const result = await request<ProductionReportWire>('/api/production/reports', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeReport(result);
}

async function postTransition(
  docNo: string,
  action: ProductionReportAction,
): Promise<ProductionReport> {
  const result = await request<ProductionReportWire>(
    `/api/production/reports/${encodeURIComponent(docNo)}/${action}`,
    { method: 'POST' },
  );
  return normalizeReport(result);
}

export function approveProductionReport(docNo: string): Promise<ProductionReport> {
  return postTransition(docNo, 'approve');
}

export function postProductionReport(docNo: string): Promise<ProductionReport> {
  return postTransition(docNo, 'post');
}

export function cancelProductionReport(docNo: string): Promise<ProductionReport> {
  return postTransition(docNo, 'cancel');
}
