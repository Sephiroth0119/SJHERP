import type { ReportColumn } from '../../report/loadReport.ts';
import { WorkbenchTable, formatAmountText } from '../../ui/index.ts';

export function QueryTable({
  columns,
  rows,
  loading,
  empty,
  page,
  total,
  onPageChange,
}: {
  columns: readonly ReportColumn[];
  rows: readonly Record<string, string>[];
  loading: boolean;
  empty: string;
  page?: number;
  total?: number;
  onPageChange?: (page: number) => void;
}) {
  return (
    <section className="customer-list-panel report-table-panel">
      <WorkbenchTable<Record<string, string>>
        columns={columns.map((column) => ({
          title: column.title,
          dataIndex: column.key,
          key: column.key,
          render: (value: string | undefined) => formatAmountText(value),
        }))}
        dataSource={rows}
        loading={loading}
        rowKey={(row) => columns.map((column) => `${column.key}=${row[column.key] ?? ''}`).join('|')}
        page={page}
        total={total}
        onPageChange={onPageChange}
        emptyText={empty}
      />
    </section>
  );
}
