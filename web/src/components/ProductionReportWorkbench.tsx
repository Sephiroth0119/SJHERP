import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as productionReportApi from '../api/productionReportApi.ts';
import * as workOrderApi from '../api/workOrderApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';
import {
  errorMessage,
  ExecutingWorkOrderPicker,
  ProductPicker,
  WarehousePicker,
} from './productionReferencePickers.tsx';

const PAGE_SIZE = 20;

const statusLabels: Record<productionReportApi.ProductionReport['status'], string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

const actionLabels: Record<productionReportApi.ProductionReportAction, string> = {
  approve: '审核',
  post: '过账入库',
  cancel: '作废',
};

const actionNotices: Record<productionReportApi.ProductionReportAction, string> = {
  approve: '报工单已审核',
  post: '报工单已过账，完工入库并回写工单完工量',
  cancel: '报工单已作废',
};

const actionConfirms: Record<productionReportApi.ProductionReportAction, string> = {
  approve: '确认审核该报工单？审核后可过账完工入库。',
  post: '确认过账？将按已过账领料净料费完工入库并回写工单完工量。报废数量不入库；无料费时后端会拒绝。不自动完工工单。',
  cancel: '确认作废该草稿报工单？作废后不可恢复。',
};

function runReportAction(action: productionReportApi.ProductionReportAction, docNo: string) {
  switch (action) {
    case 'approve':
      return productionReportApi.approveProductionReport(docNo);
    case 'post':
      return productionReportApi.postProductionReport(docNo);
    case 'cancel':
      return productionReportApi.cancelProductionReport(docNo);
    default: {
      const exhaustive: never = action;
      throw new Error(`未知报工动作: ${exhaustive}`);
    }
  }
}

