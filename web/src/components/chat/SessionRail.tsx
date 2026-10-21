import { useState } from 'react';
import type { ChatSessionSummary } from '../../api/chatApi';

interface SessionRailProps {
  sessions: ChatSessionSummary[];
  currentSessionId: string | null;
  disabled: boolean;
  onNew: () => void;
  onSelect: (sessionId: string) => void;
  onRename: (sessionId: string, title: string) => void;
  onArchive: (sessionId: string) => void;
}

function sessionTitle(session: ChatSessionSummary): string {
  const title = session.title?.trim();
  return title ? title : '新会话';
}

function formatUpdatedAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function SessionRail({
  sessions,
  currentSessionId,
  disabled,
  onNew,
  onSelect,
  onRename,
  onArchive,
}: SessionRailProps) {
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');

  const startRename = (session: ChatSessionSummary) => {
    setRenamingId(session.sessionId);
    setDraft(sessionTitle(session));
  };

  const commitRename = (sessionId: string) => {
    const title = draft.trim();
    setRenamingId(null);
    if (!title) return;
    onRename(sessionId, title);
  };

  return (
    <aside className="session-rail" aria-label="历史会话">
      <div className="session-rail-header">
        <span className="session-rail-title">会话</span>
        <button
          type="button"
          className="session-rail-new"
          disabled={disabled}
          onClick={onNew}
        >
          新建会话
        </button>
      </div>
      <ul className="session-rail-list">
        {sessions.length === 0 && (
          <li className="session-rail-empty">暂无历史会话</li>
        )}
        {sessions.map((session) => {
          const active = session.sessionId === currentSessionId;
          const archived = session.status === 'closed';
          const renaming = renamingId === session.sessionId;
          return (
            <li
              key={session.sessionId}
              className={`session-rail-item${active ? ' session-rail-item-active' : ''}${archived ? ' session-rail-item-archived' : ''}`}
            >
              {renaming ? (
                <form
                  className="session-rail-rename"
                  onSubmit={(event) => {
                    event.preventDefault();
                    commitRename(session.sessionId);
                  }}
                >
                  <input
                    className="session-rail-rename-input"
                    value={draft}
                    onChange={(event) => setDraft(event.target.value)}
                    onBlur={() => commitRename(session.sessionId)}
                    maxLength={200}
                    autoFocus
                    aria-label="会话标题"
                  />
                </form>
              ) : (
                <button
                  type="button"
                  className="session-rail-open"
                  disabled={disabled}
                  onClick={() => onSelect(session.sessionId)}
                >
                  <span className="session-rail-item-title">{sessionTitle(session)}</span>
                  <span className="session-rail-item-meta">
                    {archived ? '已归档 · ' : ''}
                    {formatUpdatedAt(session.updatedAt)}
                  </span>
                </button>
              )}
              <div className="session-rail-actions">
                <button
                  type="button"
                  className="session-rail-action"
                  disabled={disabled || renaming}
                  onClick={() => startRename(session)}
                >
                  重命名
                </button>
                {!archived && (
                  <button
                    type="button"
                    className="session-rail-action"
                    disabled={disabled}
                    onClick={() => onArchive(session.sessionId)}
                  >
                    归档
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </aside>
  );
}
