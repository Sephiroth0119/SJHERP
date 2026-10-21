import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { AGENT_LEGACY_PROTOCOL_VERSION, AGENT_PROTOCOL_VERSION } from '../src/types/agent.ts';

const protocolDoc = readFileSync(new URL('../../docs/选项返回协议.md', import.meta.url), 'utf8');
const agentTypes = readFileSync(new URL('../src/types/agent.ts', import.meta.url), 'utf8');
const agentReply = readFileSync(new URL('../../server/sjherp-agent/src/main/java/com/sjherp/agent/reply/AgentReply.java', import.meta.url), 'utf8');

test('协议 v0.2 三处契约版本一致', () => {
  assert.equal(AGENT_PROTOCOL_VERSION, '0.2');
  assert.equal(AGENT_LEGACY_PROTOCOL_VERSION, '0.1');
  assert.match(agentTypes, /AGENT_PROTOCOL_VERSION = '0\.2'/);
  assert.match(agentReply, /PROTOCOL_VERSION = "0\.2"/);
  assert.match(protocolDoc, /v0\.2/);
  assert.match(protocolDoc, /消息流式分片/);
});
