import { request } from './http.ts';

export type ArchiveStatus = 'ENABLED' | 'DISABLED';
export type InventoryCategory =
  | 'RAW_MATERIAL'
  | 'SEMI_FINISHED'
  | 'FINISHED_GOOD'
  | 'MERCHANDISE';

export const ratePattern =
  /^(?!0(?:\.0{1,6})?$)(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/;

export interface Category {
  id: number;
  name: string;
  parentId: number | null;
  level: number;
}

export interface Unit {
  id: number;
  name: string;
  precision: number;
}

export interface Conversion {
  unitId: number;
  rate: string;
}

export interface Product {
  id: number;
  code: string;
  name: string;
  spec: string | null;
  categoryId: number | null;
  inventoryCategory: InventoryCategory;
  baseUnitId: number;
  barcode: string | null;
  status: ArchiveStatus;
  remark: string | null;
  unitConversions: Conversion[];
}

export interface ProductForm {
  code: string;
  name: string;
  spec: string;
  categoryId: number | null;
  inventoryCategory: InventoryCategory;
  baseUnitId: number | null;
  barcode: string;
  remark: string;
  unitConversions: Conversion[];
}

export interface CategoryForm {
  name: string;
  parentId: number | null;
}

export interface UnitForm {
  name: string;
  precision: number;
}

type ProductWire = Omit<Product, 'unitConversions'> & {
  unitConversions?: Array<{ unitId: number; rate: string | number }>;
};

type ProductPageWire = {
  items: ProductWire[];
  total: number;
  page: number;
  size: number;
};

export type ProductPage = Omit<ProductPageWire, 'items'> & {
  items: Product[];
};

export function normalizeConversion(value: {
  unitId: number;
  rate: string | number;
}): Conversion {
  return { unitId: value.unitId, rate: String(value.rate) };
}

export function normalizeProduct(value: ProductWire): Product {
  return {
    ...value,
    unitConversions: (value.unitConversions ?? []).map(normalizeConversion),
  };
}

export function validateConversions(
  baseUnitId: number | null,
  conversions: Conversion[],
): string | null {
  if (baseUnitId === null) {
    return '请选择基本单位';
  }

  const seen = new Set<number>();
  for (const conversion of conversions) {
    if (conversion.unitId <= 0) {
      return '换算单位必须选择有效单位';
    }
    if (conversion.unitId === baseUnitId) {
      return '基本单位无需登记换算率';
    }
    if (seen.has(conversion.unitId)) {
      return '同一换算单位不可重复登记';
    }
    if (!ratePattern.test(conversion.rate)) {
      return '换算率必须大于 0，整数最多 12 位、小数最多 6 位';
    }
    seen.add(conversion.unitId);
  }

  return null;
}

function productBody(form: ProductForm) {
  return {
    code: form.code,
    name: form.name,
    spec: form.spec,
    categoryId: form.categoryId,
    inventoryCategory: form.inventoryCategory,
    baseUnitId: form.baseUnitId,
    barcode: form.barcode,
    remark: form.remark,
    unitConversions: form.unitConversions.map(({ unitId, rate }) => ({
      unitId,
      rate,
    })),
  };
}

export async function searchProducts(
  keyword: string,
  status: string,
  page: number,
): Promise<ProductPage> {
  const params = new URLSearchParams({
    page: String(page),
    size: '20',
  });
  if (keyword.trim()) {
    params.set('keyword', keyword.trim());
  }
  if (status) {
    params.set('status', status);
  }

  const result = await request<ProductPageWire>(
    `/api/catalog/products?${params}`,
  );
  return {
    ...result,
    items: result.items.map(normalizeProduct),
  };
}

export async function getProduct(id: number): Promise<Product> {
  const result = await request<ProductWire>(`/api/catalog/products/${id}`);
  return normalizeProduct(result);
}

export async function createProduct(form: ProductForm): Promise<Product> {
  const result = await request<ProductWire>('/api/catalog/products', {
    method: 'POST',
    body: productBody(form),
  });
  return normalizeProduct(result);
}

export async function updateProduct(
  id: number,
  form: ProductForm,
): Promise<Product> {
  const result = await request<ProductWire>(`/api/catalog/products/${id}`, {
    method: 'PUT',
    body: productBody(form),
  });
  return normalizeProduct(result);
}

export async function setProductStatus(
  id: number,
  status: ArchiveStatus,
): Promise<Product> {
  const action = status === 'ENABLED' ? 'enable' : 'disable';
  const result = await request<ProductWire>(
    `/api/catalog/products/${id}/${action}`,
    { method: 'POST' },
  );
  return normalizeProduct(result);
}

export function listCategories(): Promise<Category[]> {
  return request<Category[]>('/api/catalog/categories');
}

export function createCategory(form: CategoryForm): Promise<Category> {
  return request<Category>('/api/catalog/categories', {
    method: 'POST',
    body: {
      name: form.name,
      parentId: form.parentId,
    },
  });
}

export function renameCategory(id: number, name: string): Promise<Category> {
  return request<Category>(`/api/catalog/categories/${id}`, {
    method: 'PUT',
    body: { name },
  });
}

export function listUnits(): Promise<Unit[]> {
  return request<Unit[]>('/api/catalog/units');
}

export function createUnit(form: UnitForm): Promise<Unit> {
  return request<Unit>('/api/catalog/units', {
    method: 'POST',
    body: {
      name: form.name,
      precision: form.precision,
    },
  });
}

export function updateUnit(id: number, form: UnitForm): Promise<Unit> {
  return request<Unit>(`/api/catalog/units/${id}`, {
    method: 'PUT',
    body: {
      name: form.name,
      precision: form.precision,
    },
  });
}
