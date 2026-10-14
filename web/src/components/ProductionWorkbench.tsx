import { useState } from 'react';
import { BomWorkbench } from './BomWorkbench.tsx';
import { RoutingWorkbench } from './RoutingWorkbench.tsx';

type ProductionTab = 'bom' | 'routing';

export function ProductionWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<ProductionTab>('bom');
  const canWriteBom = permissions.includes('production:bom');
  const canWriteRouting = permissions.includes('production:routing');
  const activeTab: ProductionTab = tab === 'routing' ? 'routing' : 'bom';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="生产工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'bom'}
        className={activeTab === 'bom' ? 'memory-tab-active' : ''}
        onClick={() => setTab('bom')}>BOM</button>
      <button type="button" role="tab" aria-selected={activeTab === 'routing'}
        className={activeTab === 'routing' ? 'memory-tab-active' : ''}
        onClick={() => setTab('routing')}>工艺路线</button>
    </div>
    {renderTab(activeTab, canWriteBom, canWriteRouting)}
  </>;
}

function renderTab(tab: ProductionTab, canWriteBom: boolean, canWriteRouting: boolean) {
  switch (tab) {
    case 'bom':
      return <BomWorkbench canWrite={canWriteBom} />;
    case 'routing':
      return <RoutingWorkbench canWrite={canWriteRouting} />;
    default: {
      const exhaustive: never = tab;
      throw new Error(`未知生产页签: ${exhaustive}`);
    }
  }
}
