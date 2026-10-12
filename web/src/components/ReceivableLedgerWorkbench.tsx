import { useEffect, useRef, useState } from 'react';
import * as customerApi from '../api/customerApi.ts';
import { ApiError } from '../api/http.ts';
import * as receivableLedgerApi from '../api/receivableLedgerApi.ts';
import { createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<receivableLedgerApi.ReceivableStatus, string> = {
  OPEN: '未核销',
  PARTIAL: '部分核销',
  SETTLED: '已结清',
  REVERSED: '已冲销',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function ReceivableLedgerWorkbench({ canViewSettlements }: { canViewSettlements: boolean }) {
  const [items, setItems] = useState<receivableLedgerApi.Receivable[]>([]);
  const [selected, setSelected] = useState<receivableLedgerApi.Receivable | null>(null);
  const [customerDraft, setCustomerDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('OPEN');
  const [customerId, setCustomerId] = useState<number | null>(null);
  const [status, setStatus] = useState('OPEN');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [customerNames, setCustomerNames] = useState<Map<number, string>>(new Map());
  const [settlements, setSettlements] = useState<receivableLedgerApi.SettlementRecord[]>([]);
  const [settlementError, setSettlementError] = useState('');
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const settlementVersion = useRef(createRequestGate());
  const customerNameVersion = useRef(createRequestGate());

  const rememberCustomers = (customers: customerApi.Customer[]) => {
    if (customers.length === 0) return;
    setCustomerNames((current) => {
      const next = new Map(current);
      for (const customer of customers) next.set(customer.id, `${customer.name}（${customer.code}）`);
      return next;
    });
  };

  const ensureCustomerNames = (receivables: receivableLedgerApi.Receivable[]) => {
    const ids = [...new Set(receivables.map((item) => item.customerId)
      .filter((id) => !customerNames.has(id)))];
    if (ids.length === 0) return;
    const version = customerNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await customerApi.getCustomer(id); } catch { return null; }
    })).then((values) => {
      if (!customerNameVersion.current.isCurrent(version)) return;
      rememberCustomers(values.filter((value): value is customerApi.Customer => value !== null));
    });
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void receivableLedgerApi.searchReceivables(customerId, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
        ensureCustomerNames(result.items);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '应收台账加载失败。查应收需要 sales:invoice 权限。'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [customerId, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    settlementVersion.current.next();
    setSelected(null);
    setSettlements([]);
    setSettlementError('');
  };

  const loadSettlements = (receivable: receivableLedgerApi.Receivable) => {
    if (!canViewSettlements) {
      settlementVersion.current.next();
      setSettlements([]);
      setSettlementError('');
      return;
    }
    const version = settlementVersion.current.next();
    setSettlementError('');
    void receivableLedgerApi.searchReceivableSettlements(receivable.id)
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

  const choose = (receivable: receivableLedgerApi.Receivable) => {
    const version = detailVersion.current.next();
    setSelected(receivable);
    setError('');
    setSettlementError('');
    setSettlements([]);
    ensureCustomerNames([receivable]);
    loadSettlements(receivable);
    void receivableLedgerApi.getReceivable(receivable.id).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureCustomerNames([detail]);
      loadSettlements(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '应收详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    clearSelection();
    setCustomerId(customerDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const customerName = (id: number) => customerNames.get(id) ?? `客户 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">财务 / 应收台账</p>
        <h1>应收台账</h1>
        <p>只读查看销售发票过账生成的应收；核销写动作仍走销售收款单。默认列出未核销（OPEN）。未核销字段为 openAmount。选中一行可查看该笔应收的核销流水（GET /api/settlements?type=RECEIVABLE&amp;targetId=），没有全局列表。</p>
      </div>
    </header>
    <div className="purchase-order-filters">
      <CustomerPicker label="按客户筛选" value={customerDraft} allowClear
        onChange={setCustomerDraft} onOptions={rememberCustomers} />
      <label>状态<select aria-label="应收状态" value={statusDraft}
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
          <th>应收 id</th><th>客户</th><th>来源单</th><th>应收金额</th><th>未核销</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((receivable) => <tr key={receivable.id}
            className={selected?.id === receivable.id ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button"
              onClick={() => choose(receivable)}>#{receivable.id}</button></td>
            <td>{customerName(receivable.customerId)}</td>
            <td>{receivable.sourceDocNo}</td>
            <td>{receivable.amount}</td>
            <td>{receivable.openAmount}</td>
            <td><StatusBadge status={receivable.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载应收台账…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无应收记录，请调整筛选条件。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 笔</span>
          <button type="button" disabled={page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {selected ? <ReceivableDetails receivable={selected} customerName={customerName}
          canViewSettlements={canViewSettlements} settlements={settlements}
          settlementError={settlementError} />
          : <p className="memory-empty">选择一笔应收查看详情。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: receivableLedgerApi.ReceivableStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status] ?? status}</span>;
}

interface CustomerPickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: customerApi.Customer[]) => void;
}

function CustomerPicker({ label, value, allowClear = false, onChange, onOptions }: CustomerPickerProps) {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<customerApi.Customer[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const customerVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = customerVersion.current.next();
    setLoading(true);
    setError('');
    void customerApi.searchCustomers(keyword, '', page).then((result) => {
      if (!customerVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (customerVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '客户加载失败'));
      }
    }).finally(() => {
      if (customerVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [keyword, page, refreshKey]);

  return <div className="reference-picker">
    <strong>{label}</strong>
    <div className="reference-picker-search">
      <input aria-label={`${label}关键词`} placeholder="输入客户编码或名称"
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
      {loading ? <span>正在加载客户…</span>
        : items.length === 0 ? <span>未找到客户</span>
          : items.map((customer) => <button type="button" role="option" key={customer.id}
            aria-selected={value === customer.id}
            className={value === customer.id ? 'reference-picker-selected' : ''}
            onClick={() => onChange(customer.id)}>
            {customer.name}<span>{customer.code}</span>
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
  receivable: receivableLedgerApi.Receivable;
  customerName: (id: number) => string;
  canViewSettlements: boolean;
  settlements: receivableLedgerApi.SettlementRecord[];
  settlementError: string;
}

function ReceivableDetails({ receivable, customerName, canViewSettlements, settlements, settlementError }: DetailsProps) {
  return <>
    <StatusBadge status={receivable.status} />
    <h2>应收 #{receivable.id}</h2>
    <dl>
      <dt>客户</dt><dd>{customerName(receivable.customerId)}</dd>
      <dt>来源单</dt><dd>{receivable.sourceDocNo}</dd>
      <dt>到期日</dt><dd>{receivable.dueDate || '—'}</dd>
      <dt>应收金额</dt><dd>{receivable.amount}</dd>
      <dt>已核销</dt><dd>{receivable.settledAmount}</dd>
      <dt>未核销 openAmount</dt><dd>{receivable.openAmount}</dd>
    </dl>
    <h3>核销流水</h3>
    <p>仅本笔应收（targetId=#{receivable.id}）的只读核销记录；无全局流水、不可在此核销。</p>
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
