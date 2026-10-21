/**
 * Agent 聊天面板：历史会话侧栏 + 消息列表 + 输入框。
 * 会话归属当前登录用户；越权/不存在由后端按 404 处理（fail-closed）。
 *
 * 发送文本 → { text }；点击选项 → { optionId }；提交表单 → { formId, values }。
 * 按选项返回协议 v0.2 回传；流式消费 SSE，正文打字机，结束后再渲染选项/表单/单据卡片。
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
  archiveChatSession,
  createChatSession,
  getChatSession,
  listChatSessions,
  renameChatSession,
  sendChatMessageStream,
  type ChatSessionSummary,
  type SendMessagePayload,
  type ServerChatMessage,
} from '../../api/chatApi';
import { COMPACT_CHAT_MEDIA, useMediaQuery } from '../../layout/useMediaQuery';
import { MessageList, type ChatStreamError } from './MessageList';
import { MessageInput } from './MessageInput';
import { SessionRail } from './SessionRail';

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

/** 从异常中提取用户可见的中文错误文案（优先后端原文） */
function toErrorText(error: unknown, fallback: string): string {
  if (error instanceof ChatApiError) return error.message;
  return fallback;
}

function upsertSession(list: ChatSessionSummary[], next: ChatSessionSummary): ChatSessionSummary[] {
  const others = list.filter((item) => item.sessionId !== next.sessionId);
  return [next, ...others].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}

interface ChatPanelProps {
  permissions: string[];
}

