import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  chartPercents,
  decimalToScaledInt,
  hasChartData,
  isZeroAmount,
} from '../src/report/decimalScale.ts';

test('decimal scale parses money strings with bigint instead of float', () => {
  assert.equal(decimalToScaledInt('150.50'), 150500000n);
  assert.equal(decimalToScaledInt('-1.5'), -1500000n);
  assert.equal(decimalToScaledInt(''), null);
  assert.equal(isZeroAmount('0.00'), true);
  assert.equal(isZeroAmount('0.01'), false);
  assert.equal(hasChartData(['0.00', '', '1.00']), true);
  assert.equal(hasChartData(['0', '0.00']), false);
});

test('chart percents are integer 0-100 ratios from bigint abs values', () => {
  assert.deepEqual(chartPercents(['0.00', '50.00', '100.00']), [0, 50, 100]);
  assert.deepEqual(chartPercents(['0', '0.00']), [0, 0]);
  assert.deepEqual(chartPercents(['-80.00', '20.00']), [100, 25]);
});

test('chart scale helper does not call parseFloat', () => {
  const source = readFileSync(new URL('../src/report/decimalScale.ts', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /parseFloat\s*\(/);
  assert.doesNotMatch(source, /parseInt\s*\(/);
});
