import { request } from './http.ts';
import { positiveDecimalPattern } from './productionBomApi.ts';

export type ArchiveStatus = 'ENABLED' | 'DISABLED';
type DecimalWire = string | number;

export interface DemandPlanLine {
  productId: number;
  quantity: string;
  unitId: number;
  dueDate: string | null;
}

export interface DemandPlan {
  id: number;
  docNo: string;
  planDate: string;
  status: ArchiveStatus;
  remark: string | null;
  lines: DemandPlanLine[];
  createdBy: string | null;
  createdAt: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface DemandPlanPage {
  items: DemandPlan[];
  total: number;
  page: number;
  size: number;
}

export interface DemandPlanLineForm {
  productId: number | null;
  quantity: string;
  unitId: number | null;
  dueDate: string;
}

export interface DemandPlanForm {
  planDate: string;
  remark: string;
  lines: DemandPlanLineForm[];
}

type DemandPlanLineWire = Omit<DemandPlanLine, 'quantity' | 'dueDate'> & {
  quantity: DecimalWire;
  dueDate?: string | null;
};

type DemandPlanWire = Omit<DemandPlan, 'lines'> & {
  lines: DemandPlanLineWire[];
};

type DemandPlanPageWire = Omit<DemandPlanPage, 'items'> & {
  items: DemandPlanWire[];
};

export { positiveDecimalPattern };

/** 计划日 / 行交期：YYYY-MM-DD。 */
export const isoDatePattern = /^(\d{4})-(\d{2})-(\d{2})$/;

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

export function isIsoDate(value: string): boolean {
  const trimmed = value.trim();
  const match = isoDatePattern.exec(trimmed);
  if (!match) {
    return false;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day;
}

function todayIso(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function normalizeLine(line: DemandPlanLineWire): DemandPlanLine {
  return {
    productId: line.productId,
    quantity: asString(line.quantity),
    unitId: line.unitId,
    dueDate: line.dueDate ?? null,
  };
}

function normalizePlan(plan: DemandPlanWire): DemandPlan {
  return {
    ...plan,
    lines: (plan.lines ?? []).map(normalizeLine),
  };
}

export function emptyDemandPlanForm(): DemandPlanForm {
  return {
    planDate: todayIso(),
    remark: '',
    lines: [],
  };
}

export function toDemandPlanForm(plan: DemandPlan): DemandPlanForm {
  return {
    planDate: plan.planDate,
    remark: plan.remark ?? '',
    lines: plan.lines.map((line) => ({
      productId: line.productId,
      quantity: line.quantity,
      unitId: line.unitId,
      dueDate: line.dueDate ?? '',
    })),
  };
}

function createBody(form: DemandPlanForm) {
  return {
    planDate: form.planDate.trim(),
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => ({
      productId: line.productId,
      quantity: line.quantity,
      unitId: line.unitId,
      dueDate: line.dueDate.trim() === '' ? null : line.dueDate.trim(),
    })),
  };
}

export function validateDemandPlanForm(form: DemandPlanForm): string | null {
  if (!isIsoDate(form.planDate)) {
    return '请填写有效的计划日期';
  }
  if (form.lines.length === 0) {
    return '需求计划至少需要一行';
  }

  const seen = new Set<string>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.productId) || (line.productId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择商品`;
    }
    if (!positiveDecimalPattern.test(line.quantity)) {
      return `第 ${index + 1} 行数量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (!Number.isInteger(line.unitId) || (line.unitId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择计量单位`;
    }
    if (line.dueDate.trim() !== '' && !isIsoDate(line.dueDate)) {
      return `第 ${index + 1} 行交期须为有效日期，或留空`;
    }
    const key = `${line.productId}:${line.unitId}`;
    if (seen.has(key)) {
      return '同一计划内商品+单位组合不能重复';
    }
    seen.add(key);
  }
  return null;
}

export async function searchDemandPlans(
  status: string,
  page: number,
  size = 20,
): Promise<DemandPlanPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (status) {
    params.set('status', status);
  }
  const result = await request<DemandPlanPageWire>(`/api/production/demand-plans?${params}`);
  return {
    ...result,
    items: result.items.map(normalizePlan),
  };
}

export async function getDemandPlan(docNo: string): Promise<DemandPlan> {
  const result = await request<DemandPlanWire>(
    `/api/production/demand-plans/${encodeURIComponent(docNo)}`,
  );
  return normalizePlan(result);
}

export async function createDemandPlan(form: DemandPlanForm): Promise<DemandPlan> {
  const result = await request<DemandPlanWire>('/api/production/demand-plans', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizePlan(result);
}

export async function updateDemandPlan(
  docNo: string,
  form: DemandPlanForm,
): Promise<DemandPlan> {
  const result = await request<DemandPlanWire>(
    `/api/production/demand-plans/${encodeURIComponent(docNo)}`,
    {
      method: 'PUT',
      body: createBody(form),
    },
  );
  return normalizePlan(result);
}
