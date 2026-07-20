import { MODULE_NAV_ITEMS, type ModuleKey } from '../types/navigation.ts';
import { canAccessModule } from './access.ts';

/** Client-side navigation guard; backend authorization remains authoritative. */
export function guardModule(module: ModuleKey, permissions: readonly string[]): ModuleKey {
  return canAccessModule(module, permissions) ? module : 'agent';
}

export function moduleFromHash(hash: string): ModuleKey {
  const route = hash.replace(/^#\/?/, '').split(/[/?#]/, 1)[0];
  return MODULE_NAV_ITEMS.some((item) => item.key === route)
    ? route as ModuleKey
    : 'agent';
}

export function routeForModule(module: ModuleKey): string {
  return `#/${module}`;
}
