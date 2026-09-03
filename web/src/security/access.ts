import { MODULE_NAV_ITEMS, type ModuleKey, type ModuleNavItem } from '../types/navigation.ts';

export function hasPermission(permissions: readonly string[], permission: string): boolean {
  return permissions.includes(permission);
}

export function filterNavItems(
  items: readonly ModuleNavItem[],
  permissions: readonly string[],
): ModuleNavItem[] {
  return items.filter(
    (item) =>
      !item.requiredPermissions ||
      item.requiredPermissions.some((permission) => hasPermission(permissions, permission)),
  );
}

export function canAccessModule(module: ModuleKey, permissions: readonly string[]): boolean {
  const item = MODULE_NAV_ITEMS.find((candidate) => candidate.key === module);
  return item !== undefined && filterNavItems([item], permissions).length === 1;
}
