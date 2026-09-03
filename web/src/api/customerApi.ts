import { request } from './http.ts';

export interface Customer {
  id: number;
  code: string;
  name: string;
  contactPerson: string | null;
  contactPhone: string | null;
  address: string | null;
  taxNo: string | null;
  settlementMethod: 'MONTHLY' | 'CASH' | 'PREPAID';
  creditLimit: string;
  currency: string;
  status: 'ENABLED' | 'DISABLED';
  createdAt: string;
  updatedAt: string;
}

type CustomerWire = Omit<Customer, 'creditLimit'> & {
  creditLimit: string | number | null;
};

export interface CustomerPage {
  items: Customer[];
  total: number;
  page: number;
  size: number;
}

export interface CustomerForm {
  code: string;
  name: string;
  contactPerson: string;
  contactPhone: string;
  address: string;
  taxNo: string;
  settlementMethod: Customer['settlementMethod'];
  creditLimit: string;
}

type CustomerRequest = Omit<CustomerForm, 'creditLimit'> & {
  creditLimit: string | null;
};

function normalize(wire: CustomerWire): Customer {
  return {
    ...wire,
    creditLimit: wire.creditLimit == null ? '' : String(wire.creditLimit),
  };
}

function toRequest(form: CustomerForm): CustomerRequest {
  const { creditLimit, ...rest } = form;
  return {
    ...rest,
    creditLimit: creditLimit.trim() === '' ? null : creditLimit,
  };
}

function normalizePage(page: {
  items: CustomerWire[];
  total: number;
  page: number;
  size: number;
}): CustomerPage {
  return { ...page, items: page.items.map(normalize) };
}

export function searchCustomers(keyword: string, status: string, page: number, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (keyword.trim()) params.set('keyword', keyword.trim());
  if (status) params.set('status', status);
  return request<{ items: CustomerWire[]; total: number; page: number; size: number }>(
    `/api/partner/customers?${params}`,
  ).then(normalizePage);
}

export function getCustomer(id: number) {
  return request<CustomerWire>(`/api/partner/customers/${id}`).then(normalize);
}

export function createCustomer(form: CustomerForm) {
  return request<CustomerWire>('/api/partner/customers', {
    method: 'POST',
    body: toRequest(form),
  }).then(normalize);
}

export function updateCustomer(id: number, form: CustomerForm) {
  return request<CustomerWire>(`/api/partner/customers/${id}`, {
    method: 'PUT',
    body: toRequest(form),
  }).then(normalize);
}

export function setCustomerStatus(id: number, status: Customer['status']) {
  const action = status === 'ENABLED' ? 'enable' : 'disable';
  return request<CustomerWire>(`/api/partner/customers/${id}/${action}`, {
    method: 'POST',
  }).then(normalize);
}
