import type { ReportColumn } from '../../report/loadReport.ts';

export function QueryTable({
  columns,
  rows,
  loading,
  empty,
}: {
  columns: readonly ReportColumn[];
  rows: readonly Record<string, string>[];
  loading: boolean;
  empty: string;
}) {
  return (
    <section className="customer-list-panel report-table-panel">
      <div className="memory-table-wrap">
        <table className="memory-table">
          <thead>
            <tr>
              {columns.map((column) => <th key={column.key}>{column.title}</th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={`${index}-${columns.map((column) => row[column.key] ?? '').join('|')}`}>
                {columns.map((column) => <td key={column.key}>{row[column.key] || '—'}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {loading && <p className="memory-empty" role="status">正在加载报表…</p>}
      {!loading && rows.length === 0 && <p className="memory-empty">{empty}</p>}
    </section>
  );
}