export function ChatPanel({ permissions }: ChatPanelProps) {
  const compactChat = useMediaQuery(COMPACT_CHAT_MEDIA);
  const [railOpen, setRailOpen] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [archived, setArchived] = useState(false);
  /** 等待中：会话初始化或消息往返期间为 true */
  const [pending, setPending] = useState(true);
  /** 聊天流末尾的错误提示（含重试动作）；新动作发起时清空 */
  const [error, setError] = useState<ChatStreamError | null>(null);
  /** 防止 React StrictMode 下 effect 双跑导致重复建会话 */
  const initStartedRef = useRef(false);

  const applyDetail = useCallback((detail: {
    sessionId: string;
    title?: string | null;
    status: string;
    createdAt?: string;
    updatedAt?: string;
    messages: ServerChatMessage[];
  }) => {
    const history = toChatMessages(detail.messages);
    setSessionId(detail.sessionId);
    setMessages(history.length > 0 ? history : [localGreeting()]);
    setArchived(detail.status === 'closed');
    localStorage.setItem(SESSION_STORAGE_KEY, detail.sessionId);
    setSessions((prev) => upsertSession(prev, {
      sessionId: detail.sessionId,
      title: detail.title ?? prev.find((item) => item.sessionId === detail.sessionId)?.title ?? null,
      status: detail.status,
      createdAt: detail.createdAt ?? new Date().toISOString(),
      updatedAt: detail.updatedAt ?? new Date().toISOString(),
    }));
  }, []);

  const refreshList = useCallback(async () => {
    const items = await listChatSessions();
    setSessions(items);
    return items;
  }, []);

  const openSession = useCallback(async (id: string) => {
    const detail = await getChatSession(id);
    applyDetail(detail);
  }, [applyDetail]);

  const createAndOpen = useCallback(async () => {
    const created = await createChatSession();
    localStorage.setItem(SESSION_STORAGE_KEY, created.sessionId);
    const detail = await getChatSession(created.sessionId);
    applyDetail(detail);
    await refreshList();
  }, [applyDetail, refreshList]);

  /** 初始化：拉列表 → 恢复当前会话或新建 */
  const initSession = useCallback(async () => {
    setPending(true);
    setError(null);
    try {
      const items = await refreshList();
      const storedId = localStorage.getItem(SESSION_STORAGE_KEY);
      if (storedId) {
        try {
          await openSession(storedId);
          setPending(false);
          return;
        } catch (e) {
          if (e instanceof ChatApiError && e.status === 404) {
            localStorage.removeItem(SESSION_STORAGE_KEY);
          } else {
            throw e;
          }
        }
      }
      const latest = items[0];
      if (latest) {
        await openSession(latest.sessionId);
        setPending(false);
        return;
      }
      await createAndOpen();
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
  }, [createAndOpen, openSession, refreshList]);

  useEffect(() => {
    if (initStartedRef.current) return;
    initStartedRef.current = true;
    void initSession();
  }, [initSession]);

  useEffect(() => {
    if (!compactChat) setRailOpen(false);
  }, [compactChat]);

  const handleSelectSession = useCallback(async (id: string) => {
    if (id === sessionId) {
      setRailOpen(false);
      return;
    }
    setPending(true);
    setError(null);
    setRailOpen(false);
    try {
      await openSession(id);
      setPending(false);
    } catch (e) {
      setPending(false);
      setError({
        text: toErrorText(e, '打开会话失败，请重试'),
        retry: () => {
          void handleSelectSession(id);
        },
      });
    }
  }, [openSession, sessionId]);

  const handleNewSession = useCallback(async () => {
    setPending(true);
    setError(null);
    setRailOpen(false);
    try {
      await createAndOpen();
      setPending(false);
    } catch (e) {
      setPending(false);
      setError({
        text: toErrorText(e, '新建会话失败，请重试'),
        retry: () => {
          void handleNewSession();
        },
      });
    }
  }, [createAndOpen]);

  const handleRename = useCallback(async (id: string, title: string) => {
    try {
      const updated = await renameChatSession(id, title);
      setSessions((prev) => upsertSession(prev, updated));
    } catch (e) {
      setError({
        text: toErrorText(e, '重命名失败，请重试'),
        retry: () => {
          void handleRename(id, title);
        },
      });
    }
  }, []);

  const handleArchive = useCallback(async (id: string) => {
    try {
      const updated = await archiveChatSession(id);
      setSessions((prev) => upsertSession(prev, updated));
      if (id === sessionId) setArchived(true);
    } catch (e) {
      setError({
        text: toErrorText(e, '归档失败，请重试'),
        retry: () => {
          void handleArchive(id);
        },
      });
    }
  }, [sessionId]);

  /** 实际发送（重试时复用：不再追加用户气泡，只重发载荷）；流式打字机，结束后再出选项/单据卡片 */
  const performSend = useCallback(async (sid: string, payload: SendMessagePayload) => {
    setPending(true);
    setError(null);
    const streamId = nextLocalId();
    setMessages((prev) => {
      const withoutFailedStream = prev.filter((message) => !(message.role === 'agent' && message.streaming));
      return [
        ...withoutFailedStream,
        {
          id: streamId,
          role: 'agent',
          createdAt: new Date().toISOString(),
          streaming: true,
          reply: { version: AGENT_PROTOCOL_VERSION, text: '' },
        },
      ];
    });
    try {
      await sendChatMessageStream(sid, payload, {
        onDelta: (text) => {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === streamId && message.role === 'agent'
                ? { ...message, reply: { ...message.reply, text: message.reply.text + text } }
                : message,
            ),
          );
        },
        onComplete: (reply: AgentReply) => {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === streamId && message.role === 'agent'
                ? { ...message, streaming: false, reply }
                : message,
            ),
          );
        },
        onError: (errorText) => {
          setPending(false);
          setError({
            text: errorText,
            retry: () => {
              void performSend(sid, payload);
            },
          });
        },
      });
      setPending(false);
      try {
        await refreshList();
      } catch {
        // 列表刷新失败不影响本轮对话
      }
    } catch (e) {
      setPending(false);
      setMessages((prev) => prev.filter((message) => message.id !== streamId || (message.role === 'agent' && message.reply.text !== '')));
      setError({
        text: toErrorText(e, '消息发送失败，请重试'),
        retry: () => {
          void performSend(sid, payload);
        },
      });
    }
  }, [refreshList]);

  /** 追加用户气泡并发送载荷 */
  const dispatch = useCallback(
    (payload: SendMessagePayload, bubbleText: string) => {
      if (!sessionId || archived) return;
      setMessages((prev) => [
        ...prev,
        { id: nextLocalId(), role: 'user', createdAt: new Date().toISOString(), text: bubbleText },
      ]);
      void performSend(sessionId, payload);
    },
    [archived, sessionId, performSend],
  );

  const handleSendText = useCallback(
    (text: string) => {
      dispatch({ text }, text);
    },
    [dispatch],
  );

  const handleSelectOption = useCallback(
    (option: AgentOption) => {
      dispatch({ optionId: option.id }, option.label);
    },
    [dispatch],
  );

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

  const currentTitle = sessions.find((item) => item.sessionId === sessionId)?.title;
  const headerHint = USE_MOCK
    ? 'mock 模式（VITE_USE_MOCK=true）'
    : archived
      ? '已归档（只读）'
      : currentTitle
        ? currentTitle
        : sessionId
          ? '新会话'
          : '正在连接后端…';

  return (
    <section className={compactChat && railOpen ? 'chat-panel chat-panel-rail-open' : 'chat-panel'}>
      {compactChat && railOpen && (
        <button
          type="button"
          className="chat-session-backdrop"
          aria-label="关闭会话列表"
          onClick={() => setRailOpen(false)}
        />
      )}
      <SessionRail
        sessions={sessions}
        currentSessionId={sessionId}
        disabled={pending}
        onNew={() => { void handleNewSession(); }}
        onSelect={(id) => { void handleSelectSession(id); }}
        onRename={(id, title) => { void handleRename(id, title); }}
        onArchive={(id) => { void handleArchive(id); }}
      />
      <div className="chat-main">
        <header className="chat-header">
          {compactChat && (
            <button
              type="button"
              className="chat-session-toggle"
              aria-expanded={railOpen}
              aria-controls="chat-session-rail"
              onClick={() => setRailOpen((open) => !open)}
            >
              会话
            </button>
          )}
          <h1>Agent 助手</h1>
          <span className="chat-header-hint">{headerHint}</span>
        </header>
        <MessageList
          messages={messages}
          pending={pending}
          error={error}
          permissions={permissions}
          onSelectOption={handleSelectOption}
          onSubmitForm={handleSubmitForm}
        />
        <MessageInput disabled={pending || !sessionId || archived} onSend={handleSendText} />
      </div>
    </section>
  );
}
