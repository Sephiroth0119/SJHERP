import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  AGENT_PROTOCOL_VERSION,
  reconstructReplyFromStreamEvents,
} from '../src/types/agent.ts';
import { parseSseBlock, parseSseBlocks } from '../src/api/chatStream.ts';

const chatPanelSource = readFileSync(new URL('../src/components/chat/ChatPanel.tsx', import.meta.url), 'utf8');
const messageListSource = readFileSync(new URL('../src/components/chat/MessageList.tsx', import.meta.url), 'utf8');
const chatApiSource = readFileSync(new URL('../src/api/chatApi.ts', import.meta.url), 'utf8');

test('SSE 分片按顺序拼接正文，complete 才带 options', () => {
  const raw = [
    'event: status',
    'data: {"version":"0.2","type":"status","status":"thinking"}',
    '',
    'event: delta',
    'data: {"version":"0.2","type":"delta","text":"物料 "}',
    '',
    'event: delta',
    'data: {"version":"0.2","type":"delta","text":"需补货"}',
    '',
    'event: complete',
    'data: {"version":"0.2","type":"complete","reply":{"version":"0.2","text":"物料 需补货","options":[{"id":"opt-1","label":"确认执行","risk":"high"},{"id":"opt-2","label":"取消"}],"requiresConfirmation":true}}',
    '',
  ].join('\n');
  const events = parseSseBlocks(raw);
  assert.equal(events[0].type, 'status');
  assert.equal(events[1].type, 'delta');
  assert.equal(events[2].text, '需补货');
  const reply = reconstructReplyFromStreamEvents(events);
  assert.equal(reply.text, '物料 需补货');
  assert.equal(reply.requiresConfirmation, true);
  assert.equal(reply.options.length, 2);
  assert.equal(events.filter((event) => event.type === 'delta').every((event) => !('options' in event)), true);
});

test('error 事件保留后端原文', () => {
  const event = parseSseBlock('event: error\ndata: {"version":"0.2","type":"error","error":"选项不存在或已过期: opt-x"}');
  assert.equal(event.type, 'error');
  assert.equal(event.error, '选项不存在或已过期: opt-x');
});

test('reconstructReplyFromStreamEvents 无 complete 时仍拼出正文', () => {
  const reply = reconstructReplyFromStreamEvents([
    { version: '0.2', type: 'delta', text: '半' },
    { version: '0.2', type: 'delta', text: '成品' },
  ]);
  assert.equal(reply.version, AGENT_PROTOCOL_VERSION);
  assert.equal(reply.text, '半成品');
  assert.equal(reply.options, undefined);
});

test('聊天面板走 SSE 且流式结束前不渲染选项', () => {
  assert.match(chatPanelSource, /sendChatMessageStream/);
  assert.match(chatApiSource, /Accept: 'text\/event-stream'/);
  assert.match(messageListSource, /streaming/);
  assert.match(messageListSource, /showCards && reply\.options/);
  assert.match(messageListSource, /showCards && reply\.form/);
});

test('断线提示使用可读中文且错误优先后端原文', () => {
  assert.match(chatApiSource, /连接已中断，未收到完整回复/);
  assert.match(chatApiSource, /连接已中断，请重试/);
  assert.match(chatApiSource, /handlers\.onError\(streamError\)/);
});