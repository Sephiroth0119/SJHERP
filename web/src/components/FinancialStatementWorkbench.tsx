import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as statementApi from '../api/financialStatementApi.ts';
import { downloadSpreadsheet, spreadsheetFilename } from '../report/excelExport.ts';
import { createRequestGate } from '../security/workbenchControl.ts';
import { BarChart } from './report/BarChart.tsx';

type StatementKind = 'balance-sheet' | 'income-statement';

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const amountText = (value: string) => (value === '' ? '—' : value);

function currentPeriod() {
  const value = new Date();
  return `${value.getFullYear()}${String(value.getMonth() + 1).padStart(2, '0')}`;
}

const monthValue = (period: string) => {
  const match = /^(\d{4})(\d{2})$/.exec(period.trim());
  return match ? `${match[1]}-${match[2]}` : '';
};

function statementCopy(kind: StatementKind) {
  switch (kind) {
    case 'balance-sheet':
      return {
        kicker: '财务 / 报表',
        title: '资产负债表',
        intro: '截至账期末时点，由已过账凭证累计派生。资产总计应等于负债合计加权益合计。查看需要 finance:report 权限。',
        permissionHint: '资产负债表加载失败。查看报表需要 finance:report 权限。',
        empty: '暂无资产负债表数据，请调整账期。',
        loading: '正在加载资产负债表…',
      };
    case 'income-statement':
      return {
        kicker: '财务 / 报表',
        title: '利润表',
        intro: '本期与本年累计两列，排除期末结转凭证。净利润应与关账结转净额交叉核对。查看需要 finance:report 权限。',
        permissionHint: '利润表加载失败。查看报表需要 finance:report 权限。',
        empty: '暂无利润表数据，请调整账期。',
        loading: '正在加载利润表…',
      };
    default: {
      const exhaustive: never = kind;
      throw new Error(`未知报表类型: ${exhaustive}`);
    }
  }
}

export function BalanceSheetWorkbench() {
  return <FinancialStatementWorkbench kind="balance-sheet" />;
}

export function IncomeStatementWorkbench() {
  return <FinancialStatementWorkbench kind="income-statement" />;
}

function FinancialStatementWorkbench({ kind }: { kind: StatementKind }) {
  const copy = statementCopy(kind);
  const [periodDraft, setPeriodDraft] = useState(currentPeriod);
  const [period, setPeriod] = useState(currentPeriod);
  const [refreshKey, setRefreshKey] = useState(0);
  const [sheet, setSheet] = useState<statementApi.BalanceSheet | null>(null);
  const [income, setIncome] = useState<statementApi.IncomeStatement | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const reportVersion = useRef(createRequestGate());

  useEffect(() => {
    const version = reportVersion.current.next();
    setLoading(true);
    setError('');
    const validationError = statementApi.validateReportPeriod(period);
    if (validationError) {
      setError(validationError);
      setSheet(null);
      setIncome(null);
      setLoading(false);
      return;
    }
    let query: Promise<statementApi.BalanceSheet | statementApi.IncomeStatement>;
    switch (kind) {
      case 'balance-sheet':
        query = statementApi.getBalanceSheet(period);
        break;
      case 'income-statement':
        query = statementApi.getIncomeStatement(period);
        break;
      default: {
        const exhaustive: never = kind;
        throw new Error(`未知报表类型: ${exhaustive}`);
      }
    }
    void query
      .then((result) => {
        if (!reportVersion.current.isCurrent(version)) return;
        switch (kind) {
          case 'balance-sheet':
            setSheet(result as statementApi.BalanceSheet);
            setIncome(null);
            break;
          case 'income-statement':
            setIncome(result as statementApi.IncomeStatement);
            setSheet(null);
            break;
          default: {
            const exhaustive: never = kind;
            throw new Error(`未知报表类型: ${exhaustive}`);
          }
        }
      })
      .catch((cause: unknown) => {
        if (reportVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, copy.permissionHint));
          setSheet(null);
          setIncome(null);
        }
      })
      .finally(() => {
        if (reportVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [copy.permissionHint, kind, period, refreshKey]);

  const applySearch = () => {
    const validationError = statementApi.validateReportPeriod(periodDraft);
    if (validationError) {
      setError(validationError);
      return;
    }
    setPeriod(periodDraft.trim());
    setRefreshKey((value) => value + 1);
  };

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">{copy.kicker}</p>
        <h1>{copy.title}</h1>
        <p>{copy.intro}</p>
      </div>
    </header>
    <div className="purchase-order-filters">
      <label>账期
        <input type="month" aria-label="报表账期" value={monthValue(periodDraft)}
          onChange={(event) => setPeriodDraft(event.target.value.replace('-', ''))} />
      </label>
      <label>账期文本
        <input aria-label="报表账期文本" placeholder="yyyyMM" maxLength={6} value={periodDraft}
          onChange={(event) => setPeriodDraft(event.target.value.replace(/\D/g, '').slice(0, 6))}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              applySearch();
            }
          }} />
      </label>
      <button type="button" className="memory-button" onClick={applySearch}>查询</button>
      <button type="button" className="memory-button memory-button-primary"
        disabled={loading || (!sheet && !income)}
        onClick={() => exportCurrentStatement(kind, copy.title, sheet, income)}>导出 Excel</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {loading && <p className="memory-empty" role="status">{copy.loading}</p>}
    {!loading && kind === 'balance-sheet' && sheet && <BalanceSheetPanel sheet={sheet} />}
    {!loading && kind === 'income-statement' && income && <IncomeStatementPanel statement={income} />}
    {!loading && !error && kind === 'balance-sheet' && !sheet && <p className="memory-empty">{copy.empty}</p>}
    {!loading && !error && kind === 'income-statement' && !income && <p className="memory-empty">{copy.empty}</p>}
  </section>;
}

