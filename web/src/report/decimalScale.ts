/**
 * 报表图表用的十进制字符串标度（非金额运算）。
 * 把金额/数量字符串转成固定小数位的 BigInt，避免 parseFloat/Number 参与金额计算。
 */

const SCALE = 6n;
const FACTOR = 10n ** SCALE;

export function decimalToScaledInt(value: string): bigint | null {
  const trimmed = value.trim();
  if (trimmed === '' || trimmed === '—') return null;
  const negative = trimmed.startsWith('-');
  const unsigned = negative ? trimmed.slice(1) : trimmed;
  if (!/^\d+(?:\.\d+)?$/.test(unsigned)) return null;
  const [integerPart = '0', fractionPart = ''] = unsigned.split('.');
  const integer = integerPart.replace(/^0+(?=\d)/, '') || '0';
  const fraction = fractionPart.padEnd(Number(SCALE), '0').slice(0, Number(SCALE));
  const scaled = BigInt(integer) * FACTOR + BigInt(fraction || '0');
  return negative ? -scaled : scaled;
}

export function isZeroAmount(value: string): boolean {
  const scaled = decimalToScaledInt(value);
  return scaled === null || scaled === 0n;
}

/** 返回 0–100 的整数宽度，仅用于 CSS/SVG 条形比例，不用于展示金额。 */
export function chartPercents(values: readonly string[]): number[] {
  const scaled = values.map((value) => {
    const parsed = decimalToScaledInt(value) ?? 0n;
    return parsed < 0n ? -parsed : parsed;
  });
  const max = scaled.reduce((current, item) => (item > current ? item : current), 0n);
  if (max === 0n) return scaled.map(() => 0);
  return scaled.map((item) => Number((item * 100n) / max));
}

export function hasChartData(values: readonly string[]): boolean {
  return values.some((value) => !isZeroAmount(value));
}
