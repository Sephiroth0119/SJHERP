import { request } from './http';

export type NotificationSeverity = 'ERROR' | 'WARN' | 'INFO';

/** 当前登录用户可见的站内通知。 */
export interface SystemNotification {
  id: number;
  category: string;
  severity: NotificationSeverity;
  title: string;
  content: string;
  sourceType: string;
  sourceRef: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

/** GET /api/notifications 的分页响应。 */
export interface NotificationPage {
  items: SystemNotification[];
  total: number;
  page: number;
  size: number;
}

export function fetchUnreadCount(): Promise<{ unreadCount: number }> {
  return request('/api/notifications/unread-count');
}

export function fetchNotifications(): Promise<NotificationPage> {
  return request('/api/notifications?page=1&size=20');
}

export function markNotificationRead(id: number): Promise<SystemNotification> {
  return request(`/api/notifications/${id}/read`, { method: 'POST' });
}
