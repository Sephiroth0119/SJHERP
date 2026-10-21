import { Button, Input, Select } from 'antd';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  createCustomer, getCustomer, searchCustomers, setCustomerStatus, updateCustomer,
  type Customer, type CustomerForm,
} from '../api/customerApi.ts';
import {
  AppDrawer,
  AppModal,
  FilterBar,
  FilterItem,
  WorkbenchTable,
  displayApiError,
  formatAmountText,
  useWorkbenchNotice,
} from '../ui/index.ts';

const blank: CustomerForm = {
  code: '', name: '', contactPerson: '', contactPhone: '', address: '', taxNo: '',
  settlementMethod: 'MONTHLY', creditLimit: '',
};
const fields: Array<[keyof CustomerForm, string, number]> = [
  ['code', '客户编码（创建时可留空自动生成）', 50], ['name', '客户名称', 200],
  ['contactPerson', '联系人', 64], ['contactPhone', '联系电话', 32],
  ['address', '地址', 255], ['taxNo', '税号', 64],
];
const toForm = (c: Customer): CustomerForm => ({
  code: c.code, name: c.name, contactPerson: c.contactPerson ?? '',
  contactPhone: c.contactPhone ?? '', address: c.address ?? '', taxNo: c.taxNo ?? '',
  settlementMethod: c.settlementMethod, creditLimit: c.creditLimit,
});
const settlementLabel = (value: Customer['settlementMethod']) => {
  switch (value) {
    case 'MONTHLY': return '月结';
    case 'CASH': return '现结';
    case 'PREPAID': return '预付';
    default: {
      const exhaustive: never = value;
      return exhaustive;
    }
  }
};

