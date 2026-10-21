import { ApiError } from '../api/http.ts';

/**
 * 金额/数量/普通单元格的字符串展示：空值显示破折号，其余原样输出。
 * 禁止 Number / parseFloat / 算术，避免 float 吃掉尾零或改精度。
 */
export function formatAmountText(value: string | null | undefined): string {
  if (value == null) return '—';
  const text = String(value).trim();
  return text === '' ? '—' : text;
}

/** 优先展示后端 `{error}` 原文；非 ApiError 才用页面兜底文案。 */
export function displayApiError(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback;
}
