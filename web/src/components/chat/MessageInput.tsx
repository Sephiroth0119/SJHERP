/**
 * 消息输入框：自由文本输入，Enter 发送（Shift+Enter 换行预留给后续多行输入）。
 */
import { useState, type FormEvent } from 'react';

interface MessageInputProps {
  disabled: boolean;
  onSend: (text: string) => void;
}

export function MessageInput({ disabled, onSend }: MessageInputProps) {
  const [text, setText] = useState('');

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText('');
  };

  return (
    <form className="message-input" onSubmit={handleSubmit}>
      <input
        type="text"
        value={text}
        placeholder="输入业务需求，如：给华东金属下一张 500 张不锈钢板的采购单"
        disabled={disabled}
        onChange={(e) => setText(e.target.value)}
      />
      <button type="submit" disabled={disabled || text.trim() === ''}>
        发送
      </button>
    </form>
  );
}
