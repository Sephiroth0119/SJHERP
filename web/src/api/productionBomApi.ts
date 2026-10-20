import { request } from './http.ts';

export type ArchiveStatus = 'ENABLED' | 'DISABLED';
type DecimalWire = string | number;

export interface BomLine {
  childProductId: number;
  quantity: string;
  scrapRate: string;
  unitId: number;
}

export interface Bom {
  id: number;
  productId: number;
  version: number;
  status: ArchiveStatus;
  remark: string | null;
  lines: BomLine[];
  createdBy: string | null;
  createdAt: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface BomPage {
  items: Bom[];
  total: number;
  page: number;
  size: number;
}

export interface BomExplosionNode {
  productId: number;
  quantity: string;
  unitId: number;
  level: number;
  children: BomExplosionNode[];
}

export interface BomExplosion {
  rootProductId: number;
  rootQuantity: string;
  nodes: BomExplosionNode[];
}

export interface BomLineForm {
  childProductId: number | null;
  quantity: string;
  scrapRate: string;
  unitId: number | null;
}

export interface BomForm {
  productId: number | null;
  version: string;
  remark: string;
  lines: BomLineForm[];
}

type BomLineWire = Omit<BomLine, 'quantity' | 'scrapRate'> & {
  quantity: DecimalWire;
  scrapRate: DecimalWire;
};

type BomWire = Omit<Bom, 'lines'> & {
  lines: BomLineWire[];
};

type BomPageWire = Omit<BomPage, 'items'> & {
  items: BomWire[];
};

type BomExplosionNodeWire = Omit<BomExplosionNode, 'quantity' | 'children'> & {
  quantity: DecimalWire;
  children?: BomExplosionNodeWire[];
};

type BomExplosionWire = Omit<BomExplosion, 'rootQuantity' | 'nodes'> & {
  rootQuantity: DecimalWire;
  nodes: BomExplosionNodeWire[];
};

/** 用量 / 展开数量：> 0，整数最多 12 位、小数最多 6 位。 */
export const positiveDecimalPattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

/** 损耗率：范围 [0, 1)，空表示默认 0。 */
export const scrapRatePattern = /^(?:0(?:\.\d{1,6})?|0?\.\d{1,6})$/;

export const versionPattern = /^[1-9]\d{0,8}$/;

const asString = (value: DecimalWire | null | undefined): string =>
  value == null ? '' : String(value);

function normalizeLine(line: BomLineWire): BomLine {
  return {
    ...line,
    quantity: asString(line.quantity),
    scrapRate: asString(line.scrapRate),
  };
}

function normalizeBom(bom: BomWire): Bom {
  return {
    ...bom,
    lines: (bom.lines ?? []).map(normalizeLine),
  };
}

function normalizeNode(node: BomExplosionNodeWire): BomExplosionNode {
  return {
    productId: node.productId,
    quantity: asString(node.quantity),
    unitId: node.unitId,
    level: node.level,
    children: (node.children ?? []).map(normalizeNode),
  };
}

export function emptyBomForm(): BomForm {
  return {
    productId: null,
    version: '1',
    remark: '',
    lines: [],
  };
}

export function toBomForm(bom: Bom): BomForm {
  return {
    productId: bom.productId,
    version: String(bom.version),
    remark: bom.remark ?? '',
    lines: bom.lines.map((line) => ({
      childProductId: line.childProductId,
      quantity: line.quantity,
      scrapRate: line.scrapRate,
      unitId: line.unitId,
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

function createBody(form: BomForm) {
  return {
    productId: form.productId,
    version: parsePositiveInt(form.version),
    remark: form.remark.trim() || null,
    lines: form.lines.map((line) => ({
      childProductId: line.childProductId,
      quantity: line.quantity,
      scrapRate: line.scrapRate.trim() === '' ? null : line.scrapRate,
      unitId: line.unitId,
    })),
  };
}

export function validateBomForm(form: BomForm): string | null {
  if (!Number.isInteger(form.productId) || (form.productId ?? 0) <= 0) {
    return '请选择父件商品';
  }
  if (parsePositiveInt(form.version) === null) {
    return '版本号必须是大于 0 的整数';
  }
  if (form.lines.length === 0) {
    return 'BOM 至少需要一行子件';
  }

  const childIds = new Set<number>();
  for (const [index, line] of form.lines.entries()) {
    if (!Number.isInteger(line.childProductId) || (line.childProductId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择子件商品`;
    }
    if (line.childProductId === form.productId) {
      return 'BOM 行不能引用父件自身';
    }
    if (childIds.has(line.childProductId as number)) {
      return '同一 BOM 内子件不能重复';
    }
    childIds.add(line.childProductId as number);
    if (!positiveDecimalPattern.test(line.quantity)) {
      return `第 ${index + 1} 行用量必须大于 0，整数最多 12 位、小数最多 6 位`;
    }
    if (line.scrapRate.trim() !== '' && !scrapRatePattern.test(line.scrapRate.trim())) {
      return `第 ${index + 1} 行损耗率须为 [0, 1) 的小数，最多 6 位（空则默认为 0）`;
    }
    if (!Number.isInteger(line.unitId) || (line.unitId ?? 0) <= 0) {
      return `第 ${index + 1} 行请选择计量单位`;
    }
  }
  return null;
}

export function validateExplodeQuantity(quantity: string): string | null {
  if (!positiveDecimalPattern.test(quantity.trim())) {
    return '展开数量必须大于 0，整数最多 12 位、小数最多 6 位';
  }
  return null;
}

export async function searchBoms(
  productId: number | null,
  status: string,
  page: number,
  size = 20,
): Promise<BomPage> {
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
  const result = await request<BomPageWire>(`/api/production/boms?${params}`);
  return {
    ...result,
    items: result.items.map(normalizeBom),
  };
}

export async function getBom(id: number): Promise<Bom> {
  const result = await request<BomWire>(`/api/production/boms/${id}`);
  return normalizeBom(result);
}

export async function createBom(form: BomForm): Promise<Bom> {
  const result = await request<BomWire>('/api/production/boms', {
    method: 'POST',
    body: createBody(form),
  });
  return normalizeBom(result);
}

export async function updateBom(id: number, form: BomForm): Promise<Bom> {
  const result = await request<BomWire>(`/api/production/boms/${id}`, {
    method: 'PUT',
    body: createBody(form),
  });
  return normalizeBom(result);
}

export async function enableBom(id: number): Promise<Bom> {
  const result = await request<BomWire>(`/api/production/boms/${id}/enable`, {
    method: 'POST',
  });
  return normalizeBom(result);
}

export async function disableBom(id: number): Promise<Bom> {
  const result = await request<BomWire>(`/api/production/boms/${id}/disable`, {
    method: 'POST',
  });
  return normalizeBom(result);
}

export async function explodeBom(
  productId: number,
  quantity: string,
): Promise<BomExplosion> {
  const params = new URLSearchParams({ quantity });
  const result = await request<BomExplosionWire>(
    `/api/production/boms/${productId}/explode?${params}`,
  );
  return {
    rootProductId: result.rootProductId,
    rootQuantity: asString(result.rootQuantity),
    nodes: (result.nodes ?? []).map(normalizeNode),
  };
}
