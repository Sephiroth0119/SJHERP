import { useEffect, useRef, useState } from 'react';
import * as agingReportApi from '../api/agingReportApi.ts';
import * as customerApi from '../api/customerApi.ts';
import { ApiError } from '../api/http.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import { collectAllPages, REPORT_EXPORT_PAGE_SIZE } from '../api/inventoryReportApi.ts';
import { downloadSpreadsheet, spreadsheetFilename } from '../report/excelExport.ts';
import { createRequestGate } from '../security/workbenchControl.ts';
import { BarChart } from './report/BarChart.tsx';

const PAGE_SIZE = 20;

const emptyBuckets = (): agingReportApi.AgingBuckets => ({
  notDue: '',
  overdue1To30: '',
  overdue31To60: '',
  overdue61To90: '',
  overdue90Plus: '',
  totalOutstanding: '',
});

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

function today() {
  const value = new Date();
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function agingCopy(kind: agingReportApi.AgingKind) {
  switch (kind) {
    case 'receivable':
      return {
        kicker: '财务 / 应收账龄',
        title: '应收账龄',
        intro: '按客户分桶汇总未核销应收。截止日默认为今天；全量合计覆盖当前筛选全集，不是本页小计。查账龄需要 finance:settlement 权限。',
        counterpartyLabel: '客户',
        filterLabel: '按客户筛选',
        permissionHint: '应收账龄加载失败。查看账龄需要 finance:settlement 权限。',
        empty: '暂无应收账龄记录，请调整筛选条件。',
        loading: '正在加载应收账龄…',
      };
    case 'payable':
      return {
        kicker: '财务 / 应付账龄',
        title: '应付账龄',
        intro: '按供应商分桶汇总未核销应付。截止日默认为今天；全量合计覆盖当前筛选全集，不是本页小计。查账龄需要 finance:settlement 权限。',
        counterpartyLabel: '供应商',
        filterLabel: '按供应商筛选',
        permissionHint: '应付账龄加载失败。查看账龄需要 finance:settlement 权限。',
        empty: '暂无应付账龄记录，请调整筛选条件。',
        loading: '正在加载应付账龄…',
      };
    default: {
      const exhaustive: never = kind;
      throw new Error(`未知账龄类型: ${exhaustive}`);
    }
  }
}

function counterpartyLabel(item: agingReportApi.AgingItem): string {
  const name = item.counterpartyName?.trim() ?? '';
  const code = item.counterpartyCode?.trim() ?? '';
  if (name && code) return `${name}（${code}）`;
  if (name) return name;
  if (code) return code;
  return `对手方 #${item.counterpartyId}（名称不可用）`;
}

function amountText(value: string) {
  return value === '' ? '—' : value;
}

export function ReceivableAgingWorkbench() {
  return <AgingReportWorkbench kind="receivable" />;
}

export function PayableAgingWorkbench() {
  return <AgingReportWorkbench kind="payable" />;
}

function AgingReportWorkbench({ kind }: { kind: agingReportApi.AgingKind }) {
  const copy = agingCopy(kind);
  const [items, setItems] = useState<agingReportApi.AgingItem[]>([]);
  const [selected, setSelected] = useState<agingReportApi.AgingItem | null>(null);
  const [asOfDraft, setAsOfDraft] = useState(today);
  const [counterpartyDraft, setCounterpartyDraft] = useState<number | null>(null);
  const [asOf, setAsOf] = useState(today);
  const [counterpartyId, setCounterpartyId] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [reportAsOf, setReportAsOf] = useState('');
  const [grandTotal, setGrandTotal] = useState<agingReportApi.AgingBuckets>(emptyBuckets);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState('');
  const listVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    let query: Promise<agingReportApi.AgingReport>;
    switch (kind) {
      case 'receivable':
        query = agingReportApi.searchReceivableAging(asOf, counterpartyId, page, PAGE_SIZE);
        break;
      case 'payable':
        query = agingReportApi.searchPayableAging(asOf, counterpartyId, page, PAGE_SIZE);
        break;
      default: {
        const exhaustive: never = kind;
        throw new Error(`未知账龄类型: ${exhaustive}`);
      }
    }
    void query
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
        setReportAsOf(result.asOf);
        setGrandTotal(result.grandTotal);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, copy.permissionHint));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [kind, asOf, counterpartyId, page, refreshKey, copy.permissionHint]);

  const clearSelection = () => {
    setSelected(null);
  };

  const applySearch = () => {
    setSelected(null);
    setAsOf(asOfDraft);
    setCounterpartyId(counterpartyDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const exportExcel = () => {
    if (exporting) return;
    setExporting(true);
    setError('');
    const fetchPage = (targetPage: number, size: number) => {
      switch (kind) {
        case 'receivable':
          return agingReportApi.searchReceivableAging(asOf, counterpartyId, targetPage, size);
        case 'payable':
          return agingReportApi.searchPayableAging(asOf, counterpartyId, targetPage, size);
        default: {
          const exhaustive: never = kind;
          throw new Error(`未知账龄类型: ${exhaustive}`);
        }
      }
    };
    void collectAllPages(fetchPage, REPORT_EXPORT_PAGE_SIZE)
      .then(({ first, items }) => {
        const headers = [
          copy.counterpartyLabel, '未到期', '逾期 1-30', '逾期 31-60', '逾期 61-90', '逾期 90+', '合计',
        ];
        const rowOf = (item: agingReportApi.AgingItem | agingReportApi.AgingBuckets, label: string) => [
          label,
          amountText(item.notDue),
          amountText(item.overdue1To30),
          amountText(item.overdue31To60),
          amountText(item.overdue61To90),
          amountText(item.overdue90Plus),
          amountText(item.totalOutstanding),
        ];
        downloadSpreadsheet(spreadsheetFilename(copy.title, first.asOf || asOf), [{
          name: copy.title,
          headers,
          rows: [
            rowOf(first.grandTotal, '全量合计'),
            ...items.map((item) => rowOf(item, counterpartyLabel(item))),
          ],
        }]);
      })
      .catch((cause: unknown) => {
        setError(errorMessage(cause, copy.permissionHint));
      })
      .finally(() => setExporting(false));
  };

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">{copy.kicker}</p>
        <h1>{copy.title}</h1>
        <p>{copy.intro}</p>
      </div>
      <div className="customer-toolbar">
        <button type="button" className="memory-button memory-button-primary"
          disabled={exporting || loading} onClick={exportExcel}>
          {exporting ? '正在导出…' : '导出 Excel'}
        </button>
      </div>
    </header>
    <div className="purchase-order-filters">
      <label>截止日<input type="date" aria-label="账龄截止日" value={asOfDraft}
        onChange={(event) => setAsOfDraft(event.target.value)} /></label>
      {kind === 'receivable'
        ? <CustomerPicker label={copy.filterLabel} value={counterpartyDraft} allowClear
            onChange={setCounterpartyDraft} />
        : <SupplierPicker label={copy.filterLabel} value={counterpartyDraft} allowClear
            onChange={setCounterpartyDraft} />}
      <button type="button" className="memory-button" onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    <p className="memory-empty" role="status">
      截止日 {reportAsOf || asOf || '今天'} · 全量合计（当前筛选，非本页）：
      未到期 {amountText(grandTotal.notDue)} ·
      逾期 1-30 {amountText(grandTotal.overdue1To30)} ·
      逾期 31-60 {amountText(grandTotal.overdue31To60)} ·
      逾期 61-90 {amountText(grandTotal.overdue61To90)} ·
      逾期 90+ {amountText(grandTotal.overdue90Plus)} ·
      合计 {amountText(grandTotal.totalOutstanding)}
    </p>
    <BarChart
      title="账龄分桶（当前筛选全集）"
      empty="暂无账龄金额可绘图。"
      bars={[
        { label: '未到期', value: grandTotal.notDue },
        { label: '逾期 1-30', value: grandTotal.overdue1To30 },
        { label: '逾期 31-60', value: grandTotal.overdue31To60 },
        { label: '逾期 61-90', value: grandTotal.overdue61To90 },
        { label: '逾期 90+', value: grandTotal.overdue90Plus },
      ]}
    />
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>{copy.counterpartyLabel}</th>
          <th>未到期</th><th>逾期 1-30</th><th>逾期 31-60</th>
          <th>逾期 61-90</th><th>逾期 90+</th><th>合计</th>
        </tr></thead><tbody>
          {items.map((item) => <tr key={item.counterpartyId}
            className={selected?.counterpartyId === item.counterpartyId ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button"
              onClick={() => setSelected(item)}>{counterpartyLabel(item)}</button></td>
            <td>{amountText(item.notDue)}</td>
            <td>{amountText(item.overdue1To30)}</td>
            <td>{amountText(item.overdue31To60)}</td>
            <td>{amountText(item.overdue61To90)}</td>
            <td>{amountText(item.overdue90Plus)}</td>
            <td>{amountText(item.totalOutstanding)}</td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">{copy.loading}</p>}
        {!loading && items.length === 0 && <p className="memory-empty">{copy.empty}</p>}
        <div className="memory-pagination">
          <button type="button" disabled={page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 笔</span>
          <button type="button" disabled={page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {selected
          ? <AgingDetails item={selected} counterpartyTitle={copy.counterpartyLabel} />
          : <p className="memory-empty">选择一行查看分桶明细。核销流水请到对应应收/应付台账，按子账 id 查询。</p>}
      </aside>
    </div>
  </section>;
}

function AgingDetails({
  item,
  counterpartyTitle,
}: {
  item: agingReportApi.AgingItem;
  counterpartyTitle: string;
}) {
  return <>
    <h2>{counterpartyLabel(item)}</h2>
    <dl>
      <dt>{counterpartyTitle} id</dt><dd>#{item.counterpartyId}</dd>
      <dt>编码</dt><dd>{item.counterpartyCode || '—'}</dd>
      <dt>名称</dt><dd>{item.counterpartyName || '—'}</dd>
      <dt>未到期</dt><dd>{amountText(item.notDue)}</dd>
      <dt>逾期 1-30</dt><dd>{amountText(item.overdue1To30)}</dd>
      <dt>逾期 31-60</dt><dd>{amountText(item.overdue31To60)}</dd>
      <dt>逾期 61-90</dt><dd>{amountText(item.overdue61To90)}</dd>
      <dt>逾期 90+</dt><dd>{amountText(item.overdue90Plus)}</dd>
      <dt>合计</dt><dd>{amountText(item.totalOutstanding)}</dd>
    </dl>
  </>;
}

interface PickerProps {
  label: string;
  value: number | null;
  allowClear?: boolean;
  onChange: (id: number | null) => void;
}

function CustomerPicker({ label, value, allowClear = false, onChange }: PickerProps) {
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

function SupplierPicker({ label, value, allowClear = false, onChange }: PickerProps) {
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
