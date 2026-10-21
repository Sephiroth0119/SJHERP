import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ApiError } from '../api/http.ts';
import * as catalogApi from '../api/catalogApi.ts';
import * as glAccountApi from '../api/glAccountApi.ts';
import * as masterDataApi from '../api/masterDataApi.ts';
import { downloadSpreadsheet, spreadsheetFilename } from '../report/excelExport.ts';
import {
  emptyReportFilters,
  loadReport,
  PAGE_SIZE,
  type ReportFilters,
  type ReportView,
} from '../report/loadReport.ts';
import {
  isReportKind,
  reportLabel,
  resolveReportKind,
  visibleReportKinds,
  type ReportKind,
} from '../report/reportKinds.ts';
import { createRequestGate } from '../security/workbenchControl.ts';
import { BarChart } from './report/BarChart.tsx';
import { QueryTable } from './report/QueryTable.tsx';

const DOCUMENT_STATUSES = [
  '',
  'DRAFT',
  'APPROVED',
  'EXECUTING',
  'COMPLETED',
  'CANCELLED',
  'REVERSED',
] as const;

function errorMessage(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) return cause.message;
  if (cause instanceof Error && cause.message) return cause.message;
  return fallback;
}

function parseOptionalId(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (!/^[1-9]\d{0,15}$/.test(trimmed)) return null;
  return Number(trimmed);
}

function monthValue(period: string): string {
  const match = /^(\d{4})(\d{2})$/.exec(period.trim());
  return match ? `${match[1]}-${match[2]}` : '';
}

function optionalIdText(value: number | null): string {
  return value === null ? '' : String(value);
}

