import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { createPortal } from 'react-dom';
import { Bell, CheckCheck } from 'lucide-react';
import api from '../lib/api';
import { useI18n, formatDate } from '../lib/i18n';
import { cn } from '../lib/utils';

interface Notification {
  id: string;
  type: string;
  title: string;
  message: string;
  link: string | null;
  isRead: boolean;
  createdAt: string;
}

const TYPE_STYLES: Record<string, string> = {
  REVIEW_SUBMITTED: 'bg-amber-50 text-amber-700',
  REVIEW_APPROVED: 'bg-green-50 text-green-700',
  REVIEW_REJECTED: 'bg-red-50 text-red-700',
  DOCUMENT_OBSOLETED: 'bg-gray-100 text-gray-700',
  USER_APPROVED: 'bg-blue-50 text-primary',
  // ISO 8.3.2 b) — red, because an overdue review is a non-conformity waiting
  // to be raised, not an informational notice.
  REVIEW_DUE: 'bg-red-50 text-red-700',
};

/**
 * Polls for unread notifications every 30s. Polling (not a websocket) keeps
 * the backend simple and stateless — appropriate for this system's scale
 * (a lab's internal quality team, not a high-frequency chat app).
 */
export default function NotificationBell() {
  const { t, lang } = useI18n();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);

  const fetchUnreadCount = useCallback(async () => {
    try {
      const res = await api.get('/notifications/unread-count');
      setUnreadCount(res.data.count);
    } catch {
      // Silent: notification polling should never disrupt the main app.
    }
  }, []);

  const fetchNotifications = useCallback(async () => {
    try {
      const res = await api.get('/notifications');
      setNotifications(res.data);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    fetchUnreadCount();
    const interval = setInterval(fetchUnreadCount, 30000);
    return () => clearInterval(interval);
  }, [fetchUnreadCount]);

  useEffect(() => {
    if (open) fetchNotifications();
  }, [open, fetchNotifications]);

  useEffect(() => {
    const onClickOutside = (e: MouseEvent) => {
      if (
        panelRef.current && !panelRef.current.contains(e.target as Node) &&
        buttonRef.current && !buttonRef.current.contains(e.target as Node)
      ) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const handleClick = async (n: Notification) => {
    if (!n.isRead) {
      try {
        await api.post(`/notifications/${n.id}/read`);
        setNotifications((prev) => prev.map((x) => (x.id === n.id ? { ...x, isRead: true } : x)));
        setUnreadCount((c) => Math.max(0, c - 1));
      } catch {
        // ignore
      }
    }
    setOpen(false);
    if (n.link) navigate(n.link);
  };

  const handleMarkAllRead = async () => {
    try {
      await api.post('/notifications/read-all');
      setNotifications((prev) => prev.map((x) => ({ ...x, isRead: true })));
      setUnreadCount(0);
    } catch {
      // ignore
    }
  };

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        onClick={() => setOpen((v) => !v)}
        className="relative p-2 text-textMuted hover:text-textMain hover:bg-surface rounded-md transition-colors"
        aria-label={t('notifications.title')}
      >
        <Bell className="w-5 h-5" />
        {unreadCount > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-red-600 text-white text-[10px] font-bold flex items-center justify-center">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {open && createPortal(
        <div
          ref={panelRef}
          className="fixed z-[90] w-96 max-w-[calc(100vw-2rem)] bg-white rounded-xl shadow-xl border border-borderLight overflow-hidden flex flex-col max-h-[70vh]"
          style={{
            top: (buttonRef.current?.getBoundingClientRect().bottom ?? 0) + 8,
            right: buttonRef.current ? window.innerWidth - buttonRef.current.getBoundingClientRect().right : 16,
          }}
        >
          <div className="flex items-center justify-between px-4 py-3 border-b border-borderLight">
            <h3 className="font-semibold text-sm text-textMain">{t('notifications.title')}</h3>
            {unreadCount > 0 && (
              <button onClick={handleMarkAllRead} className="flex items-center gap-1 text-xs text-primary hover:underline">
                <CheckCheck className="w-3.5 h-3.5" /> {t('notifications.markAllRead')}
              </button>
            )}
          </div>
          <div className="overflow-y-auto flex-1 divide-y divide-borderLight">
            {notifications.map((n) => (
              <button
                key={n.id}
                onClick={() => handleClick(n)}
                className={cn(
                  'w-full text-left px-4 py-3 hover:bg-surface transition-colors',
                  !n.isRead && 'bg-blue-50/50',
                )}
              >
                <div className="flex items-start gap-2">
                  <span className={cn('text-[10px] font-semibold px-1.5 py-0.5 rounded shrink-0 mt-0.5', TYPE_STYLES[n.type] ?? 'bg-gray-100 text-gray-700')}>
                    {t(`notifications.type.${n.type}`)}
                  </span>
                  {!n.isRead && <span className="w-2 h-2 rounded-full bg-primary mt-1.5 shrink-0" />}
                </div>
                <p className="text-sm font-medium text-textMain mt-1">{n.title}</p>
                <p className="text-xs text-textMuted mt-0.5 line-clamp-2">{n.message}</p>
                <p className="text-[11px] text-textMuted/70 mt-1">{formatDate(n.createdAt, lang)}</p>
              </button>
            ))}
            {notifications.length === 0 && (
              <div className="p-8 text-center text-sm text-textMuted">{t('notifications.empty')}</div>
            )}
          </div>
        </div>,
        document.body,
      )}
    </div>
  );
}
