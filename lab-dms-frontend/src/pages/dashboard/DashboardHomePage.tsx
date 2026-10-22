import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../../lib/api';
import { useI18n, formatDate } from '../../lib/i18n';
import { DocStatusBadge, EmptyState } from '../../components/ui';
import {
  FileText, GitPullRequest, Users, History, ArrowRight, Activity, HardDrive,
} from 'lucide-react';
import { motion } from 'framer-motion';
import { useAuthStore } from '../../store/authStore';
import { humanBytes } from '../../components/DiffViewer';

interface ActivityItem {
  userName: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  details: string | null;
  createdAt: string;
}

interface Stats {
  totalDocuments: number;
  documentsByStatus: Record<string, number>;
  documentsByType: Record<string, number>;
  openReviews: number;
  totalUsers: number;
  pendingUsers: number;
  totalCommits: number;
  recentActivity: ActivityItem[];
}

const STATUS_ORDER = ['DRAFT', 'IN_REVIEW', 'ACTIVE', 'OBSOLETE'];
const STATUS_BAR_COLORS: Record<string, string> = {
  DRAFT: 'bg-gray-400',
  IN_REVIEW: 'bg-amber-400',
  ACTIVE: 'bg-green-500',
  OBSOLETE: 'bg-red-400',
};

export default function DashboardHomePage() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [storage, setStorage] = useState<{ databaseBytes: number; storedBytes: number; attachmentBytes: number; dedupSavingsPercent: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const { t, lang } = useI18n();
  const isAdmin = useAuthStore((s) => s.can('ROLE_ADMIN'));

  useEffect(() => {
    api.get('/dashboard/stats')
      .then((res) => setStats(res.data))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  // Storage is an administrator's concern; the endpoint is admin-only too.
  useEffect(() => {
    if (!isAdmin) return;
    api.get('/storage/overview').then((res) => setStorage(res.data)).catch(() => setStorage(null));
  }, [isAdmin]);

  const cards = stats ? [
    { label: t('dashboard.totalDocuments'), value: stats.totalDocuments, icon: FileText, color: 'text-primary bg-blue-50', onClick: () => navigate('/dashboard/documents') },
    { label: t('dashboard.openReviews'), value: stats.openReviews, icon: GitPullRequest, color: 'text-amber-600 bg-amber-50', onClick: () => navigate('/dashboard/reviews') },
    { label: t('dashboard.totalCommits'), value: stats.totalCommits, icon: History, color: 'text-secondary bg-green-50' },
    { label: t('dashboard.activeUsers'), value: stats.totalUsers, icon: Users, color: 'text-violet-600 bg-violet-50', onClick: () => navigate('/dashboard/users') },
    ...(storage ? [{
      label: t('dashboard.storage'),
      value: humanBytes(storage.databaseBytes + storage.attachmentBytes) as string | number,
      hint: t('dashboard.storageHint').replace('{pct}', String(storage.dedupSavingsPercent)),
      icon: HardDrive, color: 'text-amber-600 bg-amber-50',
      onClick: () => navigate('/dashboard/settings?tab=storage'),
    }] : []),
  ] : [];

  const maxStatus = stats ? Math.max(1, ...Object.values(stats.documentsByStatus)) : 1;
  const maxType = stats ? Math.max(1, ...Object.values(stats.documentsByType)) : 1;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold text-textMain">{t('dashboard.title')}</h1>
        <p className="text-textMuted text-sm mt-1">{t('dashboard.subtitle')}</p>
      </div>

      {/* Stat cards */}
      <div className={`grid grid-cols-2 gap-4 ${cards.length > 4 ? 'lg:grid-cols-5' : 'lg:grid-cols-4'}`}>
        {loading
          ? Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="card h-24 animate-pulse bg-gray-50" />
            ))
          : cards.map((card, i) => (
              <motion.button
                key={card.label}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.05 }}
                onClick={card.onClick}
                disabled={!card.onClick}
                className="card flex items-center gap-4 text-left disabled:cursor-default hover:border-blue-200"
              >
                <div className={`w-11 h-11 rounded-lg flex items-center justify-center shrink-0 ${card.color}`}>
                  <card.icon className="w-5 h-5" />
                </div>
                <div className="min-w-0">
                  <p className="text-2xl font-bold text-textMain leading-none truncate">{card.value}</p>
                  <p className="text-xs text-textMuted mt-1.5">{card.label}</p>
                  {'hint' in card && card.hint && <p className="text-[10px] text-textMuted/80 mt-0.5">{card.hint}</p>}
                </div>
              </motion.button>
            ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Documents by status */}
        <div className="card">
          <h2 className="font-semibold text-textMain mb-4">{t('dashboard.byStatus')}</h2>
          <div className="space-y-3">
            {STATUS_ORDER.map((status) => {
              const count = stats?.documentsByStatus[status] ?? 0;
              return (
                <div key={status}>
                  <div className="flex justify-between items-center mb-1">
                    <DocStatusBadge status={status} />
                    <span className="text-sm font-semibold text-textMain">{count}</span>
                  </div>
                  <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                    <motion.div
                      initial={{ width: 0 }}
                      animate={{ width: `${(count / maxStatus) * 100}%` }}
                      transition={{ duration: 0.6, ease: 'easeOut' }}
                      className={`h-full rounded-full ${STATUS_BAR_COLORS[status]}`}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Documents by type */}
        <div className="card">
          <h2 className="font-semibold text-textMain mb-4">{t('dashboard.byType')}</h2>
          {stats && Object.keys(stats.documentsByType).length > 0 ? (
            <div className="space-y-3">
              {Object.entries(stats.documentsByType).map(([type, count]) => (
                <div key={type}>
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-sm font-medium text-textMain">{type}</span>
                    <span className="text-sm font-semibold text-textMain">{count}</span>
                  </div>
                  <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                    <motion.div
                      initial={{ width: 0 }}
                      animate={{ width: `${(count / maxType) * 100}%` }}
                      transition={{ duration: 0.6, ease: 'easeOut' }}
                      className="h-full rounded-full bg-primary"
                    />
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState message={t('common.none')} />
          )}
        </div>

        {/* Recent activity */}
        <div className="card flex flex-col">
          <div className="flex items-center justify-between mb-4">
            <h2 className="font-semibold text-textMain flex items-center gap-2">
              <Activity className="w-4 h-4 text-primary" />
              {t('dashboard.recentActivity')}
            </h2>
            <button
              onClick={() => navigate('/dashboard/audit')}
              className="text-xs text-primary font-medium flex items-center gap-1 hover:underline"
            >
              {t('dashboard.viewAll')} <ArrowRight className="w-3 h-3" />
            </button>
          </div>
          {stats && stats.recentActivity.length > 0 ? (
            <div className="space-y-3 overflow-y-auto max-h-72 pr-1">
              {stats.recentActivity.map((item, i) => (
                <div key={i} className="flex items-start gap-3 text-sm">
                  <div className="w-7 h-7 rounded-full bg-blue-50 text-primary flex items-center justify-center font-semibold text-xs shrink-0 mt-0.5">
                    {(item.userName ?? 'S').charAt(0)}
                  </div>
                  <div className="min-w-0">
                    <p className="text-textMain leading-snug">
                      <span className="font-medium">{item.userName ?? t('audit.system')}</span>{' '}
                      <span className="text-textMuted">{t(`action.${item.action}`)}</span>
                    </p>
                    <p className="text-xs text-textMuted mt-0.5">{formatDate(item.createdAt, lang)}</p>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState message={t('dashboard.noActivity')} />
          )}
        </div>
      </div>
    </div>
  );
}
