/**
 * 消息列表：渲染用户消息与 Agent 结构化回复（文本 + OptionCard + FormCard），
 * 以及聊天流末尾的等待指示与网络错误提示（可重试）。
 */
import { useEffect, useRef } from 'react';
import type {
  AgentForm,
  AgentFormValues,
  AgentOption,
  ChatMessage,
} from '../../types/agent';
import { visibleDocumentRefs } from '../../security/documentRef';
import { DocumentCard } from './DocumentCard';
import { OptionCard } from './OptionCard';
import { FormCard } from './FormCard';

/** 聊天流中的错误提示项：中文文案 + 重试动作 */
export interface ChatStreamError {
  text: string;
  retry: () => void;
}

interface MessageListProps {
  messages: ChatMessage[];
  /** Agent 是否正在生成回复（请求往返期间显示等待指示） */
  pending: boolean;
  /** 最近一次失败的错误提示，渲染在消息流末尾 */
  error?: ChatStreamError | null;
  /** 当前登录用户权限码；单据卡片按权限 fail-closed */
  permissions: readonly string[];
  onSelectOption: (option: AgentOption) => void;
  onSubmitForm: (form: AgentForm, values: AgentFormValues) => void;
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

export function MessageList({
  messages,
  pending,
  error,
  permissions,
  onSelectOption,
  onSubmitForm,
}: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  // 新消息/等待指示/错误提示出现时滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, pending, error]);

  const lastAgentId = [...messages].reverse().find((m) => m.role === 'agent')?.id;
  const last = messages[messages.length - 1];
  const streamingLast = last?.role === 'agent' && Boolean(last.streaming);
  const showThinking = pending && !streamingLast;

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
        // Agent 消息：仅最新一条的选项/表单可交互；流式进行中不渲染 options/form
        const streaming = Boolean(message.streaming);
        const interactive = !pending && !streaming && message.id === lastAgentId;
        const { reply } = message;
        const showCards = !streaming;
        const documents = showCards ? visibleDocumentRefs(reply.documents, permissions) : [];
        return (
          <div key={message.id} className="message message-agent">
            <div
              className={`message-bubble message-bubble-agent${streaming ? ' message-streaming' : ''}`}
            >
              {reply.text}
            </div>
            {showCards && reply.options && reply.options.length > 0 && (
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
            {showCards && reply.form && (
              <FormCard form={reply.form} interactive={interactive} onSubmit={onSubmitForm} />
            )}
            {documents.length > 0 && (
              <div className="document-card-list">
                {documents.map((document) => (
                  <DocumentCard key={`${document.type}:${document.docNo}`} document={document} />
                ))}
              </div>
            )}
            <div className="message-time">{formatTime(message.createdAt)}</div>
          </div>
        );
      })}
      {showThinking && <div className="message-pending">助手正在思考…</div>}
      {!pending && error && (
        <div className="message-error" role="alert">
          <span className="message-error-text">{error.text}</span>
          <button type="button" className="message-error-retry" onClick={error.retry}>
            重试
          </button>
        </div>
      )}
      <div ref={bottomRef} />
    </div>
  );
}
