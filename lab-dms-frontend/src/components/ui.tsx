/* Shared UI primitives: toasts, badges, modals, empty states. */
import {
  createContext, useContext, useState, useCallback, useRef,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { CheckCircle2, AlertCircle, Info, X, FileQuestion } from 'lucide-react';
import { cn } from '../lib/utils';
import { useI18n } from '../lib/i18n';

/* ------------------------------------------------------------------ */
/* Toasts                                                              */
/* ------------------------------------------------------------------ */

type ToastKind = 'success' | 'error' | 'info';
interface Toast { id: number; kind: ToastKind; message: string }

interface ToastContextValue {
  toast: (kind: ToastKind, message: string) => void;
}

const ToastContext = createContext<ToastContextValue>({ toast: () => {} });

export function useToast() {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const toast = useCallback((kind: ToastKind, message: string) => {
    const id = nextId.current++;
    setToasts((prev) => [...prev, { id, kind, message }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 4500);
  }, []);

  const icons: Record<ToastKind, ReactNode> = {
    success: <CheckCircle2 className="w-5 h-5 text-green-600 shrink-0" />,
    error: <AlertCircle className="w-5 h-5 text-red-600 shrink-0" />,
    info: <Info className="w-5 h-5 text-primary shrink-0" />,
  };

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      {createPortal(
        <div className="fixed bottom-6 right-6 z-[100] flex flex-col gap-2 w-80">
          <AnimatePresence>
            {toasts.map((t) => (
              <motion.div
                key={t.id}
                initial={{ opacity: 0, x: 40 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 40 }}
                className={cn(
                  'flex items-start gap-3 p-3.5 rounded-lg shadow-lg border bg-white text-sm',
                  t.kind === 'success' && 'border-green-200',
                  t.kind === 'error' && 'border-red-200',
                  t.kind === 'info' && 'border-blue-200',
                )}
              >
                {icons[t.kind]}
                <span className="flex-1 text-textMain">{t.message}</span>
                <button
                  onClick={() => setToasts((prev) => prev.filter((x) => x.id !== t.id))}
                  className="text-textMuted hover:text-textMain"
                >
                  <X className="w-4 h-4" />
                </button>
              </motion.div>
            ))}
          </AnimatePresence>
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
}

/* ------------------------------------------------------------------ */
/* Status badges                                                       */
/* ------------------------------------------------------------------ */

const DOC_STATUS_STYLES: Record<string, string> = {
  DRAFT: 'bg-gray-100 text-gray-700 border-gray-200',
  IN_REVIEW: 'bg-amber-50 text-amber-700 border-amber-200',
  ACTIVE: 'bg-green-50 text-green-700 border-green-200',
  OBSOLETE: 'bg-red-50 text-red-700 border-red-200',
};

export function DocStatusBadge({ status }: { status: string }) {
  const { t } = useI18n();
  return (
    <span className={cn(
      'px-2.5 py-1 rounded-full text-xs font-medium border whitespace-nowrap',
      DOC_STATUS_STYLES[status] ?? DOC_STATUS_STYLES.DRAFT,
    )}>
      {t(`status.${status}`)}
    </span>
  );
}

const REVIEW_STATUS_STYLES: Record<string, string> = {
  OPEN: 'bg-amber-50 text-amber-700 border-amber-200',
  APPROVED: 'bg-green-50 text-green-700 border-green-200',
  REJECTED: 'bg-red-50 text-red-700 border-red-200',
  MERGED: 'bg-blue-50 text-primary border-blue-200',
};

export function ReviewStatusBadge({ status }: { status: string }) {
  const { t } = useI18n();
  return (
    <span className={cn(
      'px-2.5 py-1 rounded-full text-xs font-medium border whitespace-nowrap',
      REVIEW_STATUS_STYLES[status] ?? REVIEW_STATUS_STYLES.OPEN,
    )}>
      {t(`review.${status}`)}
    </span>
  );
}

/* ------------------------------------------------------------------ */
/* Modal                                                               */
/* ------------------------------------------------------------------ */

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}

export function Modal({ isOpen, onClose, title, children, footer, wide }: ModalProps) {
  if (!isOpen) return null;
  return createPortal(
    <AnimatePresence>
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm" onMouseDown={onClose}>
        <motion.div
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={{ opacity: 0, scale: 0.95 }}
          onMouseDown={(e) => e.stopPropagation()}
          className={cn(
            'bg-white rounded-xl shadow-xl w-full overflow-hidden flex flex-col max-h-[90vh]',
            wide ? 'max-w-4xl' : 'max-w-lg',
          )}
        >
          <div className="flex justify-between items-center px-6 py-4 border-b border-borderLight">
            <h2 className="text-lg font-bold text-textMain">{title}</h2>
            <button onClick={onClose} className="text-textMuted hover:text-textMain transition-colors">
              <X className="w-5 h-5" />
            </button>
          </div>
          <div className="p-6 overflow-y-auto flex-1">{children}</div>
          {footer && (
            <div className="px-6 py-4 border-t border-borderLight flex justify-end gap-3 bg-gray-50/50">
              {footer}
            </div>
          )}
        </motion.div>
      </div>
    </AnimatePresence>,
    document.body,
  );
}

/* ------------------------------------------------------------------ */
/* Confirm dialog                                                      */
/* ------------------------------------------------------------------ */

interface ConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  body: string;
  confirmLabel?: string;
  danger?: boolean;
  loading?: boolean;
}

export function ConfirmModal({ isOpen, onClose, onConfirm, title, body, confirmLabel, danger, loading }: ConfirmModalProps) {
  const { t } = useI18n();
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={title}
      footer={
        <>
          <button onClick={onClose} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
            {t('common.cancel')}
          </button>
          <button
            onClick={onConfirm}
            disabled={loading}
            className={cn(
              'px-4 py-2 text-sm font-medium text-white rounded-md transition-colors disabled:opacity-50',
              danger ? 'bg-red-600 hover:bg-red-700' : 'bg-primary hover:bg-primary-dark',
            )}
          >
            {confirmLabel ?? t('common.confirm')}
          </button>
        </>
      }
    >
      <p className="text-sm text-textMuted">{body}</p>
    </Modal>
  );
}

/* ------------------------------------------------------------------ */
/* Empty state + loading                                               */
/* ------------------------------------------------------------------ */

export function EmptyState({ message, icon }: { message: string; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-textMuted">
      {icon ?? <FileQuestion className="w-10 h-10 mb-3 opacity-40" />}
      <p className="text-sm">{message}</p>
    </div>
  );
}

export function TableSkeleton({ cols, rows = 4 }: { cols: number; rows?: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }).map((_, c) => (
            <td key={c} className="px-6 py-4">
              <div className="h-4 bg-gray-100 rounded animate-pulse" style={{ width: `${55 + ((r + c) % 3) * 15}%` }} />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
