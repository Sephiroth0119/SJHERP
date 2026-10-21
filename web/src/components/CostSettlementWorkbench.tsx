import { useEffect, useRef, useState } from 'react';
import * as costSettlementApi from '../api/costSettlementApi.ts';
import * as glPeriodApi from '../api/glPeriodApi.ts';
import { createInFlightGuard, createRequestGate } from '../security/workbenchControl.ts';
import {
  CostSettlementWorkOrderPicker,
  errorMessage,
} from './productionReferencePickers.tsx';
import { useFocusDocument } from './useFocusDocument.ts';

const PAGE_SIZE = 20;

const statusLabels: Record<costSettlementApi.CostSettlement['status'], string> = {
  DRAFT: '草稿',
  APPROVED: '已审核',
  EXECUTING: '过账中',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  REVERSED: '已冲销',
};

const actionLabels: Record<costSettlementApi.CostSettlementAction, string> = {
  approve: '审核',
  post: '过账结转',
  cancel: '作废',
};

const actionNotices: Record<costSettlementApi.CostSettlementAction, string> = {
  approve: '成本结转单已审核',
  post: '成本结转单已过账，完工工费已追加到产成品并生成凭证',
  cancel: '成本结转单已作废',
};

const actionConfirms: Record<costSettlementApi.CostSettlementAction, string> = {
  approve: '确认审核该成本结转单？审核后可过账。',
  post: '确认过账？将按约当产量法把完工工费以 CostAdjust 追加到产成品，并生成会计凭证。料按净领料、工费按约当产量由后端计算，界面不重算。账期已关则整单回滚。',
  cancel: '确认作废该草稿成本结转单？作废后不可恢复。',
};

function runSettlementAction(action: costSettlementApi.CostSettlementAction, docNo: string) {
  switch (action) {
    case 'approve':
      return costSettlementApi.approveCostSettlement(docNo);
    case 'post':
      return costSettlementApi.postCostSettlement(docNo);
    case 'cancel':
      return costSettlementApi.cancelCostSettlement(docNo);
    default: {
      const exhaustive: never = action;
      throw new Error(`未知成本结转动作: ${exhaustive}`);
    }
  }
}

