import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as transferApi from '../api/transferApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';
import { useFocusDocument } from './useFocusDocument.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<transferApi.TransferStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

const emptyForm = (): transferApi.TransferForm => ({
  fromWarehouseId: null,
  toWarehouseId: null,
  remark: '',
  lines: [],
});

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function TransferWorkbench({ focusDocNo }: { focusDocNo?: string } = {}) {
  const [items, setItems] = useState<transferApi.Transfer[]>([]);
  const [selected, setSelected] = useState<transferApi.Transfer | null>(null);
  const [warehouseDraft, setWarehouseDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<transferApi.TransferForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(new Map());
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const warehouseNameVersion = useRef(createRequestGate());
  const productNameVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  const rememberWarehouses = (warehouses: masterDataApi.Warehouse[]) => {
    if (warehouses.length === 0) return;
    setWarehouseNames((current) => {
      const next = new Map(current);
      for (const warehouse of warehouses) next.set(warehouse.id, `${warehouse.name}（${warehouse.code}）`);
      return next;
    });
  };

  const rememberProducts = (products: catalogApi.Product[]) => {
    if (products.length === 0) return;
    setProductNames((current) => {
      const next = new Map(current);
      for (const product of products) next.set(product.id, `${product.name}（${product.code}）`);
      return next;
    });
  };

  const ensureWarehouseNames = (documents: transferApi.Transfer[]) => {
    const ids = [...new Set(documents.flatMap((item) => [item.fromWarehouseId, item.toWarehouseId])
      .filter((id) => !warehouseNames.has(id)))];
    if (ids.length === 0) return;
    const version = warehouseNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await masterDataApi.getWarehouse(id); } catch { return null; }
    })).then((values) => {
      if (!warehouseNameVersion.current.isCurrent(version)) return;
      rememberWarehouses(values.filter((value): value is masterDataApi.Warehouse => value !== null));
    });
  };

  const ensureProductNames = (lines: Array<{ productId: number }>) => {
    const ids = [...new Set(lines.map((line) => line.productId)
      .filter((id) => !productNames.has(id)))];
    if (ids.length === 0) return;
    const version = productNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await catalogApi.getProduct(id); } catch { return null; }
    })).then((values) => {
      if (!productNameVersion.current.isCurrent(version)) return;
      rememberProducts(values.filter((value): value is catalogApi.Product => value !== null));
    });
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void transferApi.searchTransfers(warehouseId, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
        ensureWarehouseNames(result.items);
        ensureProductNames(result.items.flatMap((item) => item.lines));
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '调拨单列表加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [warehouseId, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (document: transferApi.Transfer) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(document);
    setEditing(false);
    setError('');
    setNotice('');
    ensureWarehouseNames([document]);
    ensureProductNames(document.lines);
    void transferApi.getTransfer(document.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureWarehouseNames([detail]);
      ensureProductNames(detail.lines);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '调拨单详情加载失败'));
      }
    });
  };

  useFocusDocument(focusDocNo, (docNo) => {
    void transferApi.getTransfer(docNo).then(choose).catch((cause: unknown) => {
      setError(errorMessage(cause, '调拨单详情加载失败'));
    });
  });

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setWarehouseId(warehouseDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = transferApi.validateTransferForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await transferApi.createTransfer(form);
      setSelected(result);
      setEditing(false);
      setNotice('调拨单草稿已创建');
      ensureWarehouseNames([result]);
      ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '调拨单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: 'approve' | 'post' | 'reverse') => {
    if (!selected || mutationInFlight.current.isLocked()) return;
    if (action === 'post' && !window.confirm(
      '过账将从调出仓出库并按原成本调入目标仓（金额守恒）；库存不足会整单回滚。此操作不可直接撤销，确认继续吗？',
    )) return;
    if (action === 'reverse' && !window.confirm(
      '冲销将按原两腿成本反向库存（调出仓回补、调入仓出库，不出凭证），原单转为已冲销；任一步失败会整单回滚。确认继续吗？',
    )) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = action === 'approve'
        ? await transferApi.approveTransfer(selected.docNo)
        : action === 'post'
          ? await transferApi.postTransfer(selected.docNo)
          : await transferApi.reverseTransfer(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '调拨单已审核'
        : action === 'post' ? '调拨单已过账' : '调拨单已冲销');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, action === 'approve' ? '调拨单审核失败'
        : action === 'post' ? '调拨单过账失败' : '调拨单冲销失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}（名称不可用）`;
  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">库存 / 调拨</p>
        <h1>库存调拨</h1>
        <p>在两个启用仓库之间建 TR- 调拨单；过账按移动加权从调出仓出库、按原成本调入，整企业库存金额守恒。</p>
      </div>
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={() => {
          clearSelection();
          setForm(emptyForm());
          setEditing(true);
          setError('');
          setNotice('');
        }}>
        新建调拨单
      </button>
    </header>
    <div className="purchase-order-filters">
      <WarehousePicker label="按仓库筛选（调出或调入）" value={warehouseDraft} allowClear disabled={saving}
        onChange={setWarehouseDraft} onOptions={rememberWarehouses} />
      <label>状态<select aria-label="调拨单状态" value={statusDraft}
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
          <th>调拨单号</th><th>调出仓</th><th>调入仓</th><th>行数</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((document) => <tr key={document.docNo}
            className={selected?.docNo === document.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(document)}>{document.docNo}</button></td>
            <td>{warehouseName(document.fromWarehouseId)}</td>
            <td>{warehouseName(document.toWarehouseId)}</td>
            <td>{document.lines.length}</td>
            <td><StatusBadge status={document.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载调拨单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无调拨单，可从右上角新建草稿。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 张</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <TransferForm form={form} setForm={setForm} saving={saving}
          warehouseName={warehouseName} productName={productName}
          onWarehouses={rememberWarehouses} onProducts={rememberProducts}
          onSave={save} onCancel={clearSelection} />
          : selected ? <TransferDetails document={selected} warehouseName={warehouseName}
            productName={productName} saving={saving}
            onApprove={() => void transition('approve')}
            onPost={() => void transition('post')}
            onReverse={() => void transition('reverse')} />
            : <p className="memory-empty">选择一张调拨单查看详情，或新建调拨单草稿。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: transferApi.TransferStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface WarehousePickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Warehouse[]) => void;
}

function WarehousePicker({ label, value, allowClear = false, disabled, onChange, onOptions }: WarehousePickerProps) {
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
      {allowClear && value !== null && <button type="button" className="memory-button" disabled={disabled}
        onClick={() => onChange(null)}>清除</button>}
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={label}>
      {loading ? <span>正在加载仓库…</span>
        : items.length === 0 ? <span>未找到仓库</span>
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

interface ProductPickerProps {
  selectedIds: number[];
  disabled: boolean;
  onSelect: (product: catalogApi.Product) => void;
  onOptions: (items: catalogApi.Product[]) => void;
}

function ProductPicker({ selectedIds, disabled, onSelect, onOptions }: ProductPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<catalogApi.Product[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const productVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = productVersion.current.next();
    setLoading(true);
    setError('');
    void catalogApi.searchProducts(keyword, 'ENABLED', page).then((result) => {
      if (!productVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (productVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '商品加载失败'));
      }
    }).finally(() => {
      if (productVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>添加调拨商品（GET /api/catalog/products）</strong>
    <div className="reference-picker-search">
      <input aria-label="商品关键词" placeholder="输入商品编码、名称或条码"
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
    <div className="reference-picker-results" role="listbox" aria-label="调拨商品">
      {loading ? <span>正在加载商品…</span>
        : items.length === 0 ? <span>未找到商品</span>
          : items.map((product) => {
            const chosen = selectedIds.includes(product.id);
            return <button type="button" role="option" key={product.id} aria-selected={chosen}
              className={chosen ? 'reference-picker-selected' : ''}
              disabled={disabled || chosen} onClick={() => onSelect(product)}>
              {product.name}<span>{product.code}</span>
            </button>;
          })}
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

interface FormProps {
  form: transferApi.TransferForm;
  setForm: (form: transferApi.TransferForm) => void;
  saving: boolean;
  warehouseName: (id: number) => string;
  productName: (id: number) => string;
  onWarehouses: (items: masterDataApi.Warehouse[]) => void;
  onProducts: (items: catalogApi.Product[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function TransferForm({ form, setForm, saving, warehouseName, productName,
  onWarehouses, onProducts, onSave, onCancel }: FormProps) {
  const addProduct = (product: catalogApi.Product) => {
    if (form.lines.some((line) => line.productId === product.id)) return;
    setForm({ ...form, lines: [...form.lines, { productId: product.id, quantity: '' }] });
  };
  const setLineQty = (index: number, quantity: string) => {
    const lines = [...form.lines];
    if (!lines[index]) return;
    lines[index] = { ...lines[index], quantity };
    setForm({ ...form, lines });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建调拨单</h2>
    <WarehousePicker label="调出仓库" value={form.fromWarehouseId} disabled={saving}
      onChange={(fromWarehouseId) => setForm({ ...form, fromWarehouseId })}
      onOptions={onWarehouses} />
    <WarehousePicker label="调入仓库" value={form.toWarehouseId} disabled={saving}
      onChange={(toWarehouseId) => setForm({ ...form, toWarehouseId })}
      onOptions={onWarehouses} />
    <label>说明<textarea maxLength={255} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <ProductPicker selectedIds={form.lines.map((line) => line.productId)}
      disabled={saving} onSelect={addProduct} onOptions={onProducts} />
    <fieldset disabled={saving}><legend>调拨行（至少一行，数量 &gt; 0）</legend>
      {form.lines.length === 0 ? <p className="memory-empty">请加入至少一个商品。</p>
        : form.lines.map((line, index) => <div className="purchase-order-line" key={line.productId}>
          <strong>{productName(line.productId)}</strong>
          <label>调拨数量<input required inputMode="decimal"
            pattern={transferApi.positiveQuantityPattern.source}
            value={line.quantity}
            onChange={(event) => setLineQty(index, event.target.value)} /></label>
          <button type="button" className="memory-button" onClick={() => setForm({
            ...form,
            lines: form.lines.filter((_, current) => current !== index),
          })}>移除</button>
        </div>)}
    </fieldset>
    <p>{form.fromWarehouseId ? `调出：${warehouseName(form.fromWarehouseId)}` : '尚未选择调出仓库'}
      {form.toWarehouseId ? ` · 调入：${warehouseName(form.toWarehouseId)}` : ''}</p>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary"
        disabled={saving || form.lines.length === 0}>{saving ? '创建中…' : '创建草稿'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  document: transferApi.Transfer;
  warehouseName: (id: number) => string;
  productName: (id: number) => string;
  saving: boolean;
  onApprove: () => void;
  onPost: () => void;
  onReverse: () => void;
}

function TransferDetails({ document, warehouseName, productName, saving,
  onApprove, onPost, onReverse }: DetailsProps) {
  return <>
    <StatusBadge status={document.status} />
    <h2>{document.docNo}</h2>
    <dl>
      <dt>调出仓库</dt><dd>{warehouseName(document.fromWarehouseId)}</dd>
      <dt>调入仓库</dt><dd>{warehouseName(document.toWarehouseId)}</dd>
      <dt>说明</dt><dd>{document.remark || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines"><table className="memory-table"><thead><tr>
      <th>行号</th><th>商品</th><th>数量</th>
    </tr></thead><tbody>{document.lines.map((line) => <tr key={line.lineNo}>
      <td>{line.lineNo}</td><td>{productName(line.productId)}</td><td>{line.quantity}</td>
    </tr>)}</tbody></table></div>
    {document.status === 'DRAFT' && <div className="customer-actions">
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={onApprove}>{saving ? '处理中…' : '审核'}</button>
    </div>}
    {document.status === 'APPROVED' && <>
      <p className="sales-invoice-impact">
        过账将从调出仓出库并按原成本调入目标仓，金额守恒；库存不足时整张调拨单及库存写入全部回滚。
      </p>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={onPost}>{saving ? '处理中…' : '过账'}</button>
      </div>
    </>}
    {document.status === 'COMPLETED' && <>
      <p className="sales-invoice-impact">
        冲销将按原两腿成本反向库存（调出仓回补、调入仓出库），不出会计凭证；原单转为已冲销。
      </p>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-danger" disabled={saving}
          onClick={onReverse}>{saving ? '处理中…' : '冲销'}</button>
      </div>
    </>}
  </>;
}
