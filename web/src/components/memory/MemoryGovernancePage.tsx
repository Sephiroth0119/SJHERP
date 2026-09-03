import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../api/http';
import {
  activateMemory,
  expireMemory,
  fetchGovernanceCandidates,
  markMemoryConflict,
  replaceMemory,
  retryMemoryIndex,
  searchMemories,
  type GovernanceCandidates,
  type MemoryEntry,
  type MemoryForm,
  type MemoryIndexStatus,
  type MemoryPage,
  type MemorySourceType,
  type MemoryStatus,
  type MemoryType,
} from '../../api/memoryApi';

type Tab = 'list' | 'governance';

interface Filters {
  type?: MemoryType;
  status?: MemoryStatus;
  indexStatus?: MemoryIndexStatus;
}

interface EditState {
  type: MemoryType;
  title: string;
  content: string;
  sourceType: MemorySourceType;
  sourceRef: string;
  validFrom: string;
  validTo: string;
}

const TYPE_LABELS: Record<MemoryType, string> = {
  GAP_SOLUTION: '缺口解决方案',
  BUSINESS_TERM: '业务术语',
  METRIC_DEFINITION: '指标口径',
  OPERATION_PREFERENCE: '操作偏好',
};

const STATUS_LABELS: Record<MemoryStatus, string> = {
  ACTIVE: '活动',
  SUPERSEDED: '已替代',
  EXPIRED: '已失效',
  CONFLICT: '冲突',
};

const INDEX_LABELS: Record<MemoryIndexStatus, string> = {
  PENDING: '待索引',
  INDEXED: '已索引',
  FAILED: '索引失败',
};

const SOURCE_LABELS: Record<MemorySourceType, string> = {
  GAP_RECORD: '缺口记录',
  USER_INPUT: '用户输入',
  BUSINESS_DOC: '业务文档',
  SYSTEM: '系统',
};

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN');
}

