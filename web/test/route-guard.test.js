import test from 'node:test';
import assert from 'node:assert/strict';

import { guardModule } from '../src/security/routeGuard.ts';

test('redirects an unauthorized active module to the agent entry point', () => {
  assert.equal(guardModule('memory', ['ACCOUNTANT']), 'agent');
});

test('keeps an active module when the role has its permission', () => {
  assert.equal(guardModule('finance', ['ACCOUNTANT']), 'finance');
});
