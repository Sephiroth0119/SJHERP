import { validatePeriodKey } from './glPeriodApi.ts';
import { request } from './http.ts';
import { nonNegativeDecimalPattern } from './materialIssueApi.ts';
import {
  formatQuantityDisplay,
  positiveDecimalPattern,
} from './productionBomApi.ts';

type DecimalWire = string | number;

export type CostSettlementStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export type CostSettlementAction = 'approve' | 'post' | 'cancel';

export interface CostSettlementLine {
  lineNo: number;
  workOrderDocNo: string;
  materialCost: string;
  rawMaterialCost: string;
  goodsMaterialCost: string;
  laborCost: string;
  overheadCost: string;
  completedQty: string;
  completedCost: string;
  wipQty: string;
  wipCompletionPct: string;
  wipCost: string;
  alreadyTransferred: string;
  costAdjustIdemKey: string | null;
  voucherDocNo: string | null;
}

export interface CostSettlement {
  docNo: string;
  period: string;
  status: CostSettlementStatus;
  remark: string | null;
  lines: CostSettlementLine[];
}

export interface CostSettlementPage {
  items: CostSettlement[];
  total: number;
  page: number;
  size: number;
}

export interface CostSettlementLineForm {
  workOrderDocNo: string;
  wipQty: string;
  wipCompletionPct: string;
}

export interface CostSettlementForm {
  period: string;
  remark: string;
  lines: CostSettlementLineForm[];
}

type CostSettlementLineWire = Omit<
  CostSettlementLine,
  | 'materialCost'
  | 'rawMaterialCost'
  | 'goodsMaterialCost'
  | 'laborCost'
  | 'overheadCost'
  | 'completedQty'
  | 'completedCost'
  | 'wipQty'
  | 'wipCompletionPct'
  | 'wipCost'
  | 'alreadyTransferred'
  | 'costAdjustIdemKey'
  | 'voucherDocNo'
> & {
  materialCost: DecimalWire;
  rawMaterialCost: DecimalWire;
  goodsMaterialCost: DecimalWire;
  laborCost: DecimalWire;
  overheadCost: DecimalWire;
  completedQty: DecimalWire;
  completedCost: DecimalWire;
  wipQty: DecimalWire;
  wipCompletionPct: DecimalWire;
  wipCost: DecimalWire;
  alreadyTransferred: DecimalWire;
  costAdjustIdemKey?: string | null;
  voucherDocNo?: string | null;
};

type CostSettlementWire = Omit<CostSettlement, 'status' | 'remark' | 'lines'> & {
  status: string;
  remark?: string | null;
  lines?: CostSettlementLineWire[];
};

type CostSettlementPageWire = Omit<CostSettlementPage, 'items'> & {
  items: CostSettlementWire[];
};

export { formatQuantityDisplay, positiveDecimalPattern, nonNegativeDecimalPattern };

/** 完工程度：0–100，最多 2 位小数。 */
export const wipCompletionPctPattern =
  /^(?:100(?:\.0{1,2})?|(?:0|[1-9]\d?)(?:\.\d{1,2})?)$/;

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

