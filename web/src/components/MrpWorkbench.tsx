import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as mrpApi from '../api/mrpApi.ts';
import { formatQuantityDisplay } from '../api/productionBomApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const suggestionTypeLabel = (type: mrpApi.SuggestionType): string => {
  switch (type) {
    case 'PRODUCTION':
      return '生产建议';
    case 'PURCHASE':
      return '采购建议';
    default: {
      const exhaustive: never = type;
      return exhaustive;
    }
  }
};

export function MrpWorkbench({ canUse }: { canUse: boolean }) {
  const [items, setItems] = useState<mrpApi.MrpRunSummary[]>([]);
  const [selected, setSelected] = useState<mrpApi.MrpRun | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(canUse);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<mrpApi.MrpRunForm>(mrpApi.emptyMrpRunForm());
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

  const ensureProductNames = (suggestions: mrpApi.MrpSuggestion[]) => {
    const ids = [...new Set(suggestions.map((item) => item.productId).filter((id) => !productNames.has(id)))];
    for (const id of ids) {
      void catalogApi.getProduct(id).then((product) => rememberProducts([product])).catch(() => undefined);
    }
  };

  const ensureWarehouseNames = (runs: Array<{ warehouseId: number }>) => {
    const ids = [...new Set(runs.map((item) => item.warehouseId).filter((id) => !warehouseNames.has(id)))];
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
    void mrpApi.searchMrpRuns(page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      ensureWarehouseNames(result.items);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, 'MRP 运行历史加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [canUse, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (run: mrpApi.MrpRunSummary) => {
    if (!canUse || saving) return;
    const version = detailVersion.current.next();
    setEditing(false);
    setError('');
    setNotice('');
    setSelected({ ...run, suggestions: [] });
    ensureWarehouseNames([run]);
    void mrpApi.getMrpRun(run.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureWarehouseNames([detail]);
      ensureProductNames(detail.suggestions);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, 'MRP 运行详情加载失败'));
      }
    });
  };

  const startRun = () => {
    if (!canUse || saving) return;
    clearSelection();
    setForm(mrpApi.emptyMrpRunForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const trigger = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canUse || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = mrpApi.validateMrpRunForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await mrpApi.runMrp(form);
      setSelected(result);
      setEditing(false);
      setNotice(`MRP 已触发，运行号 ${result.docNo}。全量重算会生成新运行，历史保留。`);
      ensureWarehouseNames([result]);
      ensureProductNames(result.suggestions);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, 'MRP 运行失败，请检查仓库和需求来源'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}`;
  const unitName = (id: number) => units.find((unit) => unit.id === id)?.name ?? `单位 ${id}`;
  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const sourceLabel = (run: { includeForecast: boolean; includeSalesOrder: boolean }) => {
    const parts = [
      run.includeForecast ? '预测' : null,
      run.includeSalesOrder ? '销售订单' : null,
    ].filter((item): item is string => item !== null);
    return parts.length === 0 ? '—' : parts.join(' + ');
  };

  if (!canUse) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">生产 / MRP</p>
          <h1>MRP 运行</h1>
          <p>触发与查询均须 production:mrp。当前账号无此权限，未请求运行历史，避免 403 刷屏。</p>
        </div>
      </header>
      <p className="memory-empty" role="status">需要 production:mrp 权限才能查看 MRP 运行历史与触发运算。</p>
    </section>;
  }

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / MRP</p>
        <h1>MRP 运行</h1>
        <p>按选定仓库 regenerative 全重算。历史查询与触发均须 production:mrp。有净需求且存在启用 BOM 的物料走生产建议，其余走采购建议。</p>
      </div>
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startRun}>触发 MRP</button>
    </header>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>运行号</th><th>仓库</th><th>需求来源</th><th>运行时间</th>
        </tr></thead><tbody>
          {items.map((run) => <tr key={run.docNo}
            className={selected?.docNo === run.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(run)}>{run.docNo}</button></td>
            <td>{warehouseName(run.warehouseId)}</td>
            <td>{sourceLabel(run)}</td>
            <td>{run.runAt}</td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载 MRP 运行历史…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无 MRP 运行。可从右上角选择仓库触发全量重算。
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
        {editing ? <MrpRunFormView form={form} setForm={setForm} saving={saving}
          warehouseName={warehouseName} rememberWarehouses={rememberWarehouses}
          onSave={trigger} onCancel={clearSelection} />
          : selected ? <MrpRunDetails run={selected} warehouseName={warehouseName}
            productName={productName} unitName={unitName} sourceLabel={sourceLabel} />
            : <p className="memory-empty">选择左侧运行查看建议行，或触发新的 MRP 运算。</p>}
      </aside>
    </div>
  </section>;
}

interface FormProps {
  form: mrpApi.MrpRunForm;
  setForm: (form: mrpApi.MrpRunForm) => void;
  saving: boolean;
  warehouseName: (id: number) => string;
  rememberWarehouses: (warehouses: masterDataApi.Warehouse[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function MrpRunFormView({
  form, setForm, saving, warehouseName, rememberWarehouses, onSave, onCancel,
}: FormProps) {
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>触发 MRP 运行</h2>
    <p>库存按仓核算，须指定仓库。至少勾选预测或销售订单之一。运行后生成新 MRP- 编号，不覆盖历史。</p>
    <WarehousePicker label="运算仓库（GET /api/warehouse/warehouses）" value={form.warehouseId}
      disabled={saving} onChange={(warehouseId) => setForm({ ...form, warehouseId })}
      onOptions={rememberWarehouses} />
    <p>{form.warehouseId ? `当前仓库：${warehouseName(form.warehouseId)}` : '尚未选择仓库'}</p>
    <label>
      <input type="checkbox" disabled={saving} checked={form.includeForecast}
        onChange={(event) => setForm({ ...form, includeForecast: event.target.checked })} />
      含启用中的需求计划预测
    </label>
    <label>
      <input type="checkbox" disabled={saving} checked={form.includeSalesOrder}
        onChange={(event) => setForm({ ...form, includeSalesOrder: event.target.checked })} />
      含未完结销售订单剩余量
    </label>
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '运算中…' : '确认触发'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  run: mrpApi.MrpRun;
  warehouseName: (id: number) => string;
  productName: (id: number) => string;
  unitName: (id: number) => string;
  sourceLabel: (run: { includeForecast: boolean; includeSalesOrder: boolean }) => string;
}

function MrpRunDetails({
  run, warehouseName, productName, unitName, sourceLabel,
}: DetailsProps) {
  const production = run.suggestions.filter((item) => item.type === 'PRODUCTION');
  const purchase = run.suggestions.filter((item) => item.type === 'PURCHASE');
  return <>
    <h2>{run.docNo}</h2>
    <p>{run.runAt}</p>
    <dl>
      <dt>仓库</dt><dd>{warehouseName(run.warehouseId)}</dd>
      <dt>需求来源</dt><dd>{sourceLabel(run)}</dd>
      <dt>备注</dt><dd>{run.remark || '—'}</dd>
      <dt>创建</dt><dd>{run.createdBy || '—'}</dd>
    </dl>
    <SuggestionTable title="生产建议" empty="本运行无生产建议。" items={production}
      productName={productName} unitName={unitName} />
    <SuggestionTable title="采购建议" empty="本运行无采购建议。" items={purchase}
      productName={productName} unitName={unitName} />
  </>;
}

function SuggestionTable({
  title, empty, items, productName, unitName,
}: {
  title: string;
  empty: string;
  items: mrpApi.MrpSuggestion[];
  productName: (id: number) => string;
  unitName: (id: number) => string;
}) {
  return <section className="memory-group">
    <header><strong>{title}</strong><span>{items.length} 行</span></header>
    {items.length === 0 ? <p className="memory-empty">{empty}</p>
      : <div className="memory-table-wrap purchase-order-lines">
          <table className="memory-table"><thead><tr>
            <th>类型</th><th>商品</th><th>层级</th><th>毛需求</th><th>库存</th><th>净需求</th><th>基本单位</th>
          </tr></thead><tbody>
            {items.map((item) => <tr key={`${item.type}-${item.productId}-${item.level}`}>
              <td>{suggestionTypeLabel(item.type)}</td>
              <td>{productName(item.productId)}</td>
              <td>L{item.level}</td>
              <td>{formatQuantityDisplay(item.grossRequirement)}</td>
              <td>{formatQuantityDisplay(item.onHand)}</td>
              <td>{formatQuantityDisplay(item.netRequirement)}</td>
              <td>{unitName(item.baseUnitId)}</td>
            </tr>)}
          </tbody></table>
        </div>}
  </section>;
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
