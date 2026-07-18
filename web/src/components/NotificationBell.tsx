import { useEffect, useState } from 'react';
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

/** 顶栏中的个人站内通知入口，仅展示当前用户最近的通知摘要。 */
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<SystemNotification[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [markingId, setMarkingId] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    void fetchUnreadCount()
      .then((result) => {
        if (active) setUnread(result.unreadCount);
      })
      .catch(() => {
        // 入口仍可打开并加载列表；列表请求会给出可见的失败反馈。
      });
    return () => {
      active = false;
    };
  }, []);

  const loadNotifications = async () => {
    setLoading(true);
    setError(null);
    setItems([]);
    try {
      const result = await fetchNotifications();
      setItems(result.items);
    } catch (loadError) {
      setError(errorMessage(loadError, '通知加载失败'));
    } finally {
      setLoading(false);
    }
  };

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    void loadNotifications();
  };

  const markRead = async (item: SystemNotification) => {
    if (item.readAt !== null || markingId !== null) return;
    setMarkingId(item.id);
    setError(null);
    try {
      const updated = await markNotificationRead(item.id);
      setItems((current) => current.map((row) => (row.id === item.id ? updated : row)));
      if (updated.readAt !== null) {
        setUnread((current) => Math.max(0, current - 1));
      }
    } catch (markError) {
      setError(errorMessage(markError, '标记已读失败'));
    } finally {
      setMarkingId(null);
    }
  };

  const unreadLabel = unread > 0 ? `通知，${unread} 条未读` : '通知，暂无未读';

  return (
    <div className="notification-bell">
      <button
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
        {unread > 0 && (
          <span className="notification-bell-count" aria-live="polite">
            {unread > 99 ? '99+' : unread}
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
              <span>{unread > 0 ? `${unread} 条待处理` : '已全部处理'}</span>
            </div>
            <button type="button" className="notification-close" onClick={() => setOpen(false)}>
              关闭
            </button>
          </header>

          {loading && <p className="notification-state" role="status">正在加载通知…</p>}
          {!loading && error && <p className="notification-error" role="alert">{error}</p>}
          {!loading && !error && items.length === 0 && (
            <p className="notification-state">暂无通知</p>
          )}
          {!loading && !error && items.length > 0 && (
            <div className="notification-list">
              {items.map((item) => {
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
