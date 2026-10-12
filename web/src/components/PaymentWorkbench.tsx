import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as paymentApi from '../api/paymentApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<paymentApi.PaymentStatus, string> = {
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

const emptyForm = (): paymentApi.PaymentForm => ({
  supplierId: null,
  paymentAccountId: null,
  paymentDate: today(),
  remark: '',
  lines: [],
});

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const canAllocate = (payable: paymentApi.Payable) =>
  (payable.status === 'OPEN' || payable.status === 'PARTIAL')
  && paymentApi.positiveAllocatedAmountPattern.test(payable.outstandingAmount);

export function PaymentWorkbench() {
  const [items, setItems] = useState<paymentApi.Payment[]>([]);
  const [selected, setSelected] = useState<paymentApi.Payment | null>(null);
  const [supplierDraft, setSupplierDraft] = useState<number | null>(null);
  const [accountDraft, setAccountDraft] = useState<number | null>(null);
  const [statusDraft, setStatusDraft] = useState('');
  const [supplierId, setSupplierId] = useState<number | null>(null);
  const [paymentAccountId, setPaymentAccountId] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<paymentApi.PaymentForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [supplierNames, setSupplierNames] = useState<Map<number, string>>(new Map());
  const [accountNames, setAccountNames] = useState<Map<number, string>>(new Map());
  const [settlements, setSettlements] = useState<paymentApi.SettlementRecord[]>([]);
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const settlementVersion = useRef(createRequestGate());
  const supplierNameVersion = useRef(createRequestGate());
  const accountNameVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  const rememberSuppliers = (suppliers: masterDataApi.Supplier[]) => {
    if (suppliers.length === 0) return;
    setSupplierNames((current) => {
      const next = new Map(current);
      for (const supplier of suppliers) next.set(supplier.id, `${supplier.name}（${supplier.code}）`);
      return next;
    });
  };

  const rememberAccounts = (accounts: paymentApi.FundAccount[]) => {
    if (accounts.length === 0) return;
    setAccountNames((current) => {
      const next = new Map(current);
      for (const account of accounts) next.set(account.id, `${account.name}（${account.code}）`);
      return next;
    });
  };

  const ensureSupplierNames = (payments: paymentApi.Payment[]) => {
    const ids = [...new Set(payments.map((item) => item.supplierId)
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

  const ensureAccountNames = (payments: Array<{ paymentAccountId: number }>) => {
    const ids = [...new Set(payments.map((item) => item.paymentAccountId)
      .filter((id) => !accountNames.has(id)))];
    if (ids.length === 0) return;
    const version = accountNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await paymentApi.getFundAccount(id); } catch { return null; }
    })).then((values) => {
      if (!accountNameVersion.current.isCurrent(version)) return;
      rememberAccounts(values.filter((value): value is paymentApi.FundAccount => value !== null));
    });
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void paymentApi.searchPayments(supplierId, paymentAccountId, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
        ensureSupplierNames(result.items);
        ensureAccountNames(result.items);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '付款单列表加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [supplierId, paymentAccountId, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    settlementVersion.current.next();
    setSelected(null);
    setEditing(false);
    setSettlements([]);
  };

  const loadSettlements = (payment: paymentApi.Payment) => {
    const version = settlementVersion.current.next();
    void Promise.all(payment.lines.map((line) => paymentApi.searchPayableSettlements(line.payableId)))
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

  const choose = (payment: paymentApi.Payment) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(payment);
    setEditing(false);
    setError('');
    setNotice('');
    setSettlements([]);
    ensureSupplierNames([payment]);
    ensureAccountNames([payment]);
    loadSettlements(payment);
    void paymentApi.getPayment(payment.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
      ensureSupplierNames([detail]);
      ensureAccountNames([detail]);
      loadSettlements(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '付款单详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setSupplierId(supplierDraft);
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
      const validationError = paymentApi.validatePaymentForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await paymentApi.createPayment(form);
      setSelected(result);
      setEditing(false);
      setNotice('付款单草稿已创建');
      ensureSupplierNames([result]);
      ensureAccountNames([result]);
      loadSettlements(result);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '付款单创建失败，请检查输入'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: 'approve' | 'post' | 'reverse') => {
    if (!selected || mutationInFlight.current.isLocked()) return;
    if (action === 'post' && !window.confirm(
      '过账将核销应付并生成现金侧凭证（借正式应付 / 贷资金科目）；任一步失败会整单回滚。此操作不可直接撤销，确认继续吗？',
    )) return;
    if (action === 'reverse' && !window.confirm(
      '冲销将反向核销应付并红冲现金侧凭证，原单转为已冲销；任一步失败会整单回滚。确认继续吗？',
    )) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = action === 'approve'
        ? await paymentApi.approvePayment(selected.docNo)
        : action === 'post'
          ? await paymentApi.postPayment(selected.docNo)
          : await paymentApi.reversePayment(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '付款单已审核'
        : action === 'post' ? '付款单已过账' : '付款单已冲销');
      loadSettlements(result);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, action === 'approve' ? '付款单审核失败'
        : action === 'post' ? '付款单过账失败' : '付款单冲销失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const supplierName = (id: number) => supplierNames.get(id) ?? `供应商 #${id}（名称不可用）`;
  const accountName = (id: number) => accountNames.get(id) ?? `资金账户 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">采购 / 付款核销</p>
        <h1>采购付款</h1>
        <p>按未核销应付登记付款单；过账后核销应付并生成现金侧凭证。</p>
      </div>
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={() => {
          clearSelection();
          setForm(emptyForm());
          setEditing(true);
          setError('');
          setNotice('');
        }}>
        新建付款单
      </button>
    </header>
    <div className="purchase-order-filters">
      <SupplierPicker label="按供应商筛选" value={supplierDraft} allowClear disabled={saving}
        onChange={setSupplierDraft} onOptions={rememberSuppliers} />
      <FundAccountPicker label="按资金账户筛选" value={accountDraft} allowClear disabled={saving}
        onChange={setAccountDraft} onOptions={rememberAccounts} />
      <label>状态<select aria-label="付款单状态" value={statusDraft}
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
          <th>付款单号</th><th>供应商</th><th>资金账户</th><th>付款日期</th><th>金额</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((payment) => <tr key={payment.docNo}
            className={selected?.docNo === payment.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(payment)}>{payment.docNo}</button></td>
            <td>{supplierName(payment.supplierId)}</td>
            <td>{accountName(payment.paymentAccountId)}</td>
            <td>{payment.paymentDate}</td>
            <td>{payment.totalAmount}</td>
            <td><StatusBadge status={payment.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载付款单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无付款单，可从右上角新建草稿。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 张</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <PaymentForm form={form} setForm={setForm} saving={saving}
          supplierName={supplierName} accountName={accountName}
          onSuppliers={rememberSuppliers} onAccounts={rememberAccounts}
          onSave={save} onCancel={clearSelection} />
          : selected ? <PaymentDetails payment={selected} supplierName={supplierName}
            accountName={accountName} settlements={settlements} saving={saving}
            onApprove={() => void transition('approve')}
            onPost={() => void transition('post')}
            onReverse={() => void transition('reverse')} />
            : <p className="memory-empty">选择一张付款单查看详情，或新建付款单草稿。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: paymentApi.PaymentStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface SupplierPickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  disabled: boolean;
  onChange: (id: number | null) => void;
  onOptions: (items: masterDataApi.Supplier[]) => void;
}

function SupplierPicker({ label, value, allowClear = false, disabled, onChange, onOptions }: SupplierPickerProps) {
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
      {loading ? <span>正在加载供应商…</span>
        : items.length === 0 ? <span>未找到供应商</span>
          : items.map((supplier) => <button type="button" role="option" key={supplier.id}
            aria-selected={value === supplier.id}
            className={value === supplier.id ? 'reference-picker-selected' : ''}
            disabled={disabled} onClick={() => onChange(supplier.id)}>
            {supplier.name}<span>{supplier.code}</span>
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
  onOptions: (items: paymentApi.FundAccount[]) => void;
}

function FundAccountPicker({ label, value, allowClear = false, disabled, onChange, onOptions }: FundAccountPickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<paymentApi.FundAccount[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const accountVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = accountVersion.current.next();
    setLoading(true);
    setError('');
    void paymentApi.searchFundAccounts('ENABLED', page).then((result) => {
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

interface PayablePickerProps {
  supplierId: number;
  selectedIds: number[];
  disabled: boolean;
  onSelect: (payable: paymentApi.Payable) => void;
}

function PayablePicker({ supplierId, selectedIds, disabled, onSelect }: PayablePickerProps) {
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [items, setItems] = useState<paymentApi.Payable[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const payableVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = payableVersion.current.next();
    setLoading(true);
    setError('');
    void paymentApi.searchPayables(supplierId, '', page).then((result) => {
      if (!payableVersion.current.isCurrent(version)) return;
      setItems(result.items.filter(canAllocate));
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (payableVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '应付列表加载失败'));
      }
    }).finally(() => {
      if (payableVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [supplierId, page, refreshKey]);

  return <div className="reference-picker">
    <strong>未核销应付（GET /api/payables）</strong>
    <div className="reference-picker-search">
      <button type="button" className="memory-button" disabled={disabled || loading}
        onClick={() => setRefreshKey((current) => current + 1)}>刷新应付</button>
    </div>
    {error && <small className="reference-picker-error" role="alert">{error}</small>}
    <div className="reference-picker-results" role="listbox" aria-label="未核销应付">
      {loading ? <span>正在加载应付…</span>
        : items.length === 0 ? <span>该供应商暂无未核销应付</span>
          : items.map((payable) => {
            const chosen = selectedIds.includes(payable.id);
            return <button type="button" role="option" key={payable.id} aria-selected={chosen}
              className={chosen ? 'reference-picker-selected' : ''}
              disabled={disabled || chosen} onClick={() => onSelect(payable)}>
              {payable.sourceDocNo} · 应付 #{payable.id}
              <span>未核销 {payable.outstandingAmount} · {payable.status}</span>
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
  form: paymentApi.PaymentForm;
  setForm: (form: paymentApi.PaymentForm) => void;
  saving: boolean;
  supplierName: (id: number) => string;
  accountName: (id: number) => string;
  onSuppliers: (items: masterDataApi.Supplier[]) => void;
  onAccounts: (items: paymentApi.FundAccount[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function PaymentForm({ form, setForm, saving, supplierName, accountName,
  onSuppliers, onAccounts, onSave, onCancel }: FormProps) {
  const addPayable = (payable: paymentApi.Payable) => {
    if (form.lines.some((line) => line.payableId === payable.id)) return;
    setForm({
      ...form,
      lines: [...form.lines, {
        payableId: payable.id,
        allocatedAmount: payable.outstandingAmount,
        outstandingAmount: payable.outstandingAmount,
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
    <h2>新建付款单</h2>
    <SupplierPicker label="供应商" value={form.supplierId} disabled={saving}
      onChange={(supplierId) => setForm({ ...form, supplierId, lines: [] })}
      onOptions={onSuppliers} />
    <FundAccountPicker label="付出的资金账户" value={form.paymentAccountId} disabled={saving}
      onChange={(paymentAccountId) => setForm({ ...form, paymentAccountId })}
      onOptions={onAccounts} />
    <label>付款日期<input type="date" disabled={saving} value={form.paymentDate}
      onChange={(event) => setForm({ ...form, paymentDate: event.target.value })} /></label>
    <label>说明<textarea maxLength={255} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    {form.supplierId ? <PayablePicker supplierId={form.supplierId}
      selectedIds={form.lines.map((line) => line.payableId)}
      disabled={saving} onSelect={addPayable} />
      : <p className="memory-empty">请先选择供应商，再从应付列表分摊金额。</p>}
    <fieldset disabled={saving}><legend>本次付款分摊（至少一行）</legend>
      {form.lines.length === 0 ? <p className="memory-empty">请加入至少一笔未核销应付。</p>
        : form.lines.map((line, index) => <div className="purchase-order-line" key={line.payableId}>
          <strong>应付 #{line.payableId}<span>（未核销 {line.outstandingAmount}）</span></strong>
          <label>分摊金额<input required inputMode="decimal"
            pattern={paymentApi.positiveAllocatedAmountPattern.source}
            value={line.allocatedAmount}
            onChange={(event) => setLine(index, event.target.value)} /></label>
          <button type="button" className="memory-button" onClick={() => setForm({
            ...form,
            lines: form.lines.filter((_, current) => current !== index),
          })}>移除</button>
        </div>)}
    </fieldset>
    <p>{form.supplierId ? `当前供应商：${supplierName(form.supplierId)}` : '尚未选择供应商'}
      {form.paymentAccountId ? ` · 资金账户：${accountName(form.paymentAccountId)}` : ''}</p>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary"
        disabled={saving || form.lines.length === 0}>{saving ? '创建中…' : '创建草稿'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  payment: paymentApi.Payment;
  supplierName: (id: number) => string;
  accountName: (id: number) => string;
  settlements: paymentApi.SettlementRecord[];
  saving: boolean;
  onApprove: () => void;
  onPost: () => void;
  onReverse: () => void;
}

function PaymentDetails({ payment, supplierName, accountName, settlements, saving,
  onApprove, onPost, onReverse }: DetailsProps) {
  return <>
    <StatusBadge status={payment.status} />
    <h2>{payment.docNo}</h2>
    <dl>
      <dt>供应商</dt><dd>{supplierName(payment.supplierId)}</dd>
      <dt>资金账户</dt><dd>{accountName(payment.paymentAccountId)}</dd>
      <dt>付款日期</dt><dd>{payment.paymentDate}</dd>
      <dt>付款总额</dt><dd>{payment.totalAmount}</dd>
      <dt>说明</dt><dd>{payment.remark || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines"><table className="memory-table"><thead><tr>
      <th>行号</th><th>应付 id</th><th>分摊金额</th>
    </tr></thead><tbody>{payment.lines.map((line) => <tr key={line.lineNo}>
      <td>{line.lineNo}</td><td>{line.payableId}</td><td>{line.allocatedAmount}</td>
    </tr>)}</tbody></table></div>
    {settlements.length > 0 && <>
      <h3>核销记录</h3>
      <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
        <th>应付</th><th>金额</th><th>来源单</th><th>收付款单</th>
      </tr></thead><tbody>{settlements.map((record) => <tr key={record.id}>
        <td>{record.targetId}</td><td>{record.amount}</td>
        <td>{record.targetSourceDocNo || '—'}</td>
        <td>{record.paymentDocNo || '—'}</td>
      </tr>)}</tbody></table></div>
    </>}
    {payment.status === 'DRAFT' && <div className="customer-actions">
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={onApprove}>{saving ? '处理中…' : '审核'}</button>
    </div>}
    {payment.status === 'APPROVED' && <>
      <p className="sales-invoice-impact">
        过账将核销应付并生成现金侧凭证；任一步失败，整张付款单及关联写入全部回滚。
      </p>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={onPost}>{saving ? '处理中…' : '过账'}</button>
      </div>
    </>}
    {payment.status === 'COMPLETED' && <>
      <p className="sales-invoice-impact">
        冲销将反向核销应付并红冲现金侧凭证，原单转为已冲销；任一步失败会整单回滚。
      </p>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-danger" disabled={saving}
          onClick={onReverse}>{saving ? '处理中…' : '冲销'}</button>
      </div>
    </>}
  </>;
}
