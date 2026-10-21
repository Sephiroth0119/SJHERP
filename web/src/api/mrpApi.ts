import { request } from './http.ts';

type DecimalWire = string | number;

export type SuggestionType = 'PRODUCTION' | 'PURCHASE';

export interface MrpSuggestion {
  type: SuggestionType;
  productId: number;
  level: number;
  grossRequirement: string;
  onHand: string;
  netRequirement: string;
  baseUnitId: number;
}

export interface MrpRun {
  id: number;
  docNo: string;
  runAt: string;
  warehouseId: number;
  includeForecast: boolean;
  includeSalesOrder: boolean;
  remark: string | null;
  createdBy: string | null;
  suggestions: MrpSuggestion[];
}

export interface MrpRunSummary {
  id: number;
  docNo: string;
  runAt: string;
  warehouseId: number;
  includeForecast: boolean;
  includeSalesOrder: boolean;
  remark: string | null;
  createdBy: string | null;
}

export interface MrpRunPage {
  items: MrpRunSummary[];
  total: number;
  page: number;
  size: number;
}

export interface MrpRunForm {
  warehouseId: number | null;
  includeForecast: boolean;
  includeSalesOrder: boolean;
  remark: string;
}

type MrpSuggestionWire = Omit<
  MrpSuggestion,
  'grossRequirement' | 'onHand' | 'netRequirement' | 'type'
> & {
  type: string;
  grossRequirement: DecimalWire;
  onHand: DecimalWire;
  netRequirement: DecimalWire;
};

type MrpRunWire = Omit<MrpRun, 'suggestions'> & {
  suggestions?: MrpSuggestionWire[];
};

type MrpRunPageWire = Omit<MrpRunPage, 'items'> & {
  items: MrpRunSummary[];
};

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

function normalizeSuggestionType(value: string): SuggestionType {
  return value === 'PURCHASE' ? 'PURCHASE' : 'PRODUCTION';
}

function normalizeSuggestion(suggestion: MrpSuggestionWire): MrpSuggestion {
  return {
    type: normalizeSuggestionType(suggestion.type),
    productId: suggestion.productId,
    level: suggestion.level,
    grossRequirement: asString(suggestion.grossRequirement),
    onHand: asString(suggestion.onHand),
    netRequirement: asString(suggestion.netRequirement),
    baseUnitId: suggestion.baseUnitId,
  };
}

function normalizeRun(run: MrpRunWire): MrpRun {
  return {
    ...run,
    suggestions: (run.suggestions ?? []).map(normalizeSuggestion),
  };
}

export function emptyMrpRunForm(): MrpRunForm {
  return {
    warehouseId: null,
    includeForecast: true,
    includeSalesOrder: true,
    remark: '',
  };
}

function createBody(form: MrpRunForm) {
  return {
    warehouseId: form.warehouseId,
    includeForecast: form.includeForecast,
    includeSalesOrder: form.includeSalesOrder,
    remark: form.remark.trim() || null,
  };
}

export function validateMrpRunForm(form: MrpRunForm): string | null {
  if (!Number.isInteger(form.warehouseId) || (form.warehouseId ?? 0) <= 0) {
    return '请选择仓库';
  }
  if (!form.includeForecast && !form.includeSalesOrder) {
    return '至少选择一个需求来源（预测或销售订单）';
  }
  return null;
}

export async function searchMrpRuns(page: number, size = 20): Promise<MrpRunPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  return request<MrpRunPageWire>(`/api/production/mrp/runs?${params}`);
}

export async function getMrpRun(docNo: string): Promise<MrpRun> {
  const result = await request<MrpRunWire>(
    `/api/production/mrp/runs/${encodeURIComponent(docNo)}`,
  );
  return normalizeRun(result);
}

export async function runMrp(form: MrpRunForm): Promise<MrpRun> {
  const result = await request<MrpRunWire>('/api/production/mrp/runs', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeRun(result);
}
