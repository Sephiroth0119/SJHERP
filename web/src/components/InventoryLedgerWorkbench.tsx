import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import { ApiError } from '../api/http.ts';
import * as inventoryApi from '../api/inventoryApi.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;

const txnTypeLabels: Record<string, string> = {
  OPENING: '期初建账',
  COST_ADJUST: '成本调整',
  PURCHASE_IN: '采购入库',
  SALES_OUT: '销售出库',
  COUNT_GAIN: '盘盈',
  COUNT_LOSS: '盘亏',
  TRANSFER_OUT: '调出',
  TRANSFER_IN: '调入',
  PRODUCTION_ISSUE: '生产领料',
  PRODUCTION_RETURN: '生产退料',
  PRODUCTION_IN: '完工入库',
};

const emptyAdjustment = (): inventoryApi.InventoryAdjustmentForm => ({
  type: 'OPENING',
  warehouseId: null,
  productId: null,
  quantity: '',
  unitCost: '',
  adjustAmount: '',
});

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const txnLabel = (txnType: string) => txnTypeLabels[txnType] ?? txnType;

export function InventoryLedgerWorkbench({ canAdjust }: { canAdjust: boolean }) {
  const [items, setItems] = useState<inventoryApi.InventoryBalance[]>([]);
  const [selected, setSelected] = useState<inventoryApi.InventoryBalance | null>(null);
  const [transactions, setTransactions] = useState<inventoryApi.InventoryTransaction[]>([]);
  const [warehouseDraft, setWarehouseDraft] = useState<number | null>(null);
  const [productDraft, setProductDraft] = useState<number | null>(null);
  const [keywordDraft, setKeywordDraft] = useState('');
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [productId, setProductId] = useState<number | null>(null);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [txnPage, setTxnPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [txnTotal, setTxnTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [txnRefreshKey, setTxnRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [txnLoading, setTxnLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<inventoryApi.InventoryAdjustmentForm>(emptyAdjustment);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(new Map());
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const listVersion = useRef(createRequestGate());
  const txnVersion = useRef(createRequestGate());
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

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void inventoryApi.searchBalances(warehouseId, productId, keyword, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '库存余额加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [warehouseId, productId, keyword, page, refreshKey]);

  useEffect(() => {
    if (!selected) {
      txnVersion.current.next();
      setTransactions([]);
      setTxnTotal(0);
      setTxnLoading(false);
      return;
    }
    const version = txnVersion.current.next();
    setTxnLoading(true);
    void inventoryApi.searchTransactions(selected.warehouseId, selected.productId, txnPage, PAGE_SIZE)
      .then((result) => {
        if (!txnVersion.current.isCurrent(version)) return;
        setTransactions(result.items);
        setTxnTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (txnVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '库存流水加载失败'));
        }
      })
      .finally(() => {
        if (txnVersion.current.isCurrent(version)) setTxnLoading(false);
      });
  }, [selected?.warehouseId, selected?.productId, txnPage, txnRefreshKey]);

  const applySearch = () => {
    if (saving) return;
    setSelected(null);
    setEditing(false);
    setWarehouseId(warehouseDraft);
    setProductId(productDraft);
    setKeyword(keywordDraft);
    setPage(1);
    setTxnPage(1);
    setRefreshKey((value) => value + 1);
  };

  const choose = (row: inventoryApi.InventoryBalance) => {
    if (saving) return;
    setSelected(row);
    setEditing(false);
    setTxnPage(1);
    setError('');
    setNotice('');
  };

  const saveAdjustment = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canAdjust || !mutationInFlight.current.tryAcquire()) return;
    setError('');
    setNotice('');
    try {
      const validationError = inventoryApi.validateAdjustmentForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      if (!window.confirm(
        form.type === 'OPENING'
          ? '期初建账将立即增加该仓该品的数量与金额，并写入库存流水。确认继续吗？'
          : '成本调整将立即只改金额不改数量，并写入库存流水。确认继续吗？',
      )) return;
      setSaving(true);
      const result = await inventoryApi.createAdjustment(form);
      setNotice(`调整已过账：${result.srcDocNo ?? result.txnType}，过账后数量 ${result.balanceQuantityAfter} / 金额 ${result.balanceAmountAfter}`);
      setEditing(false);
      setForm(emptyAdjustment());
      setRefreshKey((value) => value + 1);
      setTxnRefreshKey((value) => value + 1);
      if (selected
        && selected.warehouseId === result.warehouseId
        && selected.productId === result.productId) {
        setTxnPage(1);
      } else {
        setSelected({
          warehouseId: result.warehouseId,
          warehouseCode: '',
          warehouseName: warehouseName(result.warehouseId),
          productId: result.productId,
          productCode: '',
          productName: productName(result.productId),
          quantity: result.balanceQuantityAfter,
          costAmount: result.balanceAmountAfter,
          unitCost: result.unitCost,
        });
        setTxnPage(1);
      }
    } catch (cause: unknown) {
      setError(errorMessage(cause, '库存调整失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}（名称不可用）`;
  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const txnPageCount = Math.max(1, Math.ceil(txnTotal / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">库存 / 台账</p>
        <h1>库存台账</h1>
        <p>按仓库、商品查看余额与流水。查询登录即可；期初/成本调整需 inventory:adjust，立即过账不可直接撤销。</p>
      </div>
      {canAdjust && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={() => {
          setEditing(true);
          setError('');
          setNotice('');
          setForm({
            ...emptyAdjustment(),
            warehouseId: selected?.warehouseId ?? warehouseDraft,
            productId: selected?.productId ?? productDraft,
          });
        }}>
        新建调整
      </button>}
    </header>
    <div className="purchase-order-filters">
      <WarehousePicker label="按仓库筛选" value={warehouseDraft} allowClear disabled={saving}
        onChange={setWarehouseDraft} onOptions={rememberWarehouses} />
      <ProductFilterPicker label="按商品筛选" value={productDraft} allowClear disabled={saving}
        onChange={setProductDraft} onOptions={rememberProducts} />
      <label>关键字<input aria-label="商品关键字" placeholder="商品编码或名称"
        disabled={saving} value={keywordDraft}
        onChange={(event) => setKeywordDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            applySearch();
          }
        }} /></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>仓库</th><th>商品</th><th>数量</th><th>金额</th><th>单价</th>
        </tr></thead><tbody>
          {items.map((row) => {
            const key = `${row.warehouseId}-${row.productId}`;
            const active = selected?.warehouseId === row.warehouseId && selected.productId === row.productId;
            return <tr key={key} className={active ? 'memory-row-selected' : ''}>
              <td><button type="button" className="table-row-button" disabled={saving}
                onClick={() => choose(row)}>
                {row.warehouseName || warehouseName(row.warehouseId)}（{row.warehouseCode || row.warehouseId}）
              </button></td>
              <td>{row.productName || productName(row.productId)}（{row.productCode || row.productId}）</td>
              <td>{row.quantity}</td>
              <td>{row.costAmount}</td>
              <td>{row.unitCost ?? '—'}</td>
            </tr>;
          })}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载库存余额…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无库存余额。可按仓库或商品筛选，或在有权限时做期初建账。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { setSelected(null); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 条</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { setSelected(null); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing && canAdjust ? <AdjustmentForm form={form} setForm={setForm} saving={saving}
          warehouseName={warehouseName} productName={productName}
          onWarehouses={rememberWarehouses} onProducts={rememberProducts}
          onSave={saveAdjustment} onCancel={() => setEditing(false)} />
          : selected ? <LedgerDetails row={selected} transactions={transactions}
            txnLoading={txnLoading} txnPage={txnPage} txnPageCount={txnPageCount} txnTotal={txnTotal}
            saving={saving} onPage={setTxnPage} />
            : <p className="memory-empty">选择一条余额查看该仓×品流水。流水接口要求仓库与商品都已选定。</p>}
      </aside>
    </div>
  </section>;
}

function WarehousePicker({ label, value, allowClear = false, disabled, onChange, onOptions }: {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Warehouse[]) => void;
}) {
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

function ProductFilterPicker({ label, value, allowClear = false, disabled, onChange, onOptions }: {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: catalogApi.Product[]) => void;
}) {
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
    <strong>{label}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${label}关键词`} placeholder="输入商品编码、名称或条码"
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
      {loading ? <span>正在加载商品…</span>
        : items.length === 0 ? <span>未找到商品</span>
          : items.map((product) => <button type="button" role="option" key={product.id}
            aria-selected={value === product.id}
            className={value === product.id ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => onChange(product.id)}>
            {product.name}<span>{product.code}</span>
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

function AdjustmentForm({ form, setForm, saving, warehouseName, productName,
  onWarehouses, onProducts, onSave, onCancel }: {
  form: inventoryApi.InventoryAdjustmentForm;
  setForm: (form: inventoryApi.InventoryAdjustmentForm) => void;
  saving: boolean;
  warehouseName: (id: number) => string;
  productName: (id: number) => string;
  onWarehouses: (items: masterDataApi.Warehouse[]) => void;
  onProducts: (items: catalogApi.Product[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}) {
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>库存调整</h2>
    <p className="sales-invoice-impact">
      仅支持期初建账 OPENING 与成本调整 COST_ADJUST。调整立即过账，没有草稿。后端没有调整单一览接口。
    </p>
    <label>类型<select aria-label="调整类型" disabled={saving} value={form.type}
      onChange={(event) => {
        const type = event.target.value === 'COST_ADJUST' ? 'COST_ADJUST' : 'OPENING';
        setForm({ ...form, type });
      }}>
      <option value="OPENING">期初建账</option>
      <option value="COST_ADJUST">成本调整</option>
    </select></label>
    <WarehousePicker label="仓库" value={form.warehouseId} disabled={saving}
      onChange={(warehouseId) => setForm({ ...form, warehouseId })}
      onOptions={onWarehouses} />
    <ProductFilterPicker label="商品" value={form.productId} disabled={saving}
      onChange={(productId) => setForm({ ...form, productId })}
      onOptions={onProducts} />
    {form.type === 'OPENING' ? <>
      <label>数量<input required inputMode="decimal"
        pattern={inventoryApi.positiveQuantityPattern.source}
        disabled={saving} value={form.quantity}
        onChange={(event) => setForm({ ...form, quantity: event.target.value })} /></label>
      <label>单价<input required inputMode="decimal"
        pattern={inventoryApi.nonNegativeUnitCostPattern.source}
        disabled={saving} value={form.unitCost}
        onChange={(event) => setForm({ ...form, unitCost: event.target.value })} /></label>
    </> : <label>调整额（可负）<input required inputMode="decimal"
      pattern={inventoryApi.nonzeroAdjustAmountPattern.source}
      disabled={saving} value={form.adjustAmount}
      onChange={(event) => setForm({ ...form, adjustAmount: event.target.value })} /></label>}
    <p>{form.warehouseId ? `仓库：${warehouseName(form.warehouseId)}` : '尚未选择仓库'}
      {form.productId ? ` · 商品：${productName(form.productId)}` : ''}</p>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary"
        disabled={saving}>{saving ? '过账中…' : '确认调整并过账'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

function LedgerDetails({ row, transactions, txnLoading, txnPage, txnPageCount, txnTotal, saving, onPage }: {
  row: inventoryApi.InventoryBalance;
  transactions: inventoryApi.InventoryTransaction[];
  txnLoading: boolean;
  txnPage: number;
  txnPageCount: number;
  txnTotal: number;
  saving: boolean;
  onPage: (page: number) => void;
}) {
  return <>
    <h2>{row.productName || `商品 #${row.productId}`}</h2>
    <dl>
      <dt>仓库</dt><dd>{row.warehouseName || `仓库 #${row.warehouseId}`}（{row.warehouseCode || row.warehouseId}）</dd>
      <dt>商品</dt><dd>{row.productName || `商品 #${row.productId}`}（{row.productCode || row.productId}）</dd>
      <dt>结存数量</dt><dd>{row.quantity}</dd>
      <dt>结存金额</dt><dd>{row.costAmount}</dd>
      <dt>加权单价</dt><dd>{row.unitCost ?? '—'}</dd>
    </dl>
    <h3>库存流水</h3>
    <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
      <th>时间</th><th>类型</th><th>数量</th><th>单价</th><th>金额</th>
      <th>过账后数量</th><th>过账后金额</th><th>来源</th><th>操作人</th>
    </tr></thead><tbody>
      {transactions.map((txn) => <tr key={txn.id}>
        <td>{txn.createdAt}</td>
        <td>{txnLabel(txn.txnType)}</td>
        <td>{txn.quantity}</td>
        <td>{txn.unitCost ?? '—'}</td>
        <td>{txn.totalCost}</td>
        <td>{txn.balanceQuantityAfter}</td>
        <td>{txn.balanceAmountAfter}</td>
        <td>{txn.srcDocNo ? `${txn.srcDocType ?? ''} ${txn.srcDocNo}${txn.srcLineNo != null ? ` #${txn.srcLineNo}` : ''}` : '—'}</td>
        <td>{txn.operator || '—'}</td>
      </tr>)}
    </tbody></table></div>
    {txnLoading && <p className="memory-empty" role="status">正在加载库存流水…</p>}
    {!txnLoading && transactions.length === 0 && <p className="memory-empty">该仓×品暂无流水。</p>}
    <div className="memory-pagination">
      <button type="button" disabled={saving || txnPage <= 1}
        onClick={() => onPage(txnPage - 1)}>上一页</button>
      <span>第 {txnPage} / {txnPageCount} 页，共 {txnTotal} 条</span>
      <button type="button" disabled={saving || txnPage >= txnPageCount}
        onClick={() => onPage(txnPage + 1)}>下一页</button>
    </div>
  </>;
}
