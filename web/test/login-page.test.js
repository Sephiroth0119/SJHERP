import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const loginSource = readFileSync(join(webRoot, 'src/components/LoginPage.tsx'), 'utf8');
const cssSource = [
  readFileSync(join(webRoot, 'src/styles/global.css'), 'utf8'),
  existsSync(join(webRoot, 'src/components/LoginPage.css'))
    ? readFileSync(join(webRoot, 'src/components/LoginPage.css'), 'utf8')
    : '',
].join('\n');
const unicodeEscape = /\\u[0-9a-fA-F]{4}/;
const garbledMarker = /鏉|锛\?/;
const heroUrl = 'http://sjh-pic.oss-cn-hangzhou.aliyuncs.com/img/image.png';
const publicHeroPath = join(webRoot, 'public/assets/login-hero.png');
const distHeroPath = join(webRoot, '../web-dist/assets/login-hero.png');

test('login page keeps the real auth login call and does not hardcode demo passwords', () => {
  assert.match(loginSource, /import \{ login \} from '\.\.\/api\/authApi'/);
  assert.match(loginSource, /await login\(username\.trim\(\), password\)/);
  assert.match(loginSource, /if \(pending\) return/);
  assert.match(loginSource, /e instanceof ApiError \? e\.message : '登录失败，请稍后重试'/);
  assert.match(loginSource, /pending \? '登录中…' : '登录'/);
  assert.doesNotMatch(loginSource, /password\s*=\s*['"][^'"]+['"]/);
  assert.doesNotMatch(loginSource, /value=\{['"][^'"]+['"]\}/);
  assert.doesNotMatch(loginSource, /admin123|password123|demo@|changeme/i);
});

test('login page uses the public warehouse URL with SJHERP wordmark copy', () => {
  assert.match(loginSource, /className="login-hero"/);
  assert.match(loginSource, new RegExp(heroUrl.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.doesNotMatch(loginSource, /\/assets\/login-hero\.png/);
  assert.match(loginSource, />SJHERP</);
  assert.match(loginSource, />Agent 原生 ERP</);
  assert.match(loginSource, />用户名</);
  assert.match(loginSource, />密码</);
  assert.match(loginSource, /className="login-error"/);
  assert.doesNotMatch(loginSource, unicodeEscape);
  assert.doesNotMatch(loginSource, garbledMarker);
});

test('login page does not ship a local warehouse photograph', () => {
  assert.equal(existsSync(publicHeroPath), false, 'public/assets/login-hero.png must be removed');
  assert.equal(existsSync(distHeroPath), false, 'web-dist/assets/login-hero.png must be removed');
});

test('login CSS is a split-screen enterprise layout with restrained motion', () => {
  assert.match(cssSource, /--login-navy:\s*#0f1c2e/);
  assert.match(cssSource, /--login-teal:\s*#3a8f8c/);
  assert.match(cssSource, /\.login-hero\s*\{[^}]*flex:\s*0\s+0\s+55%/);
  assert.match(cssSource, /linear-gradient/);
  assert.match(cssSource, /box-shadow:/);
  assert.match(cssSource, /\.login-field input:focus(-visible)?[^}]*outline/);
  assert.match(cssSource, /@keyframes login-enter/);
  assert.match(cssSource, /animation:[^;]*login-enter\s+(0\.26s|260ms)/);
  assert.match(cssSource, /@media \(max-width:\s*768px\)/);
  assert.match(cssSource, /prefers-reduced-motion:\s*reduce/);
});
