import { request } from './http.ts';
import {
  formatQuantityDisplay,
  positiveDecimalPattern,
} from './productionBomApi.ts';
import type { MaterialDocumentStatus, MaterialIssue } from './materialIssueApi.ts';

type DecimalWire = string | number;

export type MaterialReturnAction = 'approve' | 'post';

export interface MaterialReturnLine {
  lineNo: number;
  productId: number;
  quantity: string;
  unitId: number;
  srcIssueLineNo: number | null;
  returnedCost: string | null;
}

export interface MaterialReturn {
  docNo: string;
  materialIssueDocNo: string;
  warehouseId: number;
  status: MaterialDocumentStatus;
  remark: string | null;
  totalReturnedCost: string;
  lines: MaterialReturnLine[];
}

export interface MaterialReturnPage {
  items: MaterialReturn[];
  total: number;
  page: number;
  size: number;
}

export interface MaterialReturnLineForm {
  productId: number | null;
  quantity: string;
  unitId: number | null;
  srcIssueLineNo: number | null;
  issuedQty: string;
}

export interface MaterialReturnForm {
  materialIssueDocNo: string;
  warehouseId: number | null;
  remark: string;
  lines: MaterialReturnLineForm[];
}

type MaterialReturnLineWire = Omit<
  MaterialReturnLine,
  'quantity' | 'returnedCost' | 'srcIssueLineNo'
> & {
  quantity: DecimalWire;
  returnedCost?: DecimalWire | null;
  srcIssueLineNo?: number | null;
};

type MaterialReturnWire = Omit<
  MaterialReturn,
  'status' | 'totalReturnedCost' | 'lines' | 'remark'
> & {
  status: string;
  totalReturnedCost: DecimalWire;
  remark?: string | null;
  lines?: MaterialReturnLineWire[];
};

type MaterialReturnPageWire = Omit<MaterialReturnPage, 'items'> & {
  items: MaterialReturnWire[];
};

export { formatQuantityDisplay, positiveDecimalPattern };

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

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

function normalizeLine(line: MaterialReturnLineWire): MaterialReturnLine {
  return {
    lineNo: line.lineNo,
    productId: line.productId,
    quantity: asString(line.quantity),
    unitId: line.unitId,
    srcIssueLineNo: line.srcIssueLineNo ?? null,
    returnedCost: line.returnedCost == null ? null : asString(line.returnedCost),
  };
}

function normalizeReturn(item: MaterialReturnWire): MaterialReturn {
  return {
    docNo: item.docNo,
    materialIssueDocNo: item.materialIssueDocNo,
    warehouseId: item.warehouseId,
    status: normalizeStatus(item.status),
    remark: item.remark ?? null,
    totalReturnedCost: asString(item.totalReturnedCost),
    lines: (item.lines ?? []).map(normalizeLine),
  };
}

export function emptyMaterialReturnForm(): MaterialReturnForm {
  return {
    materialIssueDocNo: '',
    warehouseId: null,
    remark: '',
    lines: [],
  };
}

export function linesFromMaterialIssue(issue: MaterialIssue): MaterialReturnLineForm[] {
  return issue.lines.map((line) => ({
    productId: line.productId,
    quantity: line.quantity,
    unitId: line.unitId,
    srcIssueLineNo: line.lineNo,
    issuedQty: line.quantity,
  }));
}

function compareDecimalStrings(left: string, right: string): number {
  const [leftInteger = '0', leftFraction = ''] = left.split('.');
  const [rightInteger = '0', rightFraction = ''] = right.split('.');
  const normalizedLeftInteger = leftInteger.replace(/^0+(?=\d)/, '') || '0';
  const normalizedRightInteger = rightInteger.replace(/^0+(?=\d)/, '') || '0';
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

function createBody(form: MaterialReturnForm) {
  return {
    materialIssueDocNo: form.materialIssueDocNo.trim(),
    warehouseId: form.warehouseId,
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => ({
      productId: line.productId,
      quantity: line.quantity,
      unitId: line.unitId,
      srcIssueLineNo: line.srcIssueLineNo,
    })),
  };
}

export function validateMaterialReturnForm(form: MaterialReturnForm): string | null {
  if (form.materialIssueDocNo.trim() === '') {
    return '请选择已过账的原领料单';
  }
  if (!Number.isInteger(form.warehouseId) || (form.warehouseId ?? 0) <= 0) {
    return '请选择退料仓库';
  }
  if (form.lines.length === 0) {
    return '退料单至少要有一行';
  }
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || (line.productId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择退料商品`;
    }
    if (!positiveDecimalPattern.test(line.quantity.trim())) {
      return `第 ${index + 1} 行退料数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!Number.isInteger(line.unitId) || (line.unitId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择计量单位`;
    }
    if (line.issuedQty !== '' && compareDecimalStrings(line.quantity.trim(), line.issuedQty) > 0) {
      return `第 ${index + 1} 行退料量 ${line.quantity.trim()} 超过原领料实领量 ${line.issuedQty}，不可超退`;
    }
  }
  return null;
}

export function availableMaterialReturnActions(status: MaterialDocumentStatus): MaterialReturnAction[] {
  switch (status) {
    case 'DRAFT':
      return ['approve'];
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

export async function searchMaterialReturns(
  materialIssueDocNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<MaterialReturnPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (materialIssueDocNo.trim()) {
    params.set('materialIssueDocNo', materialIssueDocNo.trim());
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<MaterialReturnPageWire>(`/api/production/material-returns?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeReturn),
  };
}

export async function getMaterialReturn(docNo: string): Promise<MaterialReturn> {
  const result = await request<MaterialReturnWire>(
    `/api/production/material-returns/${encodeURIComponent(docNo)}`,
  );
  return normalizeReturn(result);
}

export async function createMaterialReturn(form: MaterialReturnForm): Promise<MaterialReturn> {
  const result = await request<MaterialReturnWire>('/api/production/material-returns', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeReturn(result);
}

async function postTransition(docNo: string, action: MaterialReturnAction): Promise<MaterialReturn> {
  const result = await request<MaterialReturnWire>(
    `/api/production/material-returns/${encodeURIComponent(docNo)}/${action}`,
    { method: 'POST' },
  );
  return normalizeReturn(result);
}

export function approveMaterialReturn(docNo: string): Promise<MaterialReturn> {
  return postTransition(docNo, 'approve');
}

export function postMaterialReturn(docNo: string): Promise<MaterialReturn> {
  return postTransition(docNo, 'post');
}