export function ReportWorkbench({
  permissions,
  tab,
  onTabChange,
}: {
  permissions: string[];
  tab?: string;
  onTabChange: (tab: string) => void;
}) {
  const visible = useMemo(() => visibleReportKinds(permissions), [permissions]);
  const kind = resolveReportKind(tab, permissions);
  const [draft, setDraft] = useState<ReportFilters>(emptyReportFilters);
  const [filters, setFilters] = useState<ReportFilters>(emptyReportFilters);
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [view, setView] = useState<ReportView | null>(null);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState('');
  const [warehouses, setWarehouses] = useState<masterDataApi.Warehouse[]>([]);
  const [products, setProducts] = useState<catalogApi.Product[]>([]);
  const [accounts, setAccounts] = useState<glAccountApi.GlAccount[]>([]);
  const reportVersion = useRef(createRequestGate());

  useEffect(() => {
    if (kind && tab !== kind) onTabChange(kind);
  }, [kind, onTabChange, tab]);

  useEffect(() => {
    void masterDataApi.searchWarehouses('', 'ENABLED', 1).then((result) => {
      setWarehouses(result.items);
    }).catch(() => {
      setWarehouses([]);
    });
    void catalogApi.searchProducts('', 'ENABLED', 1).then((result) => {
      setProducts(result.items);
    }).catch(() => {
      setProducts([]);
    });
    void glAccountApi.listAccounts().then((result) => {
      setAccounts(result);
    }).catch(() => {
      setAccounts([]);
    });
  }, []);

  useEffect(() => {
    if (!kind) {
      setView(null);
      setLoading(false);
      setError('');
      return;
    }
    const version = reportVersion.current.next();
    setLoading(true);
    setError('');
    void loadReport(kind, filters, page, 'page')
      .then((result) => {
        if (!reportVersion.current.isCurrent(version)) return;
        setView(result);
      })
      .catch((cause: unknown) => {
        if (!reportVersion.current.isCurrent(version)) return;
        setView(null);
        setError(errorMessage(cause, '报表加载失败'));
      })
      .finally(() => {
        if (reportVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [filters, kind, page, refreshKey]);

  const applySearch = () => {
    setFilters({ ...draft });
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const changeKind = (next: ReportKind) => {
    setPage(1);
    setView(null);
    setError('');
    onTabChange(next);
  };

  const exportExcel = () => {
    if (!kind || exporting) return;
    setExporting(true);
    setError('');
    void loadReport(kind, filters, 1, 'export')
      .then((result) => {
        downloadSpreadsheet(
          spreadsheetFilename(reportLabel(kind), result.stamp),
          result.sheets,
        );
      })
      .catch((cause: unknown) => {
        setError(errorMessage(cause, '导出失败'));
      })
      .finally(() => setExporting(false));
  };

  if (!kind) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">报表</p>
          <h1>报表查询</h1>
          <p>当前账号没有任何可查看的报表权限。</p>
        </div>
      </header>
    </section>;
  }

  const pageCount = view && view.paged
    ? Math.max(1, Math.trunc((view.total + PAGE_SIZE - 1) / PAGE_SIZE))
    : 1;

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">报表 / 查询</p>
        <h1>{reportLabel(kind)}</h1>
        <p>
          只读查询已落地报表。金额与数量按后端字符串展示，导出 Excel 不改写精度。
          无权限的报表不会出现在列表中。错误展示后端返回原文。
        </p>
      </div>
      <div className="customer-toolbar">
        <button type="button" className="memory-button memory-button-primary"
          disabled={exporting || loading}
          onClick={exportExcel}>
          {exporting ? '正在导出…' : '导出 Excel'}
        </button>
      </div>
    </header>
    <div className="purchase-order-filters report-filters">
      <label>报表
        <select aria-label="报表类型" value={kind}
          onChange={(event) => {
            if (isReportKind(event.target.value)) changeKind(event.target.value);
          }}>
          {visible.map((item) => (
            <option key={item} value={item}>{reportLabel(item)}</option>
          ))}
        </select>
      </label>
      <ReportFiltersForm kind={kind} draft={draft} warehouses={warehouses}
        products={products} accounts={accounts}
        onChange={setDraft} onSubmit={applySearch} />
      <button type="button" className="memory-button" onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {view?.summary && <p className="memory-empty" role="status">{view.summary}</p>}
    {view?.chart && <BarChart title={view.chart.title} bars={view.chart.bars} empty={view.chart.empty} />}
    <QueryTable
      columns={view?.columns ?? []}
      rows={view?.rows ?? []}
      loading={loading}
      empty="暂无数据，请调整筛选条件。"
    />
    {view?.paged && <div className="memory-pagination">
      <button type="button" disabled={page <= 1 || loading}
        onClick={() => setPage(page - 1)}>上一页</button>
      <span>第 {page} / {pageCount} 页，共 {view.total} 笔</span>
      <button type="button" disabled={page >= pageCount || loading}
        onClick={() => setPage(page + 1)}>下一页</button>
    </div>}
  </section>;
}

function ReportFiltersForm({
  kind,
  draft,
  warehouses,
  products,
  accounts,
  onChange,
  onSubmit,
}: {
  kind: ReportKind;
  draft: ReportFilters;
  warehouses: masterDataApi.Warehouse[];
  products: catalogApi.Product[];
  accounts: glAccountApi.GlAccount[];
  onChange: (next: ReportFilters) => void;
  onSubmit: () => void;
}) {
  const patch = (partial: Partial<ReportFilters>) => onChange({ ...draft, ...partial });
  const enterToSubmit = (event: KeyboardEvent) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      onSubmit();
    }
  };

  const periodFields = <>
    <label>账期
      <input type="month" aria-label="报表账期" value={monthValue(draft.period)}
        onChange={(event) => patch({ period: event.target.value.replace('-', '') })} />
    </label>
    <label>账期文本
      <input aria-label="报表账期文本" placeholder="yyyyMM" maxLength={6} value={draft.period}
        onChange={(event) => patch({ period: event.target.value.replace(/\D/g, '').slice(0, 6) })}
        onKeyDown={enterToSubmit} />
    </label>
  </>;

  const dateRangeFields = <>
    <label>起始日<input type="date" aria-label="起始日" value={draft.fromDate}
      onChange={(event) => patch({ fromDate: event.target.value })} /></label>
    <label>结束日<input type="date" aria-label="结束日" value={draft.toDate}
      onChange={(event) => patch({ toDate: event.target.value })} /></label>
  </>;

  const warehouseField = (
    <label>仓库
      <select aria-label="仓库" value={optionalIdText(draft.warehouseId)}
        onChange={(event) => patch({ warehouseId: parseOptionalId(event.target.value) })}>
        <option value="">全部仓库</option>
        {warehouses.map((warehouse) => (
          <option key={warehouse.id} value={warehouse.id}>
            {warehouse.name}（{warehouse.code}）
          </option>
        ))}
      </select>
    </label>
  );

  const productField = (
    <label>商品
      <select aria-label="商品" value={optionalIdText(draft.productId)}
        onChange={(event) => patch({ productId: parseOptionalId(event.target.value) })}>
        <option value="">全部商品</option>
        {products.map((product) => (
          <option key={product.id} value={product.id}>
            {product.name}（{product.code}）
          </option>
        ))}
      </select>
    </label>
  );

  const keywordField = (
    <label>关键字
      <input aria-label="关键字" placeholder="商品编码或名称" value={draft.keyword}
        onChange={(event) => patch({ keyword: event.target.value })}
        onKeyDown={enterToSubmit} />
    </label>
  );

  const statusField = (
    <label>状态
      <select aria-label="单据状态" value={draft.status}
        onChange={(event) => patch({ status: event.target.value })}>
        {DOCUMENT_STATUSES.map((status) => (
          <option key={status || 'all'} value={status}>{status || '全部状态'}</option>
        ))}
      </select>
    </label>
  );

  switch (kind) {
    case 'inventory-balance':
      return <>
        {warehouseField}
        {productField}
        {keywordField}
        <label className="report-checkbox">含零余额
          <input type="checkbox" aria-label="含零余额" checked={draft.includeZero}
            onChange={(event) => patch({ includeZero: event.target.checked })} />
        </label>
      </>;
    case 'stock-movement':
      return <>
        {dateRangeFields}
        {warehouseField}
        {productField}
        {keywordField}
      </>;
    case 'purchase-detail':
      return <>
        {dateRangeFields}
        <label>供应商 id
          <input aria-label="供应商 id" inputMode="numeric" value={optionalIdText(draft.supplierId)}
            onChange={(event) => patch({ supplierId: parseOptionalId(event.target.value) })}
            onKeyDown={enterToSubmit} />
        </label>
        {warehouseField}
        {productField}
        {statusField}
      </>;
    case 'sales-detail':
      return <>
        {dateRangeFields}
        <label>客户 id
          <input aria-label="客户 id" inputMode="numeric" value={optionalIdText(draft.customerId)}
            onChange={(event) => patch({ customerId: parseOptionalId(event.target.value) })}
            onKeyDown={enterToSubmit} />
        </label>
        {warehouseField}
        {productField}
        {statusField}
      </>;
    case 'receivable-aging':
      return <>
        <label>截止日<input type="date" aria-label="账龄截止日" value={draft.asOf}
          onChange={(event) => patch({ asOf: event.target.value })} /></label>
        <label>客户 id
          <input aria-label="客户 id" inputMode="numeric" value={optionalIdText(draft.customerId)}
            onChange={(event) => patch({ customerId: parseOptionalId(event.target.value) })}
            onKeyDown={enterToSubmit} />
        </label>
      </>;
    case 'payable-aging':
      return <>
        <label>截止日<input type="date" aria-label="账龄截止日" value={draft.asOf}
          onChange={(event) => patch({ asOf: event.target.value })} /></label>
        <label>供应商 id
          <input aria-label="供应商 id" inputMode="numeric" value={optionalIdText(draft.supplierId)}
            onChange={(event) => patch({ supplierId: parseOptionalId(event.target.value) })}
            onKeyDown={enterToSubmit} />
        </label>
      </>;
    case 'trial-balance':
      return periodFields;
    case 'account-balance':
      return <>
        {periodFields}
        <label>科目
          <select aria-label="科目编码" value={draft.accountCode}
            onChange={(event) => patch({ accountCode: event.target.value })}>
            <option value="">请选择科目</option>
            {accounts.map((account) => (
              <option key={account.code} value={account.code}>
                {account.code} {account.name}
              </option>
            ))}
          </select>
        </label>
        <label>科目编码
          <input aria-label="科目编码文本" placeholder="如 1001" value={draft.accountCode}
            onChange={(event) => patch({ accountCode: event.target.value.trim() })}
            onKeyDown={enterToSubmit} />
        </label>
      </>;
    case 'balance-sheet':
    case 'income-statement':
      return periodFields;
    default: {
      const exhaustive: never = kind;
      throw new Error(`未知报表类型: ${exhaustive}`);
    }
  }
}
