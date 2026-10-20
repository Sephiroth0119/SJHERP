import { useState } from 'react';
import { BomWorkbench } from './BomWorkbench.tsx';
import { DemandPlanWorkbench } from './DemandPlanWorkbench.tsx';
import { MrpWorkbench } from './MrpWorkbench.tsx';
import { RoutingWorkbench } from './RoutingWorkbench.tsx';

type ProductionTab = 'bom' | 'routing' | 'plan' | 'mrp';

export function ProductionWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<ProductionTab>('bom');
  const canWriteBom = permissions.includes('production:bom');
  const canWriteRouting = permissions.includes('production:routing');
  const canWritePlan = permissions.includes('production:plan');
  const canUseMrp = permissions.includes('production:mrp');
  const activeTab: ProductionTab = tab === 'routing'
    ? 'routing'
    : tab === 'plan'
      ? 'plan'
      : tab === 'mrp' && canUseMrp
        ? 'mrp'
        : 'bom';

  return <>
    <div className="memory-tabs purchase-workbench-tabs" role="tablist" aria-label="生产工作台">
      <button type="button" role="tab" aria-selected={activeTab === 'bom'}
        className={activeTab === 'bom' ? 'memory-tab-active' : ''}
        onClick={() => setTab('bom')}>BOM</button>
      <button type="button" role="tab" aria-selected={activeTab === 'routing'}
        className={activeTab === 'routing' ? 'memory-tab-active' : ''}
        onClick={() => setTab('routing')}>工艺路线</button>
      <button type="button" role="tab" aria-selected={activeTab === 'plan'}
        className={activeTab === 'plan' ? 'memory-tab-active' : ''}
        onClick={() => setTab('plan')}>需求计划</button>
      {canUseMrp && <button type="button" role="tab" aria-selected={activeTab === 'mrp'}
        className={activeTab === 'mrp' ? 'memory-tab-active' : ''}
        onClick={() => setTab('mrp')}>MRP</button>}
    </div>
    {renderTab(activeTab, canWriteBom, canWriteRouting, canWritePlan, canUseMrp)}
  </>;
}

function renderTab(
  tab: ProductionTab,
  canWriteBom: boolean,
  canWriteRouting: boolean,
  canWritePlan: boolean,
  canUseMrp: boolean,
) {
  switch (tab) {
    case 'bom':
      return <BomWorkbench canWrite={canWriteBom} />;
    case 'routing':
      return <RoutingWorkbench canWrite={canWriteRouting} />;
    case 'plan':
      return <DemandPlanWorkbench canWrite={canWritePlan} />;
    case 'mrp':
      return <MrpWorkbench canUse={canUseMrp} />;
    default: {
      const exhaustive: never = tab;
      throw new Error(`未知生产页签: ${exhaustive}`);
    }
  }
}
