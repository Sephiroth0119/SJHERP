/** Shared browser-session boundary; kept separate to avoid http↔chat API cycles. */
export const TOKEN_STORAGE_KEY = 'sjherp.auth.token';
export const USER_STORAGE_KEY = 'sjherp.auth.user';
export const CHAT_SESSION_STORAGE_KEY = 'sjherp.chat.sessionId';

/** End the complete frontend session before another user can sign in. */
export function endFrontendSession(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  localStorage.removeItem(USER_STORAGE_KEY);
  localStorage.removeItem(CHAT_SESSION_STORAGE_KEY);
}
