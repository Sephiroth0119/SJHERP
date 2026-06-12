/**
 * 认证 API 封装（M2-T05）：
 *
 * - POST /api/auth/login → 200 { token, displayName, roles }；失败 401 { error }
 * - GET  /api/auth/me    → 200 { userId, username, displayName, roles }
 */
import { request, setStoredUser, setToken, type AuthUser } from './http';

/** POST /api/auth/login 的响应体 */
export interface LoginResult {
  token: string;
  displayName: string;
  roles: string[];
}

/** GET /api/auth/me 的响应体 */
export interface MeResult {
  userId: number;
  username: string;
  displayName: string;
  roles: string[];
}

/** 角色枚举 → 中文名（与后端 Role 枚举一致） */
export const ROLE_LABELS: Record<string, string> = {
  ADMIN: '管理员',
  BOSS: '老板',
  ACCOUNTANT: '会计',
  WAREHOUSE: '仓管',
  PURCHASER: '采购',
  SALES: '销售',
};

/** 角色集合的中文展示（如 "管理员、销售"） */
export function formatRoles(roles: string[]): string {
  return roles.map((role) => ROLE_LABELS[role] ?? role).join('、');
}

/**
 * 登录：成功后把 token 与用户信息写入 localStorage 并返回 AuthUser。
 * 密码错误等 401 由调用方（登录页）就地展示，不走全局 401 拦截。
 */
export async function login(username: string, password: string): Promise<AuthUser> {
  const result = await request<LoginResult>('/api/auth/login', {
    method: 'POST',
    body: { username, password },
    skipUnauthorizedHandler: true,
  });
  setToken(result.token);
  const user: AuthUser = { username, displayName: result.displayName, roles: result.roles };
  setStoredUser(user);
  return user;
}

/** 当前登录用户（用于启动时校验 token 是否仍有效；401 走全局拦截自动登出） */
export async function fetchMe(): Promise<MeResult> {
  return request<MeResult>('/api/auth/me');
}
