import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../../lib/api';
import { useI18n, formatDate } from '../../lib/i18n';
import { Modal, ReviewStatusBadge, EmptyState, TableSkeleton, useToast } from '../../components/ui';
import { useAuthStore } from '../../store/authStore';
import { GitPullRequest, Check, X, ExternalLink } from 'lucide-react';
import { cn } from '../../lib/utils';

interface ReviewItem {
  id: string;
  documentId: string;
  documentCode: string;
  documentTitle: string;
  title: string;
  description: string | null;
  status: string;
  requestedByName: string;
  createdAt: string;
}

export default function ReviewsPage() {
  const [tab, setTab] = useState<'pending' | 'history'>('pending');
  const [reviews, setReviews] = useState<ReviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [decisionTarget, setDecisionTarget] = useState<{ review: ReviewItem; decision: 'APPROVED' | 'REJECTED' } | null>(null);
  const [comments, setComments] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const { user } = useAuthStore();

  const canApprove = user?.roles?.some((r) => r === 'ROLE_ADMIN' || r === 'ROLE_REVIEWER' || r === 'REVIEW_APPROVE');

  const fetchReviews = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get(tab === 'pending' ? '/reviews?status=OPEN' : '/reviews');
      const items: ReviewItem[] = res.data;
      setReviews(tab === 'pending' ? items : items.filter((r) => r.status !== 'OPEN'));
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => { fetchReviews(); }, [fetchReviews]);

  const handleDecision = async () => {
    if (!decisionTarget) return;
    if (decisionTarget.decision === 'REJECTED' && !comments.trim()) {
      toast('error', t('reviews.commentRequired'));
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/reviews/${decisionTarget.review.id}/decision`, {
        decision: decisionTarget.decision,
        comments: comments.trim() || null,
      });
      toast('success', t(decisionTarget.decision === 'APPROVED' ? 'reviews.approveSuccess' : 'reviews.rejectSuccess'));
      setDecisionTarget(null);
      setComments('');
      fetchReviews();
    } catch (err) {
      console.error(err);
      toast('error', t('reviews.decisionError'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="flex flex-col h-full gap-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-textMain">{t('reviews.title')}</h1>
          <p className="text-textMuted text-sm mt-1">{t('reviews.subtitle')}</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-borderLight">
        {(['pending', 'history'] as const).map((key) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={cn(
              'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
              tab === key
                ? 'border-primary text-primary'
                : 'border-transparent text-textMuted hover:text-textMain'
            )}
          >
            {t(key === 'pending' ? 'reviews.pending' : 'reviews.history')}
          </button>
        ))}
      </div>

      <div className="card flex-1 flex flex-col p-0 overflow-hidden">
        <div className="overflow-x-auto flex-1">
          <table className="w-full text-sm text-left">
            <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold sticky top-0">
              <tr>
                <th className="px-6 py-4">{t('reviews.document')}</th>
                <th className="px-6 py-4">{t('docs.docTitle')}</th>
                <th className="px-6 py-4">{t('reviews.requestedBy')}</th>
                <th className="px-6 py-4">{t('reviews.requestedAt')}</th>
                <th className="px-6 py-4">{t('docs.status')}</th>
                <th className="px-6 py-4 text-right">{t('common.actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-borderLight">
              {loading ? (
                <TableSkeleton cols={6} />
              ) : reviews.length === 0 ? (
                <tr>
                  <td colSpan={6}>
                    <EmptyState
                      icon={<GitPullRequest className="w-10 h-10 mb-3 opacity-40" />}
                      message={t(tab === 'pending' ? 'reviews.emptyPending' : 'reviews.emptyHistory')}
                    />
                  </td>
                </tr>
              ) : (
                reviews.map((review) => (
                  <tr key={review.id} className="hover:bg-surface/50 transition-colors">
                    <td className="px-6 py-4 font-medium text-textMain whitespace-nowrap">{review.documentCode}</td>
                    <td className="px-6 py-4 text-textMain">
                      <p className="font-medium">{review.title}</p>
                      {review.description && (
                        <p className="text-xs text-textMuted mt-0.5 line-clamp-1">{review.description}</p>
                      )}
                      <p className="text-xs text-textMuted mt-0.5">{review.documentTitle}</p>
                    </td>
                    <td className="px-6 py-4 text-textMuted whitespace-nowrap">{review.requestedByName}</td>
                    <td className="px-6 py-4 text-textMuted whitespace-nowrap">{formatDate(review.createdAt, lang)}</td>
                    <td className="px-6 py-4"><ReviewStatusBadge status={review.status} /></td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-end gap-2">
                        <button
                          onClick={() => navigate(`/dashboard/documents/${review.documentId}/edit`)}
                          className="p-1.5 text-textMuted hover:text-primary hover:bg-blue-50 rounded-md transition-colors"
                          title={t('reviews.openEditor')}
                        >
                          <ExternalLink className="w-4 h-4" />
                        </button>
                        {review.status === 'OPEN' && canApprove && (
                          <>
                            <button
                              onClick={() => { setDecisionTarget({ review, decision: 'APPROVED' }); setComments(''); }}
                              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-secondary hover:bg-secondary-dark rounded-md transition-colors"
                            >
                              <Check className="w-3.5 h-3.5" /> {t('reviews.approve')}
                            </button>
                            <button
                              onClick={() => { setDecisionTarget({ review, decision: 'REJECTED' }); setComments(''); }}
                              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 hover:bg-red-100 border border-red-200 rounded-md transition-colors"
                            >
                              <X className="w-3.5 h-3.5" /> {t('reviews.reject')}
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Decision modal */}
      <Modal
        isOpen={!!decisionTarget}
        onClose={() => setDecisionTarget(null)}
        title={t('reviews.decisionTitle')}
        footer={
          <>
            <button
              onClick={() => setDecisionTarget(null)}
              className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors"
            >
              {t('common.cancel')}
            </button>
            <button
              onClick={handleDecision}
              disabled={submitting}
              className={cn(
                'px-4 py-2 text-sm font-medium text-white rounded-md transition-colors disabled:opacity-50',
                decisionTarget?.decision === 'APPROVED'
                  ? 'bg-secondary hover:bg-secondary-dark'
                  : 'bg-red-600 hover:bg-red-700'
              )}
            >
              {t(decisionTarget?.decision === 'APPROVED' ? 'reviews.approve' : 'reviews.reject')}
            </button>
          </>
        }
      >
        {decisionTarget && (
          <div className="space-y-4">
            <div className="bg-surface rounded-lg p-4 text-sm">
              <p className="font-semibold text-textMain">{decisionTarget.review.documentCode} — {decisionTarget.review.documentTitle}</p>
              <p className="text-textMuted mt-1">{decisionTarget.review.title}</p>
              <p className="text-xs text-textMuted mt-2">
                {t('reviews.requestedBy')}: {decisionTarget.review.requestedByName} · {formatDate(decisionTarget.review.createdAt, lang)}
              </p>
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('reviews.comments')}</label>
              <textarea
                value={comments}
                onChange={(e) => setComments(e.target.value)}
                rows={4}
                className="input-field resize-none"
                placeholder={t('reviews.commentsPlaceholder')}
              />
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
