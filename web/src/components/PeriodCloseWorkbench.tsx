import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';
import * as glPeriodApi from '../api/glPeriodApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';

const statusLabels: Record<glPeriodApi.PeriodStatus, string> = {
  OPEN: '开启',
  CLOSED: '已关账',
};

const errorMessage = (cause: unknown, fallback: string) =>
  cause instanceof ApiError ? cause.message : fallback;

const amountText = (value: string) => (value === '' ? '—' : value);

const monthValue = (period: string) => {
  const match = /^(\d{4})(\d{2})$/.exec(period.trim());
  return match ? `${match[1]}-${match[2]}` : '';
};

export function PeriodCloseWorkbench({
  canClose,
  canReopen,
}: {
  canClose: boolean;
  canReopen: boolean;
}) {
  const [items, setItems] = useState<glPeriodApi.AccountingPeriod[]>([]);
  const [selected, setSelected] = useState<glPeriodApi.AccountingPeriod | null>(null);
  const [readiness, setReadiness] = useState<glPeriodApi.PeriodCloseReadiness | null>(null);
  const [closeResult, setCloseResult] = useState<glPeriodApi.PeriodCloseResult | null>(null);
  const [openDraft, setOpenDraft] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [closeConfirming, setCloseConfirming] = useState(false);
  const [reopenConfirming, setReopenConfirming] = useState(false);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState('');
  const [closeReasons, setCloseReasons] = useState<string[]>([]);
  const [notice, setNotice] = useState('');
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const precheckVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  useEffect(() => {
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void glPeriodApi.listPeriods()
      .then((result) => {
        if (!listVersion.current.isCurrent(version)) return;
        setItems(result);
      })
      .catch((cause: unknown) => {
        if (listVersion.current.isCurrent(version)) {
          setError(errorMessage(cause, '账期列表加载失败。关账需要 finance:period 权限。'));
        }
      })
      .finally(() => {
        if (listVersion.current.isCurrent(version)) setLoading(false);
      });
  }, [refreshKey]);

  const invalidateDetail = () => {
    detailVersion.current.next();
    precheckVersion.current.next();
  };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setReadiness(null);
    setCloseResult(null);
    setCloseConfirming(false);
    setReopenConfirming(false);
    setOpening(false);
    setCloseReasons([]);
  };

  const choose = (period: glPeriodApi.AccountingPeriod) => {
    if (saving) return;
    const version = detailVersion.current.next();
    precheckVersion.current.next();
    setSelected(period);
    setReadiness(null);
    setCloseResult(null);
    setCloseConfirming(false);
    setReopenConfirming(false);
    setOpening(false);
    setError('');
    setCloseReasons([]);
    setNotice('');
    void glPeriodApi.getPeriod(period.period).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '账期详情加载失败'));
      }
    });
  };

  const runPrecheck = async () => {
    if (!canClose || !selected || saving) return;
    const version = precheckVersion.current.next();
    setChecking(true);
    setError('');
    setCloseReasons([]);
    setNotice('');
    setCloseResult(null);
    try {
      const result = await glPeriodApi.precheckPeriodClose(selected.period);
      if (!precheckVersion.current.isCurrent(version)) return;
      setReadiness(result);
      setNotice(result.closeable ? '预检通过，可以关账。' : '预检不可关账，请先处理 ERROR。');
    } catch (cause: unknown) {
      if (!precheckVersion.current.isCurrent(version)) return;
      setError(errorMessage(cause, '关账预检失败'));
      setCloseReasons(cause instanceof ApiError ? cause.reasons : []);
    } finally {
      if (precheckVersion.current.isCurrent(version)) setChecking(false);
    }
  };

  const requestClose = () => {
    if (!canClose || !selected || selected.status !== 'OPEN' || saving) return;
    setCloseConfirming(true);
    setReopenConfirming(false);
    setError('');
    setCloseReasons([]);
    setNotice('');
  };

  const confirmClose = async () => {
    if (!canClose || !selected || selected.status !== 'OPEN') return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setCloseReasons([]);
    setNotice('');
    try {
      const result = await glPeriodApi.closePeriod(selected.period);
      setCloseResult(result);
      setCloseConfirming(false);
      setNotice(result.closingVoucherDocNo
        ? `账期已关账，结转凭证 ${result.closingVoucherDocNo}`
        : '账期已关账（本期无损益发生额，未生成结转凭证）');
      setRefreshKey((value) => value + 1);
      const detail = await glPeriodApi.getPeriod(selected.period);
      setSelected(detail);
      setReadiness(null);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '关账失败'));
      setCloseReasons(cause instanceof ApiError ? cause.reasons : []);
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const requestReopen = () => {
    if (!canReopen || !selected || selected.status !== 'CLOSED' || saving) return;
    setReopenConfirming(true);
    setCloseConfirming(false);
    setError('');
    setCloseReasons([]);
    setNotice('');
  };

  const confirmReopen = async () => {
    if (!canReopen || !selected || selected.status !== 'CLOSED') return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setCloseReasons([]);
    setNotice('');
    try {
      const result = await glPeriodApi.reopenPeriod(selected.period);
      setSelected(result);
      setReopenConfirming(false);
      setCloseResult(null);
      setReadiness(null);
      setNotice('账期已重开。重开不会自动冲销结转凭证。');
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '重开账期失败'));
      setCloseReasons(cause instanceof ApiError ? cause.reasons : []);
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const startOpen = () => {
    if (!canClose || saving) return;
    clearSelection();
    setOpening(true);
    setOpenDraft('');
    setError('');
    setNotice('');
  };

  const saveOpen = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canClose || !mutationInFlight.current.tryAcquire()) return;
    setError('');
    setNotice('');
    try {
      const validationError = glPeriodApi.validatePeriodKey(openDraft);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await glPeriodApi.openPeriod(openDraft);
      setSelected(result);
      setOpening(false);
      setNotice(`账期 ${result.period} 已开启`);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '开启账期失败'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">财务 / 总账</p>
        <h1>账期关账</h1>
        <p>先跑 close-precheck，再二次确认关账。关账会做一致性闸门、损益结转并过账、试算断言后把账期改为 CLOSED。写操作需要 finance:period；重开需要 finance:period_reopen。</p>
      </div>
      {canClose && <button type="button" className="memory-button memory-button-primary" disabled={saving}
        onClick={startOpen}>开启账期</button>}
    </header>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {closeReasons.length > 0 && <ul className="memory-error" role="alert" aria-label="关账失败 reasons">
      {closeReasons.map((reason) => <li key={reason}>{reason}</li>)}
    </ul>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>账期</th><th>状态</th><th>关账人</th><th>关账时间</th>
        </tr></thead><tbody>
          {items.map((item) => <tr key={item.period}
            className={selected?.period === item.period ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(item)}>{item.period}</button></td>
            <td><StatusBadge status={item.status} /></td>
            <td>{item.closedBy || '—'}</td>
            <td>{item.closedAt || '—'}</td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载账期…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">
          暂无账期。{canClose ? '可从右上角开启。' : '需要 finance:period 权限。'}
        </p>}
      </section>
      <aside className="customer-detail purchase-order-detail">
        {opening ? <OpenPeriodForm draft={openDraft} setDraft={setOpenDraft} saving={saving}
          onSave={saveOpen} onCancel={clearSelection} />
          : selected ? <PeriodDetails
            period={selected}
            readiness={readiness}
            closeResult={closeResult}
            canClose={canClose}
            canReopen={canReopen}
            saving={saving}
            checking={checking}
            closeConfirming={closeConfirming}
            reopenConfirming={reopenConfirming}
            onPrecheck={() => void runPrecheck()}
            onRequestClose={requestClose}
            onConfirmClose={() => void confirmClose()}
            onCancelClose={() => setCloseConfirming(false)}
            onRequestReopen={requestReopen}
            onConfirmReopen={() => void confirmReopen()}
            onCancelReopen={() => setReopenConfirming(false)}
          />
            : <p className="memory-empty">选择左侧账期查看详情{canClose ? '，或开启新账期。' : '。'}</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: glPeriodApi.PeriodStatus }) {
  return <span className={`purchase-order-status purchase-order-status-${status === 'OPEN' ? 'approved' : 'cancelled'}`}>
    {statusLabels[status]}</span>;
}

function OpenPeriodForm({
  draft,
  setDraft,
  saving,
  onSave,
  onCancel,
}: {
  draft: string;
  setDraft: (value: string) => void;
  saving: boolean;
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}) {
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>开启账期</h2>
    <p>账期键为 yyyyMM。已存在的账期不能重复开启。</p>
    <label>账期
      <input type="month" aria-label="开启账期" disabled={saving} value={monthValue(draft)}
        onChange={(event) => setDraft(event.target.value.replace('-', ''))} />
    </label>
    <label>账期文本
      <input aria-label="开启账期文本" placeholder="yyyyMM" maxLength={6} disabled={saving} value={draft}
        onChange={(event) => setDraft(event.target.value.replace(/\D/g, '').slice(0, 6))} />
    </label>
    <div className="customer-actions">
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '处理中…' : '开启'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

function PeriodDetails({
  period,
  readiness,
  closeResult,
  canClose,
  canReopen,
  saving,
  checking,
  closeConfirming,
  reopenConfirming,
  onPrecheck,
  onRequestClose,
  onConfirmClose,
  onCancelClose,
  onRequestReopen,
  onConfirmReopen,
  onCancelReopen,
}: {
  period: glPeriodApi.AccountingPeriod;
  readiness: glPeriodApi.PeriodCloseReadiness | null;
  closeResult: glPeriodApi.PeriodCloseResult | null;
  canClose: boolean;
  canReopen: boolean;
  saving: boolean;
  checking: boolean;
  closeConfirming: boolean;
  reopenConfirming: boolean;
  onPrecheck: () => void;
  onRequestClose: () => void;
  onConfirmClose: () => void;
  onCancelClose: () => void;
  onRequestReopen: () => void;
  onConfirmReopen: () => void;
  onCancelReopen: () => void;
}) {
  return <>
    <StatusBadge status={period.status} />
    <h2>{period.period}</h2>
    <dl>
      <dt>年份</dt><dd>{period.year}</dd>
      <dt>月份</dt><dd>{period.month}</dd>
      <dt>状态</dt><dd>{period.statusLabel || statusLabels[period.status]}</dd>
      <dt>关账人</dt><dd>{period.closedBy || '—'}</dd>
      <dt>关账时间</dt><dd>{period.closedAt || '—'}</dd>
    </dl>
    {readiness && <ReadinessPanel readiness={readiness} />}
    {closeResult && <CloseResultPanel result={closeResult} />}
    {period.status === 'OPEN' && canClose && (
      closeConfirming ? (
        <div className="purchase-receipt-post-confirm" role="alertdialog"
          aria-labelledby="period-close-confirm-title"
          aria-describedby="period-close-confirm-copy">
          <strong id="period-close-confirm-title">确认关账</strong>
          <p id="period-close-confirm-copy">
            将对账期 {period.period} 跑结转前一致性检查、生成并过账结转损益凭证、把损益类结转入本年利润 4103，随后关账。关账后该期禁止再过账，此操作不可直接撤销。确认继续吗？
          </p>
          <div className="customer-actions">
            <button type="button" className="memory-button memory-button-primary" disabled={saving}
              onClick={onConfirmClose}>{saving ? '处理中…' : '确认关账'}</button>
            <button type="button" className="memory-button" disabled={saving}
              onClick={onCancelClose}>取消</button>
          </div>
        </div>
      ) : (
        <div className="customer-actions">
          <button type="button" className="memory-button" disabled={saving || checking}
            onClick={onPrecheck}>{checking ? '预检中…' : '关账预检'}</button>
          <button type="button" className="memory-button memory-button-primary"
            disabled={saving || checking || (readiness !== null && !readiness.closeable)}
            onClick={onRequestClose}>关账</button>
        </div>
      )
    )}
    {period.status === 'CLOSED' && canReopen && (
      reopenConfirming ? (
        <div className="purchase-receipt-post-confirm" role="alertdialog"
          aria-labelledby="period-reopen-confirm-title"
          aria-describedby="period-reopen-confirm-copy">
          <strong id="period-reopen-confirm-title">确认重开账期</strong>
          <p id="period-reopen-confirm-copy">
            重开账期 {period.period} 不会自动冲销结转凭证。重开后重新关账须先冲销原结转凭证。确认继续吗？
          </p>
          <div className="customer-actions">
            <button type="button" className="memory-button memory-button-primary" disabled={saving}
              onClick={onConfirmReopen}>{saving ? '处理中…' : '确认重开'}</button>
            <button type="button" className="memory-button" disabled={saving}
              onClick={onCancelReopen}>取消</button>
          </div>
        </div>
      ) : (
        <div className="customer-actions">
          <button type="button" className="memory-button" disabled={saving}
            onClick={onRequestReopen}>重开账期</button>
        </div>
      )
    )}
  </>;
}

function ReadinessPanel({ readiness }: { readiness: glPeriodApi.PeriodCloseReadiness }) {
  return <>
    <h3>关账预检</h3>
    <dl>
      <dt>可否关账</dt><dd>{readiness.closeable ? '可以' : '不可以'}</dd>
      <dt>已有结转凭证</dt><dd>{readiness.alreadyClosed ? '是' : '否'}</dd>
      <dt>本期收入</dt><dd>{amountText(readiness.totalRevenue)}</dd>
      <dt>本期费用</dt><dd>{amountText(readiness.totalExpense)}</dd>
      <dt>净利润</dt><dd>{amountText(readiness.netProfit)}</dd>
      <dt>试算 Σ借</dt><dd>{amountText(readiness.trialBalanceDebit)}</dd>
      <dt>试算 Σ贷</dt><dd>{amountText(readiness.trialBalanceCredit)}</dd>
    </dl>
    {readiness.consistencyErrors.length > 0 && <>
      <p className="memory-empty">一致性 ERROR（阻断关账）</p>
      <ul>{readiness.consistencyErrors.map((item) => <li key={item}>{item}</li>)}</ul>
    </>}
    {readiness.consistencyWarnings.length > 0 && <>
      <p className="memory-empty">一致性 WARN（不阻断）</p>
      <ul>{readiness.consistencyWarnings.map((item) => <li key={item}>{item}</li>)}</ul>
    </>}
    {readiness.closingPreviewLines.length > 0 && <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>科目</th><th>名称</th><th>借方</th><th>贷方</th>
      </tr></thead><tbody>
        {readiness.closingPreviewLines.map((line) => <tr key={`${line.accountCode}-${line.debit}-${line.credit}`}>
          <td>{line.accountCode}</td>
          <td>{line.accountName}</td>
          <td>{amountText(line.debit)}</td>
          <td>{amountText(line.credit)}</td>
        </tr>)}
      </tbody></table>
    </div>}
  </>;
}

function CloseResultPanel({ result }: { result: glPeriodApi.PeriodCloseResult }) {
  return <>
    <h3>关账结果</h3>
    <dl>
      <dt>结转凭证</dt><dd>{result.closingVoucherDocNo || '未生成'}</dd>
      <dt>本期收入</dt><dd>{amountText(result.totalRevenue)}</dd>
      <dt>本期费用</dt><dd>{amountText(result.totalExpense)}</dd>
      <dt>净利润</dt><dd>{amountText(result.netProfit)}</dd>
      <dt>试算 Σ借</dt><dd>{amountText(result.trialBalanceDebit)}</dd>
      <dt>试算 Σ贷</dt><dd>{amountText(result.trialBalanceCredit)}</dd>
      <dt>关账人</dt><dd>{result.closedBy || '—'}</dd>
      <dt>关账时间</dt><dd>{result.closedAt || '—'}</dd>
    </dl>
  </>;
}
