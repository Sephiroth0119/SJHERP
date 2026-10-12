import { useEffect, useRef, useState } from 'react';
import * as customerApi from '../api/customerApi.ts';
import { ApiError } from '../api/http.ts';
import * as collectionApi from '../api/collectionApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<collectionApi.CollectionStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

function today() {
  const value = new Date();
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

const emptyForm = (): collectionApi.CollectionForm => ({
  customerId: null,
  paymentAccountId: null,
  receiptDate: today(),
  remark: '',
  lines: [],
});

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const canAllocate = (receivable: collectionApi.Receivable) =>
  (receivable.status === 'OPEN' || receivable.status === 'PARTIAL')
  && collectionApi.positiveAllocatedAmountPattern.test(receivable.openAmount);

export function CollectionWorkbench() {
  const [items, setItems] = useState<collectionApi.Collection[]>([]);
  const [selected, setSelected] = useState<collectionApi.Collection | null>(null);
  const [customerDraft, setCustomerDraft] = useState<number | null>(null);
  const [accountDraft, setAccountDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [customerId, setCustomerId] = useState<number | null>(null);
  const [paymentAccountId, setPaymentAccountId] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<collectionApi.CollectionForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [customerNames, setCustomerNames] = useState<Map<number, string>>(new Map());
  const [accountNames, setAccountNames] = useState<Map<number, string>>(new Map());
  const [settlements, setSettlements] = useState<collectionApi.SettlementRecord[]>([]);
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const settlementVersion = useRef(createRequestGate());
  const customerNameVersion = useRef(createRequestGate());
  const accountNameVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  const rememberCustomers = (customers: customerApi.Customer[]) => {
    if (customers.length === 0) return;
    setCustomerNames((current) => {
      const next = new Map(current);
      for (const customer of customers) next.set(customer.id, `${customer.name}（${customer.code}）`);
      return next;
    });
  };

  const rememberAccounts = (accounts: collectionApi.FundAccount[]) => {
    if (accounts.length === 0) return;
    setAccountNames((current) => {
      const next = new Map(current);
      for (const account of accounts) next.set(account.id, `${account.name}（${account.code}）`);
      return next;
    });
  };

  const ensureCustomerNames = (collections: collectionApi.Collection[]) => {
    const ids = [...new Set(collections.map((item) => item.customerId)
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

  const ensureAccountNames = (collections: Array<{ paymentAccountId: number }>) => {
    const ids = [...new Set(collections.map((item) => item.paymentAccountId)
      .filter((id) => !accountNames.has(id)))];
    if (ids.length === 0) return;
    const version = accountNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await collectionApi.getFundAccount(id); } catch { return null; }
    })).then((values) => {
      if (!accountNameVersion.current.isCurrent(version)) return;
      rememberAccounts(values.filter((value): value is collectionApi.FundAccount => value !== null));
    });
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void collectionApi.searchCollections(customerId, paymentAccountId, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
        ensureCustomerNames(result.items);
        ensureAccountNames(result.items);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '收款单列表加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [customerId, paymentAccountId, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    settlementVersion.current.next();
    setSelected(null);
    setEditing(false);
    setSettlements([]);
  };

  const loadSettlements = (collection: collectionApi.Collection) => {
    const version = settlementVersion.current.next();
    void Promise.all(collection.lines.map((line) =>
      collectionApi.searchReceivableSettlements(line.receivableId)))
      .then((groups) => {
        if (!settlementVersion.current.isCurrent(version)) return;
        setSettlements(groups.flat());
      })
      .catch((cause: unknown) => {
        if (settlementVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '核销记录加载失败'));
        }
      });
  };

  const choose = (collection: collectionApi.Collection) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(collection);
    setEditing(false);
    setError('');
    setNotice('');
    setSettlements([]);
    ensureCustomerNames([collection]);
    ensureAccountNames([collection]);
    loadSettlements(collection);
    void collectionApi.getCollection(collection.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureCustomerNames([detail]);
      ensureAccountNames([detail]);
      loadSettlements(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '收款单详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setCustomerId(customerDraft);
    setPaymentAccountId(accountDraft);
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
      const validationError = collectionApi.validateCollectionForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await collectionApi.createCollection(form);
      setSelected(result);
      setEditing(false);
      setNotice('收款单草稿已创建');
      ensureCustomerNames([result]);
      ensureAccountNames([result]);
      loadSettlements(result);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '收款单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: 'approve' | 'post' | 'reverse') => {
    if (!selected || mutationInFlight.current.isLocked()) return;
    if (action === 'post' && !window.confirm(
      '过账将核销应收并生成现金侧凭证（借资金科目 / 贷应收账款）；任一步失败会整单回滚。此操作不可直接撤销，确认继续吗？',
    )) return;
    if (action === 'reverse' && !window.confirm(
      '冲销将反向核销应收并红冲现金侧凭证，原单转为已冲销；任一步失败会整单回滚。确认继续吗？',
    )) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = action === 'approve'
        ? await collectionApi.approveCollection(selected.docNo)
        : action === 'post'
          ? await collectionApi.postCollection(selected.docNo)
          : await collectionApi.reverseCollection(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '收款单已审核'
        : action === 'post' ? '收款单已过账' : '收款单已冲销');
      loadSettlements(result);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, action === 'approve' ? '收款单审核失败'
        : action === 'post' ? '收款单过账失败' : '收款单冲销失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const customerName = (id: number) => customerNames.get(id) ?? `客户 #${id}（名称不可用）`;
  const accountName = (id: number) => accountNames.get(id) ?? `资金账户 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">销售 / 收款核销</p>
        <h1>销售收款</h1>
        <p>按未核销应收登记收款单；过账后核销应收并生成现金侧凭证。</p>
      </div>
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={() => {
          clearSelection();
          setForm(emptyForm());
          setEditing(true);
          setError('');
          setNotice('');
        }}>
        新建收款单
      </button>
    </header>
    <div className="purchase-order-filters">
      <CustomerPicker label="按客户筛选" value={customerDraft} allowClear disabled={saving}
        onChange={setCustomerDraft} onOptions={rememberCustomers} />
      <FundAccountPicker label="按资金账户筛选" value={accountDraft} allowClear disabled={saving}
        onChange={setAccountDraft} onOptions={rememberAccounts} />
      <label>状态<select aria-label="收款单状态" value={statusDraft}
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
          <th>收款单号</th><th>客户</th><th>资金账户</th><th>收款日期</th><th>金额</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((collection) => <tr key={collection.docNo}
            className={selected?.docNo === collection.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(collection)}>{collection.docNo}</button></td>
            <td>{customerName(collection.customerId)}</td>
            <td>{accountName(collection.paymentAccountId)}</td>
            <td>{collection.receiptDate}</td>
            <td>{collection.totalAmount}</td>
            <td><StatusBadge status={collection.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载收款单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无收款单，可从右上角新建草稿。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 张</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <CollectionForm form={form} setForm={setForm} saving={saving}
          customerName={customerName} accountName={accountName}
          onCustomers={rememberCustomers} onAccounts={rememberAccounts}
          onSave={save} onCancel={clearSelection} />
          : selected ? <CollectionDetails collection={selected} customerName={customerName}
            accountName={accountName} settlements={settlements} saving={saving}
            onApprove={() => void transition('approve')}
            onPost={() => void transition('post')}
            onReverse={() => void transition('reverse')} />
            : <p className="memory-empty">选择一张收款单查看详情，或新建收款单草稿。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: collectionApi.CollectionStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface CustomerPickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: customerApi.Customer[]) => void;
}

function CustomerPicker({ label, value, allowClear = false, disabled, onChange, onOptions }: CustomerPickerProps) {
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
      {loading ? <span>正在加载客户…</span>
        : items.length === 0 ? <span>未找到客户</span>
          : items.map((customer) => <button type="button" role="option" key={customer.id}
            aria-selected={value === customer.id}
            className={value === customer.id ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => onChange(customer.id)}>
            {customer.name}<span>{customer.code}</span>
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

interface FundAccountPickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: collectionApi.FundAccount[]) => void;
}

function FundAccountPicker({ label, value, allowClear = false, disabled, onChange, onOptions }: FundAccountPickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<collectionApi.FundAccount[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const accountVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = accountVersion.current.next();
    setLoading(true);
    setError('');
    void collectionApi.searchFundAccounts('ENABLED', page).then((result) => {
      if (!accountVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
      onOptions(result.items);
    }).catch((cause: unknown) => {
      if (accountVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '资金账户加载失败'));
      }
    }).finally(() => {
      if (accountVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [page, refreshKey]);

  return <div className="reference-picker">
    <strong>{label}</strong>
    <div className="reference-picker-search">
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => setRefreshKey((current) => current + 1)}>刷新账户</button>
      {allowClear && value !== null && <button type="button" className="memory-button" disabled={disabled}
        onClick={() => onChange(null)}>清除</button>}
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label={label}>
      {loading ? <span>正在加载资金账户…</span>
        : items.length === 0 ? <span>暂无启用中的资金账户</span>
          : items.map((account) => <button type="button" role="option" key={account.id}
            aria-selected={value === account.id}
            className={value === account.id ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => onChange(account.id)}>
            {account.name}<span>{account.code} · {account.accountType}</span>
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

interface ReceivablePickerProps {
  customerId: number;
  selectedIds: number[];
  disabled: boolean;
  onSelect: (receivable: collectionApi.Receivable) => void;
}

function ReceivablePicker({ customerId, selectedIds, disabled, onSelect }: ReceivablePickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<collectionApi.Receivable[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const receivableVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = receivableVersion.current.next();
    setLoading(true);
    setError('');
    void collectionApi.searchReceivables(customerId, '', page).then((result) => {
      if (!receivableVersion.current.isCurrent(version)) return;
      setItems(result.items.filter(canAllocate));
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (receivableVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '应收列表加载失败。查应收需要 sales:invoice 权限。'));
      }
    }).finally(() => {
      if (receivableVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [customerId, page, refreshKey]);

  return <div className="reference-picker">
    <strong>未核销应收（GET /api/receivables，权限 sales:invoice）</strong>
    <div className="reference-picker-search">
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => setRefreshKey((current) => current + 1)}>刷新应收</button>
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label="未核销应收">
      {loading ? <span>正在加载应收…</span>
        : items.length === 0 ? <span>该客户暂无未核销应收</span>
          : items.map((receivable) => {
            const chosen = selectedIds.includes(receivable.id);
            return <button type="button" role="option" key={receivable.id} aria-selected={chosen}
              className={chosen ? 'reference-picker-selected' : ''}
              disabled={disabled || chosen} onClick={() => onSelect(receivable)}>
              {receivable.sourceDocNo} · 应收 #{receivable.id}
              <span>未核销 {receivable.openAmount} · {receivable.status}</span>
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
  form: collectionApi.CollectionForm;
  setForm: (form: collectionApi.CollectionForm) => void;
  saving: boolean;
  customerName: (id: number) => string;
  accountName: (id: number) => string;
  onCustomers: (items: customerApi.Customer[]) => void;
  onAccounts: (items: collectionApi.FundAccount[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function CollectionForm({ form, setForm, saving, customerName, accountName,
  onCustomers, onAccounts, onSave, onCancel }: FormProps) {
  const addReceivable = (receivable: collectionApi.Receivable) => {
    if (form.lines.some((line) => line.receivableId === receivable.id)) return;
    setForm({
      ...form,
      lines: [...form.lines, {
        receivableId: receivable.id,
        allocatedAmount: receivable.openAmount,
        openAmount: receivable.openAmount,
      }],
    });
  };
  const setLine = (index: number, allocatedAmount: string) => {
    const lines = [...form.lines];
    if (!lines[index]) return;
    lines[index] = { ...lines[index], allocatedAmount };
    setForm({ ...form, lines });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建收款单</h2>
    <CustomerPicker label="客户" value={form.customerId} disabled={saving}
      onChange={(customerId) => setForm({ ...form, customerId, lines: [] })}
      onOptions={onCustomers} />
    <FundAccountPicker label="收入的资金账户" value={form.paymentAccountId} disabled={saving}
      onChange={(paymentAccountId) => setForm({ ...form, paymentAccountId })}
      onOptions={onAccounts} />
    <label>收款日期<input type="date" disabled={saving} value={form.receiptDate}
      onChange={(event) => setForm({ ...form, receiptDate: event.target.value })} /></label>
    <label>说明<textarea maxLength={255} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    {form.customerId ? <ReceivablePicker customerId={form.customerId}
      selectedIds={form.lines.map((line) => line.receivableId)}
      disabled={saving} onSelect={addReceivable} />
      : <p className="memory-empty">请先选择客户，再从应收列表分摊金额。</p>}
    <fieldset disabled={saving}><legend>本次收款分摊（至少一行）</legend>
      {form.lines.length === 0 ? <p className="memory-empty">请加入至少一笔未核销应收。</p>
        : form.lines.map((line, index) => <div className="purchase-order-line" key={line.receivableId}>
          <strong>应收 #{line.receivableId}<span>（未核销 {line.openAmount}）</span></strong>
          <label>分摊金额<input required inputMode="decimal"
            pattern={collectionApi.positiveAllocatedAmountPattern.source}
            value={line.allocatedAmount}
            onChange={(event) => setLine(index, event.target.value)} /></label>
          <button type="button" className="memory-button" onClick={() => setForm({
            ...form,
            lines: form.lines.filter((_, current) => current !== index),
          })}>移除</button>
        </div>)}
    </fieldset>
    <p>{form.customerId ? `当前客户：${customerName(form.customerId)}` : '尚未选择客户'}
      {form.paymentAccountId ? ` · 资金账户：${accountName(form.paymentAccountId)}` : ''}</p>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary"
        disabled={saving || form.lines.length === 0}>{saving ? '创建中…' : '创建草稿'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  collection: collectionApi.Collection;
  customerName: (id: number) => string;
  accountName: (id: number) => string;
  settlements: collectionApi.SettlementRecord[];
  saving: boolean;
  onApprove: () => void;
  onPost: () => void;
  onReverse: () => void;
}

function CollectionDetails({ collection, customerName, accountName, settlements, saving,
  onApprove, onPost, onReverse }: DetailsProps) {
  return <>
    <StatusBadge status={collection.status} />
    <h2>{collection.docNo}</h2>
    <dl>
      <dt>客户</dt><dd>{customerName(collection.customerId)}</dd>
      <dt>资金账户</dt><dd>{accountName(collection.paymentAccountId)}</dd>
      <dt>收款日期</dt><dd>{collection.receiptDate}</dd>
      <dt>收款总额</dt><dd>{collection.totalAmount}</dd>
      <dt>说明</dt><dd>{collection.remark || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines"><table className="memory-table"><thead><tr>
      <th>行号</th><th>应收 id</th><th>分摊金额</th>
    </tr></thead><tbody>{collection.lines.map((line) => <tr key={line.lineNo}>
      <td>{line.lineNo}</td><td>{line.receivableId}</td><td>{line.allocatedAmount}</td>
    </tr>)}</tbody></table></div>
    {settlements.length > 0 && <>
      <h3>核销记录</h3>
      <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
        <th>应收</th><th>金额</th><th>来源单</th><th>收付款单</th>
      </tr></thead><tbody>{settlements.map((record) => <tr key={record.id}>
        <td>{record.targetId}</td><td>{record.amount}</td>
        <td>{record.targetSourceDocNo || '—'}</td>
        <td>{record.paymentDocNo || '—'}</td>
      </tr>)}</tbody></table></div>
    </>}
    {collection.status === 'DRAFT' && <div className="customer-actions">
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={onApprove}>{saving ? '处理中…' : '审核'}</button>
    </div>}
    {collection.status === 'APPROVED' && <>
      <p className="sales-invoice-impact">
        过账将核销应收并生成现金侧凭证；任一步失败，整张收款单及关联写入全部回滚。
      </p>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={onPost}>{saving ? '处理中…' : '过账'}</button>
      </div>
    </>}
    {collection.status === 'COMPLETED' && <>
      <p className="sales-invoice-impact">
        冲销将反向核销应收并红冲现金侧凭证，原单转为已冲销；任一步失败会整单回滚。
      </p>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-danger" disabled={saving}
          onClick={onReverse}>{saving ? '处理中…' : '冲销'}</button>
      </div>
    </>}
  </>;
}
