/** 首次登录向导：状态存在当前浏览器，按用户名隔离，不写业务库。 */

export const ONBOARDING_STORAGE_PREFIX = 'sjherp.onboarding.status.';

export type OnboardingStatus = 'pending' | 'dismissed' | 'completed';

export type OnboardingStepId =
  | 'welcome'
  | 'archives'
  | 'opening'
  | 'first-order'
  | 'done';

export const ARCHIVE_PERMISSIONS = [
  'catalog:write',
  'catalog:create_product',
  'warehouse:create_warehouse',
  'partner:create_supplier',
  'partner:create_customer',
] as const;

export const OPENING_PERMISSIONS = ['inventory:adjust'] as const;
export const FIRST_ORDER_PERMISSIONS = ['purchase:order'] as const;

const WRITE_STEPS = ['archives', 'opening', 'first-order'] as const;

export interface OnboardingContext {
  unitId: number | null;
  productId: number | null;
  warehouseId: number | null;
  supplierId: number | null;
  customerId: number | null;
}

export function emptyOnboardingContext(): OnboardingContext {
  return {
    unitId: null,
    productId: null,
    warehouseId: null,
    supplierId: null,
    customerId: null,
  };
}

export function onboardingStorageKey(username: string): string {
  return `${ONBOARDING_STORAGE_PREFIX}${username.trim()}`;
}

export function readOnboardingStatus(username: string): OnboardingStatus {
  if (typeof localStorage === 'undefined') return 'pending';
  const raw = localStorage.getItem(onboardingStorageKey(username));
  switch (raw) {
    case 'dismissed':
    case 'completed':
      return raw;
    default:
      return 'pending';
  }
}

export function writeOnboardingStatus(
  username: string,
  status: Exclude<OnboardingStatus, 'pending'>,
): void {
  localStorage.setItem(onboardingStorageKey(username), status);
}

export function shouldAutoOpenOnboarding(username: string): boolean {
  return username.trim().length > 0 && readOnboardingStatus(username) === 'pending';
}

export function hasAnyPermission(
  permissions: readonly string[],
  required: readonly string[],
): boolean {
  return required.some((permission) => permissions.includes(permission));
}

export function canRunOnboardingStep(
  step: OnboardingStepId,
  permissions: readonly string[],
): boolean {
  switch (step) {
    case 'welcome':
    case 'done':
      return true;
    case 'archives':
      return hasAnyPermission(permissions, ARCHIVE_PERMISSIONS);
    case 'opening':
      return hasAnyPermission(permissions, OPENING_PERMISSIONS);
    case 'first-order':
      return hasAnyPermission(permissions, FIRST_ORDER_PERMISSIONS);
    default: {
      const exhaustive: never = step;
      return exhaustive;
    }
  }
}

/** 欢迎与完成始终展示；写步骤按当前登录权限裁剪，无权不露出提交按钮。 */
export function visibleOnboardingSteps(
  permissions: readonly string[],
): OnboardingStepId[] {
  const steps: OnboardingStepId[] = ['welcome'];
  for (const step of WRITE_STEPS) {
    if (canRunOnboardingStep(step, permissions)) steps.push(step);
  }
  steps.push('done');
  return steps;
}

export function parseChoiceId(choice: string): number | 'new' | null {
  if (choice === 'new') return 'new';
  if (!/^[1-9]\d*$/.test(choice)) return null;
  return Number(choice);
}
