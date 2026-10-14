import { request } from './http.ts';
import {
  positiveDecimalPattern,
  versionPattern,
} from './productionBomApi.ts';

export type ArchiveStatus = 'ENABLED' | 'DISABLED';
type DecimalWire = string | number;

export interface RoutingOperation {
  sequenceNo: number;
  operationName: string;
  standardHours: string;
  workCenter: string | null;
  costRate: string | null;
}

export interface Routing {
  id: number;
  productId: number;
  version: number;
  status: ArchiveStatus;
  remark: string | null;
  operations: RoutingOperation[];
  createdBy: string | null;
  createdAt: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface RoutingPage {
  items: Routing[];
  total: number;
  page: number;
  size: number;
}

export interface RoutingOperationForm {
  sequenceNo: string;
  operationName: string;
  standardHours: string;
  workCenter: string;
  costRate: string;
}

export interface RoutingForm {
  productId: number | null;
  version: string;
  remark: string;
  operations: RoutingOperationForm[];
}

type RoutingOperationWire = Omit<RoutingOperation, 'standardHours' | 'costRate'> & {
  standardHours: DecimalWire;
  costRate: DecimalWire | null;
};

type RoutingWire = Omit<Routing, 'operations'> & {
  operations: RoutingOperationWire[];
};

type RoutingPageWire = Omit<RoutingPage, 'items'> & {
  items: RoutingWire[];
};

export { positiveDecimalPattern, versionPattern };

/** 费率预留字段：可空，非空时须为非负数，小数最多 6 位。 */
export const nonNegativeDecimalPattern =
  /^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

function normalizeOperation(operation: RoutingOperationWire): RoutingOperation {
  return {
    ...operation,
    standardHours: asString(operation.standardHours),
    costRate: operation.costRate == null ? null : asString(operation.costRate),
    workCenter: operation.workCenter ?? null,
  };
}

function normalizeRouting(routing: RoutingWire): Routing {
  return {
    ...routing,
    operations: (routing.operations ?? []).map(normalizeOperation),
  };
}

export function emptyRoutingForm(): RoutingForm {
  return {
    productId: null,
    version: '1',
    remark: '',
    operations: [
      {
        sequenceNo: '10',
        operationName: '',
        standardHours: '',
        workCenter: '',
        costRate: '',
      },
    ],
  };
}

export function toRoutingForm(routing: Routing): RoutingForm {
  return {
    productId: routing.productId,
    version: String(routing.version),
    remark: routing.remark ?? '',
    operations: routing.operations.map((operation) => ({
      sequenceNo: String(operation.sequenceNo),
      operationName: operation.operationName,
      standardHours: operation.standardHours,
      workCenter: operation.workCenter ?? '',
      costRate: operation.costRate ?? '',
    })),
  };
}

function parsePositiveInt(value: string): number | null {
  const trimmed = value.trim();
  if (!versionPattern.test(trimmed)) {
    return null;
  }
  return Number.parseInt(trimmed, 10);
}

function createBody(form: RoutingForm) {
  return {
    productId: form.productId,
    version: parsePositiveInt(form.version),
    remark: form.remark.trim() || null,
    operations: form.operations.map((operation) => ({
      sequenceNo: parsePositiveInt(operation.sequenceNo),
      operationName: operation.operationName.trim(),
      standardHours: operation.standardHours,
      workCenter: operation.workCenter.trim() || null,
      costRate: operation.costRate.trim() === '' ? null : operation.costRate,
    })),
  };
}

export function validateRoutingForm(form: RoutingForm): string | null {
  if (!Number.isInteger(form.productId) || (form.productId ?? 0) <= 0) {
    return '请选择产品';
  }
  if (parsePositiveInt(form.version) === null) {
    return '版本号必须是大于 0 的整数';
  }
  if (form.operations.length === 0) {
    return '工艺路线至少需要一道工序';
  }

  const sequences = new Set<number>();
  for (const [index, operation] of form.operations.entries()) {
    const sequenceNo = parsePositiveInt(operation.sequenceNo);
    if (sequenceNo === null) {
      return `第 ${index + 1} 道工序序号必须是大于 0 的整数`;
    }
    if (sequences.has(sequenceNo)) {
      return `工序序号重复: sequenceNo=${sequenceNo}`;
    }
    sequences.add(sequenceNo);
    if (!operation.operationName.trim()) {
      return `第 ${index + 1} 道工序名称不能为空`;
    }
    if (operation.operationName.trim().length > 200) {
      return `第 ${index + 1} 道工序名称不能超过 200 个字符`;
    }
    if (!positiveDecimalPattern.test(operation.standardHours)) {
      return `第 ${index + 1} 道工序标准工时必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (operation.workCenter.trim().length > 100) {
      return `第 ${index + 1} 道工序工作中心不能超过 100 个字符`;
    }
    if (
      operation.costRate.trim() !== '' &&
      !nonNegativeDecimalPattern.test(operation.costRate.trim())
    ) {
      return `第 ${index + 1} 道工序费率须为非负数，整数最多 12 位、小数最多 6 位（可空）`;
    }
  }
  return null;
}

export async function searchRoutings(
  productId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<RoutingPage> {
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
  const result = await request<RoutingPageWire>(`/api/production/routings?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeRouting),
  };
}

export async function getRouting(id: number): Promise<Routing> {
  const result = await request<RoutingWire>(`/api/production/routings/${id}`);
  return normalizeRouting(result);
}

export async function createRouting(form: RoutingForm): Promise<Routing> {
  const result = await request<RoutingWire>('/api/production/routings', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeRouting(result);
}

export async function updateRouting(id: number, form: RoutingForm): Promise<Routing> {
  const result = await request<RoutingWire>(`/api/production/routings/${id}`, {
    method: 'PUT',
    body: createBody(form),
  });
  return normalizeRouting(result);
}

export async function enableRouting(id: number): Promise<Routing> {
  const result = await request<RoutingWire>(`/api/production/routings/${id}/enable`, {
    method: 'POST',
  });
  return normalizeRouting(result);
}

export async function disableRouting(id: number): Promise<Routing> {
  const result = await request<RoutingWire>(`/api/production/routings/${id}/disable`, {
    method: 'POST',
  });
  return normalizeRouting(result);
}
