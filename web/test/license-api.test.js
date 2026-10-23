import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { fetchLicenseStatus, licenseStatusTone } from '../src/api/licenseApi.ts';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const readSrc = (relative) => readFileSync(join(webRoot, relative), 'utf8');

const storage = { getItem: () => 'token', setItem() {}, removeItem() {} };

test('license status uses GET /api/license/status and maps tones', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (url, init) => {
      assert.equal(url, '/api/license/status');
      assert.equal(init?.method ?? 'GET', 'GET');
      return {
        ok: true,
        status: 200,
        json: async () => ({
          status: 'UNCONFIGURED',
          statusLabel: '未配置',
          configured: false,
          enforce: false,
          blocking: false,
          subject: null,
          issuedAt: null,
          expiresAt: null,
          graceUntil: null,
          seats: null,
          modules: [],
          kid: null,
          message: '未配置 license',
        }),
      };
    };
    const status = await fetchLicenseStatus();
    assert.equal(status.status, 'UNCONFIGURED');
    assert.equal(licenseStatusTone('VALID'), 'ok');
    assert.equal(licenseStatusTone('GRACE'), 'warn');
    assert.equal(licenseStatusTone('UNCONFIGURED'), 'info');
    assert.equal(licenseStatusTone('EXPIRED'), 'error');
    assert.equal(licenseStatusTone('INVALID_SIGNATURE'), 'error');
    assert.equal(licenseStatusTone('MALFORMED'), 'error');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('help page shows license status only with license:view and has no payment UI', () => {
  const help = readSrc('src/components/HelpWorkbench.tsx');
  const app = readSrc('src/App.tsx');
  const api = readSrc('src/api/licenseApi.ts');
  assert.match(app, /canViewLicense=\{hasPermission\(permissions, 'license:view'\)\}/);
  assert.match(help, /aria-label="许可状态"/);
  assert.match(help, /fetchLicenseStatus/);
  assert.match(help, /if \(!canViewLicense\)/);
  assert.match(help, /没有在线支付或订阅入口/);
  assert.doesNotMatch(help, /Stripe|微信支付|下单|checkout/i);
  assert.doesNotMatch(api, /Stripe|微信支付|subscribe/i);
  assert.match(api, /\/api\/license\/status/);
});
