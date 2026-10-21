import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as mrpApi from '../api/mrpApi.ts';
import * as workOrderApi from '../api/workOrderApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<workOrderApi.WorkOrderStatus, string> = {
  DRAFT: '计划',
  APPROVED: '下达',
  EXECUTING: '生产中',
  COMPLETED: '完工',
  CANCELLED: '作废',
  REVERSED: '冲销',
};

const sourceLabels: Record<workOrderApi.WorkOrderSourceType, string> = {
  MANUAL: '手工',
  MRP_SUGGESTION: 'MRP 建议',
};

const actionLabels: Record<workOrderApi.WorkOrderAction, string> = {
  release: '下达',
  start: '开工',
  complete: '完工',
  cancel: '作废',
  reverse: '冲销',
};

const actionNotices: Record<workOrderApi.WorkOrderAction, string> = {
  release: '工单已下达',
  start: '工单已开工',
  complete: '工单已完工',
  cancel: '工单已作废',
  reverse: '工单已冲销',
};

const actionConfirms: Record<workOrderApi.WorkOrderAction, string> = {
  release: '确认下达该工单？下达后可开工或冲销。',
  start: '确认开工？开工后进入生产中，本页不可再冲销。',
  complete: '确认完工？本页只推进工单状态，不触发后续执行页。',
  cancel: '确认作废该计划工单？作废后不可恢复。',
  reverse: '确认冲销该已下达、未开工工单？本页冲销只改状态。',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

function runWorkOrderAction(action: workOrderApi.WorkOrderAction, docNo: string) {
  switch (action) {
    case 'release':
      return workOrderApi.releaseWorkOrder(docNo);
    case 'start':
      return workOrderApi.startWorkOrder(docNo);
    case 'complete':
      return workOrderApi.completeWorkOrder(docNo);
    case 'cancel':
      return workOrderApi.cancelWorkOrder(docNo);
    case 'reverse':
      return workOrderApi.reverseWorkOrder(docNo);
    default: {
      const exhaustive: never = action;
      throw new Error(`未知工单动作: ${exhaustive}`);
    }
  }
}

type EditorMode = 'manual' | 'from-mrp' | null;

export function WorkOrderWorkbench({
  canUse,
  canUseMrp,
}: {
  canUse: boolean;
  canUseMrp: boolean;
}) {
  const [items, setItems] = useState<workOrderApi.WorkOrderSummary[]>([]);
  const [selected, setSelected] = useState<workOrderApi.WorkOrder | null>(null);
  const [productFilterDraft, setProductFilterDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [productFilter, setProductFilter] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(canUse);
  const [saving, setSaving] = useState(false);
  const [editor, setEditor] = useState<EditorMode>(null);
  const [form, setForm] = useState<workOrderApi.WorkOrderForm>(workOrderApi.emptyWorkOrderForm());
  const [fromMrpForm, setFromMrpForm] = useState<workOrderApi.WorkOrderFromMrpForm>(
    workOrderApi.emptyFromMrpForm(),
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

  const ensureProductNames = (orders: Array<{ productId: number }>) => {
    const ids = [...new Set(orders.map((item) => item.productId).filter((id) => !productNames.has(id)))];
    for (const id of ids) {
      void catalogApi.getProduct(id).then((product) => rememberProducts([product])).catch(() => undefined);
    }
  };

  const ensureWarehouseNames = (orders: Array<{ warehouseId?: number | null }>) => {
    const ids = [...new Set(orders
      .map((item) => item.warehouseId)
      .filter((id): id is number => typeof id === 'number' && !warehouseNames.has(id)))];
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
    void workOrderApi.searchWorkOrders(productFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureProductNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '工单列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [canUse, productFilter, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditor(null);
  };

  const choose = (order: workOrderApi.WorkOrderSummary) => {
    if (!canUse || saving) return;
    const version = detailVersion.current.next();
    setEditor(null);
    setError('');
    setNotice('');
    setSelected({
      ...order,
      completedQty: '',
      bomVersion: null,
      routingVersion: null,
      warehouseId: null,
      mrpRunDocNo: null,
      plannedStartDate: null,
      plannedEndDate: null,
      remark: null,
    });
    ensureProductNames([order]);
    void workOrderApi.getWorkOrder(order.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureProductNames([detail]);
      ensureWarehouseNames([detail]);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '工单详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setProductFilter(productFilterDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startManual = () => {
    if (!canUse || saving) return;
    clearSelection();
    setForm(workOrderApi.emptyWorkOrderForm());
    setEditor('manual');
    setError('');
    setNotice('');
  };

  const startFromMrp = () => {
    if (!canUse || !canUseMrp || saving) return;
    clearSelection();
    setFromMrpForm(workOrderApi.emptyFromMrpForm());
    setEditor('from-mrp');
    setError('');
    setNotice('');
  };

  const saveManual = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canUse || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = workOrderApi.validateWorkOrderForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await workOrderApi.createWorkOrder(form);
      setSelected(result);
      setEditor(null);
      setNotice(`工单已创建：${result.docNo}`);
      ensureProductNames([result]);
      ensureWarehouseNames([result]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '工单创建失败，请检查商品、数量和单位'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const saveFromMrp = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canUse || !canUseMrp || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = workOrderApi.validateFromMrpForm(fromMrpForm);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await workOrderApi.createWorkOrderFromMrp(fromMrpForm);
      setSelected(result);
      setEditor(null);
      setNotice(`已从 MRP 建议创建工单：${result.docNo}`);
      ensureProductNames([result]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '从 MRP 建议建单失败，请确认运行号与生产建议'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: workOrderApi.WorkOrderAction) => {
    if (!canUse || !selected || mutationInFlight.current.isLocked()) return;
    if (!workOrderApi.availableWorkOrderActions(selected.status).includes(action)) return;
    if (!window.confirm(actionConfirms[action])) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await runWorkOrderAction(action, selected.docNo);
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

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canUse) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">生产 / 工单</p>
          <h1>生产工单</h1>
          <p>查询与写操作均须 production:wo。当前账号无此权限，未请求工单列表，避免 403 刷屏。</p>
        </div>
      </header>
      <p className="memory-empty" role="status">需要 production:wo 权限才能查看和操作工单。</p>
    </section>;
  }

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 工单</p>
        <h1>生产工单</h1>
        <p>手工建单或从 MRP 生产建议建单，再按状态下达、开工、完工、作废或冲销。查询与流转均须 production:wo。从 MRP 建单还需 production:mrp 以读取运行与建议。本页只做工单头与状态流转。</p>
      </div>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={startManual}>手工建单</button>
        {canUseMrp && <button type="button" className="memory-button" disabled={saving}
          onClick={startFromMrp}>从 MRP 建单</button>}
      </div>
    </header>
    <div className="purchase-order-filters">
      <label>状态<select aria-label="工单状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="DRAFT">计划</option>
        <option value="APPROVED">下达</option>
        <option value="EXECUTING">生产中</option>
        <option value="COMPLETED">完工</option>
        <option value="CANCELLED">作废</option>
        <option value="REVERSED">冲销</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
      {productFilterDraft !== null && <button type="button" className="memory-button" disabled={saving}
        onClick={() => setProductFilterDraft(null)}>清除商品筛选</button>}
    </div>
    <ProductPicker disabled={saving} selectedIds={productFilterDraft === null ? [] : [productFilterDraft]}
      heading="按商品筛选（GET /api/catalog/products，查询时带 productId）"
      onSelect={(product) => { rememberProducts([product]); setProductFilterDraft(product.id); }}
      onOptions={rememberProducts} />
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>单号</th><th>商品</th><th>计划数量</th><th>来源</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((order) => <tr key={order.docNo}
            className={selected?.docNo === order.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(order)}>{order.docNo}</button></td>
            <td>{productName(order.productId)}</td>
            <td>{workOrderApi.formatQuantityDisplay(order.plannedQty)}</td>
            <td>{sourceLabels[order.sourceType]}</td>
            <td><StatusBadge status={order.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载工单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无工单。可从右上角手工建单{canUseMrp ? '或从 MRP 生产建议建单。' : '。'}
        </p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editor === 'manual' ? <ManualFormView form={form} setForm={setForm} saving={saving}
          units={units} productName={productName} warehouseName={warehouseName}
          rememberProducts={rememberProducts} rememberWarehouses={rememberWarehouses}
          onSave={saveManual} onCancel={clearSelection} />
          : editor === 'from-mrp' ? <FromMrpFormView form={fromMrpForm} setForm={setFromMrpForm}
            saving={saving} productName={productName} rememberProducts={rememberProducts}
            onSave={saveFromMrp} onCancel={clearSelection} />
            : selected ? <WorkOrderDetails order={selected} canUse={canUse} saving={saving}
              productName={productName} unitName={unitName} warehouseName={warehouseName}
              onTransition={(action) => void transition(action)} />
              : <p className="memory-empty">选择左侧工单查看详情，或新建。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: workOrderApi.WorkOrderStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface ManualFormProps {
  form: workOrderApi.WorkOrderForm;
  setForm: (form: workOrderApi.WorkOrderForm) => void;
  saving: boolean;
  units: catalogApi.Unit[];
  productName: (id: number) => string;
  warehouseName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  rememberWarehouses: (warehouses: masterDataApi.Warehouse[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function ManualFormView({
  form, setForm, saving, units, productName, warehouseName,
  rememberProducts, rememberWarehouses, onSave, onCancel,
}: ManualFormProps) {
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>手工建单</h2>
    <p>必填：商品、计划数量大于 0、计量单位。仓库、BOM/工艺版本、计划日期可空。</p>
    <ProductPicker disabled={saving} selectedIds={form.productId === null ? [] : [form.productId]}
      heading="选择生产商品"
      onSelect={(product) => {
        rememberProducts([product]);
        setForm({
          ...form,
          productId: product.id,
          unitId: form.unitId ?? product.baseUnitId,
        });
      }}
      onOptions={rememberProducts} />
    <p>{form.productId ? `当前商品：${productName(form.productId)}` : '尚未选择商品'}</p>
    <label>计划数量
      <input required inputMode="decimal" aria-label="计划数量" disabled={saving}
        pattern={workOrderApi.positiveDecimalPattern.source} value={form.plannedQty}
        onChange={(event) => setForm({ ...form, plannedQty: event.target.value })} /></label>
    <label>计量单位
      <select required aria-label="计量单位" disabled={saving} value={form.unitId ?? ''}
        onChange={(event) => setForm({
          ...form,
          unitId: event.target.value ? Number.parseInt(event.target.value, 10) : null,
        })}>
        <option value="">请选择</option>
        {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.name}</option>)}
      </select></label>
    <WarehousePicker label="生产仓库（可选，GET /api/warehouse/warehouses）" value={form.warehouseId}
      disabled={saving} onChange={(warehouseId) => setForm({ ...form, warehouseId })}
      onOptions={rememberWarehouses} />
    <p>{form.warehouseId ? `当前仓库：${warehouseName(form.warehouseId)}` : '未指定仓库'}</p>
    <label>BOM 版本
      <input inputMode="numeric" aria-label="BOM 版本" disabled={saving} value={form.bomVersion}
        placeholder="可空" onChange={(event) => setForm({ ...form, bomVersion: event.target.value })} /></label>
    <label>工艺路线版本
      <input inputMode="numeric" aria-label="工艺路线版本" disabled={saving} value={form.routingVersion}
        placeholder="可空" onChange={(event) => setForm({ ...form, routingVersion: event.target.value })} /></label>
    <label>计划开始
      <input type="date" aria-label="计划开始日期" disabled={saving} value={form.plannedStartDate}
        onChange={(event) => setForm({ ...form, plannedStartDate: event.target.value })} /></label>
    <label>计划结束
      <input type="date" aria-label="计划结束日期" disabled={saving} value={form.plannedEndDate}
        onChange={(event) => setForm({ ...form, plannedEndDate: event.target.value })} /></label>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '创建中…' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface FromMrpFormProps {
  form: workOrderApi.WorkOrderFromMrpForm;
  setForm: (form: workOrderApi.WorkOrderFromMrpForm) => void;
  saving: boolean;
  productName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function FromMrpFormView({
  form, setForm, saving, productName, rememberProducts, onSave, onCancel,
}: FromMrpFormProps) {
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>从 MRP 生产建议建单</h2>
    <p>先选 MRP 运行，再选该运行中的 PRODUCTION 建议。计划数量与单位由后端按净需求和基本单位写入。</p>
    <MrpSuggestionPicker form={form} setForm={setForm} saving={saving}
      productName={productName} rememberProducts={rememberProducts} />
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '创建中…' : '从建议创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  order: workOrderApi.WorkOrder;
  canUse: boolean;
  saving: boolean;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  warehouseName: (id: number) => string;
  onTransition: (action: workOrderApi.WorkOrderAction) => void;
}

function WorkOrderDetails({
  order, canUse, saving, productName, unitName, warehouseName, onTransition,
}: DetailsProps) {
  const actions = workOrderApi.availableWorkOrderActions(order.status);
  return <>
    <StatusBadge status={order.status} />
    <h2>{order.docNo}</h2>
    <p>{productName(order.productId)}</p>
    <dl>
      <dt>计划数量</dt><dd>{workOrderApi.formatQuantityDisplay(order.plannedQty)}（{unitName(order.unitId)}）</dd>
      <dt>完工数量</dt><dd>{order.completedQty === '' ? '—' : workOrderApi.formatQuantityDisplay(order.completedQty)}</dd>
      <dt>来源</dt><dd>{sourceLabels[order.sourceType]}{order.mrpRunDocNo ? ` · ${order.mrpRunDocNo}` : ''}</dd>
      <dt>仓库</dt><dd>{order.warehouseId ? warehouseName(order.warehouseId) : '—'}</dd>
      <dt>BOM 版本</dt><dd>{order.bomVersion ?? '—'}</dd>
      <dt>工艺路线版本</dt><dd>{order.routingVersion ?? '—'}</dd>
      <dt>计划日期</dt>
      <dd>{order.plannedStartDate || '—'} ~ {order.plannedEndDate || '—'}</dd>
      <dt>备注</dt><dd>{order.remark || '—'}</dd>
      <dt>创建</dt><dd>{order.createdBy || '—'}</dd>
    </dl>
    {canUse && actions.length > 0 && <div className="customer-actions">
      {actions.map((action) => <button type="button" key={action}
        className={action === 'cancel' || action === 'reverse'
          ? 'memory-button'
          : 'memory-button memory-button-primary'}
        disabled={saving} onClick={() => onTransition(action)}>
        {saving ? '处理中…' : actionLabels[action]}
      </button>)}
    </div>}
    {actions.length === 0 && <p className="memory-empty">当前状态无可执行流转。</p>}
  </>;
}

function MrpSuggestionPicker({
  form, setForm, saving, productName, rememberProducts,
}: {
  form: workOrderApi.WorkOrderFromMrpForm;
  setForm: (form: workOrderApi.WorkOrderFromMrpForm) => void;
  saving: boolean;
  productName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
}) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [runs, setRuns] = useState<mrpApi.MrpRunSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<mrpApi.MrpRun | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const listGate = useRef(createRequestGate());
  const detailGate = useRef(createRequestGate());

  useEffect(() => {
    const version = listGate.current.next();
    setLoading(true);
    setError('');
    void mrpApi.searchMrpRuns(page, PAGE_SIZE).then((result) => {
      if (!listGate.current.isCurrent(version)) return;
      setRuns(result.items);
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (listGate.current.isCurrent(version)) {
        setError(errorMessage(cause, 'MRP 运行历史加载失败'));
      }
    }).finally(() => {
      if (listGate.current.isCurrent(version)) setLoading(false);
    });
  }, [page, refreshKey]);

  useEffect(() => {
    if (!form.mrpRunDocNo) {
      detailGate.current.next();
      setDetail(null);
      return;
    }
    const version = detailGate.current.next();
    setDetailLoading(true);
    void mrpApi.getMrpRun(form.mrpRunDocNo).then((run) => {
      if (!detailGate.current.isCurrent(version)) return;
      setDetail(run);
      const ids = [...new Set(run.suggestions.filter((item) => item.type === 'PRODUCTION').map((item) => item.productId))];
      void Promise.all(ids.map((id) => catalogApi.getProduct(id).catch(() => null))).then((products) => {
        rememberProducts(products.filter((product): product is catalogApi.Product => product !== null));
      });
    }).catch((cause: unknown) => {
      if (!detailGate.current.isCurrent(version)) return;
      setDetail(null);
      setError(errorMessage(cause, 'MRP 运行详情加载失败'));
    }).finally(() => {
      if (detailGate.current.isCurrent(version)) setDetailLoading(false);
    });
  }, [form.mrpRunDocNo]);

  const production = (detail?.suggestions ?? []).filter((item) => item.type === 'PRODUCTION');

  return <div className="reference-picker">
    <strong>选择 MRP 运行（GET /api/production/mrp/runs）</strong>
    <div className="reference-picker-search">
      <button type="button" className="memory-button" disabled={saving || loading}
        onClick={() => { setPage(1); setRefreshKey((value) => value + 1); }}>刷新运行</button>
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label="MRP 运行">
      {loading ? <span>正在加载 MRP 运行…</span>
        : runs.length === 0 ? <span>暂无 MRP 运行，请先到 MRP 页触发运算。</span>
          : runs.map((run) => <button type="button" role="option" key={run.docNo}
            aria-selected={form.mrpRunDocNo === run.docNo}
            className={form.mrpRunDocNo === run.docNo ? 'reference-picker-selected' : ''}
            disabled={saving} onClick={() => setForm({ mrpRunDocNo: run.docNo, productId: null })}>
            {run.docNo}<span>{run.runAt}</span>
          </button>)}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={saving || loading || page <= 1}
        onClick={() => setPage((value) => value - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={saving || loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((value) => value + 1)}>下一页</button>
    </div>}
    {form.mrpRunDocNo && <section className="memory-group">
      <header><strong>生产建议</strong><span>{form.mrpRunDocNo}</span></header>
      {detailLoading ? <p className="memory-empty" role="status">正在加载建议行…</p>
        : production.length === 0 ? <p className="memory-empty">该运行没有 PRODUCTION 建议。</p>
          : <div className="memory-table-wrap purchase-order-lines">
              <table className="memory-table"><thead><tr>
                <th>商品</th><th>层级</th><th>净需求</th>
              </tr></thead><tbody>
                {production.map((item) => <tr key={`${item.productId}-${item.level}`}>
                  <td><button type="button" className="table-row-button" disabled={saving}
                    onClick={() => setForm({ ...form, productId: item.productId })}>
                    {productName(item.productId)}
                    {form.productId === item.productId ? '（已选）' : ''}
                  </button></td>
                  <td>L{item.level}</td>
                  <td>{workOrderApi.formatQuantityDisplay(item.netRequirement)}</td>
                </tr>)}
              </tbody></table>
            </div>}
    </section>}
  </div>;
}

interface ProductPickerProps {
  disabled: boolean;
  selectedIds: Array<number | null>;
  heading: string;
  onSelect: (product: catalogApi.Product) => void;
  onOptions: (items: catalogApi.Product[]) => void;
}

function ProductPicker({ disabled, selectedIds, heading, onSelect, onOptions }: ProductPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<catalogApi.Product[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const version = useRef(createRequestGate());

  useEffect(() => {
    const current = version.current.next();
    setLoading(true);
    setError('');
    void catalogApi.searchProducts(keyword, 'ENABLED', page).then((result) => {
      if (!version.current.isCurrent(current)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (version.current.isCurrent(current)) {
        setError(errorMessage(cause, '商品加载失败'));
      }
    }).finally(() => {
      if (version.current.isCurrent(current)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>{heading}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${heading}关键词`} placeholder="输入商品编码、名称或条码"
        disabled={disabled || loading} value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            setKeyword(draft);
            setPage(1);
            setRefreshKey((value) => value + 1);
          }
        }} />
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => { setKeyword(draft); setPage(1); setRefreshKey((value) => value + 1); }}>查找</button>
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={heading}>
      {loading ? <span>正在加载商品…</span>
        : items.length === 0 ? <span>未找到启用中的商品</span>
          : items.map((product) => {
            const selected = selectedIds.includes(product.id);
            return <button type="button" role="option" aria-selected={selected}
              className={selected ? 'reference-picker-selected' : ''}
              disabled={disabled} key={product.id} onClick={() => onSelect(product)}>
              {product.name}<span>{product.code}</span>
            </button>;
          })}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={disabled || loading || page <= 1}
        onClick={() => setPage((value) => value - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((value) => value + 1)}>下一页</button>
    </div>}
  </div>;
}

interface WarehousePickerProps {
  label: string;
  value: number | null;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Warehouse[]) => void;
}

function WarehousePicker({ label, value, disabled, onChange, onOptions }: WarehousePickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<masterDataApi.Warehouse[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const warehouseVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = warehouseVersion.current.next();
    setLoading(true);
    setError('');
    void masterDataApi.searchWarehouses(keyword, 'ENABLED', page).then((result) => {
      if (!warehouseVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (warehouseVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '仓库加载失败'));
      }
    }).finally(() => {
      if (warehouseVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>{label}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${label}关键词`} placeholder="输入仓库编码或名称"
        disabled={disabled || loading} value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            setKeyword(draft);
            setPage(1);
            setRefreshKey((current) => current + 1);
          }
        }} />
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => { setKeyword(draft); setPage(1); setRefreshKey((current) => current + 1); }}>查找</button>
      {value !== null && <button type="button" className="memory-button" disabled={disabled}
        onClick={() => onChange(null)}>清除仓库</button>}
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={label}>
      {loading ? <span>正在加载仓库…</span>
        : items.length === 0 ? <span>未找到启用中的仓库</span>
          : items.map((warehouse) => <button type="button" role="option" key={warehouse.id}
            aria-selected={value === warehouse.id}
            className={value === warehouse.id ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => onChange(warehouse.id)}>
            {warehouse.name}<span>{warehouse.code}</span>
          </button>)}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={disabled || loading || page <= 1}
        onClick={() => setPage((current) => current - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={disabled || loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((current) => current + 1)}>下一页</button>
    </div>}
  </div>;
}
