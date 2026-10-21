import { isIsoDate } from './demandPlanApi.ts';
import { request } from './http.ts';
import {
  formatQuantityDisplay,
  positiveDecimalPattern,
  versionPattern,
} from './productionBomApi.ts';

type DecimalWire = string | number;

export type WorkOrderStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REVERSED';

export type WorkOrderSourceType = 'MANUAL' | 'MRP_SUGGESTION';

export type WorkOrderAction = 'release' | 'start' | 'complete' | 'cancel' | 'reverse';

export interface WorkOrderSummary {
  id: number;
  docNo: string;
  productId: number;
  plannedQty: string;
  unitId: number;
  sourceType: WorkOrderSourceType;
  status: WorkOrderStatus;
  createdBy: string | null;
}

export interface WorkOrder extends WorkOrderSummary {
  completedQty: string;
  bomVersion: number | null;
  routingVersion: number | null;
  warehouseId: number | null;
  mrpRunDocNo: string | null;
  plannedStartDate: string | null;
  plannedEndDate: string | null;
  remark: string | null;
}

export interface WorkOrderPage {
  items: WorkOrderSummary[];
  total: number;
  page: number;
  size: number;
}

export interface WorkOrderForm {
  productId: number | null;
  plannedQty: string;
  unitId: number | null;
  bomVersion: string;
  routingVersion: string;
  warehouseId: number | null;
  plannedStartDate: string;
  plannedEndDate: string;
  remark: string;
}

export interface WorkOrderFromMrpForm {
  mrpRunDocNo: string;
  productId: number | null;
}

type WorkOrderSummaryWire = Omit<WorkOrderSummary, 'plannedQty' | 'sourceType' | 'status'> & {
  plannedQty: DecimalWire;
  sourceType: string;
  status: string;
  createdBy?: string | null;
};

type WorkOrderWire = Omit<
  WorkOrder,
  'plannedQty' | 'completedQty' | 'sourceType' | 'status' | 'mrpRunDocNo' | 'remark'
> & {
  plannedQty: DecimalWire;
  completedQty: DecimalWire;
  sourceType: string;
  status: string;
  mrpRunDocNo?: string | null;
  remark?: string | null;
  createdBy?: string | null;
};

type WorkOrderPageWire = Omit<WorkOrderPage, 'items'> & {
  items: WorkOrderSummaryWire[];
};

export { formatQuantityDisplay, positiveDecimalPattern };

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

function normalizeStatus(value: string): WorkOrderStatus {
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

function normalizeSourceType(value: string): WorkOrderSourceType {
  return value === 'MRP_SUGGESTION' ? 'MRP_SUGGESTION' : 'MANUAL';
}

function normalizeSummary(item: WorkOrderSummaryWire): WorkOrderSummary {
  return {
    id: item.id,
    docNo: item.docNo,
    productId: item.productId,
    plannedQty: asString(item.plannedQty),
    unitId: item.unitId,
    sourceType: normalizeSourceType(item.sourceType),
    status: normalizeStatus(item.status),
    createdBy: item.createdBy ?? null,
  };
}

function normalizeWorkOrder(item: WorkOrderWire): WorkOrder {
  return {
    ...normalizeSummary(item),
    completedQty: asString(item.completedQty),
    bomVersion: item.bomVersion ?? null,
    routingVersion: item.routingVersion ?? null,
    warehouseId: item.warehouseId ?? null,
    mrpRunDocNo: item.mrpRunDocNo ?? null,
    plannedStartDate: item.plannedStartDate ?? null,
    plannedEndDate: item.plannedEndDate ?? null,
    remark: item.remark ?? null,
  };
}

export function emptyWorkOrderForm(): WorkOrderForm {
  return {
    productId: null,
    plannedQty: '1',
    unitId: null,
    bomVersion: '',
    routingVersion: '',
    warehouseId: null,
    plannedStartDate: '',
    plannedEndDate: '',
    remark: '',
  };
}

export function emptyFromMrpForm(): WorkOrderFromMrpForm {
  return {
    mrpRunDocNo: '',
    productId: null,
  };
}

function parseOptionalVersion(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed === '') {
    return null;
  }
  if (!versionPattern.test(trimmed)) {
    return null;
  }
  return Number.parseInt(trimmed, 10);
}

