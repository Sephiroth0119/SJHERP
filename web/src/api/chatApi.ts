/**
 * 聊天后端 API 封装（前后端契约，配合选项返回协议 v0.2）：
 *
 * - POST /api/chat/sessions                 → 201 { sessionId, createdAt }
 * - GET  /api/chat/sessions/{id}            → 200 { sessionId, status, messages[] }（旧→新）；404 { error }
 * - POST /api/chat/sessions/{id}/messages
 *     Accept: application/json → 200 完整 AgentReply（非流式客户端，字段兼容 v0.1）
 *     Accept: text/event-stream → SSE 分片（status/delta/complete/error）
 *   请求体三选一：{ text } / { optionId } / { formId, values }
 *
 * mock 切换收敛在本文件：VITE_USE_MOCK=true 时全部走内存 mock（src/mock/mockAgent.ts），
 * 默认走真实后端（相对路径 /api，经 vite 开发代理转发到 Spring Boot）。
 *
 * 请求封装与 Authorization 头统一走 src/api/http.ts（M2-T05 认证）。
 */
import {
  AGENT_PROTOCOL_VERSION,
  type AgentReply,
  type AgentStreamEvent,
} from '../types/agent.ts';
import { GREETING_REPLY, getMockReply } from '../mock/mockAgent.ts';
import { ApiError, getToken, handleUnauthorizedResponse, request } from './http.ts';
import { CHAT_SESSION_STORAGE_KEY as SESSION_STORAGE_KEY } from './session.ts';
import { parseSseBlock } from './chatStream.ts';

export { parseSseBlock, parseSseBlocks } from './chatStream.ts';

/** 是否启用 mock 模式（默认 false，走真实后端） */
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';

/** localStorage 键：当前聊天会话 id（退出登录时一并清除，避免串到他人会话） */
export const CHAT_SESSION_STORAGE_KEY = SESSION_STORAGE_KEY;

/** POST /api/chat/sessions 的响应体 */
export interface ChatSessionCreated {
  sessionId: string;
  /** ISO-8601 时间戳 */
  createdAt: string;
}

/** GET 会话历史中的单条消息（后端视角，旧→新排序） */
export interface ServerChatMessage {
  /** 后端为会话内 seq（整数），mock 为字符串；前端渲染前统一转字符串 */
  id: number | string;
  role: 'user' | 'agent';
  /** 用户消息正文；agent 消息可能为 null */
  text: string | null;
  /** role=agent 时为结构化回复（协议 v0.1/v0.2），role=user 时为 null */
  reply: AgentReply | null;
  createdAt: string;
}

/** GET /api/chat/sessions/{id} 的响应体 */
export interface ChatSessionDetail {
  sessionId: string;
  status: string;
  messages: ServerChatMessage[];
}

/** 发送消息的请求体：自由文本 / 点击选项 / 提交表单，三选一 */
export type SendMessagePayload =
  | { text: string }
  | { optionId: string }
  | { formId: string; values: Record<string, string> };

/**
 * 聊天 API 错误类型：沿用通用 ApiError（历史名 ChatApiError 保留为别名，
 * 既有组件的 instanceof 判断不受影响）。
 */
export { ApiError as ChatApiError };

// ============================================================
// mock 实现：内存会话，仅 VITE_USE_MOCK=true 时启用。
// 刷新页面后内存丢失，getChatSession 对未知 id 抛 404，
// 与真实后端"会话不存在"的行为一致（前端会清掉 localStorage 重建）。
// ============================================================

/** mock 回复延迟（毫秒），模拟 Agent 思考 */
const MOCK_DELAY_MS = 400;

const mockSessions = new Map<string, ServerChatMessage[]>();
let mockSeq = 0;

