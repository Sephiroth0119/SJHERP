/** Permission codes shared by the frontend access map and backend REST semantics. */
export const PERMISSION_CODES = [
  'catalog:create_product', 'catalog:write',
  'partner:create_customer', 'partner:create_supplier', 'partner:write',
  'warehouse:create_warehouse', 'warehouse:write',
  'inventory:adjust', 'inventory:count', 'inventory:transfer',
  'purchase:order', 'purchase:receipt', 'purchase:invoice',
  'sales:order', 'sales:delivery', 'sales:invoice',
  'data:import',
  'finance:account', 'finance:period', 'finance:period_reopen',
  'finance:voucher', 'finance:settlement', 'finance:payment_account', 'finance:report',
  'production:bom', 'production:routing', 'production:plan', 'production:mrp',
  'production:wo', 'production:material', 'production:report', 'production:cost',
  'memory:manage', 'gap:triage', 'gap:issue', 'developer:agent',
] as const;

export type PermissionCode = (typeof PERMISSION_CODES)[number];
