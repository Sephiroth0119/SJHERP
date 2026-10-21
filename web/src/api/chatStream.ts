/**
 * SSE 分片解析（选项返回协议 v0.2 传输层）。
 * 独立于 Vite env / fetch，便于 node:test 直接加载。
 */
import {
  AGENT_PROTOCOL_VERSION,
  type AgentReply,
  type AgentStreamEvent,
} from '../types/agent.ts';

/** 解析一块 SSE 文本（按空行分事件），供测试与流式消费共用 */
export function parseSseBlocks(raw: string): AgentStreamEvent[] {
  const events: AgentStreamEvent[] = [];
  const blocks = raw.split(/\r?\n\r?\n/);
  for (const block of blocks) {
    const event = parseSseBlock(block);
    if (event) events.push(event);
  }
  return events;
}

export function parseSseBlock(block: string): AgentStreamEvent | null {
  const trimmed = block.trim();
  if (trimmed === '' || trimmed.startsWith(':')) return null;
  let eventName = '';
  const dataLines: string[] = [];
  for (const line of trimmed.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim());
    }
  }
  if (dataLines.length === 0) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(dataLines.join('\n'));
  } catch {
    return null;
  }
  return asStreamEvent(parsed, eventName);
}

function asStreamEvent(value: unknown, eventName: string): AgentStreamEvent | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const candidate = value as Record<string, unknown>;
  const type = typeof candidate.type === 'string' ? candidate.type : eventName;
  const version = typeof candidate.version === 'string' ? candidate.version : AGENT_PROTOCOL_VERSION;
  switch (type) {
    case 'status':
      return typeof candidate.status === 'string'
        ? { version, type: 'status', status: candidate.status }
        : null;
    case 'delta':
      return typeof candidate.text === 'string'
        ? { version, type: 'delta', text: candidate.text }
        : null;
    case 'complete':
      return isAgentReply(candidate.reply)
        ? { version, type: 'complete', reply: candidate.reply }
        : null;
    case 'error':
      return typeof candidate.error === 'string'
        ? { version, type: 'error', error: candidate.error }
        : null;
    default:
      return null;
  }
}

function isAgentReply(value: unknown): value is AgentReply {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.text === 'string';
}