function exportCurrentStatement(
  kind: StatementKind,
  title: string,
  sheet: statementApi.BalanceSheet | null,
  income: statementApi.IncomeStatement | null,
) {
  switch (kind) {
    case 'balance-sheet':
      if (!sheet) return;
      downloadSpreadsheet(spreadsheetFilename(title, sheet.period), [{
        name: title,
        headers: ['类别', '项目', '金额'],
        rows: [
          ...sheet.assetLines.map((line) => ['资产', line.name, amountText(line.amount)]),
          ['资产', '资产总计', amountText(sheet.totalAssets)],
          ...sheet.liabilityLines.map((line) => ['负债', line.name, amountText(line.amount)]),
          ['负债', '负债合计', amountText(sheet.totalLiabilities)],
          ...sheet.equityLines.map((line) => ['所有者权益', line.name, amountText(line.amount)]),
          ['所有者权益', '权益合计', amountText(sheet.totalEquity)],
        ],
      }]);
      return;
    case 'income-statement':
      if (!income) return;
      downloadSpreadsheet(spreadsheetFilename(title, income.period), [{
        name: title,
        headers: ['项目', '本期', '本年累计'],
        rows: income.lines.map((line) => [
          line.name, amountText(line.currentPeriod), amountText(line.yearToDate),
        ]),
      }]);
      return;
    default: {
      const exhaustive: never = kind;
      throw new Error(`未知报表类型: ${exhaustive}`);
    }
  }
}

function BalanceSheetPanel({ sheet }: { sheet: statementApi.BalanceSheet }) {
  return <>
    <p className="memory-empty" role="status">
      账期 {sheet.period} · {sheet.balanced ? '已平衡（资产 = 负债 + 权益）' : '未平衡，请核对未分类科目或凭证'}
    </p>
    <BarChart
      title="资产负债结构"
      empty="暂无资产负债表金额可绘图。"
      bars={[
        { label: '资产总计', value: sheet.totalAssets },
        { label: '负债合计', value: sheet.totalLiabilities },
        { label: '权益合计', value: sheet.totalEquity },
      ]}
    />
    <div className="customer-layout purchase-order-layout">
      <StatementBlock title="资产" lines={sheet.assetLines} totalLabel="资产总计" total={sheet.totalAssets} />
      <StatementBlock title="负债" lines={sheet.liabilityLines} totalLabel="负债合计" total={sheet.totalLiabilities} />
      <StatementBlock title="所有者权益" lines={sheet.equityLines} totalLabel="权益合计" total={sheet.totalEquity} />
    </div>
  </>;
}

function StatementBlock({
  title,
  lines,
  totalLabel,
  total,
}: {
  title: string;
  lines: statementApi.BalanceSheetLine[];
  totalLabel: string;
  total: string;
}) {
  return <section className="customer-list-panel">
    <h2>{title}</h2>
    <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
      <th>项目</th><th>金额</th>
    </tr></thead><tbody>
      {lines.map((line) => <tr key={line.name}>
        <td>{line.name}</td>
        <td>{amountText(line.amount)}</td>
      </tr>)}
      <tr>
        <td><strong>{totalLabel}</strong></td>
        <td><strong>{amountText(total)}</strong></td>
      </tr>
    </tbody></table></div>
  </section>;
}

function IncomeStatementPanel({ statement }: { statement: statementApi.IncomeStatement }) {
  return <>
    <p className="memory-empty" role="status">
      账期 {statement.period} · 本期净利润 {amountText(statement.netProfitCurrent)} ·
      本年累计净利润 {amountText(statement.netProfitYtd)}
    </p>
    <BarChart
      title="净利润本期 vs 本年累计"
      empty="暂无利润金额可绘图。"
      bars={[
        { label: '本期净利润', value: statement.netProfitCurrent },
        { label: '本年累计净利润', value: statement.netProfitYtd },
      ]}
    />
    <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
      <th>项目</th><th>本期</th><th>本年累计</th>
    </tr></thead><tbody>
      {statement.lines.map((line) => <tr key={line.name}>
        <td>{line.name}</td>
        <td>{amountText(line.currentPeriod)}</td>
        <td>{amountText(line.yearToDate)}</td>
      </tr>)}
    </tbody></table></div>
  </>;
}