export function ProductionReportWorkbench({
  canUse,
  canUseWo,
}: {
  canUse: boolean;
  canUseWo: boolean;
}) {
  const [items, setItems] = useState<productionReportApi.ProductionReport[]>([]);
  const [selected, setSelected] = useState<productionReportApi.ProductionReport | null>(null);
  const [workOrderDraft, setWorkOrderDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [workOrderFilter, setWorkOrderFilter] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(canUse);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<productionReportApi.ProductionReportForm>(
    productionReportApi.emptyProductionReportForm(),
  );
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [units, setUnits] = useState<catalogApi.Unit[]>([]);
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(new Map());
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const supportVersion = useRef(createRequestGate());
  const warehouseNameVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  const rememberProducts = (products: catalogApi.Product[]) => {
    if (products.length === 0) return;
    setProductNames((current) => {
      const next = new Map(current);
      for (const product of products) {
        next.set(product.id, `${product.name}（${product.code}）`);
      }
      return next;
    });
  };

  const rememberWarehouses = (warehouses: masterDataApi.Warehouse[]) => {
    if (warehouses.length === 0) return;
    setWarehouseNames((current) => {
      const next = new Map(current);
      for (const warehouse of warehouses) {
        next.set(warehouse.id, `${warehouse.name}（${warehouse.code}）`);
      }
      return next;
    });
  };

  const ensureProductNames = (rows: Array<{ productId: number }>) => {
    const ids = [...new Set(rows.map((item) => item.productId).filter((id) => !productNames.has(id)))];
    for (const id of ids) {
      void catalogApi.getProduct(id).then((product) => rememberProducts([product])).catch(() => undefined);
    }
  };

  const ensureWarehouseNames = (docs: Array<{ warehouseId: number }>) => {
    const ids = [...new Set(docs.map((item) => item.warehouseId).filter((id) => !warehouseNames.has(id)))];
    if (ids.length === 0) return;
    const version = warehouseNameVersion.current.next();
    void Promise.all(ids.map((id) => masterDataApi.getWarehouse(id).catch(() => null))).then((warehouses) => {
      if (!warehouseNameVersion.current.isCurrent(version)) return;
      rememberWarehouses(warehouses.filter((warehouse): warehouse is masterDataApi.Warehouse => warehouse !== null));
    });
  };

  useEffect(() => {
    if (!canUse) return;
    const version = supportVersion.current.next();
    void catalogApi.listUnits().then((nextUnits) => {
      if (!supportVersion.current.isCurrent(version)) return;
      setUnits(nextUnits);
    }).catch((cause: unknown) => {
      if (supportVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '计量单位加载失败'));
      }
    });
  }, [canUse]);

  useEffect(() => {
    if (!canUse) {
      listVersion.current.next();
      setLoading(false);
      setItems([]);
      setTotal(0);
      return;
    }
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void productionReportApi.searchProductionReports(workOrderFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureWarehouseNames(result.items);
      ensureProductNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '报工单列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [canUse, workOrderFilter, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (doc: productionReportApi.ProductionReport) => {
    if (!canUse || saving) return;
    const version = detailVersion.current.next();
    setEditing(false);
    setError('');
    setNotice('');
    setSelected(doc);
    ensureWarehouseNames([doc]);
    ensureProductNames([doc]);
    void productionReportApi.getProductionReport(doc.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureWarehouseNames([detail]);
      ensureProductNames([detail]);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '报工单详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setWorkOrderFilter(workOrderDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canUse || saving) return;
    clearSelection();
    setForm(productionReportApi.emptyProductionReportForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canUse || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = productionReportApi.validateProductionReportForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await productionReportApi.createProductionReport(form);
      setSelected(result);
      setEditing(false);
      setNotice(`报工单已创建：${result.docNo}`);
      ensureWarehouseNames([result]);
      ensureProductNames([result]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '报工单创建失败，请确认工单已开工、商品与工单一致、工时行完整'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: productionReportApi.ProductionReportAction) => {
    if (!canUse || !selected || mutationInFlight.current.isLocked()) return;
    if (!productionReportApi.availableProductionReportActions(selected.status).includes(action)) return;
    if (!window.confirm(actionConfirms[action])) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await runReportAction(action, selected.docNo);
      setSelected(result);
      setNotice(actionNotices[action]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, `${actionLabels[action]}失败`));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const applyWorkOrder = (docNo: string, order: workOrderApi.WorkOrder | null) => {
    setForm({
      ...form,
      workOrderDocNo: docNo,
      productId: order?.productId ?? form.productId,
      unitId: order?.unitId ?? form.unitId,
      warehouseId: order?.warehouseId ?? form.warehouseId,
      lines: form.lines.map((line) => ({
        ...line,
        unitId: line.unitId ?? order?.unitId ?? null,
      })),
    });
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canUse) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">生产 / 报工</p>
          <h1>报工与完工入库</h1>
          <p>查询与写操作均须 production:report。当前账号无此权限，未请求报工列表，避免 403 刷屏。</p>
        </div>
      </header>
      <p className="memory-empty" role="status">需要 production:report 权限才能查看和操作报工。</p>
    </section>;
  }

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 报工</p>
        <h1>报工与完工入库</h1>
        <p>一单合一：工时行 + 完工数量；过账时完工入库。工单须已开工。报废数量只记录不入库。数量与工时以字符串传输。</p>
      </div>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={startNew}>新建报工单</button>
      </div>
    </header>
    <div className="purchase-order-filters">
      <label>工单号<input aria-label="报工工单筛选" disabled={saving} value={workOrderDraft}
        placeholder="精确筛选工单号"
        onChange={(event) => setWorkOrderDraft(event.target.value)} /></label>
      <label>状态<select aria-label="报工单状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        {Object.entries(statusLabels).map(([value, label]) =>
          <option key={value} value={value}>{label}</option>)}
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>单号</th><th>工单</th><th>商品</th><th>完工数量</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((doc) => <tr key={doc.docNo}
            className={selected?.docNo === doc.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(doc)}>{doc.docNo}</button></td>
            <td>{doc.workOrderDocNo}</td>
            <td>{productName(doc.productId)}</td>
            <td>{productionReportApi.formatQuantityDisplay(doc.completedQty)}</td>
            <td><StatusBadge status={doc.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载报工单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无报工单。可从右上角新建。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <ReportFormView form={form} setForm={setForm} saving={saving} canUseWo={canUseWo}
          units={units} productName={productName} warehouseName={warehouseName}
          rememberProducts={rememberProducts} rememberWarehouses={rememberWarehouses}
          onWorkOrder={applyWorkOrder} onSave={save} onCancel={clearSelection} />
          : selected ? <ReportDetails doc={selected} canUse={canUse} saving={saving}
            productName={productName} unitName={unitName} warehouseName={warehouseName}
            onTransition={(action) => void transition(action)} />
            : <p className="memory-empty">选择左侧报工单查看详情，或新建。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: productionReportApi.ProductionReport['status'] }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface ReportFormProps {
  form: productionReportApi.ProductionReportForm;
  setForm: (form: productionReportApi.ProductionReportForm) => void;
  saving: boolean;
  canUseWo: boolean;
  units: catalogApi.Unit[];
  productName: (id: number) => string;
  warehouseName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  rememberWarehouses: (warehouses: masterDataApi.Warehouse[]) => void;
  onWorkOrder: (docNo: string, order: workOrderApi.WorkOrder | null) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function ReportFormView({
  form, setForm, saving, canUseWo, units, productName, warehouseName,
  rememberProducts, rememberWarehouses, onWorkOrder, onSave, onCancel,
}: ReportFormProps) {
  const updateLine = (index: number, patch: Partial<productionReportApi.ProductionReportLineForm>) => {
    setForm({
      ...form,
      lines: form.lines.map((line, current) => current === index ? { ...line, ...patch } : line),
    });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建报工单</h2>
    <p>必填：已开工工单、入库仓、与工单一致的商品、完工数量大于 0、至少一行工时。报废数量可空，不入库。</p>
    <ExecutingWorkOrderPicker canUseWo={canUseWo} disabled={saving} value={form.workOrderDocNo}
      heading="选择已开工工单（GET /api/production/work-orders?status=EXECUTING）"
      onChange={onWorkOrder} onProducts={rememberProducts} onWarehouses={rememberWarehouses} />
    <ProductPicker disabled={saving} selectedIds={form.productId === null ? [] : [form.productId]}
      heading="生产商品（须与工单一致，GET /api/catalog/products）"
      onSelect={(product) => {
        rememberProducts([product]);
        setForm({
          ...form,
          productId: product.id,
          unitId: form.unitId ?? product.baseUnitId,
          lines: form.lines.map((line) => ({
            ...line,
            unitId: line.unitId ?? product.baseUnitId,
          })),
        });
      }}
      onOptions={rememberProducts} />
    <p>{form.productId ? `当前商品：${productName(form.productId)}` : '尚未选择商品'}</p>
    <WarehousePicker label="完工入库仓库（GET /api/warehouse/warehouses）" value={form.warehouseId}
      disabled={saving} onChange={(warehouseId) => setForm({ ...form, warehouseId })}
      onOptions={rememberWarehouses} />
    <p>{form.warehouseId ? `当前仓库：${warehouseName(form.warehouseId)}` : '尚未选择入库仓'}</p>
    <label>完工数量
      <input required inputMode="decimal" aria-label="完工数量" disabled={saving}
        pattern={productionReportApi.positiveDecimalPattern.source} value={form.completedQty}
        onChange={(event) => setForm({ ...form, completedQty: event.target.value })} /></label>
    <label>报废数量
      <input inputMode="decimal" aria-label="报废数量" disabled={saving} value={form.scrapQty}
        placeholder="可空，不入库"
        onChange={(event) => setForm({ ...form, scrapQty: event.target.value })} /></label>
    <label>计量单位
      <select required aria-label="计量单位" disabled={saving} value={form.unitId ?? ''}
        onChange={(event) => setForm({
          ...form,
          unitId: event.target.value ? Number.parseInt(event.target.value, 10) : null,
        })}>
        <option value="">请选择</option>
        {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.name}</option>)}
      </select></label>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>工序序号</th><th>工序名</th><th>工作中心</th><th>工时</th><th>报工数量</th><th>单位</th><th></th>
      </tr></thead><tbody>
        {form.lines.map((line, index) => <tr key={index}>
          <td><input inputMode="numeric" aria-label={`第 ${index + 1} 行工序序号`} disabled={saving}
            value={line.operationSeqNo} placeholder="可空"
            onChange={(event) => updateLine(index, { operationSeqNo: event.target.value })} /></td>
          <td><input aria-label={`第 ${index + 1} 行工序名`} disabled={saving} value={line.operationName}
            placeholder="可空"
            onChange={(event) => updateLine(index, { operationName: event.target.value })} /></td>
          <td><input aria-label={`第 ${index + 1} 行工作中心`} disabled={saving} value={line.workCenter}
            placeholder="可空"
            onChange={(event) => updateLine(index, { workCenter: event.target.value })} /></td>
          <td><input required inputMode="decimal" aria-label={`第 ${index + 1} 行工时`} disabled={saving}
            pattern={productionReportApi.positiveDecimalPattern.source} value={line.reportedHours}
            onChange={(event) => updateLine(index, { reportedHours: event.target.value })} /></td>
          <td><input inputMode="decimal" aria-label={`第 ${index + 1} 行报工数量`} disabled={saving}
            value={line.reportedQty} placeholder="可空"
            onChange={(event) => updateLine(index, { reportedQty: event.target.value })} /></td>
          <td><select required aria-label={`第 ${index + 1} 行工时单位`} disabled={saving} value={line.unitId ?? ''}
            onChange={(event) => updateLine(index, {
              unitId: event.target.value ? Number.parseInt(event.target.value, 10) : null,
            })}>
            <option value="">请选择</option>
            {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.name}</option>)}
          </select></td>
          <td><button type="button" className="memory-button" disabled={saving || form.lines.length <= 1}
            onClick={() => setForm({ ...form, lines: form.lines.filter((_, current) => current !== index) })}>
            移除</button></td>
        </tr>)}
      </tbody></table>
    </div>
    <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving}
        onClick={() => setForm({
          ...form,
          lines: [...form.lines, productionReportApi.emptyProductionReportLine()],
        })}>增行</button>
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '创建中…' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface ReportDetailsProps {
  doc: productionReportApi.ProductionReport;
  canUse: boolean;
  saving: boolean;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  warehouseName: (id: number) => string;
  onTransition: (action: productionReportApi.ProductionReportAction) => void;
}

function ReportDetails({
  doc, canUse, saving, productName, unitName, warehouseName, onTransition,
}: ReportDetailsProps) {
  const actions = productionReportApi.availableProductionReportActions(doc.status);
  return <>
    <StatusBadge status={doc.status} />
    <h2>{doc.docNo}</h2>
    <p>{productName(doc.productId)}</p>
    <dl>
      <dt>工单</dt><dd>{doc.workOrderDocNo}</dd>
      <dt>入库仓</dt><dd>{warehouseName(doc.warehouseId)}</dd>
      <dt>完工数量</dt><dd>{productionReportApi.formatQuantityDisplay(doc.completedQty)}（{unitName(doc.unitId)}）</dd>
      <dt>报废数量</dt><dd>{productionReportApi.formatQuantityDisplay(doc.scrapQty)}</dd>
      <dt>入库成本</dt><dd>{doc.inboundCost == null ? '—' : productionReportApi.formatQuantityDisplay(doc.inboundCost)}</dd>
      <dt>备注</dt><dd>{doc.remark || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>行</th><th>工序</th><th>工作中心</th><th>工时</th><th>报工数量</th>
      </tr></thead><tbody>
        {doc.lines.map((line) => <tr key={line.lineNo}>
          <td>{line.lineNo}</td>
          <td>{line.operationSeqNo ?? '—'} {line.operationName || ''}</td>
          <td>{line.workCenter || '—'}</td>
          <td>{productionReportApi.formatQuantityDisplay(line.reportedHours)}（{unitName(line.unitId)}）</td>
          <td>{line.reportedQty == null ? '—' : productionReportApi.formatQuantityDisplay(line.reportedQty)}</td>
        </tr>)}
      </tbody></table>
    </div>
    {canUse && actions.length > 0 && <div className="customer-actions">
      {actions.map((action) => <button type="button" key={action}
        className={action === 'cancel' ? 'memory-button' : 'memory-button memory-button-primary'}
        disabled={saving} onClick={() => onTransition(action)}>
        {saving ? '处理中…' : actionLabels[action]}
      </button>)}
    </div>}
    {actions.length === 0 && <p className="memory-empty">当前状态无可执行流转。</p>}
  </>;
}
