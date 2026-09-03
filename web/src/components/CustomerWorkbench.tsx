import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http';
import {
  createCustomer, getCustomer, searchCustomers, setCustomerStatus, updateCustomer,
  type Customer, type CustomerForm,
} from '../api/customerApi';

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

export function CustomerWorkbench({ permissions }: { permissions: string[] }) {
  const canCreate = permissions.includes('partner:create_customer');
  const canWrite = permissions.includes('partner:write');
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
  const [notice, setNotice] = useState('');
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
      setError(cause instanceof ApiError ? cause.message : '客户列表加载失败，请重试');
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
      if (requestId === detailRequest.current) setError(cause instanceof ApiError ? cause.message : '客户详情加载失败');
    });
  };
  const startNew = () => { setSelected(null); setForm(blank); setEditing(true); setNotice(''); setError(''); };
  const startEdit = () => { if (selected) { setForm(toForm(selected)); setEditing(true); setNotice(''); setError(''); } };
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSaving(true); setError(''); setNotice('');
    try {
      const result = selected ? await updateCustomer(selected.id, form) : await createCustomer(form);
      setSelected(result); setEditing(false); setNotice('客户档案已保存'); setRefreshKey((key) => key + 1);
    } catch (cause) { setError(cause instanceof ApiError ? cause.message : '保存失败，请检查输入后重试'); }
    finally { setSaving(false); }
  };
  const toggle = async () => {
    if (!selected || saving) return;
    setSaving(true); setError('');
    try { const result = await setCustomerStatus(selected.id, selected.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'); setSelected(result); setNotice(result.status === 'ENABLED' ? '客户已启用' : '客户已停用'); setRefreshKey((key) => key + 1); }
    catch (cause) { setError(cause instanceof ApiError ? cause.message : '状态更新失败'); }
    finally { setSaving(false); }
  };
  const update = (key: keyof CustomerForm, value: string) => setForm((current) => ({ ...current, [key]: value }));

  return <section className="customer-page">
    <header className="customer-header"><div><p className="page-kicker">销售 / 基础档案</p><h1>客户档案</h1><p>查找、维护销售往来的客户资料。</p></div>{canCreate && <button type="button" className="memory-button memory-button-primary" onClick={startNew}>新建客户</button>}</header>
    {error && <div className="memory-error" role="alert" tabIndex={-1}>{error}<button type="button" onClick={() => setRefreshKey((key) => key + 1)}>重试</button></div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-toolbar"><input aria-label="搜索客户" placeholder="编码、名称、联系人或电话" value={draftKeyword} onChange={(event) => setDraftKeyword(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') applySearch(); }} /><select aria-label="客户状态" value={draftStatus} onChange={(event) => setDraftStatus(event.target.value)}><option value="">全部状态</option><option value="ENABLED">启用</option><option value="DISABLED">停用</option></select><button type="button" className="memory-button" onClick={applySearch}>查询</button></div>
    <div className="customer-layout"><div className="customer-list-panel">{loading ? <p className="memory-empty">正在加载客户档案…</p> : items.length === 0 ? <p className="memory-empty">暂无客户档案。{canCreate ? '可以新建一个客户。' : '请调整筛选条件。'}</p> : <div className="memory-table-wrap"><table className="memory-table"><thead><tr><th>编码</th><th>客户名称</th><th>联系人</th><th>电话</th><th>状态</th></tr></thead><tbody>{items.map((customer) => <tr key={customer.id} tabIndex={0} aria-selected={selected?.id === customer.id} className={selected?.id === customer.id ? 'memory-row-selected' : ''} onClick={() => choose(customer)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(customer); } }}><td>{customer.code}</td><td>{customer.name}</td><td>{customer.contactPerson || '—'}</td><td>{customer.contactPhone || '—'}</td><td><span className={`customer-status customer-status-${customer.status.toLowerCase()}`}>{customer.status === 'ENABLED' ? '启用' : '停用'}</span></td></tr>)}</tbody></table></div>}{total > 0 && <div className="memory-pagination"><span>共 {total} 条 · 第 {page} 页</span><span><button type="button" disabled={page <= 1 || loading} onClick={() => setPage((current) => current - 1)}>上一页</button><button type="button" disabled={page * 20 >= total || loading} onClick={() => setPage((current) => current + 1)}>下一页</button></span></div>}</div>
      <aside className="customer-detail">{editing ? <><h2>{selected ? '编辑客户' : '新建客户'}</h2><form className="customer-form" onSubmit={save}>{fields.map(([key, label, maxLength]) => <label key={key}>{label}<input name={key} value={form[key]} maxLength={maxLength} required={key === 'name' || (key === 'code' && Boolean(selected))} onChange={(event) => update(key, event.target.value)} /></label>)}<label>信用额度（可留空，非负，最多 16 位整数/2 位小数）<input name="creditLimit" value={form.creditLimit} inputMode="decimal" pattern="^$|^[0-9]{1,16}([.][0-9]{1,2})?$" onChange={(event) => update('creditLimit', event.target.value)} /></label><label>结算方式<select name="settlementMethod" value={form.settlementMethod} onChange={(event) => update('settlementMethod', event.target.value)}><option value="MONTHLY">月结</option><option value="CASH">现结</option><option value="PREPAID">预付</option></select></label><div className="customer-actions"><button type="submit" disabled={saving} className="memory-button memory-button-primary">{saving ? '保存中…' : '保存'}</button><button type="button" disabled={saving} className="memory-button" onClick={() => setEditing(false)}>取消</button></div></form></> : selected ? <><span className={`customer-status customer-status-${selected.status.toLowerCase()}`}>{selected.status === 'ENABLED' ? '启用' : '停用'}</span><h2>{selected.name}</h2><p>{selected.code}</p><dl><dt>联系人</dt><dd>{selected.contactPerson || '—'}</dd><dt>电话</dt><dd>{selected.contactPhone || '—'}</dd><dt>地址</dt><dd>{selected.address || '—'}</dd><dt>税号</dt><dd>{selected.taxNo || '—'}</dd><dt>结算</dt><dd>{selected.settlementMethod}</dd><dt>信用额度</dt><dd>{selected.creditLimit || '未设置'} {selected.currency}</dd></dl>{canWrite && <div className="customer-actions"><button type="button" disabled={saving} className="memory-button" onClick={startEdit}>编辑</button><button type="button" disabled={saving} className="memory-button" onClick={() => void toggle()}>{selected.status === 'ENABLED' ? '停用' : '启用'}</button></div>}</> : <p className="memory-empty">选择左侧客户查看详情。</p>}</aside></div>
  </section>;
}
