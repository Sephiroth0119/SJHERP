import { Button, Form } from 'antd';
import type { ReactNode } from 'react';

export const FilterItem = Form.Item;

/** 行内筛选项 + 查询按钮。提交不收集值，由调用方读取受控 draft。 */
export function FilterBar({
  children,
  onSearch,
  searchText = '查询',
}: {
  children: ReactNode;
  onSearch: () => void;
  searchText?: string;
}) {
  return (
    <Form layout="inline" className="workbench-filter-bar" colon={false} onFinish={onSearch}>
      {children}
      <FilterItem>
        <Button type="primary" htmlType="submit">{searchText}</Button>
      </FilterItem>
    </Form>
  );
}