export function CostSettlementWorkbench({
  canUse,
  canUseWo,
  focusDocNo,
}: {
  canUse: boolean;
  canUseWo: boolean;
  focusDocNo?: string;
}) {
  const [items, setItems] = useState<costSettlementApi.CostSettlement[]>([]);
  const [selected, setSelected] = useState<costSettlementApi.CostSettlement | null>(null);
  const [periodDraft, setPeriodDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [periodFilter, setPeriodFilter] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [loading, setLoading] = useState(canUse);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<costSettlementApi.CostSettlementForm>(
    costSettlementApi.emptyCostSettlementForm(),
  );
  const [periods, setPeriods] = useState<glPeriodApi.AccountingPeriod[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const listVersion = useRef(createRequestGate());
  const detailVersion = useRef(createRequestGate());
  const periodVersion = useRef(createRequestGate());
  const mutationInFlight = useRef(createInFlightGuard());

  useEffect(() => {
    if (!canUse) {
      periodVersion.current.next();
      setPeriods([]);
      return;
    }
    const version = periodVersion.current.next();
    void glPeriodApi.listPeriods().then((nextPeriods) => {
      if (!periodVersion.current.isCurrent(version)) return;
      setPeriods(nextPeriods);
    }).catch((cause: unknown) => {
      if (periodVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '账期列表加载失败，可手工填写 YYYYMM'));
      }
    });
  }, [canUse]);

  useEffect(() => {
    if (!canUse) {
      listVersion.current.next();
      setLoading(false);
      setItems([]);
      setTotal(0);
      return;
    }
    const version = listVersion.current.next();
    setLoading(true);
    setError('');
    void costSettlementApi.searchCostSettlements(periodFilter, status, page, PAGE_SIZE).then((result) => {
      if (!listVersion.current.isCurrent(version)) return;
      setItems(result.items);
      setTotal(result.total);
    }).catch((cause: unknown) => {
      if (listVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '成本结转单列表加载失败'));
      }
    }).finally(() => {
      if (listVersion.current.isCurrent(version)) setLoading(false);
    });
  }, [canUse, periodFilter, status, page, refreshKey]);

  const invalidateDetail = () => { detailVersion.current.next(); };
  const clearSelection = () => {
    invalidateDetail();
    setSelected(null);
    setEditing(false);
  };

  const choose = (doc: costSettlementApi.CostSettlement) => {
    if (!canUse || saving) return;
    const version = detailVersion.current.next();
    setEditing(false);
    setError('');
    setNotice('');
    setSelected(doc);
    void costSettlementApi.getCostSettlement(doc.docNo).then((detail) => {
      if (!detailVersion.current.isCurrent(version)) return;
      setSelected(detail);
    }).catch((cause: unknown) => {
      if (detailVersion.current.isCurrent(version)) {
        setError(errorMessage(cause, '成本结转单详情加载失败'));
      }
    });
  };

  useFocusDocument(focusDocNo, (docNo) => {
    if (!canUse) return;
    void costSettlementApi.getCostSettlement(docNo).then(choose).catch((cause: unknown) => {
      setError(errorMessage(cause, '成本结转单详情加载失败'));
    });
  });

  const applySearch = () => {
    if (saving) return;
    clearSelection();
    setPeriodFilter(periodDraft);
    setStatus(statusDraft);
    setPage(1);
    setRefreshKey((value) => value + 1);
  };

  const startNew = () => {
    if (!canUse || saving) return;
    clearSelection();
    setForm(costSettlementApi.emptyCostSettlementForm());
    setEditing(true);
    setError('');
    setNotice('');
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canUse || !mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setError('');
    setNotice('');
    try {
      const validationError = costSettlementApi.validateCostSettlementForm(form);
      if (validationError) {
        setError(validationError);
        return;
      }
      setSaving(true);
      const result = await costSettlementApi.createCostSettlement(form);
      setSelected(result);
      setEditing(false);
      setNotice(`成本结转单已创建：${result.docNo}`);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, '成本结转单创建失败，请确认账期格式、工单已开工或已完工'));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const transition = async (action: costSettlementApi.CostSettlementAction) => {
    if (!canUse || !selected || mutationInFlight.current.isLocked()) return;
    if (!costSettlementApi.availableCostSettlementActions(selected.status).includes(action)) return;
    if (!window.confirm(actionConfirms[action])) return;
    if (!mutationInFlight.current.tryAcquire()) return;
    invalidateDetail();
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await runSettlementAction(action, selected.docNo);
      setSelected(result);
      setNotice(actionNotices[action]);
      setRefreshKey((value) => value + 1);
    } catch (cause: unknown) {
      setError(errorMessage(cause, `${actionLabels[action]}失败`));
    } finally {
      setSaving(false);
      mutationInFlight.current.release();
    }
  };

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canUse) {
    return <section className="customer-page">
      <header className="customer-header">
        <div>
          <p className="page-kicker">生产 / 成本结转</p>
          <h1>月末成本结转</h1>
          <p>查询与写操作均须 production:cost。当前账号无此权限，未请求结转列表，避免 403 刷屏。</p>
        </div>
      </header>
      <p className="memory-empty" role="status">需要 production:cost 权限才能查看和操作成本结转。</p>
    </section>;
  }

  return <section className="customer-page">
    <header className="customer-header">
      <div>
        <p className="page-kicker">生产 / 成本结转</p>
        <h1>月末成本结转</h1>
        <p>按账期归集工单料工费。料按净领料、工费按约当产量由后端计算；过账做 CostAdjust 并出 GL。界面只展示结果并驱动流转，不重算金额。数量与金额以字符串传输。</p>
      </div>
      <div className="customer-actions">
        <button type="button" className="memory-button memory-button-primary" disabled={saving}
          onClick={startNew}>新建结转单</button>
      </div>
    </header>
    <div className="purchase-order-filters">
      <label>账期<input aria-label="成本结转账期筛选" disabled={saving} value={periodDraft}
        placeholder="YYYYMM"
        onChange={(event) => setPeriodDraft(event.target.value)} /></label>
      <label>状态<select aria-label="成本结转单状态" disabled={saving} value={statusDraft}
        onChange={(event) => setStatusDraft(event.target.value)}>
        <option value="">全部</option>
        {Object.entries(statusLabels).map(([value, label]) =>
          <option key={value} value={value}>{label}</option>)}
      </select></label>
      <button type="button" className="memory-button" disabled={saving} onClick={applySearch}>查询</button>
    </div>
    {error && <div className="memory-error" role="alert">{error}</div>}
    {notice && <div className="customer-success" role="status">{notice}</div>}
    <div className="customer-layout purchase-order-layout">
      <section className="customer-list-panel">
        <div className="memory-table-wrap"><table className="memory-table"><thead><tr>
          <th>单号</th><th>账期</th><th>工单行</th><th>状态</th>
        </tr></thead><tbody>
          {items.map((doc) => <tr key={doc.docNo}
            className={selected?.docNo === doc.docNo ? 'memory-row-selected' : ''}>
            <td><button type="button" className="table-row-button" disabled={saving}
              onClick={() => choose(doc)}>{doc.docNo}</button></td>
            <td>{doc.period}</td>
            <td>{doc.lines.length}</td>
            <td><StatusBadge status={doc.status} /></td>
          </tr>)}
        </tbody></table></div>
        {loading && <p className="memory-empty" role="status">正在加载成本结转单…</p>}
        {!loading && items.length === 0 && <p className="memory-empty">暂无成本结转单。可从右上角新建。</p>}
        <div className="memory-pagination">
          <button type="button" disabled={saving || page <= 1}
            onClick={() => { clearSelection(); setPage(page - 1); }}>上一页</button>
          <span>第 {page} / {pageCount} 页，共 {total} 个</span>
          <button type="button" disabled={saving || page >= pageCount}
            onClick={() => { clearSelection(); setPage(page + 1); }}>下一页</button>
        </div>
      </section>
      <aside className="customer-detail purchase-order-detail">
        {editing ? <SettlementFormView form={form} setForm={setForm} saving={saving}
          canUseWo={canUseWo} periods={periods} onSave={save} onCancel={clearSelection} />
          : selected ? <SettlementDetails doc={selected} canUse={canUse} saving={saving}
            onTransition={(action) => void transition(action)} />
            : <p className="memory-empty">选择左侧结转单查看详情，或新建。</p>}
      </aside>
    </div>
  </section>;
}

