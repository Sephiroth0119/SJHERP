import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import * as demandPlanApi from '../api/demandPlanApi.ts';
import { ApiError } from '../api/http.ts';
import { formatQuantityDisplay } from '../api/productionBomApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<demandPlanApi.ArchiveStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function DemandPlanWorkbench({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<demandPlanApi.DemandPlan[]>([]);
  const [selected, setSelected] = useState<demandPlanApi.DemandPlan | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<demandPlanApi.DemandPlanForm>(demandPlanApi.emptyDemandPlanForm());
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [units, setUnits] = useState<catalogApi.Unit[]>([]);
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const supportVersion = useRef(createRequestGate());
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

  const ensureProductNames = (plans: demandPlanApi.DemandPlan[]) => {
    const ids = new Set<number>();
    for (const plan of plans) {
      for (const line of plan.lines) ids.add(line.productId);
    }
    for (const id of ids) {
      if (productNames.has(id)) continue;
      void catalogApi.getProduct(id).then((product) => rememberProducts([product])).catch(() => undefined);
    }
  };

  useEffect(() => {
    const version = supportVersion.current.next();
    void catalogApi.listUnits().then((nextUnits) => {
      if (!supportVersion.current.isCurrent(version)) return;
      setUnits(nextUnits);
    }).catch((cause: unknown) => {
      if (supportVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '计量单位加载失败'));
      }
    });
  }, []);

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void demandPlanApi.searchDemandPlans(status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureProductNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '需求计划列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (plan: demandPlanApi.DemandPlan) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(plan);
    setEditing(false);
    setError('');
    setNotice('');
    ensureProductNames([plan]);
    void demandPlanApi.getDemandPlan(plan.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureProductNames([detail]);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '需求计划详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canWrite || saving) return;
    clearSelection();
    setForm(demandPlanApi.emptyDemandPlanForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const startEdit = () => {
    if (!canWrite || !selected || saving) return;
    invalidateDetail();
    setForm(demandPlanApi.toDemandPlanForm(selected));
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canWrite || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = demandPlanApi.validateDemandPlanForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = selected
        ? await demandPlanApi.updateDemandPlan(selected.docNo, form)
        : await demandPlanApi.createDemandPlan(form);
      setSelected(result);
      setEditing(false);
      setNotice(selected ? '需求计划行已整体更新' : '需求计划已创建');
      ensureProductNames([result]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '需求计划保存失败，请检查计划日期、商品、数量和单位'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 需求计划</p>
        <h1>需求计划</h1>
        <p>手工预测落表，供 MRP 作为预测来源。查询登录即可；新建与整体改行需要 production:plan。无删除入口，状态仅 ENABLED/DISABLED。</p>
      </div>
      {canWrite && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startNew}>新建需求计划</button>}
    </header>
    <div className="purchase-order-filters">
      <label>状态<select aria-label="需求计划状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="ENABLED">启用</option>
        <option value="DISABLED">停用</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>单号</th><th>计划日期</th><th>状态</th><th>行数</th>
        </tr></thead><tbody>
          {items.map((plan) => <tr key={plan.docNo}
            className={selected?.docNo === plan.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(plan)}>{plan.docNo}</button></td>
            <td>{plan.planDate}</td>
            <td><StatusBadge status={plan.status} /></td>
            <td>{plan.lines.length}</td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载需求计划…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无需求计划。{canWrite ? '可从右上角新建。' : '请调整筛选条件。'}
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
        {editing ? <DemandPlanFormView form={form} setForm={setForm} saving={saving}
          updating={selected !== null} units={units} productName={productName}
          rememberProducts={rememberProducts} onSave={save} onCancel={clearSelection} />
          : selected ? <DemandPlanDetails plan={selected} canWrite={canWrite} saving={saving}
            productName={productName} unitName={unitName} onEdit={startEdit} />
            : <p className="memory-empty">选择左侧需求计划查看详情{canWrite ? '，或新建。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: demandPlanApi.ArchiveStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface FormProps {
  form: demandPlanApi.DemandPlanForm;
  setForm: (form: demandPlanApi.DemandPlanForm) => void;
  saving: boolean;
  updating: boolean;
  units: catalogApi.Unit[];
  productName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function DemandPlanFormView({
  form, setForm, saving, updating, units, productName, rememberProducts, onSave, onCancel,
}: FormProps) {
  const addProduct = (product: catalogApi.Product) => {
    rememberProducts([product]);
    const exists = form.lines.some((line) =>
      line.productId === product.id && line.unitId === product.baseUnitId);
    if (exists) return;
    setForm({
      ...form,
      lines: [
        ...form.lines,
        {
          productId: product.id,
          quantity: '1',
          unitId: product.baseUnitId,
          dueDate: '',
        },
      ],
    });
  };
  const setLine = (index: number, patch: Partial<demandPlanApi.DemandPlanLineForm>) => {
    const lines = form.lines.map((line, current) => current === index ? { ...line, ...patch } : line);
    setForm({ ...form, lines });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>{updating ? '整体更新需求计划行' : '新建需求计划'}</h2>
    <label>计划日期
      <input required type="date" disabled={saving} value={form.planDate} aria-label="计划日期"
        onChange={(event) => setForm({ ...form, planDate: event.target.value })} /></label>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <fieldset disabled={saving}>
      <legend>需求行（整体替换）</legend>
      {form.lines.length === 0 ? <p className="memory-empty">请从下方添加商品。数量必须大于 0，空行不可保存。</p>
        : form.lines.map((line, index) => <div className="customer-toolbar" key={`${line.productId}-${index}`}>
          <strong>{line.productId === null ? '未选商品' : productName(line.productId)}</strong>
          <label>数量<input aria-label={`数量 ${index + 1}`} required inputMode="decimal"
            pattern={demandPlanApi.positiveDecimalPattern.source} value={line.quantity}
            onChange={(event) => setLine(index, { quantity: event.target.value })} /></label>
          <label>单位<select aria-label={`单位 ${index + 1}`} required value={line.unitId ?? ''}
            onChange={(event) => setLine(index, {
              unitId: event.target.value ? Number.parseInt(event.target.value, 10) : null,
            })}>
            <option value="">请选择</option>
            {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.name}</option>)}
          </select></label>
          <label>交期<input aria-label={`交期 ${index + 1}`} type="date" value={line.dueDate}
            onChange={(event) => setLine(index, { dueDate: event.target.value })} /></label>
          <button type="button" className="memory-button" onClick={() => setForm({
            ...form,
            lines: form.lines.filter((_, current) => current !== index),
          })}>移除</button>
        </div>)}
    </fieldset>
    <ProductPicker disabled={saving} selectedIds={form.lines.map((line) => line.productId)}
      heading="添加商品（GET /api/catalog/products；同一计划内商品+单位不可重复）"
      onSelect={addProduct} onOptions={rememberProducts} />
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '保存中…' : updating ? '保存行列表' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  plan: demandPlanApi.DemandPlan;
  canWrite: boolean;
  saving: boolean;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  onEdit: () => void;
}

function DemandPlanDetails({
  plan, canWrite, saving, productName, unitName, onEdit,
}: DetailsProps) {
  return <>
    <StatusBadge status={plan.status} />
    <h2>{plan.docNo}</h2>
    <p>计划日期 {plan.planDate}</p>
    <dl>
      <dt>备注</dt><dd>{plan.remark || '—'}</dd>
      <dt>创建</dt><dd>{plan.createdBy || '—'} · {plan.createdAt || '—'}</dd>
      <dt>更新</dt><dd>{plan.updatedBy || '—'} · {plan.updatedAt || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>商品</th><th>数量</th><th>单位</th><th>交期</th>
      </tr></thead><tbody>
        {plan.lines.map((line, index) => <tr key={`${line.productId}-${line.unitId}-${index}`}>
          <td>{productName(line.productId)}</td>
          <td>{formatQuantityDisplay(line.quantity)}</td>
          <td>{unitName(line.unitId)}</td>
          <td>{line.dueDate || '—'}</td>
        </tr>)}
      </tbody></table>
    </div>
    {canWrite && <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving} onClick={onEdit}>编辑行</button>
    </div>}
  </>;
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
