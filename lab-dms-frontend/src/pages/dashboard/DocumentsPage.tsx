import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../../lib/api';
import VisibilityModal from '../../components/VisibilityModal';
import { Plus, Search, Edit3, Eye, Archive, FileText, History, AlertTriangle, Download, CalendarClock, Globe, Building2, Lock } from 'lucide-react';
import CreateDocumentModal from './CreateDocumentModal';
import { useI18n, formatDate } from '../../lib/i18n';
import { DocStatusBadge, ConfirmModal, EmptyState, TableSkeleton, useToast } from '../../components/ui';
import { useAuthStore } from '../../store/authStore';
import { cn } from '../../lib/utils';
import { porqueNaoPodeEditar } from '../../lib/permissoes';

const DOC_TYPES = ['IT', 'PQ', 'PO', 'MQ'];
const DOC_STATUSES = ['DRAFT', 'IN_REVIEW', 'ACTIVE', 'OBSOLETE'];

export default function DocumentsPage() {
  // Which document's read access is being changed.
  const [visibilityOf, setVisibilityOf] = useState<any | null>(null);
  const [documents, setDocuments] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  // Espelho da regra do servidor, não a regra em si: quem não tem a permissão
  // também é recusado pelo endpoint. Aqui só evitamos oferecer o que vai falhar.
  const user = useAuthStore((s) => s.user);
  const canCreate = useAuthStore((s) => s.can('DOCUMENT_CREATE'));
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [authorFilter, setAuthorFilter] = useState('');
  const [reviewerFilter, setReviewerFilter] = useState('');
  const [people, setPeople] = useState<any[]>([]);
  const [reviewers, setReviewers] = useState<any[]>([]);
  const [obsoleteTarget, setObsoleteTarget] = useState<any | null>(null);
  const [obsoleting, setObsoleting] = useState(false);
  const navigate = useNavigate();
  const { t, lang } = useI18n();
  const { toast } = useToast();

  const fetchDocuments = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (searchQuery) params.append('title', searchQuery);
      if (statusFilter) params.append('status', statusFilter);
      if (typeFilter) params.append('docType', typeFilter);
      if (authorFilter) params.append('createdBy', authorFilter);
      if (reviewerFilter) params.append('assignedReviewerId', reviewerFilter);
      params.append('size', '50');

      const res = await api.get(`/documents?${params.toString()}`);
      setDocuments(res.data.content || res.data.data || []);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [searchQuery, statusFilter, typeFilter, authorFilter, reviewerFilter]);

  useEffect(() => {
    const timer = setTimeout(fetchDocuments, searchQuery ? 300 : 0);
    return () => clearTimeout(timer);
  }, [fetchDocuments, searchQuery]);

  // Populates the author/reviewer pickers. The full user list is admin-only, so
  // non-admins fall back to the reviewer list plus whoever authored a document
  // they can already see — the filter still works without leaking the directory.
  useEffect(() => {
    api.get('/users/reviewers')
      .then((res) => setReviewers(res.data))
      .catch(() => setReviewers([]));
    api.get('/users?status=ACTIVE')
      .then((res) => setPeople(res.data))
      .catch(() => setPeople([]));
  }, []);

  const authorOptions = useMemo(() => {
    if (people.length > 0) return people;
    const seen = new Map<string, { id: string; fullName: string }>();
    documents.forEach((d) => {
      if (d.createdBy && d.authorName && !seen.has(d.createdBy)) {
        seen.set(d.createdBy, { id: d.createdBy, fullName: d.authorName });
      }
    });
    return Array.from(seen.values());
  }, [people, documents]);

  /** ISO 17025 mandatory record: controlled index handed to the assessor. */
  const handleExportMasterList = async () => {
    try {
      const res = await api.get('/documents/master-list', { responseType: 'blob' });
      const url = URL.createObjectURL(res.data);
      const a = document.createElement('a');
      a.href = url;
      a.download = `lista-mestra-${new Date().toISOString().slice(0, 10)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error(err);
      toast('error', t('common.error'));
    }
  };

  const handleObsolete = async () => {
    if (!obsoleteTarget) return;
    setObsoleting(true);
    try {
      await api.patch(`/documents/${obsoleteTarget.id}/status`, { status: 'OBSOLETE' });
      toast('success', t('docs.obsoleteSuccess'));
      setObsoleteTarget(null);
      fetchDocuments();
    } catch (err) {
      console.error(err);
      toast('error', t('docs.obsoleteError'));
    } finally {
      setObsoleting(false);
    }
  };

  return (
    <div className="flex flex-col h-full gap-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-textMain">{t('docs.title')}</h1>
          <p className="text-textMuted text-sm mt-1">{t('docs.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={handleExportMasterList} className="btn-outline flex items-center gap-2">
            <Download className="w-4 h-4" />
            <span className="hidden lg:inline">{t('docs.exportMasterList')}</span>
          </button>
          {/* Desativado, não escondido.
              Um botão que desaparece deixa a pessoa a procurar uma função que
              lhe disseram existir; um botão apagado que explica o motivo ao
              passar o rato responde à pergunta sem ela ter de perguntar a
              ninguém. `disabled` também tira o elemento da navegação por
              teclado, então não é só o rato que fica impedido. */}
          <button
            onClick={() => setIsModalOpen(true)}
            disabled={!canCreate}
            title={canCreate ? undefined : t('docs.newDenied')}
            className={cn(
              'btn-primary flex items-center gap-2',
              !canCreate && 'opacity-50 cursor-not-allowed',
            )}
          >
            <Plus className="w-4 h-4" />
            {t('docs.new')}
          </button>
        </div>
      </div>

      <div className="card flex-1 flex flex-col p-0 overflow-hidden">
        <div className="p-4 border-b border-borderLight flex flex-wrap gap-4 bg-gray-50/50">
          <div className="relative flex-1 min-w-[200px] max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-textMuted" />
            <input
              type="text"
              placeholder={t('docs.searchPlaceholder')}
              className="input-field pl-9 h-9 text-sm"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <div className="flex gap-2">
            <select
              className="input-field h-9 text-sm py-0 w-40"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="">{t('docs.allStatus')}</option>
              {DOC_STATUSES.map((s) => (
                <option key={s} value={s}>{t(`status.${s}`)}</option>
              ))}
            </select>
            <select
              className="input-field h-9 text-sm py-0 w-36"
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
            >
              <option value="">{t('docs.allTypes')}</option>
              {DOC_TYPES.map((type) => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
            <select
              className="input-field h-9 text-sm py-0 w-44"
              value={authorFilter}
              onChange={(e) => setAuthorFilter(e.target.value)}
            >
              <option value="">{t('docs.allAuthors')}</option>
              {authorOptions.map((u: any) => (
                <option key={u.id} value={u.id}>{u.fullName}</option>
              ))}
            </select>
            <select
              className="input-field h-9 text-sm py-0 w-44"
              value={reviewerFilter}
              onChange={(e) => setReviewerFilter(e.target.value)}
            >
              <option value="">{t('docs.allReviewers')}</option>
              {reviewers.map((u: any) => (
                <option key={u.id} value={u.id}>{u.fullName}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="overflow-x-auto flex-1">
          <table className="w-full text-sm text-left">
            <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold sticky top-0">
              <tr>
                <th className="px-6 py-4">{t('docs.code')}</th>
                <th className="px-6 py-4">{t('docs.docTitle')}</th>
                <th className="px-6 py-4">{t('docs.type')}</th>
                <th className="px-6 py-4">{t('docs.status')}</th>
                <th className="px-6 py-4">{t('docs.author')}</th>
                <th className="px-6 py-4">{t('docs.reviewer')}</th>
                <th className="px-6 py-4">{t('docs.updatedAt')}</th>
                <th className="px-6 py-4 text-right">{t('common.actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-borderLight">
              {loading ? (
                <TableSkeleton cols={8} />
              ) : documents.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <EmptyState
                      icon={<FileText className="w-10 h-10 mb-3 opacity-40" />}
                      message={t('docs.empty')}
                    />
                  </td>
                </tr>
              ) : (
                documents.map((doc) => (
                  <tr
                    key={doc.id}
                    className="hover:bg-surface/50 transition-colors group cursor-pointer"
                    onClick={() => navigate(`/dashboard/documents/${doc.id}/edit`)}
                  >
                    <td className="px-6 py-4 font-medium text-textMain whitespace-nowrap">{doc.code}</td>
                    <td className="px-6 py-4 text-textMain">{doc.title}</td>
                    <td className="px-6 py-4 text-textMuted">{doc.docType}</td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-2">
                        <DocStatusBadge status={doc.status} />
                        {doc.officialIsLatest === false && (
                          <span title={t('docs.officialOutdated')}>
                            <AlertTriangle className="w-3.5 h-3.5 text-amber-600" />
                          </span>
                        )}
                        {doc.reviewOverdue && (
                          <span title={t('review.overdue')}>
                            <CalendarClock className="w-3.5 h-3.5 text-red-600" />
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-6 py-4 text-textMuted whitespace-nowrap">{doc.authorName || '—'}</td>
                    <td className="px-6 py-4 text-textMuted whitespace-nowrap">
                      {doc.reviewerName || t('docs.noReviewer')}
                    </td>
                    <td className="px-6 py-4 text-textMuted whitespace-nowrap">
                      {doc.updatedAt ? formatDate(doc.updatedAt, lang) : '—'}
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                        <button
                          onClick={(e) => { e.stopPropagation(); setVisibilityOf(doc); }}
                          className="text-textMuted hover:text-primary transition-colors"
                          title={t('privacy.label')}
                        >
                          {doc.visibility === 'PUBLIC' ? <Globe className="w-4 h-4" />
                            : doc.visibility === 'RESTRICTED' ? <Lock className="w-4 h-4" />
                            : <Building2 className="w-4 h-4" />}
                        </button>
                        <button
                          onClick={(e) => { e.stopPropagation(); navigate(`/dashboard/documents/${doc.id}/edit?panel=history`); }}
                          className="p-1.5 text-textMuted hover:text-primary hover:bg-blue-50 rounded-md transition-colors"
                          title={t('editor.commitHistory')}
                        >
                          <History className="w-4 h-4" />
                        </button>
                        {/* Este botão ABRE o documento — e o editor é também o
                            leitor. Desativá-lo para quem não pode escrever
                            tiraria a leitura a quem tem direito a ela, que é
                            pior do que oferecer um lápis enganador. Em vez de o
                            bloquear, ele diz a verdade: lápis para quem edita,
                            olho para quem só lê. Quem entra sem poder escrever
                            encontra os botões de gravar e submeter desativados,
                            com o motivo. */}
                        {(() => {
                          const soLeitura = !!porqueNaoPodeEditar(user, {
                            createdBy: doc.createdBy,
                            assignedReviewerId: doc.assignedReviewerId,
                            documentGroupId: doc.documentGroupId,
                          });
                          return (
                            <button
                              onClick={(e) => { e.stopPropagation(); navigate(`/dashboard/documents/${doc.id}/edit`); }}
                              className="p-1.5 text-textMuted hover:text-primary hover:bg-blue-50 rounded-md transition-colors"
                              title={soLeitura ? t('docs.openReadOnly') : t('docs.edit')}
                              aria-label={soLeitura ? t('docs.openReadOnly') : t('docs.edit')}
                            >
                              {soLeitura ? <Eye className="w-4 h-4" /> : <Edit3 className="w-4 h-4" />}
                            </button>
                          );
                        })()}
                        {doc.status !== 'OBSOLETE' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); setObsoleteTarget(doc); }}
                            className="p-1.5 text-textMuted hover:text-red-600 hover:bg-red-50 rounded-md transition-colors"
                            title={t('docs.obsolete')}
                          >
                            <Archive className="w-4 h-4" />
                          </button>
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

      <VisibilityModal
        documentId={visibilityOf?.id ?? null}
        documentCode={visibilityOf?.code ?? ''}
        onClose={() => setVisibilityOf(null)}
        onSaved={() => fetchDocuments()}
      />

      <CreateDocumentModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSuccess={() => {
          setIsModalOpen(false);
          fetchDocuments();
        }}
      />

      <ConfirmModal
        isOpen={!!obsoleteTarget}
        onClose={() => setObsoleteTarget(null)}
        onConfirm={handleObsolete}
        title={t('docs.obsoleteConfirmTitle')}
        body={t('docs.obsoleteConfirmBody')}
        confirmLabel={t('docs.obsolete')}
        danger
        loading={obsoleting}
      />
    </div>
  );
}
