/**
 * OptionCard：选项返回协议中 options[] 的渲染组件。
 * 将 Agent 给出的选项渲染为可点击卡片；risk=high 用醒目样式渲染，
 * 对应 Human-in-the-loop 高风险确认场景。
 */
import type { AgentOption } from '../../types/agent';

interface OptionCardProps {
  option: AgentOption;
  /** 是否可交互：仅最新一条 Agent 消息的选项可点击，历史选项只读 */
  interactive: boolean;
  /** 点击选项后回传 option（由上层取 id 回传后端、取 label 展示） */
  onSelect: (option: AgentOption) => void;
}

export function OptionCard({ option, interactive, onSelect }: OptionCardProps) {
  // 协议 v0.1：risk=high → 醒目（danger）样式
  const riskClass = option.risk === 'high' ? ' option-card-danger' : '';
  return (
    <button
      type="button"
      className={`option-card${riskClass}`}
      disabled={!interactive}
      onClick={() => onSelect(option)}
    >
      <span className="option-card-label">{option.label}</span>
      {option.description && <span className="option-card-desc">{option.description}</span>}
    </button>
  );
}
