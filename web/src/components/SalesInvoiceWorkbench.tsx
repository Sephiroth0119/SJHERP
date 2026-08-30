import { useEffect, useRef, useState } from 'react';
import * as catalogApi from '../api/catalogApi.ts';
import * as customerApi from '../api/customerApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import * as invoiceApi from '../api/salesInvoiceApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<invoiceApi.SalesInvoiceStatus, string> = {
  DRAFT: '草稿', APPROVED: '已审核', EXECUTING: '过账中',
  COMPLETED: '已完成', CANCELLED: '已作废', REVERSED: '已冲销',
};

function today() {
  const value = new Date();
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

const emptyForm = (): invoiceApi.SalesInvoiceForm => ({
  salesDeliveryNo: '', invoiceDate: today(), dueDate: '', remark: '', lines: [],
});
const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function SalesInvoiceWorkbench() {
  const [items, setItems] = useState<invoiceApi.SalesInvoice[]>([]);
  const [selected, setSelected] = useState<invoiceApi.SalesInvoice | null>(null);
  const [customerDraft, setCustomerDraft] = useState('');
  const [deliveryDraft, setDeliveryDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [customerId, setCustomerId] = useState<number | null>(null);
  const [deliveryNo, setDeliveryNo] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<invoiceApi.SalesInvoiceForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [customerNames, setCustomerNames] = useState<Map<number, string>>(new Map());
  const [warehouseNames, setWarehouseNames] = useState<Map<number, string>>(new Map());
  const [productNames, setProductNames] = useState<Map<number, string>>(new Map());
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const customerNameVersion = useRef(createRequestGate());
  const warehouseNameVersion = useRef(createRequestGate());
  const productNameVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  const ensureCustomerNames = (invoices: invoiceApi.SalesInvoice[]) => {
    const ids = [...new Set(invoices.map((item) => item.customerId)
      .filter((id) => !customerNames.has(id)))];
    if (ids.length === 0) return;
    const version = customerNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await customerApi.getCustomer(id); } catch { return null; }
    })).then((values) => {
      if (!customerNameVersion.current.isCurrent(version)) return;
      setCustomerNames((current) => {
        const next = new Map(current);
        values.forEach((value) => value && next.set(value.id, `${value.name}（${value.code}）`));
        return next;
      });
    });
  };

  const ensureWarehouseNames = (deliveries: invoiceApi.SalesInvoiceDeliveryOption[]) => {
    const ids = [...new Set(deliveries.map((item) => item.warehouseId)
      .filter((id) => !warehouseNames.has(id)))];
    if (ids.length === 0) return;
    const version = warehouseNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await masterDataApi.getWarehouse(id); } catch { return null; }
    })).then((values) => {
      if (!warehouseNameVersion.current.isCurrent(version)) return;
      setWarehouseNames((current) => {
        const next = new Map(current);
        values.forEach((value) => value && next.set(value.id, `${value.name}（${value.code}）`));
        return next;
      });
    });
  };

  const ensureProductNames = (lines: Array<{ productId: number }>) => {
    const ids = [...new Set(lines.map((item) => item.productId)
      .filter((id) => !productNames.has(id)))];
    if (ids.length === 0) return;
    const version = productNameVersion.current.next();
    void Promise.all(ids.map(async (id) => {
      try { return await catalogApi.getProduct(id); } catch { return null; }
    })).then((values) => {
      if (!productNameVersion.current.isCurrent(version)) return;
      setProductNames((current) => {
        const next = new Map(current);
        values.forEach((value) => value && next.set(value.id, `${value.name}（${value.code}）`));
        return next;
      });
    });
  };

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true); setError('');
    void invoiceApi.searchSalesInvoices(customerId, deliveryNo, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items); setTotal(result.total); ensureCustomerNames(result.items);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) setError(errorMessage(cause, '销售发票列表加载失败'));
      })
      .finally(() => { if (listVersion.current.isCurrent(version)) setLoading(false); });
  }, [customerId, deliveryNo, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => { invalidateDetail(); setSelected(null); setEditing(false); };
  const choose = (invoice: invoiceApi.SalesInvoice) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(invoice); setEditing(false); setError(''); setNotice('');
    ensureCustomerNames([invoice]); ensureProductNames(invoice.lines);
    void invoiceApi.getSalesInvoice(invoice.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail); ensureCustomerNames([detail]); ensureProductNames(detail.lines);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) setError(errorMessage(cause, '销售发票详情加载失败'));
    });
  };

  const applySearch = () => {
    if (saving) return;
    const trimmed = customerDraft.trim();
    if (trimmed && (!/^\d+$/.test(trimmed) || trimmed === '0')) {
      setError('客户 ID 必须是正整数'); return;
    }
    clearSelection(); setCustomerId(trimmed ? Number(trimmed) : null);
    setDeliveryNo(deliveryDraft.trim()); setStatus(statusDraft); setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail(); setError(''); setNotice('');
    try {
      const validationError = invoiceApi.validateSalesInvoiceForm(form);
      if (validationError) { setError(validationError); return; }
      setSaving(true);
      const result = await invoiceApi.createSalesInvoice(form);
      setSelected(result); setEditing(false); setNotice('销售发票草稿已创建');
      ensureCustomerNames([result]); ensureProductNames(result.lines);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '销售发票创建失败，请检查输入'));
    } finally { setSaving(false); mutationInFlight.current.release(); }
  };

  const transition = async (action: 'approve' | 'post' | 'cancel') => {
    if (!selected || mutationInFlight.current.isLocked()) return;
    if (action === 'post' && !window.confirm(
      '过账将回写出库行已开票量、生成应收账款与会计凭证；任一步失败会整单回滚。此操作不可直接撤销，确认继续吗？',
    )) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail(); setSaving(true); setError(''); setNotice('');
    try {
      const result = action === 'approve'
        ? await invoiceApi.approveSalesInvoice(selected.docNo)
        : action === 'post'
          ? await invoiceApi.postSalesInvoice(selected.docNo)
          : await invoiceApi.cancelSalesInvoice(selected.docNo);
      setSelected(result);
      setNotice(action === 'approve' ? '销售发票已审核'
        : action === 'post' ? '销售发票已过账' : '销售发票草稿已作废');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, action === 'approve' ? '销售发票审核失败'
        : action === 'post' ? '销售发票过账失败' : '销售发票作废失败'));
    } finally { setSaving(false); mutationInFlight.current.release(); }
  };

  const customerName = (id: number) => customerNames.get(id) ?? `客户 #${id}（名称不可用）`;
  const warehouseName = (id: number) => warehouseNames.get(id) ?? `仓库 #${id}（名称不可用）`;
  const productName = (id: number) => productNames.get(id) ?? `商品 #${id}（名称不可用）`;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div><p className="page-kicker">销售 / 发票应收</p><h1>销售发票</h1>
        <p>从已过账销售出库单登记发票；过账后生成应收与会计凭证。</p></div>
      <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={() => { clearSelection(); setForm(emptyForm()); setEditing(true); setError(''); setNotice(''); }}>
        新建销售发票
      </button>
    </header>
    <div className="purchase-order-filters">
      <label>客户 ID<input inputMode="numeric" value={customerDraft}
        onChange={(event) => setCustomerDraft(event.target.value)} /></label>
      <label>销售出库单<input value={deliveryDraft}
        onChange={(event) => setDeliveryDraft(event.target.value)} /></label>
      <label>状态<select value={statusDraft} onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>{Object.entries(statusLabels).map(([value, label]) =>
          <option key={value} value={value}>{label}</option>)}</select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>发票号</th><th>客户</th><th>出库单</th><th>日期</th><th>金额</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((invoice) => <tr key={invoice.docNo}
            className={selected?.docNo === invoice.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(invoice)}>{invoice.docNo}</button></td><td>{customerName(invoice.customerId)}</td>
            <td>{invoice.salesDeliveryNo}</td><td>{invoice.invoiceDate}</td>
            <td>{invoice.totalAmount}</td><td><StatusBadge status={invoice.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载销售发票…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无销售发票，可从右上角新建草稿。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1} onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 张</span>
          <button type="button" disabled={saving || page >= pageCount} onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <InvoiceForm form={form} setForm={setForm} saving={saving}
          warehouseName={warehouseName} productNames={productNames}
          onDeliveries={(values) => { ensureWarehouseNames(values); ensureProductNames(values.flatMap((item) => item.lines)); }}
          onSave={save} onCancel={clearSelection} />
          : selected ? <InvoiceDetails invoice={selected} customerName={customerName}
            productName={productName} saving={saving}
            onApprove={() => void transition('approve')} onPost={() => void transition('post')}
            onCancel={() => void transition('cancel')} />
            : <p className="memory-empty">选择一张发票查看详情，或新建销售发票草稿。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: invoiceApi.SalesInvoiceStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface PickerProps {
  value: string; disabled: boolean; warehouseName: (id: number) => string;
  onDeliveries: (items: invoiceApi.SalesInvoiceDeliveryOption[]) => void;
  onSelect: (item: invoiceApi.SalesInvoiceDeliveryOption) => void;
}

function DeliveryPicker({ value, disabled, warehouseName, onDeliveries, onSelect }: PickerProps) {
  const [items, setItems] = useState<invoiceApi.SalesInvoiceDeliveryOption[]>([]);
  const [page, setPage] = useState(1); const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0); const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const deliveryVersion = useRef(createRequestGate());
  const deliveryDetailVersion = useRef(createRequestGate());

  useEffect(() => {
    deliveryDetailVersion.current.next();
    const version = deliveryVersion.current.next(); setLoading(true); setError('');
    void invoiceApi.searchSalesInvoiceDeliveryOptions(page, PAGE_SIZE).then((result) => {
      if (!deliveryVersion.current.isCurrent(version)) return;
      setItems(result.items); setTotal(result.total); onDeliveries(result.items);
    }).catch((cause: unknown) => {
      if (deliveryVersion.current.isCurrent(version)) setError(errorMessage(cause, '可开票销售出库单加载失败'));
    }).finally(() => { if (deliveryVersion.current.isCurrent(version)) setLoading(false); });
  }, [page, refreshKey]);

  const choose = (item: invoiceApi.SalesInvoiceDeliveryOption) => {
    if (disabled) return;
    const version = deliveryDetailVersion.current.next(); setError('');
    void invoiceApi.getSalesInvoiceDeliveryOption(item.docNo).then((detail) => {
      if (!deliveryDetailVersion.current.isCurrent(version)) return;
      onDeliveries([detail]); onSelect(detail);
    }).catch((cause: unknown) => {
      if (deliveryDetailVersion.current.isCurrent(version)) setError(errorMessage(cause, '该出库单已不可开票，请刷新候选'));
    });
  };
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  return <div className="reference-picker"><strong>已过账且仍可开票的销售出库单</strong>
    <div className="reference-picker-results" role="listbox" aria-label="可开票销售出库单">
      {loading ? <span role="status">正在加载…</span> : items.length === 0 ? <span>暂无可开票出库单</span>
        : items.map((item) => <button type="button" role="option" aria-selected={value === item.docNo}
          className={value === item.docNo ? 'reference-picker-selected' : ''}
          disabled={disabled} key={item.docNo} onClick={() => choose(item)}>
          {item.docNo}<span>{item.salesOrderNo} · {warehouseName(item.warehouseId)} · {item.lines.length} 行可开</span>
        </button>)}
    </div>
    {error && <span className="reference-picker-error" role="alert">{error}</span>}
    <div className="reference-picker-pagination">
      <button type="button" disabled={disabled || page <= 1}
        onClick={() => { deliveryDetailVersion.current.next(); setPage(page - 1); }}>上一页</button>
      <span>第 {page} / {pageCount} 页</span>
      <button type="button" disabled={disabled || page >= pageCount}
        onClick={() => { deliveryDetailVersion.current.next(); setPage(page + 1); }}>下一页</button>
      <button type="button" disabled={disabled}
        onClick={() => { deliveryDetailVersion.current.next(); setRefreshKey((value) => value + 1); }}>刷新候选</button>
    </div>
  </div>;
}

