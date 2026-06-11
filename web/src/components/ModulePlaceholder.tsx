/**
 * 传统业务入口占位页：信息架构先行，具体单据列表/表单后续迭代。
 */
import type { ModuleNavItem } from '../types/navigation';

interface ModulePlaceholderProps {
  module: ModuleNavItem;
}

export function ModulePlaceholder({ module }: ModulePlaceholderProps) {
  return (
    <section className="module-placeholder">
      <h1>{module.label}</h1>
      <p className="module-placeholder-desc">{module.description}</p>
      <p className="module-placeholder-hint">
        传统业务入口建设中。当前推荐通过左侧「Agent 助手」以对话方式完成{module.label}业务。
      </p>
    </section>
  );
}
