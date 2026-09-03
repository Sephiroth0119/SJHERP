import test from 'node:test';
import assert from 'node:assert/strict';

import { guardModule, moduleFromHash } from '../src/security/routeGuard.ts';

test('redirects an unauthorized active module to the agent entry point', () => {
  assert.equal(guardModule('memory', ['finance:voucher']), 'agent');
});

test('keeps an active module when the permission set grants it', () => {
  assert.equal(guardModule('finance', ['finance:voucher']), 'finance');
});

test('resolves direct hash routes and safely falls back for unknown routes', () => {
  assert.equal(moduleFromHash('#/finance'), 'finance');
  assert.equal(moduleFromHash('#/not-a-module'), 'agent');
  assert.equal(moduleFromHash(''), 'agent');
});