export function CustomerWorkbench({ permissions }: { permissions: string[] }) {
  const canCreate = permissions.includes('partner:create_customer');
  const canWrite = permissions.includes('partner:write');
  const notice = useWorkbenchNotice();
  const [items, setItems] = useState<Customer[]>([]);
  const [selected, setSelected] = useState<Customer | null>(null);
  const [form, setForm] = useState(blank);
  const [draftKeyword, setDraftKeyword] = useState('');
  const [draftStatus, setDraftStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [refreshKey, setRefreshKey] = useState(0);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const detailRequest = useRef(0);
  const listRequest = useRef(0);

  useEffect(() => {
    const requestId = ++listRequest.current;
    let cancelled = false;
    setLoading(true);
    setError('');
    void searchCustomers(keyword, status, page).then((result) => {
      if (cancelled || requestId !== listRequest.current) return;
      setItems(result.items); setTotal(result.total); setLoading(false);
    }).catch((cause: unknown) => {
      if (cancelled || requestId !== listRequest.current) return;
      setError(displayApiError(cause, '客户列表加载失败，请重试'));
      setLoading(false);
    });
    return () => { cancelled = true; };
  }, [keyword, status, page, refreshKey]);

  const applySearch = () => {
    setKeyword(draftKeyword); setStatus(draftStatus); setPage(1);
    setRefreshKey((key) => key + 1);
  };
  const choose = (customer: Customer) => {
    const requestId = ++detailRequest.current;
    setSelected(customer); setEditing(false);
    void getCustomer(customer.id).then((result) => {
      if (requestId === detailRequest.current) setSelected(result);
    }).catch((cause: unknown) => {
      if (requestId === detailRequest.current) {
        setError(displayApiError(cause, '客户详情加载失败'));
      }
    });
  };
  const startNew = () => {
    setSelected(null); setForm(blank); setEditing(true); setError('');
  };
  const startEdit = () => {
    if (selected) { setForm(toForm(selected)); setEditing(true); setError(''); }
  };
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSaving(true); setError('');
    try {
      const result = selected ? await updateCustomer(selected.id, form) : await createCustomer(form);
      setSelected(result); setEditing(false); notice.success('客户档案已保存');
      setRefreshKey((key) => key + 1);
    } catch (cause) {
      setError(displayApiError(cause, '保存失败，请检查输入后重试'));
    } finally { setSaving(false); }
  };
  const toggle = async () => {
    if (!selected || saving) return;
    const disabling = selected.status === 'ENABLED';
    if (disabling) {
      const confirmed = await notice.confirm('停用后新的销售订单不得引用该客户，历史单据不受影响。确认停用吗？');
      if (!confirmed) return;
    }
    setSaving(true); setError('');
    try {
      const result = await setCustomerStatus(selected.id, disabling ? 'DISABLED' : 'ENABLED');
      setSelected(result);
      notice.success(result.status === 'ENABLED' ? '客户已启用' : '客户已停用');
      setRefreshKey((key) => key + 1);
    } catch (cause) {
      setError(displayApiError(cause, '状态更新失败'));
    } finally { setSaving(false); }
  };
  const update = (key: keyof CustomerForm, value: string) => setForm((current) => ({ ...current, [key]: value }));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">销售 / 基础档案</p>
        <h1>客户档案</h1>
        <p>查找、维护销售往来的客户资料。</p>
      </div>
      {canCreate && <Button type="primary" onClick={startNew}>新建客户</Button>}
    </header>
    {error && <div className="memory-error" role="alert" tabIndex={-1}>
      {error}
      <button type="button" onClick={() => setRefreshKey((key) => key + 1)}>重试</button>
    </div>}
    <FilterBar onSearch={applySearch}>
      <FilterItem>
        <Input
          allowClear
          style={{ width: 280 }}
          aria-label="搜索客户"
          placeholder="编码、名称、联系人或电话"
          value={draftKeyword}
          onChange={(event) => setDraftKeyword(event.target.value)}
        />
      </FilterItem>
      <FilterItem>
        <Select
          allowClear
          style={{ width: 140 }}
          aria-label="客户状态"
          placeholder="全部状态"
          value={draftStatus || undefined}
          onChange={(value) => setDraftStatus(value ?? '')}
          options={[
            { value: 'ENABLED', label: '启用' },
            { value: 'DISABLED', label: '停用' },
          ]}
        />
      </FilterItem>
    </FilterBar>
    <div className="customer-list-panel">
      <WorkbenchTable<Customer>
        columns={[
          { title: '编码', dataIndex: 'code', key: 'code' },
          { title: '客户名称', dataIndex: 'name', key: 'name' },
          { title: '联系人', dataIndex: 'contactPerson', key: 'contactPerson', render: (value: string | null) => value || '—' },
          { title: '电话', dataIndex: 'contactPhone', key: 'contactPhone', render: (value: string | null) => value || '—' },
          {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            render: (value: Customer['status']) => (
              <span className={`customer-status customer-status-${value.toLowerCase()}`}>
                {value === 'ENABLED' ? '启用' : '停用'}
              </span>
            ),
          },
        ]}
        dataSource={items}
        loading={loading}
        rowKey="id"
        selectedKey={selected?.id}
        onRowClick={choose}
        page={page}
        total={total}
        onPageChange={setPage}
        emptyText={canCreate ? '暂无客户档案。可以新建一个客户。' : '暂无客户档案。请调整筛选条件。'}
      />
    </div>
    <AppDrawer
      open={selected !== null && !editing}
      title={selected?.name ?? '客户详情'}
      onClose={() => setSelected(null)}
    >
      {selected && <>
        <span className={`customer-status customer-status-${selected.status.toLowerCase()}`}>
          {selected.status === 'ENABLED' ? '启用' : '停用'}
        </span>
        <h2>{selected.name}</h2>
        <p>{selected.code}</p>
        <dl className="customer-drawer-dl">
          <dt>联系人</dt><dd>{selected.contactPerson || '—'}</dd>
          <dt>电话</dt><dd>{selected.contactPhone || '—'}</dd>
          <dt>地址</dt><dd>{selected.address || '—'}</dd>
          <dt>税号</dt><dd>{selected.taxNo || '—'}</dd>
          <dt>结算</dt><dd>{settlementLabel(selected.settlementMethod)}</dd>
          <dt>信用额度</dt><dd>{selected.creditLimit ? `${formatAmountText(selected.creditLimit)} ${selected.currency}` : '未设置'}</dd>
        </dl>
        {canWrite && <div className="customer-actions">
          <Button onClick={startEdit} disabled={saving}>编辑</Button>
          <Button onClick={() => void toggle()} disabled={saving}>
            {selected.status === 'ENABLED' ? '停用' : '启用'}
          </Button>
        </div>}
      </>}
    </AppDrawer>
    <AppModal
      open={editing}
      title={selected ? '编辑客户' : '新建客户'}
      onCancel={() => setEditing(false)}
    >
      <form className="customer-form" onSubmit={(event) => void save(event)}>
        {fields.map(([key, label, maxLength]) => (
          <label key={key}>{label}
            <Input
              name={key}
              value={form[key]}
              maxLength={maxLength}
              required={key === 'name' || (key === 'code' && Boolean(selected))}
              onChange={(event) => update(key, event.target.value)}
            />
          </label>
        ))}
        <label>信用额度（可留空，非负，最多 16 位整数/2 位小数）
          <Input
            name="creditLimit"
            value={form.creditLimit}
            inputMode="decimal"
            pattern="^$|^[0-9]{1,16}([.][0-9]{1,2})?$"
            onChange={(event) => update('creditLimit', event.target.value)}
          />
        </label>
        <label>结算方式
          <Select
            style={{ width: '100%' }}
            value={form.settlementMethod}
            onChange={(value) => update('settlementMethod', value)}
            options={[
              { value: 'MONTHLY', label: '月结' },
              { value: 'CASH', label: '现结' },
              { value: 'PREPAID', label: '预付' },
            ]}
          />
        </label>
        <div className="customer-actions">
          <Button type="primary" htmlType="submit" loading={saving}>{saving ? '保存中…' : '保存'}</Button>
          <Button disabled={saving} onClick={() => setEditing(false)}>取消</Button>
        </div>
      </form>
    </AppModal>
  </section>;
}
