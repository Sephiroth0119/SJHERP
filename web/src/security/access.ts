import { MODULE_NAV_ITEMS, type ModuleKey, type ModuleNavItem } from '../types/navigation.ts';
import { PERMISSION_CODES, type PermissionCode } from './permissions.ts';

const ROLE_PERMISSIONS: Record<string, readonly PermissionCode[]> = {
  BOSS: PERMISSION_CODES.filter((permission) => permission !== 'developer:agent'),
  PURCHASER: ['partner:create_supplier', 'purchase:order', 'purchase:receipt', 'purchase:invoice'],
  SALES: ['partner:create_customer', 'sales:order', 'sales:delivery', 'sales:invoice'],
  WAREHOUSE: [
    'warehouse:create_warehouse', 'warehouse:write', 'inventory:adjust', 'inventory:count',
    'inventory:transfer', 'purchase:receipt', 'sales:delivery',
  ],
  ACCOUNTANT: [
    'purchase:invoice', 'sales:invoice', 'finance:account', 'finance:period', 'finance:voucher',
    'finance:settlement', 'finance:payment_account', 'finance:report', 'production:cost',
  ],
};

export function getPermissionsForRoles(roles: readonly string[]): ReadonlySet<PermissionCode> {
  const permissions = new Set<PermissionCode>();
  for (const role of roles) {
    if (role === 'ADMIN') {
      for (const permission of PERMISSION_CODES) permissions.add(permission);
      continue;
    }
    for (const permission of ROLE_PERMISSIONS[role] ?? []) permissions.add(permission);
  }
  return permissions;
}

export function hasPermission(roles: readonly string[], permission: PermissionCode): boolean {
  return getPermissionsForRoles(roles).has(permission);
}

export function filterNavItems(
  items: readonly ModuleNavItem[],
  roles: readonly string[],
): ModuleNavItem[] {
  const permissions = getPermissionsForRoles(roles);
  return items.filter(
    (item) =>
      !item.requiredPermissions ||
      item.requiredPermissions.some((permission) => permissions.has(permission)),
  );
}

export function canAccessModule(module: ModuleKey, roles: readonly string[]): boolean {
  const item = MODULE_NAV_ITEMS.find((candidate) => candidate.key === module);
  return item !== undefined && filterNavItems([item], roles).length === 1;
}
