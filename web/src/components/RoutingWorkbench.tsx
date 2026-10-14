import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as routingApi from '../api/productionRoutingApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<routingApi.ArchiveStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function RoutingWorkbench({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<routingApi.Routing[]>([]);
  const [selected, setSelected] = useState<routingApi.Routing | null>(null);
  const [productFilterDraft, setProductFilterDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [productFilter, setProductFilter] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<routingApi.RoutingForm>(routingApi.emptyRoutingForm());
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
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

  const ensureProductNames = (routings: routingApi.Routing[]) => {
    const ids = [...new Set(routings.map((item) => item.productId).filter((id) => !productNames.has(id)))];
    for (const id of ids) {
      void catalogApi.getProduct(id).then((product) => rememberProducts([product])).catch(() => undefined);
    }
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void routingApi.searchRoutings(productFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureProductNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '工艺路线列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [productFilter, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (routing: routingApi.Routing) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(routing);
    setEditing(false);
    setError('');
    setNotice('');
    ensureProductNames([routing]);
    void routingApi.getRouting(routing.id).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureProductNames([detail]);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '工艺路线详情加载失败'));
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

  const startNew = () => {
    if (!canWrite || saving) return;
    clearSelection();
    setForm(routingApi.emptyRoutingForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const startEdit = () => {
    if (!canWrite || !selected || saving) return;
    invalidateDetail();
    setForm(routingApi.toRoutingForm(selected));
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
      const validationError = routingApi.validateRoutingForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = selected
        ? await routingApi.updateRouting(selected.id, form)
        : await routingApi.createRouting(form);
      setSelected(result);
      setEditing(false);
      setNotice(selected ? '工艺路线工序已整体更新' : '工艺路线已创建（默认启用，同产品其他启用版本已停用）');
      ensureProductNames([result]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '工艺路线保存失败，请检查产品、工序序号和标准工时'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const toggle = async () => {
    if (!canWrite || !selected || mutationInFlight.current.isLocked()) return;
    const disabling = selected.status === 'ENABLED';
    const confirmText = disabling
      ? '停用后成本归集不再使用该版本。确认停用吗？'
      : '启用此版本将停用同产品其他启用中的工艺路线。确认启用吗？';
    if (!window.confirm(confirmText)) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = disabling
        ? await routingApi.disableRouting(selected.id)
        : await routingApi.enableRouting(selected.id);
      setSelected(result);
      setNotice(result.status === 'ENABLED' ? '工艺路线已启用' : '工艺路线已停用');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, disabling ? '工艺路线停用失败' : '工艺路线启用失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 基础数据</p>
        <h1>工艺路线</h1>
        <p>按产品维护有序工序与标准工时。查询登录即可；新建、整体改工序、启停需要 production:routing。工作中心/费率为成本归集预留，可空。</p>
      </div>
      {canWrite && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startNew}>新建工艺路线</button>}
    </header>
    <div className="purchase-order-filters">
      <label>状态<select aria-label="工艺路线状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="ENABLED">启用</option>
        <option value="DISABLED">停用</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
      {productFilterDraft !== null && <button type="button" className="memory-button" disabled={saving}
        onClick={() => setProductFilterDraft(null)}>清除商品筛选</button>}
    </div>
    <ProductPicker disabled={saving} selectedIds={productFilterDraft === null ? [] : [productFilterDraft]}
      heading="按产品筛选（GET /api/catalog/products，查询时带 productId）"
      onSelect={(product) => { rememberProducts([product]); setProductFilterDraft(product.id); }}
      onOptions={rememberProducts} />
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>产品</th><th>版本</th><th>状态</th><th>工序数</th>
        </tr></thead><tbody>
          {items.map((routing) => <tr key={routing.id}
            className={selected?.id === routing.id ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(routing)}>{productName(routing.productId)}</button></td>
            <td>v{routing.version}</td>
            <td><StatusBadge status={routing.status} /></td>
            <td>{routing.operations.length}</td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载工艺路线…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无工艺路线。{canWrite ? '可从右上角新建。' : '请调整筛选条件。'}
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
        {editing ? <RoutingFormView form={form} setForm={setForm} saving={saving}
          updating={selected !== null} productName={productName}
          rememberProducts={rememberProducts} onSave={save} onCancel={clearSelection} />
          : selected ? <RoutingDetails routing={selected} canWrite={canWrite} saving={saving}
            productName={productName} onEdit={startEdit} onToggle={() => void toggle()} />
            : <p className="memory-empty">选择左侧工艺路线查看详情{canWrite ? '，或新建。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: routingApi.ArchiveStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface FormProps {
  form: routingApi.RoutingForm;
  setForm: (form: routingApi.RoutingForm) => void;
  saving: boolean;
  updating: boolean;
  productName: (id: number) => string;
  rememberProducts: (products: catalogApi.Product[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function RoutingFormView({
  form, setForm, saving, updating, productName, rememberProducts, onSave, onCancel,
}: FormProps) {
  const nextSequence = () => {
    let max = 0;
    for (const operation of form.operations) {
      if (!/^[1-9]\d{0,8}$/.test(operation.sequenceNo.trim())) continue;
      const value = Number.parseInt(operation.sequenceNo.trim(), 10);
      if (value > max) max = value;
    }
    return String(max + 10);
  };
  const setOperation = (index: number, patch: Partial<routingApi.RoutingOperationForm>) => {
    const operations = form.operations.map((operation, current) =>
      current === index ? { ...operation, ...patch } : operation);
    setForm({ ...form, operations });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>{updating ? '整体更新工序' : '新建工艺路线'}</h2>
    <label>版本{updating ? '（创建后不可改）' : ''}
      <input required inputMode="numeric" pattern={routingApi.versionPattern.source}
        disabled={saving || updating} value={form.version} aria-label="工艺路线版本"
        onChange={(event) => setForm({ ...form, version: event.target.value })} /></label>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    {updating ? <p>产品：{form.productId === null ? '—' : productName(form.productId)}（更新时不可改）</p>
      : <ProductPicker disabled={saving}
        selectedIds={form.productId === null ? [] : [form.productId]}
        heading="选择产品"
        onSelect={(product) => {
          rememberProducts([product]);
          setForm({ ...form, productId: product.id });
        }}
        onOptions={rememberProducts} />}
    <fieldset disabled={saving}>
      <legend>工序（整体替换，序号须唯一）</legend>
      {form.operations.map((operation, index) => <div className="customer-toolbar" key={`op-${index}`}>
        <label>序号<input aria-label={`工序序号 ${index + 1}`} required inputMode="numeric"
          pattern={routingApi.versionPattern.source} value={operation.sequenceNo}
          onChange={(event) => setOperation(index, { sequenceNo: event.target.value })} /></label>
        <label>名称<input aria-label={`工序名称 ${index + 1}`} required maxLength={200}
          value={operation.operationName}
          onChange={(event) => setOperation(index, { operationName: event.target.value })} /></label>
        <label>标准工时<input aria-label={`标准工时 ${index + 1}`} required inputMode="decimal"
          pattern={routingApi.positiveDecimalPattern.source} value={operation.standardHours}
          onChange={(event) => setOperation(index, { standardHours: event.target.value })} /></label>
        <label>工作中心<input aria-label={`工作中心 ${index + 1}`} maxLength={100}
          value={operation.workCenter}
          onChange={(event) => setOperation(index, { workCenter: event.target.value })} /></label>
        <label>费率<input aria-label={`费率 ${index + 1}`} inputMode="decimal" placeholder="可空"
          value={operation.costRate}
          onChange={(event) => setOperation(index, { costRate: event.target.value })} /></label>
        <button type="button" className="memory-button" onClick={() => setForm({
          ...form,
          operations: form.operations.filter((_, current) => current !== index),
        })}>移除</button>
      </div>)}
      <button type="button" className="memory-button" onClick={() => setForm({
        ...form,
        operations: [
          ...form.operations,
          {
            sequenceNo: nextSequence(),
            operationName: '',
            standardHours: '',
            workCenter: '',
            costRate: '',
          },
        ],
      })}>新增工序</button>
    </fieldset>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '保存中…' : updating ? '保存工序' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  routing: routingApi.Routing;
  canWrite: boolean;
  saving: boolean;
  productName: (id: number) => string;
  onEdit: () => void;
  onToggle: () => void;
}

function RoutingDetails({ routing, canWrite, saving, productName, onEdit, onToggle }: DetailsProps) {
  return <>
    <StatusBadge status={routing.status} />
    <h2>{productName(routing.productId)}</h2>
    <p>v{routing.version}</p>
    <dl>
      <dt>备注</dt><dd>{routing.remark || '—'}</dd>
      <dt>创建</dt><dd>{routing.createdBy || '—'} · {routing.createdAt || '—'}</dd>
      <dt>更新</dt><dd>{routing.updatedBy || '—'} · {routing.updatedAt || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>序号</th><th>工序</th><th>标准工时</th><th>工作中心</th><th>费率</th>
      </tr></thead><tbody>
        {routing.operations.map((operation) => <tr key={operation.sequenceNo}>
          <td>{operation.sequenceNo}</td>
          <td>{operation.operationName}</td>
          <td>{operation.standardHours}</td>
          <td>{operation.workCenter || '—'}</td>
          <td>{operation.costRate || '—'}</td>
        </tr>)}
      </tbody></table>
    </div>
    {canWrite && <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving} onClick={onEdit}>编辑工序</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onToggle}>
        {saving ? '处理中…' : routing.status === 'ENABLED' ? '停用' : '启用'}
      </button>
    </div>}
  </>;
}

interface ProductPickerProps {
  disabled: boolean;
  selectedIds: number[];
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
              disabled={disabled || selected} key={product.id} onClick={() => onSelect(product)}>
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
