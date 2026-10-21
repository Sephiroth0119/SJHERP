import { request } from './http.ts';
import {
  formatQuantityDisplay,
  positiveDecimalPattern,
} from './productionBomApi.ts';

type DecimalWire = string | number;

export type MaterialDocumentStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export type MaterialIssueAction = 'approve' | 'post' | 'cancel';

export interface MaterialIssueLine {
  lineNo: number;
  productId: number;
  requiredQty: string;
  quantity: string;
  unitId: number;
  issuedCost: string | null;
}

export interface MaterialIssue {
  docNo: string;
  workOrderDocNo: string;
  warehouseId: number;
  status: MaterialDocumentStatus;
  remark: string | null;
  totalIssuedCost: string;
  lines: MaterialIssueLine[];
}

export interface MaterialIssuePage {
  items: MaterialIssue[];
  total: number;
  page: number;
  size: number;
}

export interface MaterialIssueLineForm {
  productId: number | null;
  requiredQty: string;
  quantity: string;
  unitId: number | null;
}

export interface MaterialIssueForm {
  workOrderDocNo: string;
  warehouseId: number | null;
  remark: string;
  lines: MaterialIssueLineForm[];
}

export interface KittingLine {
  productId: number;
  unitId: number;
  required: string;
  available: string;
  shortage: string;
}

export interface KittingCheck {
  workOrderDocNo: string;
  warehouseId: number;
  kitted: boolean;
  lines: KittingLine[];
}

type MaterialIssueLineWire = Omit<
  MaterialIssueLine,
  'requiredQty' | 'quantity' | 'issuedCost'
> & {
  requiredQty: DecimalWire;
  quantity: DecimalWire;
  issuedCost?: DecimalWire | null;
};

type MaterialIssueWire = Omit<MaterialIssue, 'status' | 'totalIssuedCost' | 'lines' | 'remark'> & {
  status: string;
  totalIssuedCost: DecimalWire;
  remark?: string | null;
  lines?: MaterialIssueLineWire[];
};

type MaterialIssuePageWire = Omit<MaterialIssuePage, 'items'> & {
  items: MaterialIssueWire[];
};

type KittingLineWire = Omit<KittingLine, 'required' | 'available' | 'shortage'> & {
  required: DecimalWire;
  available: DecimalWire;
  shortage: DecimalWire;
};

type KittingCheckWire = Omit<KittingCheck, 'lines'> & {
  lines?: KittingLineWire[];
};

export { formatQuantityDisplay, positiveDecimalPattern };

/** 应领量允许 0：整数最多 12 位、小数最多 6 位。 */
export const nonNegativeDecimalPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

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

function normalizeLine(line: MaterialIssueLineWire): MaterialIssueLine {
  return {
    lineNo: line.lineNo,
    productId: line.productId,
    requiredQty: asString(line.requiredQty),
    quantity: asString(line.quantity),
    unitId: line.unitId,
    issuedCost: line.issuedCost == null ? null : asString(line.issuedCost),
  };
}

function normalizeIssue(item: MaterialIssueWire): MaterialIssue {
  return {
    docNo: item.docNo,
    workOrderDocNo: item.workOrderDocNo,
    warehouseId: item.warehouseId,
    status: normalizeStatus(item.status),
    remark: item.remark ?? null,
    totalIssuedCost: asString(item.totalIssuedCost),
    lines: (item.lines ?? []).map(normalizeLine),
  };
}

export function emptyMaterialIssueLine(): MaterialIssueLineForm {
  return {
    productId: null,
    requiredQty: '0',
    quantity: '1',
    unitId: null,
  };
}

export function emptyMaterialIssueForm(): MaterialIssueForm {
  return {
    workOrderDocNo: '',
    warehouseId: null,
    remark: '',
    lines: [emptyMaterialIssueLine()],
  };
}

export function linesFromKitting(check: KittingCheck): MaterialIssueLineForm[] {
  const lines = check.lines
    .filter((line) => positiveDecimalPattern.test(line.required.trim()))
    .map((line) => ({
      productId: line.productId,
      requiredQty: line.required,
      quantity: line.required,
      unitId: line.unitId,
    }));
  return lines.length > 0 ? lines : [emptyMaterialIssueLine()];
}

