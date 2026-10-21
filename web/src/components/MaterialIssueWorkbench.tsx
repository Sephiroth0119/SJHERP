import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as materialIssueApi from '../api/materialIssueApi.ts';
import type { WorkOrder } from '../api/workOrderApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';
import { useFocusDocument } from './useFocusDocument.ts';
import {
  errorMessage,
  ExecutingWorkOrderPicker,
  ProductPicker,
  WarehousePicker,
} from './productionReferencePickers.tsx';

const PAGE_SIZE = 20;

const statusLabels: Record<materialIssueApi.MaterialDocumentStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

const actionLabels: Record<materialIssueApi.MaterialIssueAction, string> = {
  approve: '审核',
  post: '过账',
  cancel: '作废',
};

const actionNotices: Record<materialIssueApi.MaterialIssueAction, string> = {
  approve: '领料单已审核',
  post: '领料单已过账，已按移动加权扣减库存',
  cancel: '领料单已作废',
};

const actionConfirms: Record<materialIssueApi.MaterialIssueAction, string> = {
  approve: '确认审核该领料单？审核后可过账扣库存。',
  post: '确认过账？将按移动加权从领料仓扣减库存；库存不足整批回滚。齐套检查仅提示，不阻断过账。',
  cancel: '确认作废该草稿领料单？作废后不可恢复。',
};

function runIssueAction(action: materialIssueApi.MaterialIssueAction, docNo: string) {
  switch (action) {
    case 'approve':
      return materialIssueApi.approveMaterialIssue(docNo);
    case 'post':
      return materialIssueApi.postMaterialIssue(docNo);
    case 'cancel':
      return materialIssueApi.cancelMaterialIssue(docNo);
    default: {
      const exhaustive: never = action;
      throw new Error(`未知领料动作: ${exhaustive}`);
    }
  }
}

