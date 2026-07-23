import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');

test('navigation selection does not add a hook only after the logged-in early return', () => {
  const callbackIndex = appSource.indexOf('const selectModule = useCallback');
  const earlyReturnIndex = appSource.indexOf('  if (!user) {');
  assert.ok(callbackIndex >= 0);
  assert.ok(callbackIndex < earlyReturnIndex);
});