function createBody(form: MaterialIssueForm) {
  return {
    workOrderDocNo: form.workOrderDocNo.trim(),
    warehouseId: form.warehouseId,
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => ({
      productId: line.productId,
      requiredQty: line.requiredQty,
      quantity: line.quantity,
      unitId: line.unitId,
    })),
  };
}

export function validateMaterialIssueForm(form: MaterialIssueForm): string | null {
  if (form.workOrderDocNo.trim() === '') {
    return '请选择已开工的工单';
  }
  if (!Number.isInteger(form.warehouseId) || (form.warehouseId ?? 0) <= 0) {
    return '请选择领料仓库';
  }
  if (form.lines.length === 0) {
    return '领料单至少要有一行';
  }
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || (line.productId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择子件商品`;
    }
    if (!nonNegativeDecimalPattern.test(line.requiredQty.trim())) {
      return `第 ${index + 1} 行应领数量须为非负数，整数最多 12 位、小数最多 6 位`;
    }
    if (!positiveDecimalPattern.test(line.quantity.trim())) {
      return `第 ${index + 1} 行实领数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!Number.isInteger(line.unitId) || (line.unitId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择计量单位`;
    }
  }
  return null;
}

export function validateKittingQuery(workOrderDocNo: string, warehouseId: number | null): string | null {
  if (workOrderDocNo.trim() === '') {
    return '齐套检查请填写工单号';
  }
  if (!Number.isInteger(warehouseId) || (warehouseId ?? 0) <= 0) {
    return '齐套检查请选择仓库';
  }
  return null;
}

export function availableMaterialIssueActions(status: MaterialDocumentStatus): MaterialIssueAction[] {
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

export async function searchMaterialIssues(
  workOrderDocNo: string,
  status: string,
  page: number,
  size = 20,
): Promise<MaterialIssuePage> {
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
  const result = await request<MaterialIssuePageWire>(`/api/production/material-issues?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeIssue),
  };
}

export async function getMaterialIssue(docNo: string): Promise<MaterialIssue> {
  const result = await request<MaterialIssueWire>(
    `/api/production/material-issues/${encodeURIComponent(docNo)}`,
  );
  return normalizeIssue(result);
}

export async function createMaterialIssue(form: MaterialIssueForm): Promise<MaterialIssue> {
  const result = await request<MaterialIssueWire>('/api/production/material-issues', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeIssue(result);
}

async function postTransition(docNo: string, action: MaterialIssueAction): Promise<MaterialIssue> {
  const result = await request<MaterialIssueWire>(
    `/api/production/material-issues/${encodeURIComponent(docNo)}/${action}`,
    { method: 'POST' },
  );
  return normalizeIssue(result);
}

export function approveMaterialIssue(docNo: string): Promise<MaterialIssue> {
  return postTransition(docNo, 'approve');
}

export function postMaterialIssue(docNo: string): Promise<MaterialIssue> {
  return postTransition(docNo, 'post');
}

export function cancelMaterialIssue(docNo: string): Promise<MaterialIssue> {
  return postTransition(docNo, 'cancel');
}

function normalizeKittingLine(line: KittingLineWire): KittingLine {
  return {
    productId: line.productId,
    unitId: line.unitId,
    required: asString(line.required),
    available: asString(line.available),
    shortage: asString(line.shortage),
  };
}

export async function checkKitting(
  workOrderDocNo: string,
  warehouseId: number,
): Promise<KittingCheck> {
  const params = new URLSearchParams({
    workOrderDocNo: workOrderDocNo.trim(),
    warehouseId: String(warehouseId),
  });
  const result = await request<KittingCheckWire>(`/api/production/kitting-check?${params}`);
  return {
    workOrderDocNo: result.workOrderDocNo,
    warehouseId: result.warehouseId,
    kitted: result.kitted,
    lines: (result.lines ?? []).map(normalizeKittingLine),
  };
}