export function MaterialIssueWorkbench({
  canUse,
  canUseWo,
  focusDocNo,
}: {
  canUse: boolean;
  canUseWo: boolean;
  focusDocNo?: string;
}) {
  const [items, setItems] = useState<materialIssueApi.MaterialIssue[]>([]);
  const [selected, setSelected] = useState<materialIssueApi.MaterialIssue | null>(null);
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
  const [form, setForm] = useState<materialIssueApi.MaterialIssueForm>(
    materialIssueApi.emptyMaterialIssueForm(),
  );
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [units, setUnits] = useState<catalogApi.Unit[]>([]);
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(new Map());
  const [kittingWorkOrder, setKittingWorkOrder] = useState('');
  const [kittingWarehouseId, setKittingWarehouseId] = useState<number | null>(null);
  const [kitting, setKitting] = useState<materialIssueApi.KittingCheck | null>(null);
  const [kittingLoading, setKittingLoading] = useState(false);
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const supportVersion = useRef(createRequestGate());
  const warehouseNameVersion = useRef(createRequestGate());
  const kittingVersion = useRef(createRequestGate());
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
    void materialIssueApi.searchMaterialIssues(workOrderFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureWarehouseNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '领料单列表加载失败'));
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

  const choose = (issue: materialIssueApi.MaterialIssue) => {
    if (!canUse || saving) return;
    const version = detailVersion.current.next();
    setEditing(false);
    setError('');
    setNotice('');
    setSelected(issue);
    ensureWarehouseNames([issue]);
    ensureProductNames(issue.lines);
    void materialIssueApi.getMaterialIssue(issue.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureWarehouseNames([detail]);
      ensureProductNames(detail.lines);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '领料单详情加载失败'));
      }
    });
  };

  useFocusDocument(focusDocNo, (docNo) => {
    if (!canUse) return;
    void materialIssueApi.getMaterialIssue(docNo).then(choose).catch((cause: unknown) => {
      setError(errorMessage(cause, '领料单详情加载失败'));
    });
  });

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
    setForm({
      ...materialIssueApi.emptyMaterialIssueForm(),
      workOrderDocNo: kittingWorkOrder,
      warehouseId: kittingWarehouseId,
    });
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
      const validationError = materialIssueApi.validateMaterialIssueForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await materialIssueApi.createMaterialIssue(form);
      setSelected(result);
      setEditing(false);
      setNotice(`领料单已创建：${result.docNo}`);
      ensureWarehouseNames([result]);
      ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '领料单创建失败，请确认工单已开工、行数量与仓库'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: materialIssueApi.MaterialIssueAction) => {
    if (!canUse || !selected || mutationInFlight.current.isLocked()) return;
    if (!materialIssueApi.availableMaterialIssueActions(selected.status).includes(action)) return;
    if (!window.confirm(actionConfirms[action])) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await runIssueAction(action, selected.docNo);
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

  const runKitting = async () => {
    if (!canUse || kittingLoading) return;
    const validationError = materialIssueApi.validateKittingQuery(kittingWorkOrder, kittingWarehouseId);
    if (validationError) {
      setError(validationError);
      return;
    }
    const version = kittingVersion.current.next();
    setKittingLoading(true);
    setError('');
    try {
      const result = await materialIssueApi.checkKitting(kittingWorkOrder, kittingWarehouseId as number);
      if (!kittingVersion.current.isCurrent(version)) return;
      setKitting(result);
      ensureProductNames(result.lines);
      setNotice(result.kitted ? '齐套检查：齐套（kitted=true），仅提示不阻断领料。' : '齐套检查：缺料（kitted=false），仍可分批领料。');
    } catch (cause: unknown) {
      if (kittingVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '齐套检查失败'));
      }
    } finally {
      if (kittingVersion.current.isCurrent(version)) setKittingLoading(false);
    }
  };

  const fillFromKitting = () => {
    if (!kitting || !editing) return;
    const lines = materialIssueApi.linesFromKitting(kitting);
    setForm({
      ...form,
      workOrderDocNo: kitting.workOrderDocNo,
      warehouseId: kitting.warehouseId,
      lines,
    });
    ensureProductNames(kitting.lines);
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canUse) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">生产 / 领料</p>
          <h1>领料与齐套</h1>
          <p>查询与写操作均须 production:material。当前账号无此权限，未请求领料或齐套接口，避免 403 刷屏。</p>
        </div>
      </header>
      <p className="memory-empty" role="status">需要 production:material 权限才能查看和操作领料、齐套。</p>
    </section>;
  }

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 领料</p>
        <h1>领料与齐套</h1>
        <p>选择已开工工单与领料仓建单，按草稿→审核→过账/作废流转。过账扣库存；齐套只读提示，不阻断领料。工单须已开工才能领料。</p>
      </div>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={startNew}>新建领料单</button>
      </div>
    </header>
    <section className="memory-group">
      <header><strong>齐套检查（只读，GET /api/production/kitting-check）</strong></header>
      <p>输入工单号与仓库后查看缺料清单。kitted 仅提示，不预留库存、不阻断领料。</p>
      <div className="purchase-order-filters">
        <label>工单号
          <input aria-label="齐套工单号" disabled={saving || kittingLoading} value={kittingWorkOrder}
            placeholder="WO-…" onChange={(event) => setKittingWorkOrder(event.target.value)} /></label>
        <button type="button" className="memory-button" disabled={saving || kittingLoading}
          onClick={() => void runKitting()}>{kittingLoading ? '检查中…' : '齐套检查'}</button>
        {editing && kitting && <button type="button" className="memory-button" disabled={saving}
          onClick={fillFromKitting}>用齐套结果填入领料行</button>}
      </div>
      <WarehousePicker label="齐套仓库（GET /api/warehouse/warehouses）" value={kittingWarehouseId}
        disabled={saving || kittingLoading} onChange={setKittingWarehouseId} onOptions={rememberWarehouses} />
      {kitting && <div className="memory-table-wrap">
        <p role="status">{kitting.kitted ? '齐套（kitted=true）' : '缺料（kitted=false）'} · {kitting.workOrderDocNo}</p>
        <table className="memory-table"><thead><tr>
          <th>子件</th><th>应领</th><th>可用</th><th>缺料</th>
        </tr></thead><tbody>
          {kitting.lines.map((line) => <tr key={`${line.productId}-${line.unitId}`}>
            <td>{productName(line.productId)}</td>
            <td>{materialIssueApi.formatQuantityDisplay(line.required)}（{unitName(line.unitId)}）</td>
            <td>{materialIssueApi.formatQuantityDisplay(line.available)}</td>
            <td>{materialIssueApi.formatQuantityDisplay(line.shortage)}</td>
          </tr>)}
        </tbody></table>
        {kitting.lines.length === 0 && <p className="memory-empty">无 active BOM 直接子件，视为齐套。</p>}
      </div>}
    </section>
    <div className="purchase-order-filters">
      <label>工单号<input aria-label="领料工单筛选" disabled={saving} value={workOrderDraft}
        placeholder="精确筛选工单号"
        onChange={(event) => setWorkOrderDraft(event.target.value)} /></label>
      <label>状态<select aria-label="领料单状态" disabled={saving} value={statusDraft}
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
          <th>单号</th><th>工单</th><th>仓库</th><th>领料成本</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((issue) => <tr key={issue.docNo}
            className={selected?.docNo === issue.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(issue)}>{issue.docNo}</button></td>
            <td>{issue.workOrderDocNo}</td>
            <td>{warehouseName(issue.warehouseId)}</td>
            <td>{materialIssueApi.formatQuantityDisplay(issue.totalIssuedCost)}</td>
            <td><StatusBadge status={issue.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载领料单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无领料单。可从右上角新建。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <IssueFormView form={form} setForm={setForm} saving={saving} canUseWo={canUseWo}
          units={units} productName={productName} warehouseName={warehouseName}
          rememberProducts={rememberProducts} rememberWarehouses={rememberWarehouses}
          onWorkOrder={(docNo, order) => {
            setForm({
              ...form,
              workOrderDocNo: docNo,
              warehouseId: order?.warehouseId ?? form.warehouseId,
            });
            setKittingWorkOrder(docNo);
            if (order?.warehouseId) setKittingWarehouseId(order.warehouseId);
          }}
          onSave={save} onCancel={clearSelection} />
          : selected ? <IssueDetails issue={selected} canUse={canUse} saving={saving}
            productName={productName} unitName={unitName} warehouseName={warehouseName}
            onTransition={(action) => void transition(action)} />
            : <p className="memory-empty">选择左侧领料单查看详情，或新建。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: materialIssueApi.MaterialDocumentStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface IssueFormProps {
  form: materialIssueApi.MaterialIssueForm;
  setForm: (form: materialIssueApi.MaterialIssueForm) => void;
  saving: boolean;
  canUseWo: boolean;
  units: catalogApi.Unit[];
  productName: (id: number) => string;
  warehouseName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  rememberWarehouses: (warehouses: masterDataApi.Warehouse[]) => void;
  onWorkOrder: (docNo: string, order: WorkOrder | null) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function IssueFormView({
  form, setForm, saving, canUseWo, units, productName, warehouseName,
  rememberProducts, rememberWarehouses, onWorkOrder, onSave, onCancel,
}: IssueFormProps) {
  const [lineProductIndex, setLineProductIndex] = useState(0);
  const updateLine = (index: number, patch: Partial<materialIssueApi.MaterialIssueLineForm>) => {
    setForm({
      ...form,
      lines: form.lines.map((line, current) => current === index ? { ...line, ...patch } : line),
    });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建领料单</h2>
    <p>必填：已开工工单、领料仓、至少一行子件。应领可为 0，实领必须大于 0。数量以字符串提交。</p>
    <ExecutingWorkOrderPicker canUseWo={canUseWo} disabled={saving} value={form.workOrderDocNo}
      heading="选择已开工工单（GET /api/production/work-orders?status=EXECUTING）"
      onChange={onWorkOrder} onProducts={rememberProducts} onWarehouses={rememberWarehouses} />
    <WarehousePicker label="领料仓库（GET /api/warehouse/warehouses）" value={form.warehouseId}
      disabled={saving} onChange={(warehouseId) => setForm({ ...form, warehouseId })}
      onOptions={rememberWarehouses} />
    <p>{form.warehouseId ? `当前仓库：${warehouseName(form.warehouseId)}` : '尚未选择领料仓'}</p>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>子件</th><th>应领</th><th>实领</th><th>单位</th><th></th>
      </tr></thead><tbody>
        {form.lines.map((line, index) => <tr key={index}>
          <td>
            <button type="button" className="table-row-button" disabled={saving}
              onClick={() => setLineProductIndex(index)}>
              {line.productId ? productName(line.productId) : '选择子件'}
            </button>
          </td>
          <td><input required inputMode="decimal" aria-label={`第 ${index + 1} 行应领`} disabled={saving}
            pattern={materialIssueApi.nonNegativeDecimalPattern.source} value={line.requiredQty}
            onChange={(event) => updateLine(index, { requiredQty: event.target.value })} /></td>
          <td><input required inputMode="decimal" aria-label={`第 ${index + 1} 行实领`} disabled={saving}
            pattern={materialIssueApi.positiveDecimalPattern.source} value={line.quantity}
            onChange={(event) => updateLine(index, { quantity: event.target.value })} /></td>
          <td><select required aria-label={`第 ${index + 1} 行单位`} disabled={saving} value={line.unitId ?? ''}
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
    <ProductPicker disabled={saving}
      selectedIds={form.lines.map((line) => line.productId)}
      heading="选择子件商品（GET /api/catalog/products）"
      onSelect={(product) => {
        rememberProducts([product]);
        const target = form.lines[lineProductIndex] ?? form.lines[0];
        if (!target) return;
        const index = form.lines.indexOf(target);
        updateLine(index < 0 ? 0 : index, {
          productId: product.id,
          unitId: target.unitId ?? product.baseUnitId,
        });
      }}
      onOptions={rememberProducts} />
    <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving}
        onClick={() => setForm({
          ...form,
          lines: [...form.lines, materialIssueApi.emptyMaterialIssueLine()],
        })}>增行</button>
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '创建中…' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface IssueDetailsProps {
  issue: materialIssueApi.MaterialIssue;
  canUse: boolean;
  saving: boolean;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  warehouseName: (id: number) => string;
  onTransition: (action: materialIssueApi.MaterialIssueAction) => void;
}

function IssueDetails({
  issue, canUse, saving, productName, unitName, warehouseName, onTransition,
}: IssueDetailsProps) {
  const actions = materialIssueApi.availableMaterialIssueActions(issue.status);
  return <>
    <StatusBadge status={issue.status} />
    <h2>{issue.docNo}</h2>
    <dl>
      <dt>工单</dt><dd>{issue.workOrderDocNo}</dd>
      <dt>领料仓</dt><dd>{warehouseName(issue.warehouseId)}</dd>
      <dt>领料成本</dt><dd>{materialIssueApi.formatQuantityDisplay(issue.totalIssuedCost)}</dd>
      <dt>备注</dt><dd>{issue.remark || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>行</th><th>子件</th><th>应领</th><th>实领</th><th>领料成本</th>
      </tr></thead><tbody>
        {issue.lines.map((line) => <tr key={line.lineNo}>
          <td>{line.lineNo}</td>
          <td>{productName(line.productId)}</td>
          <td>{materialIssueApi.formatQuantityDisplay(line.requiredQty)}（{unitName(line.unitId)}）</td>
          <td>{materialIssueApi.formatQuantityDisplay(line.quantity)}</td>
          <td>{line.issuedCost == null ? '—' : materialIssueApi.formatQuantityDisplay(line.issuedCost)}</td>
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
