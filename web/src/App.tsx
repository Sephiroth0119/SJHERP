/**
 * 应用根组件：左侧业务入口菜单 + 主区域（默认 Agent 聊天）。
 */
import { useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { ChatPanel } from './components/chat/ChatPanel';
import { ModulePlaceholder } from './components/ModulePlaceholder';
import { MODULE_NAV_ITEMS, type ModuleKey } from './types/navigation';

export function App() {
  const [activeModule, setActiveModule] = useState<ModuleKey>('agent');

  const activeItem = MODULE_NAV_ITEMS.find((item) => item.key === activeModule);

  return (
    <div className="app-layout">
      <Sidebar active={activeModule} onSelect={setActiveModule} />
      <main className="app-main">
        {activeModule === 'agent' || !activeItem ? (
          <ChatPanel />
        ) : (
          <ModulePlaceholder module={activeItem} />
        )}
      </main>
    </div>
  );
}
