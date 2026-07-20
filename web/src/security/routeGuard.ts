import type { ModuleKey } from '../types/navigation.ts';
import { canAccessModule } from './access.ts';

/** Client-side navigation guard; backend authorization remains authoritative. */
export function guardModule(module: ModuleKey, roles: readonly string[]): ModuleKey {
  return canAccessModule(module, roles) ? module : 'agent';
}
