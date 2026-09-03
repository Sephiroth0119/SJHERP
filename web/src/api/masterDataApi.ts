import { request } from "./http.ts";

export type ArchiveStatus = "ENABLED" | "DISABLED";
export interface Supplier {
  id: number;
  code: string;
  name: string;
  contactPerson: string | null;
  contactPhone: string | null;
  address: string | null;
  taxNo: string | null;
  settlementMethod: "MONTHLY" | "CASH" | "PREPAID";
  status: ArchiveStatus;
  createdAt: string;
  updatedAt: string;
}
export interface Warehouse {
  id: number;
  code: string;
  name: string;
  address: string | null;
  manager: string | null;
  locationEnabled: boolean;
  status: ArchiveStatus;
  createdAt: string;
  updatedAt: string;
}
export interface SupplierForm {
  code: string;
  name: string;
  contactPerson: string;
  contactPhone: string;
  address: string;
  taxNo: string;
  settlementMethod: Supplier["settlementMethod"];
}
export interface WarehouseForm {
  code: string;
  name: string;
  address: string;
  manager: string;
  locationEnabled: boolean;
}
type Page<T> = { items: T[]; total: number; page: number; size: number };

const query = <T>(
  path: string,
  keyword: string,
  status: string,
  page: number,
) => {
  const params = new URLSearchParams({ page: String(page), size: "20" });
  if (keyword.trim()) params.set("keyword", keyword.trim());
  if (status) params.set("status", status);
  return request<Page<T>>(`${path}?${params}`);
};
const supplierBody = (f: SupplierForm) => ({
  code: f.code,
  name: f.name,
  contactPerson: f.contactPerson,
  contactPhone: f.contactPhone,
  address: f.address,
  taxNo: f.taxNo,
  settlementMethod: f.settlementMethod,
});
const warehouseBody = (f: WarehouseForm) => ({
  code: f.code,
  name: f.name,
  address: f.address,
  manager: f.manager,
  locationEnabled: f.locationEnabled,
});

export const searchSuppliers = (k: string, s: string, p: number) =>
  query<Supplier>("/api/partner/suppliers", k, s, p);
export const getSupplier = (id: number) =>
  request<Supplier>(`/api/partner/suppliers/${id}`);
export const createSupplier = (body: SupplierForm) =>
  request<Supplier>("/api/partner/suppliers", {
    method: "POST",
    body: supplierBody(body),
  });
export const updateSupplier = (id: number, body: SupplierForm) =>
  request<Supplier>(`/api/partner/suppliers/${id}`, {
    method: "PUT",
    body: supplierBody(body),
  });
export const setSupplierStatus = (id: number, status: ArchiveStatus) =>
  request<Supplier>(
    `/api/partner/suppliers/${id}/${status === "ENABLED" ? "enable" : "disable"}`,
    { method: "POST" },
  );
export const searchWarehouses = (k: string, s: string, p: number) =>
  query<Warehouse>("/api/warehouse/warehouses", k, s, p);
export const getWarehouse = (id: number) =>
  request<Warehouse>(`/api/warehouse/warehouses/${id}`);
export const createWarehouse = (body: WarehouseForm) =>
  request<Warehouse>("/api/warehouse/warehouses", {
    method: "POST",
    body: warehouseBody(body),
  });
export const updateWarehouse = (id: number, body: WarehouseForm) =>
  request<Warehouse>(`/api/warehouse/warehouses/${id}`, {
    method: "PUT",
    body: warehouseBody(body),
  });
export const setWarehouseStatus = (id: number, status: ArchiveStatus) =>
  request<Warehouse>(
    `/api/warehouse/warehouses/${id}/${status === "ENABLED" ? "enable" : "disable"}`,
    { method: "POST" },
  );