function StatusBadge({ status }: { status: costSettlementApi.CostSettlement['status'] }) {
  return <span className={`customer-status customer-status-${status.toLowerCase()}`}>
    {statusLabels[status]}</span>;
}

interface SettlementFormProps {
  form: costSettlementApi.CostSettlementForm;
  setForm: (form: costSettlementApi.CostSettlementForm) => void;
  saving: boolean;
  canUseWo: boolean;
  periods: glPeriodApi.AccountingPeriod[];
  onSave: (event: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}

function SettlementFormView({
  form, setForm, saving, canUseWo, periods, onSave, onCancel,
}: SettlementFormProps) {
  const selectedDocNos = form.lines.map((line) => line.workOrderDocNo.trim()).filter(Boolean);
  const updateLine = (index: number, patch: Partial<costSettlementApi.CostSettlementLineForm>) => {
    setForm({
      ...form,
      lines: form.lines.map((line, current) => current === index ? { ...line, ...patch } : line),
    });
  };
  const pickWorkOrder = (docNo: string) => {
    if (selectedDocNos.includes(docNo)) return;
    const emptyIndex = form.lines.findIndex((line) => line.workOrderDocNo.trim() === '');
    if (emptyIndex >= 0) {
      updateLine(emptyIndex, { workOrderDocNo: docNo });
      return;
    }
    setForm({
      ...form,
      lines: [...form.lines, { ...costSettlementApi.emptyCostSettlementLine(), workOrderDocNo: docNo }],
    });
  };
  return <form className="customer-form purchase-order-form" onSubmit={onSave}>
    <h2>新建成本结转单</h2>
    <p>必填：账期 YYYYMM、至少一行工单。在产数量与完工程度可空，空视为 0。料工费由后端按净领料和约当产量计算，不要填写金额。</p>
    <label>账期
      <input required aria-label="结转账期" disabled={saving} value={form.period}
        placeholder="YYYYMM" pattern="\d{6}"
        onChange={(event) => setForm({ ...form, period: event.target.value })} /></label>
    {periods.length > 0 && <div className="reference-picker">
      <strong>从已开账期点选（GET /api/gl/periods，登录即可）</strong>
      <div className="reference-picker-results" role="listbox" aria-label="账期">
        {periods.map((item) => <button type="button" role="option" key={item.period}
          aria-selected={form.period === item.period}
          className={form.period === item.period ? 'reference-picker-selected' : ''}
          disabled={saving} onClick={() => setForm({ ...form, period: item.period })}>
          {item.period}<span>{item.statusLabel}</span>
        </button>)}
      </div>
    </div>}
    <label>备注<textarea maxLength={500} disabled={saving} value={form.remark}
      onChange={(event) => setForm({ ...form, remark: event.target.value })} /></label>
    <CostSettlementWorkOrderPicker canUseWo={canUseWo} disabled={saving}
      selectedDocNos={selectedDocNos} onPick={pickWorkOrder} />
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>工单号</th><th>期末在产数量</th><th>完工程度 %</th><th></th>
      </tr></thead><tbody>
        {form.lines.map((line, index) => <tr key={index}>
          <td><input required aria-label={`第 ${index + 1} 行工单号`} disabled={saving}
            value={line.workOrderDocNo} placeholder="WO-…"
            onChange={(event) => updateLine(index, { workOrderDocNo: event.target.value })} /></td>
          <td><input inputMode="decimal" aria-label={`第 ${index + 1} 行在产数量`} disabled={saving}
            pattern={costSettlementApi.nonNegativeDecimalPattern.source} value={line.wipQty}
            placeholder="0"
            onChange={(event) => updateLine(index, { wipQty: event.target.value })} /></td>
          <td><input inputMode="decimal" aria-label={`第 ${index + 1} 行完工程度`} disabled={saving}
            pattern={costSettlementApi.wipCompletionPctPattern.source} value={line.wipCompletionPct}
            placeholder="0–100"
            onChange={(event) => updateLine(index, { wipCompletionPct: event.target.value })} /></td>
          <td><button type="button" className="memory-button" disabled={saving || form.lines.length <= 1}
            onClick={() => setForm({ ...form, lines: form.lines.filter((_, current) => current !== index) })}>
            移除</button></td>
        </tr>)}
      </tbody></table>
    </div>
    <div className="customer-actions">
      <button type="button" className="memory-button" disabled={saving}
        onClick={() => setForm({
          ...form,
          lines: [...form.lines, costSettlementApi.emptyCostSettlementLine()],
        })}>增行</button>
      <button type="submit" className="memory-button memory-button-primary" disabled={saving}>
        {saving ? '创建中…' : '创建'}</button>
      <button type="button" className="memory-button" disabled={saving} onClick={onCancel}>返回</button>
    </div>
  </form>;
}

interface SettlementDetailsProps {
  doc: costSettlementApi.CostSettlement;
  canUse: boolean;
  saving: boolean;
  onTransition: (action: costSettlementApi.CostSettlementAction) => void;
}

function SettlementDetails({ doc, canUse, saving, onTransition }: SettlementDetailsProps) {
  const actions = costSettlementApi.availableCostSettlementActions(doc.status);
  const amount = (value: string) => costSettlementApi.formatQuantityDisplay(value);
  return <>
    <StatusBadge status={doc.status} />
    <h2>{doc.docNo}</h2>
    <dl>
      <dt>账期</dt><dd>{doc.period}</dd>
      <dt>备注</dt><dd>{doc.remark || '—'}</dd>
    </dl>
    <p>以下金额由后端按净领料与约当产量计算，界面不重算。</p>
    <div className="memory-table-wrap purchase-order-lines">
      <table className="memory-table"><thead><tr>
        <th>行</th><th>工单</th><th>料费</th><th>原材料</th><th>产成品料</th>
        <th>人工</th><th>制造费用</th><th>完工数量</th><th>完工成本</th>
        <th>在产数量</th><th>完工程度%</th><th>在产成本</th><th>前期已结转</th><th>凭证号</th>
      </tr></thead><tbody>
        {doc.lines.map((line) => <tr key={line.lineNo}>
          <td>{line.lineNo}</td>
          <td>{line.workOrderDocNo}</td>
          <td>{amount(line.materialCost)}</td>
          <td>{amount(line.rawMaterialCost)}</td>
          <td>{amount(line.goodsMaterialCost)}</td>
          <td>{amount(line.laborCost)}</td>
          <td>{amount(line.overheadCost)}</td>
          <td>{amount(line.completedQty)}</td>
          <td>{amount(line.completedCost)}</td>
          <td>{amount(line.wipQty)}</td>
          <td>{amount(line.wipCompletionPct)}</td>
          <td>{amount(line.wipCost)}</td>
          <td>{amount(line.alreadyTransferred)}</td>
          <td>{line.voucherDocNo || '—'}</td>
        </tr>)}
      </tbody></table>
    </div>
    {canUse && actions.length > 0 && <div className="customer-actions">
      {actions.map((action) => <button type="button" key={action}
        className={action === 'cancel' ? 'memory-button' : 'memory-button memory-button-primary'}
        disabled={saving} onClick={() => onTransition(action)}>
        {saving ? '处理中…' : actionLabels[action]}
      </button>)}
    </div>}
    {actions.length === 0 && <p className="memory-empty">当前状态无可执行流转。本刀不做冲销。</p>}
  </>;
}
