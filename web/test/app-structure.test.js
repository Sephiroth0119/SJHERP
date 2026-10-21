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

test('first-login wizard state hooks stay above the logged-in early return', () => {
  const wizardIndex = appSource.indexOf('const [forceWizard, setForceWizard]');
  const statusIndex = appSource.indexOf('readOnboardingStatus(user.username)');
  const earlyReturnIndex = appSource.indexOf('return <LoginPage');
  assert.ok(wizardIndex >= 0 && wizardIndex < earlyReturnIndex);
  assert.ok(statusIndex >= 0 && statusIndex < earlyReturnIndex);
  assert.match(appSource, /<OnboardingWizard/);
});
