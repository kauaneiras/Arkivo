import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../../lib/api';
import { useI18n, formatDate } from '../../lib/i18n';
import { Modal, ReviewStatusBadge, EmptyState, TableSkeleton, useToast, ConfirmModal } from '../../components/ui';
import { useAuthStore } from '../../store/authStore';
import { porqueNaoPodeAprovar, porqueNaoPodeEditar } from '../../lib/permissoes';
import DiffViewer from '../../components/DiffViewer';
import { GitPullRequest, Check, X, ExternalLink, Diff, Undo2, MessageSquareText } from 'lucide-react';
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
  documentCreatedBy?: string | null;
  assignedReviewerId?: string | null;
  documentGroupId?: string | null;
  documentCurrentCommitId?: string | null;
  documentOfficialCommitId?: string | null;
  /** The exact version submitted, and the version it was built on. */
  reviewedCommitId?: string | null;
  baseCommitId?: string | null;
  decision?: string | null;
  decisionComments?: string | null;
  reviewerName?: string | null;
  decidedAt?: string | null;
}

export default function ReviewsPage() {
  const [tab, setTab] = useState<'pending' | 'history'>('pending');
  const [reviews, setReviews] = useState<ReviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [decisionTarget, setDecisionTarget] = useState<{ review: ReviewItem; decision: 'APPROVED' | 'REJECTED' } | null>(null);
  const [diffTarget, setDiffTarget] = useState<ReviewItem | null>(null);
  const [withdrawTarget, setWithdrawTarget] = useState<ReviewItem | null>(null);
  const [comments, setComments] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const { t, lang } = useI18n();
  const { toast } = useToast();
  const { user } = useAuthStore();

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
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message ?? t('reviews.decisionError'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleWithdraw = async () => {
    if (!withdrawTarget) return;
    try {
      await api.post(`/reviews/${withdrawTarget.id}/withdraw`);
      toast('success', t('editor.withdrawSuccess'));
      setWithdrawTarget(null);
      fetchReviews();
    } catch (err: any) {
      toast('error', err.response?.data?.message ?? t('common.error'));
    }
  };

  /** The pair a review compares: the version in force when submitted vs the one proposed. */
  const diffPair = (r: ReviewItem) => ({
    from: r.documentOfficialCommitId && r.documentOfficialCommitId !== r.reviewedCommitId
      ? r.documentOfficialCommitId : (r.baseCommitId ?? undefined),
    to: r.reviewedCommitId ?? r.documentCurrentCommitId ?? undefined,
  });

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
              tab === key ? 'border-primary text-primary' : 'border-transparent text-textMuted hover:text-textMain'
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
                reviews.map((review) => {
                  const recusa = porqueNaoPodeAprovar(user, {
                    createdBy: review.documentCreatedBy,
                    assignedReviewerId: review.assignedReviewerId,
                    documentGroupId: review.documentGroupId,
                  });
                  const podeRetirar = !porqueNaoPodeEditar(user, {
                    createdBy: review.documentCreatedBy,
                    assignedReviewerId: review.assignedReviewerId,
                    documentGroupId: review.documentGroupId,
                  });
                  const motivo = recusa ? t(recusa) : undefined;
                  return (
                    <tr key={review.id} className="hover:bg-surface/50 transition-colors">
                      <td className="px-6 py-4 font-medium text-textMain whitespace-nowrap">{review.documentCode}</td>
                      <td className="px-6 py-4 text-textMain">
                        <p className="font-medium">{review.title}</p>
                        {review.description && (
                          <p className="text-xs text-textMuted mt-0.5 line-clamp-1">{review.description}</p>
                        )}
                        <p className="text-xs text-textMuted mt-0.5">{review.documentTitle}</p>
                        {review.decision && (
                          <p className="text-xs mt-1 flex items-start gap-1 text-textMuted">
                            <MessageSquareText className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                            <span>
                              <span className="font-medium text-textMain">{review.reviewerName}</span>
                              {review.decidedAt && <span> · {formatDate(review.decidedAt, lang)}</span>}
                              {review.decisionComments && <span className="block italic line-clamp-2">{review.decisionComments}</span>}
                            </span>
                          </p>
                        )}
                      </td>
                      <td className="px-6 py-4 text-textMuted whitespace-nowrap">{review.requestedByName}</td>
                      <td className="px-6 py-4 text-textMuted whitespace-nowrap">{formatDate(review.createdAt, lang)}</td>
                      <td className="px-6 py-4"><ReviewStatusBadge status={review.status} /></td>
                      <td className="px-6 py-4">
                        <div className="flex items-center justify-end gap-2">
                          <button
                            onClick={() => setDiffTarget(review)}
                            className="p-1.5 text-textMuted hover:text-primary hover:bg-blue-50 rounded-md transition-colors"
                            title={t('reviews.seeChanges')}
                          >
                            <Diff className="w-4 h-4" />
                          </button>
                          <button
                            onClick={() => navigate(`/dashboard/documents/${review.documentId}/edit?panel=history`)}
                            className="p-1.5 text-textMuted hover:text-primary hover:bg-blue-50 rounded-md transition-colors"
                            title={t('reviews.openEditor')}
                          >
                            <ExternalLink className="w-4 h-4" />
                          </button>
                          {review.status === 'OPEN' && (
                            <>
                              {podeRetirar && (
                                <button
                                  onClick={() => setWithdrawTarget(review)}
                                  className="p-1.5 text-textMuted hover:text-amber-700 hover:bg-amber-50 rounded-md transition-colors"
                                  title={t('editor.withdrawReview')}
                                >
                                  <Undo2 className="w-4 h-4" />
                                </button>
                              )}
                              <button
                                onClick={() => { setDecisionTarget({ review, decision: 'APPROVED' }); setComments(''); }}
                                disabled={!!recusa}
                                title={motivo}
                                className={cn(
                                  'flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-secondary rounded-md transition-colors',
                                  recusa ? 'opacity-40 cursor-not-allowed' : 'hover:bg-secondary-dark',
                                )}
                              >
                                <Check className="w-3.5 h-3.5" /> {t('reviews.approve')}
                              </button>
                              <button
                                onClick={() => { setDecisionTarget({ review, decision: 'REJECTED' }); setComments(''); }}
                                disabled={!!recusa}
                                title={motivo}
                                className={cn(
                                  'flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 border border-red-200 rounded-md transition-colors',
                                  recusa ? 'opacity-40 cursor-not-allowed' : 'hover:bg-red-100',
                                )}
                              >
                                <X className="w-3.5 h-3.5" /> {t('reviews.reject')}
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Decision modal — the diff is right here, before deciding */}
      <Modal
        isOpen={!!decisionTarget}
        onClose={() => setDecisionTarget(null)}
        title={t('reviews.decisionTitle')}
        wide
        footer={
          <>
            <button onClick={() => setDecisionTarget(null)} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button
              onClick={handleDecision}
              disabled={submitting}
              className={cn(
                'px-4 py-2 text-sm font-medium text-white rounded-md transition-colors disabled:opacity-50',
                decisionTarget?.decision === 'APPROVED' ? 'bg-secondary hover:bg-secondary-dark' : 'bg-red-600 hover:bg-red-700'
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
              {decisionTarget.review.description && (
                <p className="text-textMuted mt-1 text-xs whitespace-pre-wrap">{decisionTarget.review.description}</p>
              )}
              <p className="text-xs text-textMuted mt-2">
                {t('reviews.requestedBy')}: {decisionTarget.review.requestedByName} · {formatDate(decisionTarget.review.createdAt, lang)}
              </p>
            </div>
            {decisionTarget.decision === 'APPROVED' && (
              <p className="text-xs text-green-800 bg-green-50 border border-green-200 rounded px-3 py-2">{t('reviews.approveEffect')}</p>
            )}
            <div className="space-y-2">
              <p className="text-sm font-medium text-textMain">{t('diff.title')}</p>
              <DiffViewer
                documentId={decisionTarget.review.documentId}
                {...diffPair(decisionTarget.review)}
                height="40vh"
                caption={t('diff.againstOfficial')}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('reviews.comments')}</label>
              <textarea
                value={comments}
                onChange={(e) => setComments(e.target.value)}
                rows={3}
                className="input-field resize-none"
                placeholder={t('reviews.commentsPlaceholder')}
              />
            </div>
          </div>
        )}
      </Modal>

      {/* Just the diff, for anyone who can read the document */}
      <Modal
        isOpen={!!diffTarget}
        onClose={() => setDiffTarget(null)}
        title={<span className="flex items-center gap-2"><Diff className="w-5 h-5 text-primary" />{diffTarget?.documentCode} — {t('diff.title')}</span>}
        wide
      >
        {diffTarget && (
          <DiffViewer documentId={diffTarget.documentId} {...diffPair(diffTarget)} height="65vh"
                      caption={`${diffTarget.title} — ${t('diff.againstOfficial')}`} />
        )}
      </Modal>

      <ConfirmModal
        isOpen={!!withdrawTarget}
        onClose={() => setWithdrawTarget(null)}
        onConfirm={handleWithdraw}
        title={t('editor.withdrawTitle')}
        body={t('editor.withdrawBody')}
        confirmLabel={t('editor.withdrawReview')}
        danger
      />
    </div>
  );
}
