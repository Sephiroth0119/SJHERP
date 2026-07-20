/**
 * 左侧导航：Agent 主入口 + 传统业务入口（采购/销售/库存/生产/财务）。
 */
import { MODULE_NAV_ITEMS, type ModuleKey } from '../types/navigation';
import { filterNavItems } from '../security/access';
import { AGENT_PROTOCOL_VERSION } from '../types/agent';
import { USE_MOCK } from '../api/chatApi';

interface SidebarProps {
  active: ModuleKey;
  onSelect: (key: ModuleKey) => void;
  permissions: string[];
}

export function Sidebar({ active, onSelect, permissions }: SidebarProps) {
  const visibleItems = filterNavItems(MODULE_NAV_ITEMS, permissions);

  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <span className="sidebar-brand-name">SJHERP</span>
        <span className="sidebar-brand-sub">Agent 原生 ERP</span>
      </div>
      <nav className="sidebar-nav">
        {visibleItems.map((item) => (
          <button
            key={item.key}
            type="button"
            className={`sidebar-item${item.key === active ? ' sidebar-item-active' : ''}`}
            onClick={() => onSelect(item.key)}
            title={item.description}
          >
            {item.label}
          </button>
        ))}
      </nav>
      <div className="sidebar-footer">
        协议版本 v{AGENT_PROTOCOL_VERSION}
        {USE_MOCK ? ' · mock 模式' : ''}
      </div>
    </aside>
  );
}