function toLocalInput(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function toIso(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error('有效期时间格式不正确');
  return date.toISOString();
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '大记忆功能未启用或暂不可用';
    if (error.status === 409) return '记忆状态已变化，请刷新后重试';
    return error.message;
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试';
}

function allCandidateEntries(candidates: GovernanceCandidates): MemoryEntry[] {
  return [
    ...candidates.duplicateGroups.flatMap((group) => group.entries),
    ...candidates.conflictGroups.flatMap((group) => group.entries),
  ];
}

export function MemoryGovernancePage() {
  const [tab, setTab] = useState<Tab>('list');
  const [draftFilters, setDraftFilters] = useState<Filters>({});
  const [filters, setFilters] = useState<Filters>({});
  const [pageNumber, setPageNumber] = useState(1);
  const [page, setPage] = useState<MemoryPage | null>(null);
  const [candidates, setCandidates] = useState<GovernanceCandidates | null>(null);
  const [selected, setSelected] = useState<MemoryEntry | null>(null);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async (preferredMemoryNo?: string) => {
    setLoading(true);
    setError(null);
    try {
      const [nextPage, nextCandidates] = await Promise.all([
        searchMemories({ ...filters, page: pageNumber, size: 20 }),
        fetchGovernanceCandidates(),
      ]);
      setPage(nextPage);
      setCandidates(nextCandidates);
      const available = [...nextPage.items, ...allCandidateEntries(nextCandidates)];
      setSelected((current) => {
        const target = preferredMemoryNo ?? current?.memoryNo;
        return (target ? available.find((entry) => entry.memoryNo === target) : undefined)
          ?? nextPage.items[0]
          ?? null;
      });
    } catch (loadError) {
      setError(errorText(loadError));
    } finally {
      setLoading(false);
    }
  }, [filters, pageNumber]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const perform = useCallback(async (
    action: () => Promise<unknown>,
    preferredMemoryNo?: string,
  ) => {
    setSubmitting(true);
    setError(null);
    try {
      await action();
      setEdit(null);
      await reload(preferredMemoryNo);
    } catch (actionError) {
      setError(errorText(actionError));
    } finally {
      setSubmitting(false);
    }
  }, [reload]);

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    setPageNumber(1);
    setFilters(draftFilters);
  }

  function beginEdit(entry: MemoryEntry) {
    setEdit({
      type: entry.type,
      title: entry.title,
      content: entry.content,
      sourceType: entry.sourceType,
      sourceRef: entry.sourceRef,
      validFrom: toLocalInput(entry.validFrom),
      validTo: toLocalInput(entry.validTo),
    });
  }

  async function submitEdit(event: FormEvent) {
    event.preventDefault();
    if (!selected || !edit) return;
    if (!edit.title.trim() || !edit.content.trim() || !edit.sourceRef.trim()) {
      setError('标题、正文和来源编号不能为空');
      return;
    }
    let form: MemoryForm;
    try {
      form = {
        ...edit,
        title: edit.title.trim(),
        content: edit.content.trim(),
        sourceRef: edit.sourceRef.trim(),
        validFrom: toIso(edit.validFrom),
        validTo: toIso(edit.validTo),
      };
    } catch (dateError) {
      setError(errorText(dateError));
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const replacement = await replaceMemory(selected.memoryNo, form);
      setEdit(null);
      await reload(replacement.memoryNo);
    } catch (saveError) {
      setError(errorText(saveError));
    } finally {
      setSubmitting(false);
    }
  }

  function confirmExpire(entry: MemoryEntry) {
    if (!window.confirm('确认将该记忆设为失效？记录、版本历史和审计日志仍会保留。')) return;
    void perform(() => expireMemory(entry.memoryNo));
  }

  function confirmConflict(entries: MemoryEntry[]) {
    if (!window.confirm('确认将整组记忆标记为冲突并暂停召回？系统不会自动选择正确口径。')) return;
    void perform(() => markMemoryConflict(entries.map((entry) => entry.memoryNo)));
  }

  const totalPages = Math.max(1, Math.ceil((page?.total ?? 0) / (page?.size ?? 20)));

  return (
    <section className="memory-page">
      <header className="memory-header">
        <div>
          <h1>记忆治理</h1>
          <p>只识别候选，由管理员人工确认；所有修改保留版本与审计。</p>
        </div>
        <button type="button" className="memory-button" disabled={loading || submitting}
          onClick={() => void reload()}>
          刷新
        </button>
      </header>

      <div className="memory-tabs" role="tablist">
        <button type="button" className={tab === 'list' ? 'memory-tab-active' : ''}
          onClick={() => setTab('list')}>记忆列表</button>
        <button type="button" className={tab === 'governance' ? 'memory-tab-active' : ''}
          onClick={() => setTab('governance')}>
          治理候选
          {candidates && (
            <span>{candidates.duplicateGroups.length + candidates.conflictGroups.length}</span>
          )}
        </button>
      </div>

      {error && <div className="memory-error" role="alert">{error}</div>}
      {loading && <div className="memory-empty">正在加载大记忆…</div>}

      {!loading && tab === 'list' && (
        <>
          <form className="memory-toolbar" onSubmit={applyFilters}>
            <select value={draftFilters.type ?? ''}
              onChange={(event) => setDraftFilters({ ...draftFilters,
                type: (event.target.value || undefined) as MemoryType | undefined })}>
              <option value="">全部类型</option>
              {Object.entries(TYPE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <select value={draftFilters.status ?? ''}
              onChange={(event) => setDraftFilters({ ...draftFilters,
                status: (event.target.value || undefined) as MemoryStatus | undefined })}>
              <option value="">全部状态</option>
              {Object.entries(STATUS_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <select value={draftFilters.indexStatus ?? ''}
              onChange={(event) => setDraftFilters({ ...draftFilters,
                indexStatus: (event.target.value || undefined) as MemoryIndexStatus | undefined })}>
              <option value="">全部索引状态</option>
              {Object.entries(INDEX_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <button type="submit" className="memory-button memory-button-primary">查询</button>
          </form>

          <div className="memory-layout">
            <div className="memory-list-panel">
              {page && page.items.length > 0 ? (
                <div className="memory-table-wrap">
                  <table className="memory-table">
                    <thead><tr><th>编号 / 标题</th><th>类型</th><th>状态</th><th>来源</th><th>索引</th></tr></thead>
                    <tbody>
                      {page.items.map((entry) => (
                        <tr key={entry.memoryNo}
                          className={selected?.memoryNo === entry.memoryNo ? 'memory-row-selected' : ''}
                          onClick={() => { setSelected(entry); setEdit(null); }}>
                          <td><strong>{entry.memoryNo}</strong><span>{entry.title} · v{entry.version}</span></td>
                          <td>{TYPE_LABELS[entry.type]}</td>
                          <td><span className={`memory-status memory-status-${entry.status.toLowerCase()}`}>
                            {STATUS_LABELS[entry.status]}</span></td>
                          <td>{SOURCE_LABELS[entry.sourceType]}<span>{entry.sourceRef}</span></td>
                          <td>{INDEX_LABELS[entry.indexStatus]}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : <div className="memory-empty">当前筛选条件下没有记忆</div>}
              <div className="memory-pagination">
                <button type="button" disabled={pageNumber <= 1 || submitting}
                  onClick={() => setPageNumber((value) => value - 1)}>上一页</button>
                <span>第 {pageNumber} / {totalPages} 页，共 {page?.total ?? 0} 条</span>
                <button type="button" disabled={pageNumber >= totalPages || submitting}
                  onClick={() => setPageNumber((value) => value + 1)}>下一页</button>
              </div>
            </div>
            <MemoryDetail entry={selected} edit={edit} submitting={submitting}
              onBeginEdit={beginEdit} onEditChange={setEdit} onSubmitEdit={submitEdit}
              onCancelEdit={() => setEdit(null)} onExpire={confirmExpire}
              onRetry={(entry) => void perform(() => retryMemoryIndex(entry.memoryNo), entry.memoryNo)}
              onActivate={(entry) => void perform(() => activateMemory(entry.memoryNo), entry.memoryNo)} />
          </div>
        </>
      )}

      {!loading && tab === 'governance' && candidates && (
        <GovernanceCandidatesView candidates={candidates} submitting={submitting}
          onExpire={confirmExpire} onMarkConflict={confirmConflict} />
      )}
    </section>
  );
}

interface DetailProps {
  entry: MemoryEntry | null;
  edit: EditState | null;
  submitting: boolean;
  onBeginEdit: (entry: MemoryEntry) => void;
  onEditChange: (state: EditState) => void;
  onSubmitEdit: (event: FormEvent) => void;
  onCancelEdit: () => void;
  onExpire: (entry: MemoryEntry) => void;
  onRetry: (entry: MemoryEntry) => void;
  onActivate: (entry: MemoryEntry) => void;
}

function MemoryDetail(props: DetailProps) {
  const { entry, edit, submitting } = props;
  if (!entry) return <aside className="memory-detail memory-empty">选择一条记忆查看详情</aside>;
  if (edit) {
    return (
      <aside className="memory-detail">
        <h2>创建替代版本</h2>
        <form className="memory-edit-form" onSubmit={props.onSubmitEdit}>
          <label>类型<select value={edit.type} onChange={(event) => props.onEditChange({ ...edit,
            type: event.target.value as MemoryType })}>
            {Object.entries(TYPE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label>
          <label>标题<input value={edit.title} maxLength={200}
            onChange={(event) => props.onEditChange({ ...edit, title: event.target.value })} /></label>
          <label>正文<textarea value={edit.content} rows={8}
            onChange={(event) => props.onEditChange({ ...edit, content: event.target.value })} /></label>
          <label>来源类型<select value={edit.sourceType}
            onChange={(event) => props.onEditChange({ ...edit,
              sourceType: event.target.value as MemorySourceType })}>
            {Object.entries(SOURCE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label>
          <label>来源编号<input value={edit.sourceRef} maxLength={128}
            onChange={(event) => props.onEditChange({ ...edit, sourceRef: event.target.value })} /></label>
          <label>生效时间<input type="datetime-local" value={edit.validFrom}
            onChange={(event) => props.onEditChange({ ...edit, validFrom: event.target.value })} /></label>
          <label>结束时间<input type="datetime-local" value={edit.validTo}
            onChange={(event) => props.onEditChange({ ...edit, validTo: event.target.value })} /></label>
          <div className="memory-actions">
            <button className="memory-button memory-button-primary" disabled={submitting}>保存新版本</button>
            <button type="button" className="memory-button" disabled={submitting}
              onClick={props.onCancelEdit}>取消</button>
          </div>
        </form>
      </aside>
    );
  }
  return (
    <aside className="memory-detail">
      <h2>{entry.title}</h2>
      <div className="memory-meta"><span>{entry.memoryNo}</span><span>版本 {entry.version}</span>
        <span>{STATUS_LABELS[entry.status]}</span></div>
      <p className="memory-content">{entry.content}</p>
      <dl>
        <dt>类型</dt><dd>{TYPE_LABELS[entry.type]}</dd>
        <dt>来源</dt><dd>{SOURCE_LABELS[entry.sourceType]} · {entry.sourceRef}</dd>
        <dt>有效期</dt><dd>{formatTime(entry.validFrom)} ～ {formatTime(entry.validTo)}</dd>
        <dt>逻辑键</dt><dd>{entry.memoryKey}</dd>
        <dt>前版主键</dt><dd>{entry.previousId ?? '—'}</dd>
        <dt>索引</dt><dd>{INDEX_LABELS[entry.indexStatus]} · {entry.embeddingModel ?? '—'}
          {entry.embeddingDimension ? ` · ${entry.embeddingDimension} 维` : ''}</dd>
        <dt>更新</dt><dd>{entry.updatedBy} · {formatTime(entry.updatedAt)}</dd>
      </dl>
      {entry.lastIndexError && <div className="memory-index-error">{entry.lastIndexError}</div>}
      <div className="memory-actions">
        {entry.status === 'ACTIVE' && <button type="button" className="memory-button"
          disabled={submitting} onClick={() => props.onBeginEdit(entry)}>编辑</button>}
        {entry.status === 'CONFLICT' && <button type="button" className="memory-button memory-button-primary"
          disabled={submitting} onClick={() => props.onActivate(entry)}>恢复活动</button>}
        {(entry.status === 'ACTIVE' || entry.status === 'CONFLICT') &&
          <button type="button" className="memory-button memory-button-danger"
            disabled={submitting} onClick={() => props.onExpire(entry)}>失效</button>}
        {entry.status === 'ACTIVE' && entry.indexStatus === 'FAILED' &&
          <button type="button" className="memory-button" disabled={submitting}
            onClick={() => props.onRetry(entry)}>重试索引</button>}
      </div>
    </aside>
  );
}

function GovernanceCandidatesView({ candidates, submitting, onExpire, onMarkConflict }: {
  candidates: GovernanceCandidates;
  submitting: boolean;
  onExpire: (entry: MemoryEntry) => void;
  onMarkConflict: (entries: MemoryEntry[]) => void;
}) {
  return (
    <div className="memory-governance">
      <section>
        <h2>完全重复候选</h2>
        <p>同类型且正文完全相同。请核对来源后逐条失效多余记录，系统不会自动选择保留项。</p>
        {candidates.duplicateGroups.length === 0
          ? <div className="memory-empty">暂无完全重复候选</div>
          : candidates.duplicateGroups.map((group) => (
            <CandidateGroup key={`${group.type}-${group.entries[0]?.contentHash}`}
              title={TYPE_LABELS[group.type]} entries={group.entries} submitting={submitting}
              onExpire={onExpire} />
          ))}
      </section>
      <section>
        <h2>冲突候选</h2>
        <p>同类型、标题完全相同，但正文不同。整组标记后会暂停这些记忆的聊天召回。</p>
        {candidates.conflictGroups.length === 0
          ? <div className="memory-empty">暂无标题相同、内容不同的冲突候选</div>
          : candidates.conflictGroups.map((group) => (
            <CandidateGroup key={`${group.type}-${group.title}`} title={`${TYPE_LABELS[group.type]} · ${group.title}`}
              entries={group.entries} submitting={submitting} onExpire={onExpire}
              onMarkConflict={() => onMarkConflict(group.entries)} />
          ))}
      </section>
    </div>
  );
}

function CandidateGroup({ title, entries, submitting, onExpire, onMarkConflict }: {
  title: string;
  entries: MemoryEntry[];
  submitting: boolean;
  onExpire: (entry: MemoryEntry) => void;
  onMarkConflict?: () => void;
}) {
  return (
    <article className="memory-group">
      <header><strong>{title}</strong><span>{entries.length} 条</span>
        {onMarkConflict && <button type="button" className="memory-button memory-button-danger"
          disabled={submitting} onClick={onMarkConflict}>整组标记冲突</button>}</header>
      <div className="memory-group-entries">
        {entries.map((entry) => (
          <div key={entry.memoryNo} className="memory-candidate">
            <div><strong>{entry.memoryNo}</strong><span>{SOURCE_LABELS[entry.sourceType]} · {entry.sourceRef}</span></div>
            <p>{entry.content}</p>
            <footer><span>v{entry.version} · {formatTime(entry.updatedAt)}</span>
              <button type="button" className="memory-button memory-button-danger"
                disabled={submitting} onClick={() => onExpire(entry)}>失效此条</button></footer>
          </div>
        ))}
      </div>
    </article>
  );
}
