import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchNotifications,
  fetchUnreadCount,
  markNotificationRead,
  type NotificationSeverity,
  type SystemNotification,
} from '../api/notificationApi';

const SEVERITY_LABELS: Record<NotificationSeverity, string> = {
  ERROR: '错误',
  WARN: '警告',
  INFO: '提示',
};

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

type UnreadCountState =
  | { status: 'unknown' }
  | { status: 'loading' }
  | { status: 'ready'; value: number }
  | { status: 'error'; message: string };

interface NotificationData {
  items: SystemNotification[];
  unreadCount: UnreadCountState;
}

/** 顶栏中的个人站内通知入口，仅展示当前用户最近的通知摘要。 */
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<NotificationData>({
    items: [],
    unreadCount: { status: 'unknown' },
  });
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [markingId, setMarkingId] = useState<number | null>(null);
  const bellButtonRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const mountedRef = useRef(false);
  const listGenerationRef = useRef(0);
  const countGenerationRef = useRef(0);
  const countReadyRef = useRef(false);
  const markInFlightRef = useRef<number | null>(null);

  const loadUnreadCount = useCallback(async () => {
    const generation = countGenerationRef.current + 1;
    countGenerationRef.current = generation;
    countReadyRef.current = false;
    setData((current) => ({ ...current, unreadCount: { status: 'loading' } }));
    try {
      const result = await fetchUnreadCount();
      if (!mountedRef.current || countGenerationRef.current !== generation) return;
      countReadyRef.current = true;
      setData((current) => ({
        ...current,
        unreadCount: { status: 'ready', value: result.unreadCount },
      }));
    } catch (countError) {
      if (!mountedRef.current || countGenerationRef.current !== generation) return;
      countReadyRef.current = false;
      setData((current) => ({
        ...current,
        unreadCount: {
          status: 'error',
          message: errorMessage(countError, '未读数加载失败'),
        },
      }));
    }
  }, []);

  const loadNotifications = async () => {
    const generation = listGenerationRef.current + 1;
    listGenerationRef.current = generation;
    setListLoading(true);
    setListError(null);
    setActionError(null);
    setData((current) => ({ ...current, items: [] }));
    try {
      const result = await fetchNotifications();
      if (!mountedRef.current || listGenerationRef.current !== generation) return;
      setData((current) => ({ ...current, items: result.items }));
    } catch (loadError) {
      if (!mountedRef.current || listGenerationRef.current !== generation) return;
      setListError(errorMessage(loadError, '通知加载失败'));
    } finally {
      if (mountedRef.current && listGenerationRef.current === generation) {
        setListLoading(false);
      }
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    void loadUnreadCount();
    return () => {
      mountedRef.current = false;
      listGenerationRef.current += 1;
      countGenerationRef.current += 1;
      markInFlightRef.current = null;
    };
  }, [loadUnreadCount]);

  useEffect(() => {
    if (open) closeButtonRef.current?.focus();
  }, [open]);

  const closePopover = () => {
    listGenerationRef.current += 1;
    countGenerationRef.current += 1;
    setListLoading(false);
    setData((current) => current.unreadCount.status === 'loading'
      ? { ...current, unreadCount: { status: 'unknown' } }
      : current);
    setOpen(false);
    bellButtonRef.current?.focus();
  };

  const toggle = () => {
    if (open) {
      closePopover();
      return;
    }
    setOpen(true);
    void loadUnreadCount();
    void loadNotifications();
  };

  const markRead = async (item: SystemNotification) => {
    if (item.readAt !== null || markInFlightRef.current !== null) return;
    markInFlightRef.current = item.id;
    setMarkingId(item.id);
    setActionError(null);
    try {
      const updated = await markNotificationRead(item.id);
      if (!mountedRef.current) return;
      const countNeedsReload = !countReadyRef.current;
      setData((current) => {
        const localItem = current.items.find((row) => row.id === item.id);
        if (!localItem || localItem.readAt !== null || updated.readAt === null) return current;
        return {
          items: current.items.map((row) => (row.id === item.id ? updated : row)),
          unreadCount: current.unreadCount.status === 'ready'
            ? {
                status: 'ready',
                value: Math.max(0, current.unreadCount.value - 1),
              }
            : current.unreadCount,
        };
      });
      if (countNeedsReload) void loadUnreadCount();
    } catch (markError) {
      if (mountedRef.current) {
        setActionError(errorMessage(markError, '标记已读失败'));
      }
    } finally {
      if (markInFlightRef.current === item.id) {
        markInFlightRef.current = null;
        if (mountedRef.current) setMarkingId(null);
      }
    }
  };

  const unreadLabel = data.unreadCount.status === 'ready'
    ? data.unreadCount.value > 0
      ? `通知，${data.unreadCount.value} 条未读`
      : '通知，暂无未读'
    : data.unreadCount.status === 'loading'
      ? '通知，正在获取未读数'
      : data.unreadCount.status === 'error'
        ? '通知，未读数加载失败'
        : '通知，未读数未知';

  return (
    <div className="notification-bell">
      <button
        ref={bellButtonRef}
        type="button"
        className="notification-bell-button"
        aria-label={unreadLabel}
        aria-expanded={open}
        aria-controls="notification-popover"
        onClick={toggle}
      >
        <svg className="notification-bell-icon" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M18 10a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
          <path d="M10 22h4" />
        </svg>
        <span className="notification-bell-text">通知</span>
        {data.unreadCount.status === 'ready' && data.unreadCount.value > 0 && (
          <span className="notification-bell-count" aria-live="polite">
            {data.unreadCount.value > 99 ? '99+' : data.unreadCount.value}
          </span>
        )}
        {data.unreadCount.status !== 'ready' && (
          <span
            className={`notification-bell-count notification-bell-count-${data.unreadCount.status}`}
            aria-hidden="true"
          >
            {data.unreadCount.status === 'loading'
              ? '…'
              : data.unreadCount.status === 'error' ? '!' : '?'}
          </span>
        )}
      </button>

      {open && (
        <section
          id="notification-popover"
          className="notification-popover"
          role="dialog"
          aria-label="通知中心"
        >
          <header className="notification-popover-header">
            <div>
              <h2>通知中心</h2>
              {data.unreadCount.status === 'ready' && (
                <span>{data.unreadCount.value > 0
                  ? `${data.unreadCount.value} 条待处理`
                  : '已全部处理'}</span>
              )}
              {data.unreadCount.status === 'loading' && (
                <span className="notification-count-status" role="status">正在获取未读数…</span>
              )}
              {data.unreadCount.status === 'unknown' && (
                <span className="notification-count-status">未读数未知</span>
              )}
              {data.unreadCount.status === 'error' && (
                <span
                  className="notification-count-status notification-count-status-error"
                  title={data.unreadCount.message}
                >
                  未读数加载失败
                  <button
                    type="button"
                    className="notification-count-retry"
                    onClick={() => void loadUnreadCount()}
                  >
                    重试
                  </button>
                </span>
              )}
            </div>
            <button
              ref={closeButtonRef}
              type="button"
              className="notification-close"
              onClick={closePopover}
            >
              关闭
            </button>
          </header>

          {listLoading && <p className="notification-state" role="status">正在加载通知…</p>}
          {!listLoading && listError && <p className="notification-error" role="alert">{listError}</p>}
          {actionError && <p className="notification-error" role="alert">{actionError}</p>}
          {!listLoading && !listError && data.items.length === 0 && (
            <p className="notification-state">暂无通知</p>
          )}
          {!listLoading && !listError && data.items.length > 0 && (
            <div className="notification-list">
              {data.items.map((item) => {
                const unreadItem = item.readAt === null;
                return (
                  <article
                    key={item.id}
                    className={`notification-item notification-severity-${item.severity.toLowerCase()}${
                      unreadItem ? ' notification-unread' : ''
                    }`}
                  >
                    <div className="notification-item-heading">
                      <span className="notification-severity-label">{SEVERITY_LABELS[item.severity]}</span>
                      <strong>{item.title}</strong>
                    </div>
                    <p>{item.content}</p>
                    {unreadItem && (
                      <button
                        type="button"
                        className="notification-mark-read"
                        disabled={markingId !== null}
                        onClick={() => void markRead(item)}
                      >
                        {markingId === item.id ? '标记中…' : '标记已读'}
                      </button>
                    )}
                  </article>
                );
              })}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
