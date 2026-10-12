import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as voucherApi from '../api/voucherApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const PAGE_SIZE = 20;
const statusLabels: Record<voucherApi.VoucherStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已过账',
  REVERSED: '已冲销',
  CANCELLED: '已作废',
};

function today() {
  const value = new Date();
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

const emptyForm = (): voucherApi.VoucherForm => ({
  ...voucherApi.emptyVoucherForm(),
  voucherDate: today(),
});

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const monthValue = (period: string) => {
  const match = /^(\d{4})(\d{2})$/.exec(period.trim());
  return match ? `${match[1]}-${match[2]}` : '';
};

export function VoucherWorkbench({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<voucherApi.Voucher[]>([]);
  const [selected, setSelected] = useState<voucherApi.Voucher | null>(null);
  const [periodDraft, setPeriodDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [period, setPeriod] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [postConfirming, setPostConfirming] = useState(false);
  const [form, setForm] = useState<voucherApi.VoucherForm>(emptyForm);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [glAccounts, setGlAccounts] = useState<voucherApi.GlLeafAccount[]>([]);
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const glVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  useEffect(() => {
    const version = glVersion.current.next();
    void voucherApi.listLeafGlAccounts().then((accounts) => {
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
    void voucherApi.searchVouchers(period, status, page, PAGE_SIZE)
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result.items);
        setTotal(result.total);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '凭证列表加载失败'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [period, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
    setPostConfirming(false);
  };

  const choose = (voucher: voucherApi.Voucher) => {
    if (saving) return;
    const version = detailVersion.current.next();
    setSelected(voucher);
    setEditing(false);
    setPostConfirming(false);
    setError('');
    setNotice('');
    void voucherApi.getVoucher(voucher.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '凭证详情加载失败'));
      }
    });
  };

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setPeriod(periodDraft.trim());
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canWrite || saving) return;
    clearSelection();
    setForm(emptyForm());
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
      const validationError = voucherApi.validateVoucherForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await voucherApi.createVoucher(form);
      setSelected(result);
      setEditing(false);
      setNotice('凭证草稿已创建');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '凭证创建失败，请检查借贷是否平衡'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const requestPost = () => {
    if (!canWrite || !selected || selected.status !== 'DRAFT' || saving) return;
    setPostConfirming(true);
    setError('');
    setNotice('');
  };

  const cancelPost = () => {
    setPostConfirming(false);
  };

  const confirmPost = async () => {
    if (!canWrite || !selected || selected.status !== 'DRAFT') return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await voucherApi.postVoucher(selected.docNo);
      setSelected(result);
      setPostConfirming(false);
      setNotice('凭证已过账');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '凭证过账失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const totals = voucherApi.sumVoucherForm(form);
  const canSubmit = canWrite && voucherApi.validateVoucherForm(form) === null;

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">财务 / 总账</p>
        <h1>记账凭证</h1>
        <p>手工编制借贷平衡的记账凭证。草稿保存后过账记入总账；账期由凭证日期决定。错误草稿请另建一张，本页不提供修改或冲销。</p>
      </div>
      {canWrite && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startNew}>新建凭证</button>}
    </header>
    <div className="purchase-order-filters">
      <label>账期（yyyyMM）
        <input type="month" aria-label="账期" disabled={saving} value={monthValue(periodDraft)}
          onChange={(event) => setPeriodDraft(event.target.value.replace('-', ''))} />
      </label>
      <label>账期文本<input aria-label="账期文本" placeholder="yyyyMM" maxLength={6}
        disabled={saving} value={periodDraft}
        onChange={(event) => setPeriodDraft(event.target.value.replace(/\D/g, '').slice(0, 6))}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            applySearch();
          }
        }} /></label>
      <label>状态<select aria-label="凭证状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        <option value="DRAFT">草稿</option>
        <option value="APPROVED">已过账</option>
        <option value="REVERSED">已冲销</option>
        <option value="CANCELLED">已作废</option>
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>凭证号</th><th>凭证日期</th><th>账期</th><th>摘要</th><th>总额</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((voucher) => <tr key={voucher.docNo}
            className={selected?.docNo === voucher.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(voucher)}>{voucher.docNo}</button></td>
            <td>{voucher.voucherDate}</td>
            <td>{voucher.period}</td>
            <td>{voucher.summary || '—'}</td>
            <td>{voucher.totalAmount}</td>
            <td><StatusBadge status={voucher.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载凭证…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无凭证。{canWrite ? '可从右上角新建草稿。' : '请调整筛选条件。'}
        </p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 张</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <VoucherFormPanel form={form} setForm={setForm} saving={saving}
          glAccounts={glAccounts} totals={totals} canSubmit={canSubmit}
          onSave={save} onCancel={clearSelection} />
          : selected ? <VoucherDetails voucher={selected} canWrite={canWrite} saving={saving}
            postConfirming={postConfirming} onRequestPost={requestPost}
            onConfirmPost={() => void confirmPost()} onCancelPost={cancelPost} />
            : <p className="memory-empty">选择左侧凭证查看详情{canWrite ? '，或新建草稿。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: voucherApi.VoucherStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status.toLowerCase()}`}>
    {statusLabels[status] ?? status}</span>;
}

interface FormProps {
  form: voucherApi.VoucherForm;
  setForm: (form: voucherApi.VoucherForm) => void;
  saving: boolean;
  glAccounts: voucherApi.GlLeafAccount[];
  totals: ReturnType<typeof voucherApi.sumVoucherForm>;
  canSubmit: boolean;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function VoucherFormPanel({ form, setForm, saving, glAccounts, totals, canSubmit, onSave, onCancel }: FormProps) {
  const update = <K extends keyof voucherApi.VoucherForm>(
    key: K,
    value: voucherApi.VoucherForm[K],
  ) => setForm({ ...form, [key]: value });
  const updateLine = (index: number, patch: Partial<voucherApi.VoucherLineForm>) => {
    const lines = form.lines.map((line, current) => current === index ? { ...line, ...patch } : line);
    setForm({ ...form, lines });
  };
  const addLine = () => setForm({ ...form, lines: [...form.lines, voucherApi.emptyVoucherLine()] });
  const removeLine = (index: number) => {
    if (form.lines.length <= 2) return;
    setForm({ ...form, lines: form.lines.filter((_, current) => current !== index) });
  };

  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建凭证草稿</h2>
    <p>凭证字固定为「记」。账期由凭证日期自动推算，无需填写。</p>
    <label>凭证日期<input type="date" required disabled={saving} value={form.voucherDate}
      onChange={(event) => update('voucherDate', event.target.value)} /></label>
    <label>摘要<input maxLength={200} disabled={saving} value={form.summary}
      onChange={(event) => update('summary', event.target.value)} /></label>
    <fieldset disabled={saving}><legend>分录（至少两行，恰好一方借贷大于 0）</legend>
      <div className="memory-table-wrap purchase-order-lines"><table className="memory-table"><thead><tr>
        <th>科目</th><th>借方</th><th>贷方</th><th>行摘要</th><th></th>
      </tr></thead><tbody>
        {form.lines.map((line, index) => <tr key={index}>
          <td>
            <select aria-label={`第 ${index + 1} 行科目`} required value={line.accountCode}
              onChange={(event) => updateLine(index, { accountCode: event.target.value })}>
              <option value="">请选择末级启用科目</option>
              {glAccounts.map((account) => <option key={account.code} value={account.code}>
                {account.code} · {account.name}
              </option>)}
            </select>
          </td>
          <td><input aria-label={`第 ${index + 1} 行借方`} inputMode="decimal"
            value={line.debit} placeholder="0.00"
            onChange={(event) => updateLine(index, { debit: event.target.value })} /></td>
          <td><input aria-label={`第 ${index + 1} 行贷方`} inputMode="decimal"
            value={line.credit} placeholder="0.00"
            onChange={(event) => updateLine(index, { credit: event.target.value })} /></td>
          <td><input aria-label={`第 ${index + 1} 行摘要`} value={line.summary}
            onChange={(event) => updateLine(index, { summary: event.target.value })} /></td>
          <td><button type="button" className="memory-button" disabled={form.lines.length <= 2}
            onClick={() => removeLine(index)}>移除</button></td>
        </tr>)}
      </tbody></table></div>
      <button type="button" className="memory-button" onClick={addLine}>添加行</button>
    </fieldset>
    {glAccounts.length === 0 && <p className="memory-empty">暂无可用末级启用科目，无法编制凭证。</p>}
    <p role="status">Σ借 {totals.totalDebit}　Σ贷 {totals.totalCredit}　差额 {totals.difference}
      {totals.balanced ? '　已平衡' : '　未平衡，不可提交'}</p>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary"
        disabled={saving || !canSubmit}>{saving ? '保存中…' : '创建草稿'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface DetailsProps {
  voucher: voucherApi.Voucher;
  canWrite: boolean;
  saving: boolean;
  postConfirming: boolean;
  onRequestPost: () => void;
  onConfirmPost: () => void;
  onCancelPost: () => void;
}

function VoucherDetails({ voucher, canWrite, saving, postConfirming,
  onRequestPost, onConfirmPost, onCancelPost }: DetailsProps) {
  return <>
    <StatusBadge status={voucher.status} />
    <h2>{voucher.docNo}</h2>
    <dl>
      <dt>凭证字</dt><dd>{voucher.word || '记'}</dd>
      <dt>凭证日期</dt><dd>{voucher.voucherDate}</dd>
      <dt>账期</dt><dd>{voucher.period}</dd>
      <dt>摘要</dt><dd>{voucher.summary || '—'}</dd>
      <dt>总额</dt><dd>{voucher.totalAmount}</dd>
      <dt>来源单</dt><dd>{voucher.sourceDocNo || '—'}</dd>
      <dt>来源类型</dt><dd>{voucher.sourceDocType || '—'}</dd>
    </dl>
    <div className="memory-table-wrap purchase-order-lines"><table className="memory-table"><thead><tr>
      <th>行号</th><th>科目</th><th>借方</th><th>贷方</th><th>行摘要</th>
    </tr></thead><tbody>{voucher.lines.map((line) => <tr key={line.lineNo}>
      <td>{line.lineNo}</td>
      <td>{line.accountCode}</td>
      <td>{line.debit}</td>
      <td>{line.credit}</td>
      <td>{line.summary || '—'}</td>
    </tr>)}</tbody></table></div>
    {voucher.status === 'DRAFT' && canWrite && (
      postConfirming ? (
        <div className="purchase-receipt-post-confirm" role="alertdialog"
          aria-labelledby="voucher-post-confirm-title"
          aria-describedby="voucher-post-confirm-copy">
          <strong id="voucher-post-confirm-title">确认过账</strong>
          <p id="voucher-post-confirm-copy">
            过账将把草稿凭证记入总账（DRAFT→APPROVED）。账期已关将返回 409。此操作不可直接撤销（冲销不在本页），确认继续过账吗？
          </p>
          <div className="customer-actions">
            <button type="button" className="memory-button memory-button-primary" disabled={saving}
              onClick={onConfirmPost}>{saving ? '处理中…' : '确认过账'}</button>
            <button type="button" className="memory-button" disabled={saving}
              onClick={onCancelPost}>取消</button>
          </div>
        </div>
      ) : (
        <>
          <p className="sales-invoice-impact">
            过账将把草稿记入总账。账期已关会被拒绝。完成后不能在本页修改或冲销。
          </p>
          <div className="customer-actions">
            <button type="button" className="memory-button memory-button-primary" disabled={saving}
              onClick={onRequestPost}>{saving ? '处理中…' : '过账'}</button>
          </div>
        </>
      )
    )}
  </>;
}
