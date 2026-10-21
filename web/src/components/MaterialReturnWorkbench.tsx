import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as materialIssueApi from '../api/materialIssueApi.ts';
import * as materialReturnApi from '../api/materialReturnApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';
import { useFocusDocument } from './useFocusDocument.ts';
import {
  CompletedMaterialIssuePicker,
  errorMessage,
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

const actionLabels: Record<materialReturnApi.MaterialReturnAction, string> = {
  approve: '审核',
  post: '过账',
};

const actionNotices: Record<materialReturnApi.MaterialReturnAction, string> = {
  approve: '退料单已审核',
  post: '退料单已过账，已按原领料成本退回库存',
};

const actionConfirms: Record<materialReturnApi.MaterialReturnAction, string> = {
  approve: '确认审核该退料单？审核后可过账退回库存。',
  post: '确认过账？将按原领料成本退回库存，界面不填写单价。超退将被后端拒绝。',
};

function runReturnAction(action: materialReturnApi.MaterialReturnAction, docNo: string) {
  switch (action) {
    case 'approve':
      return materialReturnApi.approveMaterialReturn(docNo);
    case 'post':
      return materialReturnApi.postMaterialReturn(docNo);
    default: {
      const exhaustive: never = action;
      throw new Error(`未知退料动作: ${exhaustive}`);
    }
  }
}

export function MaterialReturnWorkbench({ canUse, focusDocNo }: { canUse: boolean; focusDocNo?: string }) {
  const [items, setItems] = useState<materialReturnApi.MaterialReturn[]>([]);
  const [selected, setSelected] = useState<materialReturnApi.MaterialReturn | null>(null);
  const [issueDraft, setIssueDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [issueFilter, setIssueFilter] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(canUse);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<materialReturnApi.MaterialReturnForm>(
    materialReturnApi.emptyMaterialReturnForm(),
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
    void materialReturnApi.searchMaterialReturns(issueFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureWarehouseNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '退料单列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [canUse, issueFilter, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (doc: materialReturnApi.MaterialReturn) => {
    if (!canUse || saving) return;
    const version = detailVersion.current.next();
    setEditing(false);
    setError('');
    setNotice('');
    setSelected(doc);
    ensureWarehouseNames([doc]);
    ensureProductNames(doc.lines);
    void materialReturnApi.getMaterialReturn(doc.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureWarehouseNames([detail]);
      ensureProductNames(detail.lines);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '退料单详情加载失败'));
      }
    });
  };

  useFocusDocument(focusDocNo, (docNo) => {
    if (!canUse) return;
    void materialReturnApi.getMaterialReturn(docNo).then(choose).catch((cause: unknown) => {
      setError(errorMessage(cause, '退料单详情加载失败'));
    });
  });

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setIssueFilter(issueDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canUse || saving) return;
    clearSelection();
    setForm(materialReturnApi.emptyMaterialReturnForm());
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
      const validationError = materialReturnApi.validateMaterialReturnForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await materialReturnApi.createMaterialReturn(form);
      setSelected(result);
      setEditing(false);
      setNotice(`退料单已创建：${result.docNo}`);
      ensureWarehouseNames([result]);
      ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '退料单创建失败，请确认原领料单已过账且未超退'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: materialReturnApi.MaterialReturnAction) => {
    if (!canUse || !selected || mutationInFlight.current.isLocked()) return;
    if (!materialReturnApi.availableMaterialReturnActions(selected.status).includes(action)) return;
    if (!window.confirm(actionConfirms[action])) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await runReturnAction(action, selected.docNo);
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

  const selectIssue = (issue: materialIssueApi.MaterialIssue) => {
    setForm({
      materialIssueDocNo: issue.docNo,
      warehouseId: issue.warehouseId,
      remark: '',
      lines: materialReturnApi.linesFromMaterialIssue(issue),
    });
    ensureWarehouseNames([issue]);
    ensureProductNames(issue.lines);
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canUse) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">生产 / 退料</p>
          <h1>退料</h1>
          <p>查询与写操作均须 production:material。当前账号无此权限，未请求退料列表，避免 403 刷屏。</p>
        </div>
      </header>
      <p className="memory-empty" role="status">需要 production:material 权限才能查看和操作退料。</p>
    </section>;
  }

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 退料</p>
        <h1>退料</h1>
        <p>引用已过账领料单建退料单，按原领料成本退回。超退由后端拒绝并展示错误原文。无作废接口，草稿仅可审核后过账。</p>
      </div>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={startNew}>新建退料单</button>
      </div>
    </header>
    <div className="purchase-order-filters">
      <label>原领料单号<input aria-label="原领料单筛选" disabled={saving} value={issueDraft}
        placeholder="精确筛选 MI 单号"
        onChange={(event) => setIssueDraft(event.target.value)} /></label>
      <label>状态<select aria-label="退料单状态" disabled={saving} value={statusDraft}
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
          <th>单号</th><th>原领料单</th><th>仓库</th><th>退料成本</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((doc) => <tr key={doc.docNo}
            className={selected?.docNo === doc.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(doc)}>{doc.docNo}</button></td>
            <td>{doc.materialIssueDocNo}</td>
            <td>{warehouseName(doc.warehouseId)}</td>
            <td>{materialReturnApi.formatQuantityDisplay(doc.totalReturnedCost)}</td>
            <td><StatusBadge status={doc.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载退料单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无退料单。可从右上角新建。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <ReturnFormView form={form} setForm={setForm} saving={saving}
          units={units} productName={productName} warehouseName={warehouseName}
          rememberWarehouses={rememberWarehouses} onSelectIssue={selectIssue}
          onSave={save} onCancel={clearSelection} />
          : selected ? <ReturnDetails doc={selected} canUse={canUse} saving={saving}
            productName={productName} unitName={unitName} warehouseName={warehouseName}
            onTransition={(action) => void transition(action)} />
            : <p className="memory-empty">选择左侧退料单查看详情，或新建。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: materialIssueApi.MaterialDocumentStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface ReturnFormProps {
  form: materialReturnApi.MaterialReturnForm;
  setForm: (form: materialReturnApi.MaterialReturnForm) => void;
  saving: boolean;
  units: catalogApi.Unit[];
  productName: (id: number) => string;
  warehouseName: (id: number) => string;
  rememberWarehouses: (warehouses: masterDataApi.Warehouse[]) => void;
  onSelectIssue: (issue: materialIssueApi.MaterialIssue) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function ReturnFormView({
  form, setForm, saving, units, productName, warehouseName,
  rememberWarehouses, onSelectIssue, onSave, onCancel,
}: ReturnFormProps) {
  const updateLine = (index: number, patch: Partial<materialReturnApi.MaterialReturnLineForm>) => {
    setForm({
      ...form,
      lines: form.lines.map((line, current) => current === index ? { ...line, ...patch } : line),
    });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建退料单</h2>
    <p>必填：已过账原领料单、退料仓、至少一行。退料量不可超过原实领。单价由过账按原领料成本回填，此处不填。</p>
    <CompletedMaterialIssuePicker disabled={saving} value={form.materialIssueDocNo} onSelect={onSelectIssue} />
    <p>{form.materialIssueDocNo ? `当前原领料单：${form.materialIssueDocNo}` : '尚未选择原领料单'}</p>
    <WarehousePicker label="退料仓库（GET /api/warehouse/warehouses）" value={form.warehouseId}
      disabled={saving} onChange={(warehouseId) => setForm({ ...form, warehouseId })}
      onOptions={rememberWarehouses} />
    <p>{form.warehouseId ? `当前仓库：${warehouseName(form.warehouseId)}` : '尚未选择退料仓'}</p>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>商品</th><th>原实领</th><th>退料数量</th><th>单位</th><th></th>
      </tr></thead><tbody>
        {form.lines.map((line, index) => <tr key={index}>
          <td>{line.productId ? productName(line.productId) : '未选商品'}</td>
          <td>{line.issuedQty ? materialReturnApi.formatQuantityDisplay(line.issuedQty) : '—'}</td>
          <td><input required inputMode="decimal" aria-label={`第 ${index + 1} 行退料数量`} disabled={saving}
            pattern={materialReturnApi.positiveDecimalPattern.source} value={line.quantity}
            onChange={(event) => updateLine(index, { quantity: event.target.value })} /></td>
          <td><select required aria-label={`第 ${index + 1} 行单位`} disabled={saving} value={line.unitId ?? ''}
            onChange={(event) => updateLine(index, {
              unitId: event.target.value ? Number.parseInt(event.target.value, 10) : null,
            })}>
            <option value="">请选择</option>
            {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.name}</option>)}
          </select></td>
          <td><button type="button" className="memory-button" disabled={saving}
            onClick={() => setForm({ ...form, lines: form.lines.filter((_, current) => current !== index) })}>
            移除</button></td>
        </tr>)}
      </tbody></table>
    </div>
    {form.lines.length === 0 && <p className="memory-empty">选择原领料单后会带出已领明细，可改退料数量，不可超退。</p>}
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '创建中…' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface ReturnDetailsProps {
  doc: materialReturnApi.MaterialReturn;
  canUse: boolean;
  saving: boolean;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  warehouseName: (id: number) => string;
  onTransition: (action: materialReturnApi.MaterialReturnAction) => void;
}

function ReturnDetails({
  doc, canUse, saving, productName, unitName, warehouseName, onTransition,
}: ReturnDetailsProps) {
  const actions = materialReturnApi.availableMaterialReturnActions(doc.status);
  return <>
    <StatusBadge status={doc.status} />
    <h2>{doc.docNo}</h2>
    <dl>
      <dt>原领料单</dt><dd>{doc.materialIssueDocNo}</dd>
      <dt>退料仓</dt><dd>{warehouseName(doc.warehouseId)}</dd>
      <dt>退料成本</dt><dd>{materialReturnApi.formatQuantityDisplay(doc.totalReturnedCost)}</dd>
      <dt>备注</dt><dd>{doc.remark || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>行</th><th>商品</th><th>退料数量</th><th>原领料行</th><th>退料成本</th>
      </tr></thead><tbody>
        {doc.lines.map((line) => <tr key={line.lineNo}>
          <td>{line.lineNo}</td>
          <td>{productName(line.productId)}</td>
          <td>{materialReturnApi.formatQuantityDisplay(line.quantity)}（{unitName(line.unitId)}）</td>
          <td>{line.srcIssueLineNo ?? '—'}</td>
          <td>{line.returnedCost == null ? '—' : materialReturnApi.formatQuantityDisplay(line.returnedCost)}</td>
        </tr>)}
      </tbody></table>
    </div>
    {canUse && actions.length > 0 && <div className="customer-actions">
      {actions.map((action) => <button type="button" key={action}
        className="memory-button memory-button-primary" disabled={saving}
        onClick={() => onTransition(action)}>
        {saving ? '处理中…' : actionLabels[action]}
      </button>)}
    </div>}
    {actions.length === 0 && <p className="memory-empty">当前状态无可执行流转。</p>}
  </>;
}
