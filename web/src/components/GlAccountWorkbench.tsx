import { useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as glAccountApi from '../api/glAccountApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const typeLabels: Record<glAccountApi.AccountType, string> = {
  ASSET: '资产',
  LIABILITY: '负债',
  EQUITY: '所有者权益',
  COST: '成本',
  PROFIT_LOSS: '损益',
};
const dirLabels: Record<glAccountApi.BalanceDir, string> = {
  DEBIT: '借',
  CREDIT: '贷',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

type ViewMode = 'tree' | 'list';

export function GlAccountWorkbench({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<glAccountApi.GlAccount[]>([]);
  const [selected, setSelected] = useState<glAccountApi.GlAccount | null>(null);
  const [typeDraft, setTypeDraft] = useState('');
  const [keywordDraft, setKeywordDraft] = useState('');
  const [enabledDraft, setEnabledDraft] = useState('');
  const [type, setType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [enabledFilter, setEnabledFilter] = useState('');
  const [viewMode, setViewMode] = useState<ViewMode>('tree');
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<glAccountApi.GlAccountForm>(glAccountApi.emptyAccountForm());
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void glAccountApi.listAccounts(type, false)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '科目列表加载失败。查看与维护科目需要 finance:account 权限。'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [type, refreshKey]);

  const visible = useMemo(() => {
    const needle = keyword.trim();
    return items.filter((account) => {
      if (enabledFilter === 'ENABLED' && !account.enabled) return false;
      if (enabledFilter === 'DISABLED' && account.enabled) return false;
      if (!needle) return true;
      return account.code.includes(needle) || account.name.includes(needle);
    });
  }, [enabledFilter, items, keyword]);

  const rows = useMemo(() => {
    if (viewMode === 'list') {
      return [...visible].sort((left, right) => left.code.localeCompare(right.code));
    }
    return glAccountApi.flattenAccountForest(glAccountApi.buildAccountForest(visible));
  }, [viewMode, visible]);

  const parentOptions = items.filter((account) => !account.leaf);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (account: glAccountApi.GlAccount) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(account);
    setEditing(false);
    setError('');
    setNotice('');
    void glAccountApi.getAccount(account.code).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '科目详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setType(typeDraft);
    setKeyword(keywordDraft);
    setEnabledFilter(enabledDraft);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canWrite || saving) return;
    clearSelection();
    setForm(glAccountApi.emptyAccountForm());
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
      const validationError = glAccountApi.validateAccountForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await glAccountApi.createAccount(form);
      setSelected(result);
      setEditing(false);
      setNotice('科目已创建');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '科目创建失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const toggle = async () => {
    if (!canWrite || !selected || saving) return;
    if (selected.preset && selected.enabled) {
      setError('预置科目不可停用');
      return;
    }
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    const disabling = selected.enabled;
    try {
      const result = disabling
        ? await glAccountApi.disableAccount(selected.code)
        : await glAccountApi.enableAccount(selected.code);
      setSelected(result);
      setNotice(disabling ? '科目已停用' : '科目已启用');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, disabling ? '科目停用失败' : '科目启用失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">财务 / 总账</p>
        <h1>科目档案</h1>
        <p>维护会计科目树。本页只对接 GET/POST /api/gl/accounts 与启停，不提供编辑（后端无 PUT）。预置科目不可停用。写操作需要 finance:account 权限。</p>
      </div>
      {canWrite && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startNew}>新建科目</button>}
    </header>
    <div className="purchase-order-filters">
      <label>关键词<input aria-label="科目关键词" placeholder="编码或名称"
        disabled={saving} value={keywordDraft}
        onChange={(event) => setKeywordDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            applySearch();
          }
        }} /></label>
      <label>类别<select aria-label="科目类别" disabled={saving} value={typeDraft}
        onChange={(event) => setTypeDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="ASSET">资产</option>
        <option value="LIABILITY">负债</option>
        <option value="EQUITY">所有者权益</option>
        <option value="COST">成本</option>
        <option value="PROFIT_LOSS">损益</option>
      </select></label>
      <label>状态<select aria-label="科目状态" disabled={saving} value={enabledDraft}
        onChange={(event) => setEnabledDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="ENABLED">启用</option>
        <option value="DISABLED">停用</option>
      </select></label>
      <label>视图<select aria-label="科目视图" disabled={saving} value={viewMode}
        onChange={(event) => setViewMode(event.target.value === 'list' ? 'list' : 'tree')}>
        <option value="tree">树形</option>
        <option value="list">列表</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>编码</th><th>名称</th><th>类别</th><th>方向</th><th>末级</th><th>状态</th>
        </tr></thead><tbody>
          {rows.map((account) => <tr key={account.code}
            className={selected?.code === account.code ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              style={viewMode === 'tree' ? { paddingLeft: `${Math.max(account.level - 1, 0) * 16}px` } : undefined}
              onClick={() => choose(account)}>{account.code}</button></td>
            <td>{account.name}</td>
            <td>{account.typeLabel || typeLabels[account.type]}</td>
            <td>{account.balanceDirLabel || dirLabels[account.balanceDir]}</td>
            <td>{account.leaf ? '是' : '否'}</td>
            <td><StatusBadge enabled={account.enabled} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载科目…</p>}
        {!loading && rows.length === 0 && <p className="memory-empty">
          暂无科目。{canWrite ? '可从右上角新建。' : '请调整筛选条件。'}
        </p>}
        <p className="memory-empty" role="status">共 {rows.length} 个科目</p>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <AccountForm form={form} setForm={setForm} saving={saving}
          parentOptions={parentOptions} onSave={save} onCancel={clearSelection} />
          : selected ? <AccountDetails account={selected} canWrite={canWrite}
            saving={saving} onToggle={() => void toggle()} />
            : <p className="memory-empty">选择左侧科目查看详情{canWrite ? '，或新建科目。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ enabled }: { enabled: boolean }) {
  return <span className={`customer-status customer-status-${enabled ? 'enabled' : 'disabled'}`}>
    {enabled ? '启用' : '停用'}</span>;
}

interface FormProps {
  form: glAccountApi.GlAccountForm;
  setForm: (form: glAccountApi.GlAccountForm) => void;
  saving: boolean;
  parentOptions: glAccountApi.GlAccount[];
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function AccountForm({ form, setForm, saving, parentOptions, onSave, onCancel }: FormProps) {
  const update = <K extends keyof glAccountApi.GlAccountForm>(
    key: K,
    value: glAccountApi.GlAccountForm[K],
  ) => setForm({ ...form, [key]: value });
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建科目</h2>
    <p>编码须以上级编码为前缀。上级须为非末级。本页不提供事后编辑。</p>
    <label>编码<input required maxLength={32} disabled={saving} value={form.code}
      onChange={(event) => update('code', event.target.value)} /></label>
    <label>名称<input required maxLength={100} disabled={saving} value={form.name}
      onChange={(event) => update('name', event.target.value)} /></label>
    <label>类别<select required disabled={saving} value={form.type} aria-label="科目类别"
      onChange={(event) => update('type', event.target.value as glAccountApi.AccountType)}>
      <option value="ASSET">资产（ASSET）</option>
      <option value="LIABILITY">负债（LIABILITY）</option>
      <option value="EQUITY">所有者权益（EQUITY）</option>
      <option value="COST">成本（COST）</option>
      <option value="PROFIT_LOSS">损益（PROFIT_LOSS）</option>
    </select></label>
    <label>余额方向<select required disabled={saving} value={form.balanceDir} aria-label="余额方向"
      onChange={(event) => update('balanceDir', event.target.value as glAccountApi.BalanceDir)}>
      <option value="DEBIT">借（DEBIT）</option>
      <option value="CREDIT">贷（CREDIT）</option>
    </select></label>
    <label>上级科目<select disabled={saving} value={form.parentCode} aria-label="上级科目"
      onChange={(event) => update('parentCode', event.target.value)}>
      <option value="">一级科目（无上级）</option>
      {parentOptions.map((account) => <option key={account.code} value={account.code}>
        {account.code} · {account.name}
      </option>)}
    </select></label>
    <label>是否末级<select disabled={saving} value={form.isLeaf ? 'true' : 'false'} aria-label="是否末级"
      onChange={(event) => update('isLeaf', event.target.value === 'true')}>
      <option value="true">是（可挂凭证行）</option>
      <option value="false">否（仅作上级）</option>
    </select></label>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '保存中…' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  account: glAccountApi.GlAccount;
  canWrite: boolean;
  saving: boolean;
  onToggle: () => void;
}

function AccountDetails({ account, canWrite, saving, onToggle }: DetailsProps) {
  const canDisable = canWrite && account.enabled && !account.preset;
  const canEnable = canWrite && !account.enabled;
  return <>
    <StatusBadge enabled={account.enabled} />
    <h2>{account.name}</h2>
    <p>{account.code}</p>
    <dl>
      <dt>类别</dt><dd>{account.typeLabel || typeLabels[account.type]}（{account.type}）</dd>
      <dt>余额方向</dt><dd>{account.balanceDirLabel || dirLabels[account.balanceDir]}（{account.balanceDir}）</dd>
      <dt>上级</dt><dd>{account.parentCode || '—'}</dd>
      <dt>层级</dt><dd>{account.level}</dd>
      <dt>末级</dt><dd>{account.leaf ? '是' : '否'}</dd>
      <dt>预置</dt><dd>{account.preset ? '是（预置科目不可停用）' : '否'}</dd>
    </dl>
    {account.preset && account.enabled && <p className="memory-empty">预置科目不可停用。</p>}
    {(canDisable || canEnable) && <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving} onClick={onToggle}>
        {saving ? '处理中…' : account.enabled ? '停用' : '启用'}
      </button>
    </div>}
  </>;
}
