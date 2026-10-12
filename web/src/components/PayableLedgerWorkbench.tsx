import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as payableLedgerApi from '../api/payableLedgerApi.ts';
import { createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<payableLedgerApi.PayableStatus, string> = {
  OPEN: '未核销',
  PARTIAL: '部分核销',
  SETTLED: '已结清',
  REVERSED: '已冲销',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function PayableLedgerWorkbench({ canViewSettlements }: { canViewSettlements: boolean }) {
  const [items, setItems] = useState<payableLedgerApi.Payable[]>([]);
  const [selected, setSelected] = useState<payableLedgerApi.Payable | null>(null);
  const [supplierDraft, setSupplierDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('OPEN');
  const [supplierId, setSupplierId] = useState<number | null>(null);
  const [status, setStatus] = useState('OPEN');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [supplierNames, setSupplierNames] = useState<Map<number, string>>(new Map());
  const [settlements, setSettlements] = useState<payableLedgerApi.SettlementRecord[]>([]);
  const [settlementError, setSettlementError] = useState('');
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const settlementVersion = useRef(createRequestGate());
  const supplierNameVersion = useRef(createRequestGate());

  const rememberSuppliers = (suppliers: masterDataApi.Supplier[]) => {
    if (suppliers.length === 0) return;
    setSupplierNames((current) => {
      const next = new Map(current);
      for (const supplier of suppliers) next.set(supplier.id, `${supplier.name}（${supplier.code}）`);
      return next;
    });
  };

  const ensureSupplierNames = (payables: payableLedgerApi.Payable[]) => {
    const ids = [...new Set(payables.map((item) => item.supplierId)
      .filter((id) => !supplierNames.has(id)))];
    if (ids.length === 0) return;
    const version = supplierNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await masterDataApi.getSupplier(id); } catch { return null; }
    })).then((values) => {
      if (!supplierNameVersion.current.isCurrent(version)) return;
      rememberSuppliers(values.filter((value): value is masterDataApi.Supplier => value !== null));
    });
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void payableLedgerApi.searchPayables(supplierId, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
        ensureSupplierNames(result.items);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '应付台账加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [supplierId, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    settlementVersion.current.next();
    setSelected(null);
    setSettlements([]);
    setSettlementError('');
  };

  const loadSettlements = (payable: payableLedgerApi.Payable) => {
    if (!canViewSettlements) {
      settlementVersion.current.next();
      setSettlements([]);
      setSettlementError('');
      return;
    }
    const version = settlementVersion.current.next();
    setSettlementError('');
    void payableLedgerApi.searchPayableSettlements(payable.id)
      .then((records) => {
        if (!settlementVersion.current.isCurrent(version)) return;
        setSettlements(records);
      })
      .catch((cause: unknown) => {
        if (settlementVersion.current.isCurrent(version)) {
          setSettlementError(errorMessage(cause, '核销流水加载失败。查看核销需要 finance:settlement 权限。'));
        }
      });
  };

  const choose = (payable: payableLedgerApi.Payable) => {
    detailVersion.current.next();
    setSelected(payable);
    setError('');
    setSettlementError('');
    setSettlements([]);
    ensureSupplierNames([payable]);
    loadSettlements(payable);
  };

  const applySearch = () => {
    clearSelection();
    setSupplierId(supplierDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const supplierName = (id: number) => supplierNames.get(id) ?? `供应商 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">财务 / 应付台账</p>
        <h1>应付台账</h1>
        <p>只读查看采购发票过账生成的应付；核销写动作仍走采购付款单。默认列出未核销（OPEN）。选中一行可查看该笔应付的核销流水（GET /api/settlements?type=PAYABLE&amp;targetId=），没有全局列表。</p>
      </div>
    </header>
    <div className="purchase-order-filters">
      <SupplierPicker label="按供应商筛选" value={supplierDraft} allowClear
        onChange={setSupplierDraft} onOptions={rememberSuppliers} />
      <label>状态<select aria-label="应付状态" value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="OPEN">未核销</option>
        <option value="PARTIAL">部分核销</option>
        <option value="SETTLED">已结清</option>
      </select></label>
      <button type="button" className="memory-button" onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>应付 id</th><th>供应商</th><th>来源单</th><th>应付金额</th><th>未核销</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((payable) => <tr key={payable.id}
            className={selected?.id === payable.id ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button"
              onClick={() => choose(payable)}>#{payable.id}</button></td>
            <td>{supplierName(payable.supplierId)}</td>
            <td>{payable.sourceDocNo}</td>
            <td>{payable.amount}</td>
            <td>{payable.outstandingAmount}</td>
            <td><StatusBadge status={payable.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载应付台账…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无应付记录，请调整筛选条件。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 笔</span>
          <button type="button" disabled={page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {selected ? <PayableDetails payable={selected} supplierName={supplierName}
          canViewSettlements={canViewSettlements} settlements={settlements}
          settlementError={settlementError} />
          : <p className="memory-empty">选择一笔应付查看详情。本接口无独立详情端点，详情取自列表行。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: payableLedgerApi.PayableStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status] ?? status}</span>;
}

interface SupplierPickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Supplier[]) => void;
}

function SupplierPicker({ label, value, allowClear = false, onChange, onOptions }: SupplierPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<masterDataApi.Supplier[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const supplierVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = supplierVersion.current.next();
    setLoading(true);
    setError('');
    void masterDataApi.searchSuppliers(keyword, '', page).then((result) => {
      if (!supplierVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (supplierVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '供应商加载失败'));
      }
    }).finally(() => {
      if (supplierVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>{label}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${label}关键词`} placeholder="输入供应商编码或名称"
        disabled={loading} value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            setKeyword(draft);
            setPage(1);
            setRefreshKey((current) => current + 1);
          }
        }} />
      <button type="button" className="memory-button" disabled={loading}
        onClick={() => { setKeyword(draft); setPage(1); setRefreshKey((current) => current + 1); }}>查找</button>
      {allowClear && value !== null && <button type="button" className="memory-button"
        onClick={() => onChange(null)}>清除</button>}
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={label}>
      {loading ? <span>正在加载供应商…</span>
        : items.length === 0 ? <span>未找到供应商</span>
          : items.map((supplier) => <button type="button" role="option" key={supplier.id}
            aria-selected={value === supplier.id}
            className={value === supplier.id ? 'reference-picker-selected' : ''}
            onClick={() => onChange(supplier.id)}>
            {supplier.name}<span>{supplier.code}</span>
          </button>)}
    </div>
    {total > PAGE_SIZE && <div className="reference-picker-pagination">
      <button type="button" disabled={loading || page <= 1}
        onClick={() => setPage((current) => current - 1)}>上一页</button>
      <span>第 {page} 页</span>
      <button type="button" disabled={loading || page * PAGE_SIZE >= total}
        onClick={() => setPage((current) => current + 1)}>下一页</button>
    </div>}
  </div>;
}

