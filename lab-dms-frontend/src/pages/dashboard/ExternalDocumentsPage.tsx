import { useState, useEffect, useCallback } from 'react';
import api from '../../lib/api';
import { Plus, Search, Pencil, Trash2, BadgeCheck, AlertTriangle, BookMarked } from 'lucide-react';
import { useI18n, formatDate } from '../../lib/i18n';
import { EmptyState, TableSkeleton, Modal, ConfirmModal, useToast } from '../../components/ui';
import { cn } from '../../lib/utils';

/**
 * ISO/IEC 17025 8.3.1 — control of EXTERNAL documents.
 *
 * These are not authored by the laboratory, so there is no revision history to
 * keep. What is controlled is which EDITION is in force and evidence that
 * somebody confirmed it is still current.
 */

const KINDS = ['STANDARD', 'MANUAL', 'CALIBRATION_TABLE', 'REGULATION', 'DATASHEET', 'OTHER'];
const STATUSES = ['ACTIVE', 'SUPERSEDED', 'WITHDRAWN'];

interface ExternalDoc {
  id: string;
  code: string;
  title: string;
  issuer: string | null;
  edition: string | null;
  docKind: string;
  publicationDate: string | null;
  lastVerifiedAt: string | null;
  lastVerifiedByName: string | null;
  verificationIntervalMonths: number | null;
  nextVerificationDate: string | null;
  verificationOverdue: boolean;
  ownerId: string | null;
  ownerName: string | null;
  location: string | null;
  notes: string | null;
  status: string;
}

const EMPTY_FORM = {
  code: '', title: '', issuer: '', edition: '', docKind: 'STANDARD',
  publicationDate: '', verificationIntervalMonths: 12, ownerId: '',
  location: '', notes: '', status: 'ACTIVE',
};

