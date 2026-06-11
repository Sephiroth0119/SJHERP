/**
 * 消息列表：渲染用户消息与 Agent 结构化回复（文本 + OptionCard + FormCard）。
 */
import { useEffect, useRef } from 'react';
import type {
  AgentForm,
  AgentFormValues,
  AgentOption,
  ChatMessage,
} from '../../types/agent';
import { OptionCard } from './OptionCard';
import { FormCard } from './FormCard';

interface MessageListProps {
  messages: ChatMessage[];
  /** Agent 是否正在生成回复（mock 延迟期间显示提示） */
  pending: boolean;
  onSelectOption: (option: AgentOption) => void;
  onSubmitForm: (form: AgentForm, values: AgentFormValues) => void;
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

export function MessageList({ messages, pending, onSelectOption, onSubmitForm }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  // 新消息到达时滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, pending]);

  const lastAgentId = [...messages].reverse().find((m) => m.role === 'agent')?.id;

  return (
    <div className="message-list">
      {messages.map((message) => {
        if (message.role === 'user') {
          return (
            <div key={message.id} className="message message-user">
              <div className="message-bubble message-bubble-user">{message.text}</div>
              <div className="message-time">{formatTime(message.createdAt)}</div>
            </div>
          );
        }
        // Agent 消息：仅最新一条的选项/表单可交互，避免对历史消息重复操作
        const interactive = !pending && message.id === lastAgentId;
        const { reply } = message;
        return (
          <div key={message.id} className="message message-agent">
            <div className="message-bubble message-bubble-agent">{reply.text}</div>
            {reply.options && reply.options.length > 0 && (
              <div className="option-card-list">
                {reply.options.map((option) => (
                  <OptionCard
                    key={option.id}
                    option={option}
                    interactive={interactive}
                    onSelect={onSelectOption}
                  />
                ))}
              </div>
            )}
            {reply.form && (
              <FormCard form={reply.form} interactive={interactive} onSubmit={onSubmitForm} />
            )}
            <div className="message-time">{formatTime(message.createdAt)}</div>
          </div>
        );
      })}
      {pending && <div className="message-pending">助手正在思考…</div>}
      <div ref={bottomRef} />
    </div>
  );
}