function normalizeStatus(value: string): CostSettlementStatus {
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

function normalizeLine(line: CostSettlementLineWire): CostSettlementLine {
  return {
    lineNo: line.lineNo,
    workOrderDocNo: line.workOrderDocNo,
    materialCost: asString(line.materialCost),
    rawMaterialCost: asString(line.rawMaterialCost),
    goodsMaterialCost: asString(line.goodsMaterialCost),
    laborCost: asString(line.laborCost),
    overheadCost: asString(line.overheadCost),
    completedQty: asString(line.completedQty),
    completedCost: asString(line.completedCost),
    wipQty: asString(line.wipQty),
    wipCompletionPct: asString(line.wipCompletionPct),
    wipCost: asString(line.wipCost),
    alreadyTransferred: asString(line.alreadyTransferred),
    costAdjustIdemKey: line.costAdjustIdemKey ?? null,
    voucherDocNo: line.voucherDocNo ?? null,
  };
}

function normalizeSettlement(item: CostSettlementWire): CostSettlement {
  return {
    docNo: item.docNo,
    period: item.period,
    status: normalizeStatus(item.status),
    remark: item.remark ?? null,
    lines: (item.lines ?? []).map(normalizeLine),
  };
}

export function emptyCostSettlementLine(): CostSettlementLineForm {
  return {
    workOrderDocNo: '',
    wipQty: '0',
    wipCompletionPct: '0',
  };
}

export function emptyCostSettlementForm(): CostSettlementForm {
  return {
    period: '',
    remark: '',
    lines: [emptyCostSettlementLine()],
  };
}

function createBody(form: CostSettlementForm) {
  return {
    period: form.period.trim(),
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => ({
      workOrderDocNo: line.workOrderDocNo.trim(),
      wipQty: line.wipQty.trim() === '' ? '0' : line.wipQty.trim(),
      wipCompletionPct: line.wipCompletionPct.trim() === '' ? '0' : line.wipCompletionPct.trim(),
    })),
  };
}

export function validateCostSettlementForm(form: CostSettlementForm): string | null {
  const periodError = validatePeriodKey(form.period);
  if (periodError) {
    return periodError;
  }
  if (form.lines.length === 0) {
    return '成本结转单至少要有一行（本期无待结转工单）';
  }
  const workOrders = new Set<string>();
  for (const [index, line] of form.lines.entries()) {
    const workOrderDocNo = line.workOrderDocNo.trim();
    if (workOrderDocNo === '') {
      return `第 ${index + 1} 行请填写工单号`;
    }
    if (workOrders.has(workOrderDocNo)) {
      return `工单 ${workOrderDocNo} 不能出现多行`;
    }
    workOrders.add(workOrderDocNo);
    const wipQty = line.wipQty.trim() === '' ? '0' : line.wipQty.trim();
    if (!nonNegativeDecimalPattern.test(wipQty)) {
      return `第 ${index + 1} 行在产数量须为非负数，整数最多 12 位、小数最多 6 位`;
    }
    const wipPct = line.wipCompletionPct.trim() === '' ? '0' : line.wipCompletionPct.trim();
    if (!wipCompletionPctPattern.test(wipPct)) {
      return `第 ${index + 1} 行完工程度必须在 0–100 之间，最多 2 位小数`;
    }
  }
  return null;
}

export function availableCostSettlementActions(status: CostSettlementStatus): CostSettlementAction[] {
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

export async function searchCostSettlements(
  period: string,
  status: string,
  page: number,
  size = 20,
): Promise<CostSettlementPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (period.trim()) {
    params.set('period', period.trim());
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<CostSettlementPageWire>(`/api/production/cost-settlements?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeSettlement),
  };
}

export async function getCostSettlement(docNo: string): Promise<CostSettlement> {
  const result = await request<CostSettlementWire>(
    `/api/production/cost-settlements/${encodeURIComponent(docNo)}`,
  );
  return normalizeSettlement(result);
}

export async function createCostSettlement(form: CostSettlementForm): Promise<CostSettlement> {
  const result = await request<CostSettlementWire>('/api/production/cost-settlements', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeSettlement(result);
}

async function postTransition(
  docNo: string,
  action: CostSettlementAction,
): Promise<CostSettlement> {
  const result = await request<CostSettlementWire>(
    `/api/production/cost-settlements/${encodeURIComponent(docNo)}/${action}`,
    { method: 'POST' },
  );
  return normalizeSettlement(result);
}

export function approveCostSettlement(docNo: string): Promise<CostSettlement> {
  return postTransition(docNo, 'approve');
}

export function postCostSettlement(docNo: string): Promise<CostSettlement> {
  return postTransition(docNo, 'post');
}

export function cancelCostSettlement(docNo: string): Promise<CostSettlement> {
  return postTransition(docNo, 'cancel');
}
