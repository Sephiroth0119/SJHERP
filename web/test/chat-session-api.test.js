import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

test('listChatSessions calls GET /api/chat/sessions and returns items', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({
        items: [
          {
            sessionId: 's-2',
            title: '华东金属采购',
            status: 'active',
            createdAt: '2026-10-21T08:00:00Z',
            updatedAt: '2026-10-21T09:00:00Z',
          },
        ],
      });
    };
    const api = await import('../src/api/chatApi.ts');
    const items = await api.listChatSessions();
    assert.equal(calls[0][0], '/api/chat/sessions');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(items[0].sessionId, 's-2');
    assert.equal(items[0].title, '华东金属采购');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('rename and archive use exact URLs without DELETE', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({
        sessionId: 's-1',
        title: '新标题',
        status: init.method === 'POST' ? 'closed' : 'active',
        createdAt: '2026-10-21T08:00:00Z',
        updatedAt: '2026-10-21T09:00:00Z',
      });
    };
    const api = await import('../src/api/chatApi.ts');
    await api.renameChatSession('s-1', '新标题');
    await api.archiveChatSession('s-1');
    assert.equal(calls[0][0], '/api/chat/sessions/s-1/title');
    assert.equal(calls[0][1].method, 'PUT');
    assert.deepEqual(JSON.parse(calls[0][1].body), { title: '新标题' });
    assert.equal(calls[1][0], '/api/chat/sessions/s-1/archive');
    assert.equal(calls[1][1].method, 'POST');
    assert.equal(calls.some((call) => call[1].method === 'DELETE'), false);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('archived send surfaces backend 409 error text', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async () => response(
      { error: '会话已归档，无法继续发送消息: s-1' },
      409,
    );
    const api = await import('../src/api/chatApi.ts');
    await assert.rejects(
      () => api.sendChatMessage('s-1', { text: '还想发' }),
      (error) => error.status === 409 && error.message === '会话已归档，无法继续发送消息: s-1',
    );
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('chat panel lists sessions, archives without DELETE, and gates document cards on permissions', () => {
  const panel = readFileSync(new URL('../src/components/chat/ChatPanel.tsx', import.meta.url), 'utf8');
  const rail = readFileSync(new URL('../src/components/chat/SessionRail.tsx', import.meta.url), 'utf8');
  const messages = readFileSync(new URL('../src/components/chat/MessageList.tsx', import.meta.url), 'utf8');
  const api = readFileSync(new URL('../src/api/chatApi.ts', import.meta.url), 'utf8');
  const combined = `${panel}\n${rail}\n${messages}\n${api}`;
  assert.match(panel, /<SessionRail/);
  assert.match(panel, /listChatSessions/);
  assert.match(panel, /createChatSession/);
  assert.match(rail, /新建会话/);
  assert.match(rail, /归档/);
  assert.match(messages, /visibleDocumentRefs\(reply\.documents, permissions\)/);
  assert.match(messages, /<DocumentCard/);
  assert.doesNotMatch(combined, /method:\s*['"]DELETE['"]/);
});
