/**
 * Agent 聊天面板：消息列表 + 输入框，会话状态由后端持久化（src/api/chatApi.ts）。
 *
 * 会话生命周期：
 * - 首次进入：localStorage 有 sessionId 则 GET 回放历史（404 则清掉重建）；
 *   没有则 POST 新建并写入 localStorage。
 * - 发送文本 → { text }；点击选项 → { optionId }；提交表单 → { formId, values }。
 *   均按选项返回协议 v0.1 回传，响应即 AgentReply。
 * - 网络错误在聊天流末尾显示中文错误提示，可点击重试，不白屏。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  AgentForm,
  AgentFormValues,
  AgentOption,
  ChatMessage,
} from '../../types/agent';
import { AGENT_PROTOCOL_VERSION, type AgentReply } from '../../types/agent';
import {
  CHAT_SESSION_STORAGE_KEY,
  ChatApiError,
  USE_MOCK,
  createChatSession,
  getChatSession,
  sendChatMessage,
  type SendMessagePayload,
  type ServerChatMessage,
} from '../../api/chatApi';
import { MessageList, type ChatStreamError } from './MessageList';
import { MessageInput } from './MessageInput';

/** localStorage 中保存会话 id 的键（退出登录时由 App 一并清除） */
const SESSION_STORAGE_KEY = CHAT_SESSION_STORAGE_KEY;

/** 本地临时消息 id（用户气泡、本地开场白等），加前缀避免与后端 id 撞车 */
let localSeq = 0;
function nextLocalId(): string {
  localSeq += 1;
  return `local-${localSeq}`;
}

/** 把后端历史消息映射为前端 ChatMessage（容错：缺 reply 的 agent 消息按纯文本渲染） */
function toChatMessages(serverMessages: ServerChatMessage[]): ChatMessage[] {
  const result: ChatMessage[] = [];
  for (const message of serverMessages) {
    if (message.role === 'user') {
      result.push({
        id: String(message.id),
        role: 'user',
        createdAt: message.createdAt,
        text: message.text ?? '',
      });
    } else if (message.reply) {
      result.push({
        id: String(message.id),
        role: 'agent',
        createdAt: message.createdAt,
        reply: message.reply,
      });
    } else if (message.text) {
      result.push({
        id: String(message.id),
        role: 'agent',
        createdAt: message.createdAt,
        reply: { version: AGENT_PROTOCOL_VERSION, text: message.text },
      });
    }
    // role=agent 且 text/reply 均为空：异常数据，跳过不渲染
  }
  return result;
}

/** 新会话无历史时的本地开场白（纯文本，不带选项——选项 id 须由后端生成才可回传） */
function localGreeting(): ChatMessage {
  return {
    id: nextLocalId(),
    role: 'agent',
    createdAt: new Date().toISOString(),
    reply: {
      version: AGENT_PROTOCOL_VERSION,
      text: '你好，我是 SJHERP 业务助手。直接输入你要做的业务，例如：给华东金属下一张 500 张不锈钢板的采购单。',
    },
  };
}

/** 从异常中提取用户可见的中文错误文案 */
function toErrorText(error: unknown, fallback: string): string {
  if (error instanceof ChatApiError) return error.message;
  return fallback;
}