function createManualBody(form: WorkOrderForm) {
  return {
    productId: form.productId,
    plannedQty: form.plannedQty,
    unitId: form.unitId,
    bomVersion: parseOptionalVersion(form.bomVersion),
    routingVersion: parseOptionalVersion(form.routingVersion),
    warehouseId: form.warehouseId,
    plannedStartDate: form.plannedStartDate.trim() || null,
    plannedEndDate: form.plannedEndDate.trim() || null,
    remark: form.remark.trim() || null,
  };
}

export function validateWorkOrderForm(form: WorkOrderForm): string | null {
  if (!Number.isInteger(form.productId) || (form.productId ?? 0) <= 0) {
    return '请选择生产商品';
  }
  if (!positiveDecimalPattern.test(form.plannedQty.trim())) {
    return '计划数量必须大于 0，整数最多 12 位、小数最多 6 位';
  }
  if (!Number.isInteger(form.unitId) || (form.unitId ?? 0) <= 0) {
    return '请选择计量单位';
  }
  if (form.bomVersion.trim() !== '' && !versionPattern.test(form.bomVersion.trim())) {
    return 'BOM 版本须为正整数，或留空';
  }
  if (form.routingVersion.trim() !== '' && !versionPattern.test(form.routingVersion.trim())) {
    return '工艺路线版本须为正整数，或留空';
  }
  if (form.plannedStartDate.trim() !== '' && !isIsoDate(form.plannedStartDate)) {
    return '计划开始日期须为有效日期，或留空';
  }
  if (form.plannedEndDate.trim() !== '' && !isIsoDate(form.plannedEndDate)) {
    return '计划结束日期须为有效日期，或留空';
  }
  if (
    form.plannedStartDate.trim() !== ''
    && form.plannedEndDate.trim() !== ''
    && form.plannedStartDate.trim() > form.plannedEndDate.trim()
  ) {
    return '计划结束日期不能早于开始日期';
  }
  return null;
}

export function validateFromMrpForm(form: WorkOrderFromMrpForm): string | null {
  if (form.mrpRunDocNo.trim() === '') {
    return '请选择 MRP 运行';
  }
  if (!Number.isInteger(form.productId) || (form.productId ?? 0) <= 0) {
    return '请选择带生产建议的商品';
  }
  return null;
}

export function availableWorkOrderActions(status: WorkOrderStatus): WorkOrderAction[] {
  switch (status) {
    case 'DRAFT':
      return ['release', 'cancel'];
    case 'APPROVED':
      return ['start', 'reverse'];
    case 'EXECUTING':
      return ['complete'];
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

export async function searchWorkOrders(
  productId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<WorkOrderPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (productId !== null) {
    params.set('productId', String(productId));
  }
  if (status) {
    params.set('status', status);
  }
  const result = await request<WorkOrderPageWire>(`/api/production/work-orders?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeSummary),
  };
}

export async function getWorkOrder(docNo: string): Promise<WorkOrder> {
  const result = await request<WorkOrderWire>(
    `/api/production/work-orders/${encodeURIComponent(docNo)}`,
  );
  return normalizeWorkOrder(result);
}

export async function createWorkOrder(form: WorkOrderForm): Promise<WorkOrder> {
  const result = await request<WorkOrderWire>('/api/production/work-orders', {
    method: 'POST',
    body: createManualBody(form),
  });
  return normalizeWorkOrder(result);
}

export async function createWorkOrderFromMrp(form: WorkOrderFromMrpForm): Promise<WorkOrder> {
  const result = await request<WorkOrderWire>('/api/production/work-orders/from-mrp', {
    method: 'POST',
    body: {
      mrpRunDocNo: form.mrpRunDocNo.trim(),
      productId: form.productId,
    },
  });
  return normalizeWorkOrder(result);
}

async function postTransition(docNo: string, action: WorkOrderAction): Promise<WorkOrder> {
  const result = await request<WorkOrderWire>(
    `/api/production/work-orders/${encodeURIComponent(docNo)}/${action}`,
    { method: 'POST' },
  );
  return normalizeWorkOrder(result);
}

export function releaseWorkOrder(docNo: string): Promise<WorkOrder> {
  return postTransition(docNo, 'release');
}

export function startWorkOrder(docNo: string): Promise<WorkOrder> {
  return postTransition(docNo, 'start');
}

export function completeWorkOrder(docNo: string): Promise<WorkOrder> {
  return postTransition(docNo, 'complete');
}

export function cancelWorkOrder(docNo: string): Promise<WorkOrder> {
  return postTransition(docNo, 'cancel');
}

export function reverseWorkOrder(docNo: string): Promise<WorkOrder> {
  return postTransition(docNo, 'reverse');
}