interface DetailsProps {
  payable: payableLedgerApi.Payable;
  supplierName: (id: number) => string;
  canViewSettlements: boolean;
  settlements: payableLedgerApi.SettlementRecord[];
  settlementError: string;
}

function PayableDetails({ payable, supplierName, canViewSettlements, settlements, settlementError }: DetailsProps) {
  return <>
    <StatusBadge status={payable.status} />
    <h2>应付 #{payable.id}</h2>
    <dl>
      <dt>供应商</dt><dd>{supplierName(payable.supplierId)}</dd>
      <dt>来源单</dt><dd>{payable.sourceDocNo}</dd>
      <dt>到期日</dt><dd>{payable.dueDate || '—'}</dd>
      <dt>应付金额</dt><dd>{payable.amount}</dd>
      <dt>已核销</dt><dd>{payable.settledAmount}</dd>
      <dt>未核销 outstandingAmount</dt><dd>{payable.outstandingAmount}</dd>
    </dl>
    <h3>核销流水</h3>
    <p>仅本笔应付（targetId=#{payable.id}）的只读核销记录；无全局流水、不可在此核销。</p>
    {!canViewSettlements && <p className="memory-empty">核销流水需要 finance:settlement 权限。</p>}
    {settlementError && <div className="memory-error" role="alert">{settlementError}</div>}
    {canViewSettlements && settlements.length === 0 && !settlementError
      && <p className="memory-empty">暂无核销记录。</p>}
    {settlements.length > 0 && <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
      <th>记录</th><th>金额</th><th>来源单</th><th>收付款单</th><th>日期</th><th>操作人</th>
    </tr></thead><tbody>{settlements.map((record) => <tr key={record.id}>
      <td>#{record.id}</td><td>{record.amount}</td>
      <td>{record.targetSourceDocNo || '—'}</td>
      <td>{record.paymentDocNo || '—'}</td>
      <td>{record.settlementDate || record.createdAt || '—'}</td>
      <td>{record.createdBy || '—'}</td>
    </tr>)}</tbody></table></div>}
  </>;
}