function nextMockId(prefix: string): string {
  mockSeq += 1;
  return `${prefix}-${mockSeq}`;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function mockCreateSession(): Promise<ChatSessionCreated> {
  await delay(MOCK_DELAY_MS / 2);
  const sessionId = nextMockId('mock-session');
  // 开场白由 mock 会话自带，回放历史时即可渲染
  mockSessions.set(sessionId, [
    {
      id: nextMockId('mock-msg'),
      role: 'agent',
      text: null,
      reply: GREETING_REPLY,
      createdAt: new Date().toISOString(),
    },
  ]);
  return { sessionId, createdAt: new Date().toISOString() };
}

async function mockGetSession(sessionId: string): Promise<ChatSessionDetail> {
  await delay(MOCK_DELAY_MS / 2);
  const messages = mockSessions.get(sessionId);
  if (!messages) {
    throw new ApiError('会话不存在', 404);
  }
  return { sessionId, status: 'active', messages };
}

async function mockSendMessage(sessionId: string, payload: SendMessagePayload): Promise<AgentReply> {
  await delay(MOCK_DELAY_MS);
  const messages = mockSessions.get(sessionId);
  if (!messages) {
    throw new ApiError('会话不存在', 404);
  }
  // mockAgent 是关键词路由：把结构化载荷还原成语义化文本驱动它
  let routeText: string;
  if ('text' in payload) {
    routeText = payload.text;
  } else if ('optionId' in payload) {
    // 按协议语义：凭最近一条 agent 回复的 options 由 id 还原选项
    const lastAgent = [...messages].reverse().find((m) => m.role === 'agent' && m.reply);
    const option = lastAgent?.reply?.options?.find((o) => o.id === payload.optionId);
    routeText = option?.label ?? payload.optionId;
  } else {
    routeText = `表单提交（${payload.formId}）`;
  }
  messages.push({
    id: nextMockId('mock-msg'),
    role: 'user',
    text: routeText,
    reply: null,
    createdAt: new Date().toISOString(),
  });
  const reply: AgentReply = { ...getMockReply(routeText), version: AGENT_PROTOCOL_VERSION };
  messages.push({
    id: nextMockId('mock-msg'),
    role: 'agent',
    text: null,
    reply,
    createdAt: new Date().toISOString(),
  });
  return reply;
}

// ============================================================
// 对外 API：mock / 真实后端的切换只发生在这里
// ============================================================

/** 创建新会话 */
export function createChatSession(): Promise<ChatSessionCreated> {
  if (USE_MOCK) return mockCreateSession();
  return request<ChatSessionCreated>('/api/chat/sessions', { method: 'POST' });
}

/** 拉取会话详情与历史消息（旧→新）；会话不存在时抛 ChatApiError(status=404) */
export function getChatSession(sessionId: string): Promise<ChatSessionDetail> {
  if (USE_MOCK) return mockGetSession(sessionId);
  return request<ChatSessionDetail>(`/api/chat/sessions/${encodeURIComponent(sessionId)}`);
}

/** 发送一条消息（文本 / 选项 / 表单），响应即完整 AgentReply（非流式，兼容 v0.1 客户端） */
export function sendChatMessage(sessionId: string, payload: SendMessagePayload): Promise<AgentReply> {
  if (USE_MOCK) return mockSendMessage(sessionId, payload);
  return request<AgentReply>(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: 'POST',
    body: payload,
  });
}

/** 流式回调：delta 只含正文；complete 才带 options/form；error 为后端原文 */
export interface ChatStreamHandlers {
  onStatus?: (status: string) => void;
  onDelta: (text: string) => void;
  onComplete: (reply: AgentReply) => void;
  onError: (error: string) => void;
}

async function mockSendMessageStream(
  sessionId: string,
  payload: SendMessagePayload,
  handlers: ChatStreamHandlers,
): Promise<void> {
  handlers.onStatus?.('thinking');
  const reply = await mockSendMessage(sessionId, payload);
  const chunks = chunkText(reply.text, 8);
  for (const chunk of chunks) {
    handlers.onDelta(chunk);
  }
  handlers.onComplete(reply);
}

function chunkText(text: string, size: number): string[] {
  if (text === '') return [];
  const chunks: string[] = [];
  for (let i = 0; i < text.length; i += size) {
    chunks.push(text.slice(i, i + size));
  }
  return chunks;
}

async function readErrorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { error?: unknown };
    if (typeof body.error === 'string' && body.error !== '') return body.error;
  } catch {
    // 非 JSON 错误体：展示后端状态，不发明文案掩盖原文缺失
  }
  return fallback;
}

/**
 * 流式发送：消费 SSE 分片。结束前不把 options/form 交给 UI。
 * 错误与断线回调 onError，文案优先后端原文。
 */
export async function sendChatMessageStream(
  sessionId: string,
  payload: SendMessagePayload,
  handlers: ChatStreamHandlers,
): Promise<void> {
  if (USE_MOCK) {
    await mockSendMessageStream(sessionId, payload, handlers);
    return;
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
    });
  } catch {
    throw new ApiError('无法连接服务器，请检查网络或稍后重试');
  }

  if (!response.ok) {
    const fallback = `请求失败（HTTP ${response.status}）`;
    const message = await readErrorMessage(response, fallback);
    if (response.status === 401) handleUnauthorizedResponse();
    throw new ApiError(message, response.status);
  }

  if (!response.body) {
    throw new ApiError('服务器未返回流式响应，请稍后重试', response.status);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let completed = false;
  let streamError: string | null = null;

  const dispatch = (event: AgentStreamEvent) => {
    switch (event.type) {
      case 'status':
        handlers.onStatus?.(event.status);
        break;
      case 'delta':
        handlers.onDelta(event.text);
        break;
      case 'complete':
        completed = true;
        handlers.onComplete(event.reply);
        break;
      case 'error':
        streamError = event.error;
        break;
      default: {
        const exhaustive: never = event;
        void exhaustive;
        break;
      }
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split(/\r?\n\r?\n/);
      buffer = parts.pop() ?? '';
      for (const part of parts) {
        const event = parseSseBlock(part);
        if (event) dispatch(event);
      }
    }
    if (buffer.trim() !== '') {
      const event = parseSseBlock(buffer);
      if (event) dispatch(event);
    }
  } catch (error) {
    if (error instanceof ApiError) throw error;
    handlers.onError('连接已中断，请重试');
    return;
  }

  if (streamError) {
    handlers.onError(streamError);
    return;
  }
  if (!completed) {
    handlers.onError('连接已中断，未收到完整回复');
  }
}