export function ChatPanel() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  /** 等待中：会话初始化或消息往返期间为 true */
  const [pending, setPending] = useState(true);
  /** 聊天流末尾的错误提示（含重试动作）；新动作发起时清空 */
  const [error, setError] = useState<ChatStreamError | null>(null);
  /** 防止 React StrictMode 下 effect 双跑导致重复建会话 */
  const initStartedRef = useRef(false);

  /** 初始化会话：恢复历史或新建（失败可整体重试） */
  const initSession = useCallback(async () => {
    setPending(true);
    setError(null);
    try {
      const storedId = localStorage.getItem(SESSION_STORAGE_KEY);
      if (storedId) {
        try {
          const detail = await getChatSession(storedId);
          const history = toChatMessages(detail.messages);
          setSessionId(detail.sessionId);
          setMessages(history.length > 0 ? history : [localGreeting()]);
          setPending(false);
          return;
        } catch (e) {
          if (e instanceof ChatApiError && e.status === 404) {
            // 会话已失效：清掉本地记录，落到下面的新建流程
            localStorage.removeItem(SESSION_STORAGE_KEY);
          } else {
            throw e;
          }
        }
      }
      const created = await createChatSession();
      localStorage.setItem(SESSION_STORAGE_KEY, created.sessionId);
      // 回放一次历史：mock 会话自带开场白；真实后端若为空则用本地开场白
      const detail = await getChatSession(created.sessionId);
      const history = toChatMessages(detail.messages);
      setSessionId(created.sessionId);
      setMessages(history.length > 0 ? history : [localGreeting()]);
      setPending(false);
    } catch (e) {
      setPending(false);
      setError({
        text: toErrorText(e, '会话初始化失败，请重试'),
        retry: () => {
          void initSession();
        },
      });
    }
  }, []);

  useEffect(() => {
    if (initStartedRef.current) return;
    initStartedRef.current = true;
    void initSession();
  }, [initSession]);

  /** 实际发送（重试时复用：不再追加用户气泡，只重发载荷） */
  const performSend = useCallback(async (sid: string, payload: SendMessagePayload) => {
    setPending(true);
    setError(null);
    try {
      const reply: AgentReply = await sendChatMessage(sid, payload);
      setMessages((prev) => [
        ...prev,
        { id: nextLocalId(), role: 'agent', createdAt: new Date().toISOString(), reply },
      ]);
      setPending(false);
    } catch (e) {
      setPending(false);
      setError({
        text: toErrorText(e, '消息发送失败，请重试'),
        retry: () => {
          void performSend(sid, payload);
        },
      });
    }
  }, []);

  /** 追加用户气泡并发送载荷 */
  const dispatch = useCallback(
    (payload: SendMessagePayload, bubbleText: string) => {
      if (!sessionId) return;
      setMessages((prev) => [
        ...prev,
        { id: nextLocalId(), role: 'user', createdAt: new Date().toISOString(), text: bubbleText },
      ]);
      void performSend(sessionId, payload);
    },
    [sessionId, performSend],
  );

  /** 自由文本 → { text } */
  const handleSendText = useCallback(
    (text: string) => {
      dispatch({ text }, text);
    },
    [dispatch],
  );

  /** 点击选项 → { optionId }（协议：只回传 id，气泡显示 label） */
  const handleSelectOption = useCallback(
    (option: AgentOption) => {
      dispatch({ optionId: option.id }, option.label);
    },
    [dispatch],
  );

  /** 提交表单 → { formId, values }（所有值均为字符串，精度由后端 BigDecimal 解析） */
  const handleSubmitForm = useCallback(
    (form: AgentForm, values: AgentFormValues) => {
      const summary = form.fields
        .map((field) => `${field.label}: ${values[field.name] || '（空）'}`)
        .join('，');
      dispatch(
        { formId: form.id, values },
        `提交${form.title ? `「${form.title}」` : '表单'}：${summary}`,
      );
    },
    [dispatch],
  );

  const headerHint = USE_MOCK
    ? 'mock 模式（VITE_USE_MOCK=true）'
    : sessionId
      ? `会话 ${sessionId}`
      : '正在连接后端…';

  return (
    <section className="chat-panel">
      <header className="chat-header">
        <h1>Agent 助手</h1>
        <span className="chat-header-hint">{headerHint}</span>
      </header>
      <MessageList
        messages={messages}
        pending={pending}
        error={error}
        onSelectOption={handleSelectOption}
        onSubmitForm={handleSubmitForm}
      />
      <MessageInput disabled={pending || !sessionId} onSend={handleSendText} />
    </section>
  );
}
