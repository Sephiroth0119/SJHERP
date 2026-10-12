import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as fundAccountApi from '../api/fundAccountApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const typeLabels: Record<fundAccountApi.FundAccountType, string> = {
  CASH: '库存现金',
  BANK: '银行存款',
  OTHER: '其他货币资金',
};
const statusLabels: Record<fundAccountApi.FundAccountStatus, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

export function FundAccountWorkbench({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<fundAccountApi.FundAccount[]>([]);
  const [selected, setSelected] = useState<fundAccountApi.FundAccount | null>(null);
  const [keywordDraft, setKeywordDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<fundAccountApi.FundAccountForm>(fundAccountApi.emptyFundAccountForm());
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [glAccounts, setGlAccounts] = useState<fundAccountApi.GlLeafAccount[]>([]);
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const glVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  useEffect(() => {
    const version = glVersion.current.next();
    void fundAccountApi.listLeafGlAccounts().then((accounts) => {
      if (!glVersion.current.isCurrent(version)) return;
      setGlAccounts(accounts.filter((account) => account.enabled && account.leaf));
    }).catch((cause: unknown) => {
      if (glVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '末级科目加载失败'));
      }
    });
  }, []);

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void fundAccountApi.searchFundAccounts(keyword, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '资金账户列表加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [keyword, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (account: fundAccountApi.FundAccount) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(account);
    setEditing(false);
    setError('');
    setNotice('');
    void fundAccountApi.getFundAccount(account.id).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '资金账户详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setKeyword(keywordDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canWrite || saving) return;
    clearSelection();
    setForm(fundAccountApi.emptyFundAccountForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const startEdit = () => {
    if (!canWrite || !selected || saving) return;
    invalidateDetail();
    setForm(fundAccountApi.toFundAccountForm(selected));
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canWrite || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = fundAccountApi.validateFundAccountForm(form, selected !== null);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = selected
        ? await fundAccountApi.updateFundAccount(selected.id, form)
        : await fundAccountApi.createFundAccount(form);
      setSelected(result);
      setEditing(false);
      setNotice(selected ? '资金账户已更新' : '资金账户已创建');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '资金账户保存失败，请检查名称、类别和末级科目'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const toggle = async () => {
    if (!canWrite || !selected || mutationInFlight.current.isLocked()) return;
    const disabling = selected.status === 'ENABLED';
    if (disabling && !window.confirm('停用后新的收付款单不得引用该账户，历史单据不受影响。确认停用吗？')) {
      return;
    }
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = disabling
        ? await fundAccountApi.disableFundAccount(selected.id)
        : await fundAccountApi.enableFundAccount(selected.id);
      setSelected(result);
      setNotice(result.status === 'ENABLED' ? '资金账户已启用' : '资金账户已停用');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, disabling ? '资金账户停用失败' : '资金账户启用失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">财务 / 资金档案</p>
        <h1>资金账户</h1>
        <p>维护现金、银行和其他货币资金账户，并映射到启用中的末级总账科目。</p>
      </div>
      {canWrite && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startNew}>新建资金账户</button>}
    </header>
    <div className="purchase-order-filters">
      <label>关键词<input aria-label="资金账户关键词" placeholder="编码、名称或开户行"
        disabled={saving} value={keywordDraft}
        onChange={(event) => setKeywordDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            applySearch();
          }
        }} /></label>
      <label>状态<select aria-label="资金账户状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="ENABLED">启用</option>
        <option value="DISABLED">停用</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>编码</th><th>名称</th><th>类别</th><th>GL 科目</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((account) => <tr key={account.id}
            className={selected?.id === account.id ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(account)}>{account.code}</button></td>
            <td>{account.name}</td>
            <td>{account.accountTypeLabel || typeLabels[account.accountType]}</td>
            <td>{account.glAccountCode}</td>
            <td><StatusBadge status={account.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载资金账户…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无资金账户。{canWrite ? '可从右上角新建。' : '请调整筛选条件。'}
        </p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <FundAccountForm form={form} setForm={setForm} saving={saving}
          updating={selected !== null} glAccounts={glAccounts} onSave={save}
          onCancel={clearSelection} />
          : selected ? <FundAccountDetails account={selected} canWrite={canWrite}
            saving={saving} onEdit={startEdit} onToggle={() => void toggle()} />
            : <p className="memory-empty">选择左侧资金账户查看详情{canWrite ? '，或新建账户。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: fundAccountApi.FundAccountStatus }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface FormProps {
  form: fundAccountApi.FundAccountForm;
  setForm: (form: fundAccountApi.FundAccountForm) => void;
  saving: boolean;
  updating: boolean;
  glAccounts: fundAccountApi.GlLeafAccount[];
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function FundAccountForm({ form, setForm, saving, updating, glAccounts, onSave, onCancel }: FormProps) {
  const update = <K extends keyof fundAccountApi.FundAccountForm>(
    key: K,
    value: fundAccountApi.FundAccountForm[K],
  ) => setForm({ ...form, [key]: value });
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>{updating ? '编辑资金账户' : '新建资金账户'}</h2>
    <label>编码{updating ? '（必填）' : '（可留空自动编号 FA-）'}
      <input maxLength={50} disabled={saving} value={form.code} required={updating}
        onChange={(event) => update('code', event.target.value)} /></label>
    <label>名称<input required maxLength={200} disabled={saving} value={form.name}
      onChange={(event) => update('name', event.target.value)} /></label>
    <label>账户类别<select required disabled={saving} value={form.accountType} aria-label="账户类别"
      onChange={(event) => update('accountType', event.target.value as fundAccountApi.FundAccountType)}>
      <option value="CASH">库存现金（CASH）</option>
      <option value="BANK">银行存款（BANK）</option>
      <option value="OTHER">其他货币资金（OTHER）</option>
    </select></label>
    <label>映射 GL 科目（GET /api/gl/accounts?leafOnly=true，须启用末级）
      <select required disabled={saving} value={form.glAccountCode} aria-label="映射 GL 科目"
        onChange={(event) => update('glAccountCode', event.target.value)}>
        <option value="">请选择末级启用科目</option>
        {glAccounts.map((account) => <option key={account.code} value={account.code}>
          {account.code} · {account.name}（{account.typeLabel}）
        </option>)}
      </select>
    </label>
    {glAccounts.length === 0 && <p className="memory-empty">暂无可用末级启用科目，无法保存新账户。</p>}
    <label>开户行<input maxLength={200} disabled={saving} value={form.bankName}
      onChange={(event) => update('bankName', event.target.value)} /></label>
    <label>银行账号<input maxLength={64} disabled={saving} value={form.accountNo}
      onChange={(event) => update('accountNo', event.target.value)} /></label>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '保存中…' : updating ? '保存' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  account: fundAccountApi.FundAccount;
  canWrite: boolean;
  saving: boolean;
  onEdit: () => void;
  onToggle: () => void;
}

function FundAccountDetails({ account, canWrite, saving, onEdit, onToggle }: DetailsProps) {
  return <>
    <StatusBadge status={account.status} />
    <h2>{account.name}</h2>
    <p>{account.code}</p>
    <dl>
      <dt>账户类别</dt><dd>{account.accountTypeLabel || typeLabels[account.accountType]}（{account.accountType}）</dd>
      <dt>GL 科目</dt><dd>{account.glAccountCode}</dd>
      <dt>开户行</dt><dd>{account.bankName || '—'}</dd>
      <dt>银行账号</dt><dd>{account.accountNo || '—'}</dd>
      <dt>创建</dt><dd>{account.createdBy || '—'} · {account.createdAt || '—'}</dd>
      <dt>更新</dt><dd>{account.updatedBy || '—'} · {account.updatedAt || '—'}</dd>
    </dl>
    {canWrite && <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving} onClick={onEdit}>编辑</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onToggle}>
        {saving ? '处理中…' : account.status === 'ENABLED' ? '停用' : '启用'}
      </button>
    </div>}
  </>;
}