interface FormProps {
  form: invoiceApi.SalesInvoiceForm; setForm: (form: invoiceApi.SalesInvoiceForm) => void;
  saving: boolean; warehouseName: (id: number) => string; productNames: Map<number, string>;
  onDeliveries: (items: invoiceApi.SalesInvoiceDeliveryOption[]) => void;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void; onCancel: () => void;
}

function InvoiceForm({ form, setForm, saving, warehouseName, productNames,
  onDeliveries, onSave, onCancel }: FormProps) {
  const [delivery, setDelivery] = useState<invoiceApi.SalesInvoiceDeliveryOption | null>(null);
  const selectDelivery = (next: invoiceApi.SalesInvoiceDeliveryOption) => {
    setDelivery(next); setForm({ ...form, salesDeliveryNo: next.docNo, lines: [] });
  };
  const addLine = (line: invoiceApi.SalesInvoiceDeliveryLineOption) => {
    if (form.lines.some((current) => current.deliveryLineNo === line.deliveryLineNo)) return;
    setForm({ ...form, lines: [...form.lines, { deliveryLineNo: line.deliveryLineNo,
      productId: line.productId, quantity: '', unitPrice: '',
      outstandingInvoiceableQty: line.outstandingInvoiceableQty }] });
  };
  const setLine = (index: number, update: Partial<invoiceApi.SalesInvoiceLineForm>) => {
    const lines = [...form.lines]; if (!lines[index]) return;
    lines[index] = { ...lines[index], ...update }; setForm({ ...form, lines });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建销售发票</h2>
    <DeliveryPicker value={form.salesDeliveryNo} disabled={saving} warehouseName={warehouseName}
      onDeliveries={onDeliveries} onSelect={selectDelivery} />
    <label>发票日期<input type="date" required disabled={saving} value={form.invoiceDate}
      onChange={(event) => setForm({ ...form, invoiceDate: event.target.value })} /></label>
    <label>到期日（可选）<input type="date" disabled={saving} min={form.invoiceDate}
      value={form.dueDate} onChange={(event) => setForm({ ...form, dueDate: event.target.value })} /></label>
    <label>发票说明<textarea maxLength={255} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    {delivery && <div className="reference-picker"><strong>选择未开完出库行</strong>
      <div className="reference-picker-results" role="listbox" aria-label="未开完销售出库行">
        {delivery.lines.map((line) => {
          const chosen = form.lines.some((item) => item.deliveryLineNo === line.deliveryLineNo);
          return <button type="button" role="option" aria-selected={chosen}
            className={chosen ? 'reference-picker-selected' : ''} disabled={saving || chosen}
            key={line.deliveryLineNo} onClick={() => addLine(line)}>
            第 {line.deliveryLineNo} 行 · {productNames.get(line.productId) ?? `商品 #${line.productId}（名称不可用）`}
            <span>发货 {line.quantity} · 已开 {line.invoicedQty} · 剩余可开 {line.outstandingInvoiceableQty}</span>
          </button>;
        })}
      </div></div>}
    <fieldset disabled={saving}><legend>本次开票明细（至少一行）</legend>
      {form.lines.length === 0 ? <p className="memory-empty">请先选择销售出库单，再加入未开完行。</p>
        : form.lines.map((line, index) => <div className="purchase-order-line" key={line.deliveryLineNo}>
          <strong>出库第 {line.deliveryLineNo} 行 · {productNames.get(line.productId) ?? `商品 #${line.productId}（名称不可用）`}
            <span>（剩余可开 {line.outstandingInvoiceableQty}）</span></strong>
          <label>本次开票数量<input required inputMode="decimal"
            pattern={invoiceApi.positiveQuantityPattern.source} value={line.quantity}
            onChange={(event) => setLine(index, { quantity: event.target.value })} /></label>
          <label>开票单价<input required inputMode="decimal"
            pattern={invoiceApi.nonNegativeUnitPricePattern.source} value={line.unitPrice}
            onChange={(event) => setLine(index, { unitPrice: event.target.value })} /></label>
          <button type="button" className="memory-button" onClick={() => setForm({ ...form,
            lines: form.lines.filter((_, current) => current !== index) })}>移除</button>
        </div>)}
    </fieldset>
    <div className="customer-actions"><button type="submit" className="memory-button memory-button-primary"
      disabled={saving || form.lines.length === 0}>{saving ? '创建中…' : '创建草稿'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button></div>
  </form>;
}

interface DetailsProps {
  invoice: invoiceApi.SalesInvoice; customerName: (id: number) => string;
  productName: (id: number) => string; saving: boolean;
  onApprove: () => void; onPost: () => void; onCancel: () => void;
}

function InvoiceDetails({ invoice, customerName, productName, saving,
  onApprove, onPost, onCancel }: DetailsProps) {
  return <><StatusBadge status={invoice.status} /><h2>{invoice.docNo}</h2><dl>
    <dt>销售出库单</dt><dd>{invoice.salesDeliveryNo}</dd>
    <dt>客户</dt><dd>{customerName(invoice.customerId)}</dd>
    <dt>发票日期</dt><dd>{invoice.invoiceDate}</dd>
    <dt>到期日</dt><dd>{invoice.dueDate || '—'}</dd>
    <dt>发票总额</dt><dd>{invoice.totalAmount}</dd>
    <dt>说明</dt><dd>{invoice.remark || '—'}</dd>
  </dl>
  <div className="memory-table-wrap purchase-order-lines"><table className="memory-table"><thead><tr>
    <th>出库行</th><th>商品</th><th>数量</th><th>单价</th><th>金额</th>
  </tr></thead><tbody>{invoice.lines.map((line) => <tr key={line.lineNo}>
    <td>{line.deliveryLineNo}</td><td>{productName(line.productId)}</td><td>{line.quantity}</td>
    <td>{line.unitPrice}</td><td>{line.amount}</td></tr>)}</tbody></table></div>
  {invoice.status === 'DRAFT' && <div className="customer-actions">
    <button type="button" className="memory-button memory-button-primary" disabled={saving} onClick={onApprove}>
      {saving ? '处理中…' : '审核'}</button>
    <button type="button" className="memory-button memory-button-danger" disabled={saving} onClick={onCancel}>作废草稿</button>
  </div>}
  {invoice.status === 'APPROVED' && <><p className="sales-invoice-impact">
    过账会回写出库行已开票量、生成应收账款与会计凭证；任一步失败，整张发票及关联写入全部回滚。
  </p><div className="customer-actions"><button type="button"
    className="memory-button memory-button-primary" disabled={saving} onClick={onPost}>
    {saving ? '处理中…' : '过账'}</button></div></>}
  </>;
}
