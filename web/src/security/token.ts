function decodePayload(token: string): Record<string, unknown> | null {
  const parts = token.split('.');
  const payloadPart = parts[1];
  if (parts.length !== 3 || !payloadPart) return null;

  try {
    const base64 = payloadPart.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
    const bytes = Uint8Array.from(globalThis.atob(padded), (character) => character.charCodeAt(0));
    const value: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? value as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

/** Frontend expiry hint only; signature and authorization remain backend responsibilities. */
export function isUsableToken(token: string, nowMs = Date.now()): boolean {
  if (token.trim() === '') return false;
  const expiration = decodePayload(token)?.exp;
  return typeof expiration === 'number'
    && Number.isFinite(expiration)
    && expiration * 1000 > nowMs;
}