export default function ExternalDocumentsPage() {
  const { t, lang } = useI18n();
  const { toast } = useToast();

  const [docs, setDocs] = useState<ExternalDoc[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [kindFilter, setKindFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [people, setPeople] = useState<any[]>([]);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ExternalDoc | null>(null);
  const [form, setForm] = useState({ ...EMPTY_FORM });
  const [saving, setSaving] = useState(false);
  const [toDelete, setToDelete] = useState<ExternalDoc | null>(null);

  const fetchDocs = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (search) params.append('search', search);
      if (kindFilter) params.append('docKind', kindFilter);
      if (statusFilter) params.append('status', statusFilter);
      const res = await api.get(`/external-documents?${params.toString()}`);
      setDocs(res.data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [search, kindFilter, statusFilter]);

  useEffect(() => {
    const timer = setTimeout(fetchDocs, search ? 300 : 0);
    return () => clearTimeout(timer);
  }, [fetchDocs, search]);

  useEffect(() => {
    api.get('/users/reviewers').then((r) => setPeople(r.data)).catch(() => setPeople([]));
  }, []);

  const openCreate = () => {
    setEditing(null);
    setForm({ ...EMPTY_FORM });
    setModalOpen(true);
  };

  const openEdit = (d: ExternalDoc) => {
    setEditing(d);
    setForm({
      code: d.code, title: d.title, issuer: d.issuer ?? '', edition: d.edition ?? '',
      docKind: d.docKind, publicationDate: d.publicationDate ?? '',
      verificationIntervalMonths: d.verificationIntervalMonths ?? 12,
      ownerId: d.ownerId ?? '', location: d.location ?? '', notes: d.notes ?? '',
      status: d.status,
    });
    setModalOpen(true);
  };

  const handleSave = async () => {
    if (!form.code.trim() || !form.title.trim()) {
      toast('error', t('common.required'));
      return;
    }
    setSaving(true);
    try {
      const payload = {
        ...form,
        publicationDate: form.publicationDate || null,
        ownerId: form.ownerId || null,
        verificationIntervalMonths: Number(form.verificationIntervalMonths) || 12,
      };
      if (editing) {
        await api.put(`/external-documents/${editing.id}`, payload);
      } else {
        await api.post('/external-documents', payload);
      }
      toast('success', t('ext.saveSuccess'));
      setModalOpen(false);
      fetchDocs();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('ext.saveError'));
    } finally {
      setSaving(false);
    }
  };

  const handleVerify = async (d: ExternalDoc) => {
    try {
      await api.post(`/external-documents/${d.id}/verify`);
      toast('success', t('ext.verifySuccess'));
      fetchDocs();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('ext.verifyError'));
    }
  };

  const handleDelete = async () => {
    if (!toDelete) return;
    try {
      await api.delete(`/external-documents/${toDelete.id}`);
      toast('success', t('ext.deleteSuccess'));
      setToDelete(null);
      fetchDocs();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('common.error'));
      setToDelete(null);
    }
  };

  const statusStyle = (status: string) =>
    status === 'ACTIVE' ? 'bg-green-50 text-green-700 border-green-200'
      : status === 'SUPERSEDED' ? 'bg-amber-50 text-amber-700 border-amber-200'
      : 'bg-red-50 text-red-700 border-red-200';

  return (
    <div className="flex flex-col h-full gap-6">
      <div className="flex justify-between items-start gap-4">
        <div>
          <h1 className="text-2xl font-bold text-textMain">{t('ext.title')}</h1>
          <p className="text-textMuted text-sm mt-1 max-w-2xl">{t('ext.subtitle')}</p>
        </div>
        <button onClick={openCreate} className="btn-primary flex items-center gap-2 shrink-0">
          <Plus className="w-4 h-4" />
          {t('ext.new')}
        </button>
      </div>

      <div className="card flex-1 flex flex-col p-0 overflow-hidden">
        <div className="p-4 border-b border-borderLight flex flex-wrap gap-3 bg-gray-50/50">
          <div className="relative flex-1 min-w-[220px] max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-textMuted" />
            <input
              type="text"
              placeholder={t('ext.searchPlaceholder')}
              className="input-field pl-9 h-9 text-sm"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <select className="input-field h-9 text-sm py-0 w-48" value={kindFilter}
                  onChange={(e) => setKindFilter(e.target.value)}>
            <option value="">{t('ext.allKinds')}</option>
            {KINDS.map((k) => <option key={k} value={k}>{t(`ext.kind.${k}`)}</option>)}
          </select>
          <select className="input-field h-9 text-sm py-0 w-40" value={statusFilter}
                  onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">{t('ext.allStatus')}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{t(`ext.status.${s}`)}</option>)}
          </select>
        </div>

        <div className="overflow-x-auto flex-1">
          <table className="w-full text-sm text-left">
            <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold sticky top-0">
              <tr>
                <th className="px-6 py-4">{t('ext.code')}</th>
                <th className="px-6 py-4">{t('ext.docTitle')}</th>
                <th className="px-6 py-4">{t('ext.issuer')}</th>
                <th className="px-6 py-4">{t('ext.edition')}</th>
                <th className="px-6 py-4">{t('docs.status')}</th>
                <th className="px-6 py-4">{t('ext.nextVerification')}</th>
                <th className="px-6 py-4 text-right">{t('common.actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-borderLight">
              {loading ? (
                <TableSkeleton cols={7} />
              ) : docs.length === 0 ? (
                <tr><td colSpan={7}>
                  <EmptyState icon={<BookMarked className="w-10 h-10 mb-3 opacity-40" />}
                              message={t('ext.empty')} />
                </td></tr>
              ) : (
                docs.map((d) => (
                  <tr key={d.id} className="hover:bg-surface/50 transition-colors group">
                    <td className="px-6 py-4 font-medium text-textMain whitespace-nowrap">{d.code}</td>
                    <td className="px-6 py-4 text-textMain">
                      {d.title}
                      <span className="block text-xs text-textMuted">{t(`ext.kind.${d.docKind}`)}</span>
                    </td>
                    <td className="px-6 py-4 text-textMuted">{d.issuer || '—'}</td>
                    <td className="px-6 py-4 text-textMuted">{d.edition || '—'}</td>
                    <td className="px-6 py-4">
                      <span className={cn('px-2.5 py-1 rounded-full text-xs font-medium border whitespace-nowrap',
                                          statusStyle(d.status))}>
                        {t(`ext.status.${d.status}`)}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      {d.nextVerificationDate ? (
                        <span className={cn('flex items-center gap-1.5',
                                            d.verificationOverdue ? 'text-red-600 font-medium' : 'text-textMuted')}>
                          {d.verificationOverdue && <AlertTriangle className="w-3.5 h-3.5" />}
                          {formatDate(d.nextVerificationDate, lang)}
                        </span>
                      ) : <span className="text-textMuted">—</span>}
                      {d.lastVerifiedByName && (
                        <span className="block text-xs text-textMuted/70">
                          {t('ext.lastVerified')}: {d.lastVerifiedByName}
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-end gap-2 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                        <button onClick={() => handleVerify(d)} title={t('ext.verify')}
                                className="text-textMuted hover:text-green-700 transition-colors">
                          <BadgeCheck className="w-4 h-4" />
                        </button>
                        <button onClick={() => openEdit(d)} title={t('common.edit')}
                                className="text-textMuted hover:text-primary transition-colors">
                          <Pencil className="w-4 h-4" />
                        </button>
                        <button onClick={() => setToDelete(d)} title={t('common.delete')}
                                className="text-textMuted hover:text-red-600 transition-colors">
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <Modal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        title={
          <span className="flex items-center gap-2">
            <BookMarked className="w-5 h-5 text-primary" />
            {editing ? t('ext.edit') : t('ext.new')}
          </span>
        }
        wide
        footer={
          <>
            <button onClick={() => setModalOpen(false)}
                    className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button onClick={handleSave} disabled={saving} className="btn-primary py-2 px-6">
              {t('common.save')}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.code')}</label>
              <input type="text" value={form.code} className="input-field uppercase" autoFocus
                     onChange={(e) => setForm((f) => ({ ...f, code: e.target.value }))} />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.kind')}</label>
              <select value={form.docKind} className="input-field"
                      onChange={(e) => setForm((f) => ({ ...f, docKind: e.target.value }))}>
                {KINDS.map((k) => <option key={k} value={k}>{t(`ext.kind.${k}`)}</option>)}
              </select>
            </div>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('ext.docTitle')}</label>
            <input type="text" value={form.title} className="input-field"
                   onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))} />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.issuer')}</label>
              <input type="text" value={form.issuer} className="input-field"
                     placeholder={t('ext.issuerPlaceholder')}
                     onChange={(e) => setForm((f) => ({ ...f, issuer: e.target.value }))} />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.edition')}</label>
              <input type="text" value={form.edition} className="input-field"
                     placeholder={t('ext.editionPlaceholder')}
                     onChange={(e) => setForm((f) => ({ ...f, edition: e.target.value }))} />
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.publicationDate')}</label>
              <input type="date" value={form.publicationDate} className="input-field"
                     onChange={(e) => setForm((f) => ({ ...f, publicationDate: e.target.value }))} />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.verificationInterval')}</label>
              <div className="flex items-center gap-2">
                <input type="number" min={1} max={120} value={form.verificationIntervalMonths}
                       className="input-field"
                       onChange={(e) => setForm((f) => ({ ...f, verificationIntervalMonths: Number(e.target.value) }))} />
                <span className="text-sm text-textMuted whitespace-nowrap">{t('create.months')}</span>
              </div>
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('docs.status')}</label>
              <select value={form.status} className="input-field"
                      onChange={(e) => setForm((f) => ({ ...f, status: e.target.value }))}>
                {STATUSES.map((s) => <option key={s} value={s}>{t(`ext.status.${s}`)}</option>)}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.owner')}</label>
              <select value={form.ownerId} className="input-field"
                      onChange={(e) => setForm((f) => ({ ...f, ownerId: e.target.value }))}>
                <option value="">—</option>
                {people.map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
              </select>
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('ext.location')}</label>
              <input type="text" value={form.location} className="input-field"
                     onChange={(e) => setForm((f) => ({ ...f, location: e.target.value }))} />
            </div>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('ext.notes')}</label>
            <textarea value={form.notes} rows={2} className="input-field resize-none"
                      onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))} />
          </div>
        </div>
      </Modal>

      <ConfirmModal
        isOpen={!!toDelete}
        onClose={() => setToDelete(null)}
        onConfirm={handleDelete}
        title={t('ext.deleteConfirmTitle')}
        body={t('ext.deleteConfirmBody').replace('{name}', toDelete?.title ?? '')}
        confirmLabel={t('common.delete')}
        danger
      />
    </div>
  );
}
