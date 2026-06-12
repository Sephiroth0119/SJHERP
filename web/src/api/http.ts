/**
 * 通用 HTTP 工具（M2-T05 认证落地）：
 *
 * - token 存 localStorage，所有请求自动带 Authorization: Bearer 头；
 * - 401 全局拦截：清除本地登录态并触发注册的回调（App 跳登录页）。
 *   登录接口本身的 401（密码错误）不触发全局拦截，由登录页就地展示；
 * - 错误统一抛 ApiError（message 为可直接展示的中文文案）。
 */

/** localStorage 键：JWT token */
const TOKEN_STORAGE_KEY = 'sjherp.auth.token';

/** localStorage 键：当前用户展示信息（displayName/roles，刷新页面后免请求渲染） */
const USER_STORAGE_KEY = 'sjherp.auth.user';

/** 当前登录用户的前端展示信息 */
export interface AuthUser {
  username: string;
  displayName: string;
  roles: string[];
}

/**
 * 统一的 API 错误：message 为可直接展示给用户的中文文案；
 * status 为 HTTP 状态码，网络层失败（无响应）时为 undefined。
 */
export class ApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

// ============================================================
// 登录态存取
// ============================================================

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token);
}

export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthUser;
  } catch {
    return null;
  }
}

export function setStoredUser(user: AuthUser): void {
  localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user));
}

/** 清除本地登录态（退出 / 401 拦截时调用） */
export function clearAuth(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  localStorage.removeItem(USER_STORAGE_KEY);
}

// ============================================================
// 401 全局拦截
// ============================================================

let unauthorizedHandler: (() => void) | null = null;

/** 注册 401 全局回调（App 挂载时注册：清登录态并切回登录页） */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

// ============================================================
// 通用请求封装
// ============================================================

interface RequestInitLite {
  method?: string;
  body?: unknown;
  /** true 时该请求的 401 不触发全局拦截（登录接口用：密码错误就地展示） */
  skipUnauthorizedHandler?: boolean;
}

/** 通用 fetch 封装：自动带 Authorization 头；网络异常与非 2xx 统一抛 ApiError */
export async function request<T>(path: string, init?: RequestInitLite): Promise<T> {
  const headers: Record<string, string> = {};
  if (init?.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const token = getToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method: init?.method ?? 'GET',
      headers,
      body: init?.body !== undefined ? JSON.stringify(init.body) : undefined,
    });
  } catch {
    // fetch 本身 reject：网络不可达 / 后端未启动
    throw new ApiError('无法连接服务器，请检查网络或稍后重试');
  }

  if (!response.ok) {
    // 契约：错误响应体为 { "error": "..." }，解析失败时退回通用文案
    let message = `请求失败（HTTP ${response.status}）`;
    try {
      const body = (await response.json()) as { error?: unknown };
      if (typeof body.error === 'string' && body.error !== '') {
        message = body.error;
      }
    } catch {
      // 响应体不是 JSON，忽略，用通用文案
    }
    if (response.status === 401 && !init?.skipUnauthorizedHandler) {
      // 登录已过期 / 用户被停用：清登录态并通知 App 跳登录页
      clearAuth();
      unauthorizedHandler?.();
    }
    throw new ApiError(message, response.status);
  }

  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError('服务器响应格式异常，请稍后重试', response.status);
  }
}
