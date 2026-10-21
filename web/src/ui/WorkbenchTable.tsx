import { Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Key, KeyboardEvent } from 'react';

export interface WorkbenchTableProps<T extends object> {
  columns: ColumnsType<T>;
  dataSource: readonly T[];
  loading?: boolean;
  rowKey: keyof T | ((record: T) => Key);
  selectedKey?: Key | null;
  onRowClick?: (record: T) => void;
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number) => void;
  emptyText?: string;
}

function recordKey<T extends object>(
  record: T,
  rowKey: WorkbenchTableProps<T>['rowKey'],
): Key {
  if (typeof rowKey === 'function') return rowKey(record);
  const value = record[rowKey];
  if (typeof value === 'string' || typeof value === 'number') return value;
  return String(value);
}

export function WorkbenchTable<T extends object>({
  columns,
  dataSource,
  loading = false,
  rowKey,
  selectedKey,
  onRowClick,
  page = 1,
  pageSize = 20,
  total,
  onPageChange,
  emptyText = '暂无数据',
}: WorkbenchTableProps<T>) {
  const paged = typeof total === 'number' && onPageChange !== undefined;
  return (
    <Table<T>
      size="small"
      columns={columns}
      dataSource={[...dataSource]}
      loading={loading}
      rowKey={(record) => recordKey(record, rowKey)}
      locale={{ emptyText }}
      scroll={{ x: 'max-content' }}
      pagination={paged ? {
        current: page,
        pageSize,
        total,
        showSizeChanger: false,
        showTotal: (count) => `共 ${count} 条 · 第 ${page} 页`,
        onChange: onPageChange,
      } : false}
      rowClassName={(record) => (
        selectedKey != null && String(recordKey(record, rowKey)) === String(selectedKey)
          ? 'workbench-table-row-selected'
          : ''
      )}
      onRow={onRowClick ? (record) => ({
        tabIndex: 0,
        'aria-selected': selectedKey != null && String(recordKey(record, rowKey)) === String(selectedKey),
        onClick: () => onRowClick(record),
        onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            onRowClick(record);
          }
        },
      }) : undefined}
    />
  );
}
