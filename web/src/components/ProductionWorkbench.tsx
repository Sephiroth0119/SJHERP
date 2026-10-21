import { useState } from 'react';
import { BomWorkbench } from './BomWorkbench.tsx';
import { CostSettlementWorkbench } from './CostSettlementWorkbench.tsx';
import { DemandPlanWorkbench } from './DemandPlanWorkbench.tsx';
import { MaterialIssueWorkbench } from './MaterialIssueWorkbench.tsx';
import { MaterialReturnWorkbench } from './MaterialReturnWorkbench.tsx';
import { MrpWorkbench } from './MrpWorkbench.tsx';
import { ProductionReportWorkbench } from './ProductionReportWorkbench.tsx';
import { RoutingWorkbench } from './RoutingWorkbench.tsx';
import { WorkOrderWorkbench } from './WorkOrderWorkbench.tsx';

type ProductionTab = 'bom' | 'routing' | 'plan' | 'mrp' | 'wo' | 'issue' | 'return' | 'report' | 'cost';

export function ProductionWorkbench({ permissions }: { permissions: string[] }) {
  const [tab, setTab] = useState<ProductionTab>('bom');
  const canWriteBom = permissions.includes('production:bom');
  const canWriteRouting = permissions.includes('production:routing');
  const canWritePlan = permissions.includes('production:plan');
  const canUseMrp = permissions.includes('production:mrp');
  const canUseWo = permissions.includes('production:wo');
  const canUseMaterial = permissions.includes('production:material');
  const canUseReport = permissions.includes('production:report');
  const canUseCost = permissions.includes('production:cost');
  const activeTab: ProductionTab = tab === 'routing'
    ? 'routing'
    : tab === 'plan'
      ? 'plan'
      : tab === 'mrp' && canUseMrp
        ? 'mrp'
        : tab === 'wo' && canUseWo
          ? 'wo'
          : tab === 'issue' && canUseMaterial
            ? 'issue'
            : tab === 'return' && canUseMaterial
              ? 'return'
              : tab === 'report' && canUseReport
                ? 'report'
                : tab === 'cost' && canUseCost
                  ? 'cost'
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
      {canUseWo && <button type="button" role="tab" aria-selected={activeTab === 'wo'}
        className={activeTab === 'wo' ? 'memory-tab-active' : ''}
        onClick={() => setTab('wo')}>工单</button>}
      {canUseMaterial && <button type="button" role="tab" aria-selected={activeTab === 'issue'}
        className={activeTab === 'issue' ? 'memory-tab-active' : ''}
        onClick={() => setTab('issue')}>领料</button>}
      {canUseMaterial && <button type="button" role="tab" aria-selected={activeTab === 'return'}
        className={activeTab === 'return' ? 'memory-tab-active' : ''}
        onClick={() => setTab('return')}>退料</button>}
      {canUseReport && <button type="button" role="tab" aria-selected={activeTab === 'report'}
        className={activeTab === 'report' ? 'memory-tab-active' : ''}
        onClick={() => setTab('report')}>报工</button>}
      {canUseCost && <button type="button" role="tab" aria-selected={activeTab === 'cost'}
        className={activeTab === 'cost' ? 'memory-tab-active' : ''}
        onClick={() => setTab('cost')}>成本结转</button>}
    </div>
    {renderTab(
      activeTab,
      canWriteBom,
      canWriteRouting,
      canWritePlan,
      canUseMrp,
      canUseWo,
      canUseMaterial,
      canUseReport,
      canUseCost,
    )}
  </>;
}

function renderTab(
  tab: ProductionTab,
  canWriteBom: boolean,
  canWriteRouting: boolean,
  canWritePlan: boolean,
  canUseMrp: boolean,
  canUseWo: boolean,
  canUseMaterial: boolean,
  canUseReport: boolean,
  canUseCost: boolean,
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
    case 'wo':
      return <WorkOrderWorkbench canUse={canUseWo} canUseMrp={canUseMrp} />;
    case 'issue':
      return <MaterialIssueWorkbench canUse={canUseMaterial} canUseWo={canUseWo} />;
    case 'return':
      return <MaterialReturnWorkbench canUse={canUseMaterial} />;
    case 'report':
      return <ProductionReportWorkbench canUse={canUseReport} canUseWo={canUseWo} />;
    case 'cost':
      return <CostSettlementWorkbench canUse={canUseCost} canUseWo={canUseWo} />;
    default: {
      const exhaustive: never = tab;
      throw new Error(`未知生产页签: ${exhaustive}`);
    }
  }
}
