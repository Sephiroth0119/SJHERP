/**
 * Agent 聊天面板：消息列表 + 输入框，会话状态在此管理。
 * 当前接 mock Agent；后端就绪后替换为 /api 会话接口（状态由后端持久化）。
 */
import { useCallback, useRef, useState } from 'react';
import type {
  AgentForm,
  AgentFormValues,
  AgentOption,
  ChatMessage,
} from '../../types/agent';
import { GREETING_REPLY, getMockReply } from '../../mock/mockAgent';
import { MessageList } from './MessageList';
import { MessageInput } from './MessageInput';

/** 简单递增 id，mock 阶段够用；真实会话 id 由后端生成 */
let messageSeq = 0;
function nextMessageId(): string {
  messageSeq += 1;
  return `msg-${messageSeq}`;
}

/** mock 回复延迟（毫秒），模拟 Agent 思考 */
const MOCK_REPLY_DELAY_MS = 400;

export function ChatPanel() {
  const [messages, setMessages] = useState<ChatMessage[]>(() => [
    {
      id: nextMessageId(),
      role: 'agent',
      createdAt: new Date().toISOString(),
      reply: GREETING_REPLY,
    },
  ]);
  const [pending, setPending] = useState(false);
  const timerRef = useRef<number | undefined>(undefined);

  /** 发送一条用户消息并触发 mock 回复 */
  const send = useCallback((text: string) => {
    setMessages((prev) => [
      ...prev,
      { id: nextMessageId(), role: 'user', createdAt: new Date().toISOString(), text },
    ]);
    setPending(true);
    window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      setMessages((prev) => [
        ...prev,
        {
          id: nextMessageId(),
          role: 'agent',
          createdAt: new Date().toISOString(),
          reply: getMockReply(text),
        },
      ]);
      setPending(false);
    }, MOCK_REPLY_DELAY_MS);
  }, []);

  /**
   * 点击选项卡片。协议 v0.1：真实实现回传 { optionId: option.id }，
   * 后端按 id 还原 action；mock 阶段以 label 作为语义化用户消息驱动关键词路由，
   * 气泡展示文案与协议约定一致（显示 label）。
   */
  const handleSelectOption = useCallback(
    (option: AgentOption) => {
      send(option.label);
    },
    [send],
  );

  /** 提交表单：序列化为语义化文本回传（真实实现将走结构化接口） */
  const handleSubmitForm = useCallback(
    (form: AgentForm, values: AgentFormValues) => {
      const summary = form.fields
        .map((field) => `${field.label}: ${values[field.name] || '（空）'}`)
        .join('，');
      send(`表单提交（${form.id}）：${summary}`);
    },
    [send],
  );

  return (
    <section className="chat-panel">
      <header className="chat-header">
        <h1>Agent 助手</h1>
        <span className="chat-header-hint">mock 模式 · 未连接后端</span>
      </header>
      <MessageList
        messages={messages}
        pending={pending}
        onSelectOption={handleSelectOption}
        onSubmitForm={handleSubmitForm}
      />
      <MessageInput disabled={pending} onSend={send} />
    </section>
  );
}
