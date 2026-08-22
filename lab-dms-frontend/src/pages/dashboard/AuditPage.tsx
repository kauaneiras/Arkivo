import { useState, useEffect, useCallback } from 'react';
import api from '../../lib/api';
import { useI18n, formatDate } from '../../lib/i18n';
import { EmptyState, TableSkeleton } from '../../components/ui';
import { ScrollText, ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '../../lib/utils';

interface AuditEntry {
  id: number;
  userName: string | null;
  userEmail: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  details: string | null;
  createdAt: string;
}

interface AuditPage {
  entries: AuditEntry[];
  totalElements: number;
  totalPages: number;
  page: number;
}

const ACTION_COLORS: Record<string, string> = {
  DOCUMENT_CREATED: 'bg-blue-50 text-primary border-blue-200',
  DOCUMENT_STATUS_CHANGED: 'bg-violet-50 text-violet-700 border-violet-200',
  REVIEW_SUBMITTED: 'bg-amber-50 text-amber-700 border-amber-200',
  REVIEW_DECIDED: 'bg-green-50 text-green-700 border-green-200',
  COMMIT_CREATED: 'bg-gray-100 text-gray-700 border-gray-200',
};

function prettyDetails(details: string | null): string {
  if (!details) return '';
  try {
    const obj = JSON.parse(details);
    return Object.entries(obj).map(([k, v]) => `${k}: ${v}`).join(' · ');
  } catch {
    return details;
  }
}

export default function AuditPage() {
  const [data, setData] = useState<AuditPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [actionFilter, setActionFilter] = useState('');
  const [actions, setActions] = useState<string[]>([]);
  const { t, lang } = useI18n();

  useEffect(() => {
    api.get('/audit/actions').then((res) => setActions(res.data)).catch(console.error);
  }, []);

  const fetchAudit = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), size: '20' });
      if (actionFilter) params.append('action', actionFilter);
      const res = await api.get(`/audit?${params.toString()}`);
      setData(res.data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [page, actionFilter]);

  useEffect(() => { fetchAudit(); }, [fetchAudit]);

  return (
    <div className="flex flex-col h-full gap-6">
      <div className="flex justify-between items-end flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-textMain">{t('audit.title')}</h1>
          <p className="text-textMuted text-sm mt-1">{t('audit.subtitle')}</p>
        </div>
        <select
          className="input-field h-9 text-sm py-0 w-56"
          value={actionFilter}
          onChange={(e) => { setActionFilter(e.target.value); setPage(0); }}
        >
          <option value="">{t('audit.allActions')}</option>
          {actions.map((action) => (
            <option key={action} value={action}>{t(`action.${action}`)}</option>
          ))}
        </select>
      </div>

      <div className="card flex-1 flex flex-col p-0 overflow-hidden">
        <div className="overflow-x-auto flex-1">
          <table className="w-full text-sm text-left">
            <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold sticky top-0">
              <tr>
                <th className="px-6 py-4">{t('audit.when')}</th>
                <th className="px-6 py-4">{t('audit.user')}</th>
                <th className="px-6 py-4">{t('audit.action')}</th>
                <th className="px-6 py-4">{t('audit.entity')}</th>
                <th className="px-6 py-4">{t('audit.details')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-borderLight">
              {loading ? (
                <TableSkeleton cols={5} rows={8} />
              ) : !data || data.entries.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <EmptyState
                      icon={<ScrollText className="w-10 h-10 mb-3 opacity-40" />}
                      message={t('audit.empty')}
                    />
                  </td>
                </tr>
              ) : (
                data.entries.map((entry) => (
                  <tr key={entry.id} className="hover:bg-surface/50 transition-colors">
                    <td className="px-6 py-3.5 text-textMuted whitespace-nowrap">{formatDate(entry.createdAt, lang)}</td>
                    <td className="px-6 py-3.5">
                      <p className="font-medium text-textMain">{entry.userName ?? t('audit.system')}</p>
                      {entry.userEmail && <p className="text-xs text-textMuted">{entry.userEmail}</p>}
                    </td>
                    <td className="px-6 py-3.5">
                      <span className={cn(
                        'px-2.5 py-1 rounded-full text-xs font-medium border whitespace-nowrap',
                        ACTION_COLORS[entry.action] ?? 'bg-gray-100 text-gray-700 border-gray-200',
                      )}>
                        {t(`action.${entry.action}`)}
                      </span>
                    </td>
                    <td className="px-6 py-3.5 text-textMuted whitespace-nowrap">{entry.entityType}</td>
                    <td className="px-6 py-3.5 text-textMuted text-xs max-w-md truncate" title={prettyDetails(entry.details)}>
                      {prettyDetails(entry.details)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {data && data.totalPages > 1 && (
          <div className="px-6 py-3 border-t border-borderLight flex items-center justify-between bg-gray-50/50 text-sm">
            <span className="text-textMuted">
              {t('common.page')} {data.page + 1} {t('common.of')} {data.totalPages}
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="btn-outline py-1 px-2.5 text-xs flex items-center gap-1 disabled:opacity-40"
              >
                <ChevronLeft className="w-3.5 h-3.5" /> {t('common.previous')}
              </button>
              <button
                onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
                disabled={page >= data.totalPages - 1}
                className="btn-outline py-1 px-2.5 text-xs flex items-center gap-1 disabled:opacity-40"
              >
                {t('common.next')} <ChevronRight className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
