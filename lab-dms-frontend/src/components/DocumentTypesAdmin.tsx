import { useState, useEffect, useCallback } from 'react';
import { Tags, Plus, Check, X, Pencil, Trash2, EyeOff, Eye, Lock } from 'lucide-react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { useToast, ConfirmModal } from './ui';
import { cn } from '../lib/utils';

interface DocType {
  id: string;
  prefix: string;
  name: string;
  description: string | null;
  active: boolean;
  sortOrder: number;
  documentCount: number;
}

/**
 * Admin maintenance of the laboratory's document taxonomy.
 *
 * The prefix is shown but never editable after creation: it is already part of
 * every code issued under the type (IT-001), and those codes are written on
 * paper records and quoted in audits. Changing what a prefix means afterwards
 * would invalidate that trail, so the interface makes the constraint visible
 * with a lock rather than letting a user try and get an error.
 */
export default function DocumentTypesAdmin() {
  const { t } = useI18n();
  const { toast } = useToast();

  const [types, setTypes] = useState<DocType[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [newPrefix, setNewPrefix] = useState('');
  const [newName, setNewName] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [toRemove, setToRemove] = useState<DocType | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/document-types', { params: { includeInactive: true } });
      setTypes(res.data ?? []);
    } catch (err) {
      console.error(err);
      toast('error', t('types.loadError'));
    } finally {
      setLoading(false);
    }
  }, [toast, t]);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    const prefix = newPrefix.trim().toUpperCase();
    if (!prefix || !newName.trim()) return;
    setCreating(true);
    try {
      await api.post('/document-types', { prefix, name: newName.trim() });
      setNewPrefix('');
      setNewName('');
      toast('success', t('types.created'));
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('types.createError'));
    } finally {
      setCreating(false);
    }
  };

  const handleRename = async (type: DocType) => {
    if (!editName.trim() || editName.trim() === type.name) { setEditingId(null); return; }
    try {
      await api.put(`/document-types/${type.id}`, { name: editName.trim() });
      setEditingId(null);
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('types.updateError'));
    }
  };

  const handleToggleActive = async (type: DocType) => {
    try {
      await api.put(`/document-types/${type.id}`, { active: !type.active });
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('types.updateError'));
    }
  };

  const handleRemove = async () => {
    if (!toRemove) return;
    try {
      const res = await api.delete(`/document-types/${toRemove.id}`);
      toast('success', res.data?.deleted ? t('types.deleted') : t('types.deactivated'));
      setToRemove(null);
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('types.deleteError'));
      setToRemove(null);
    }
  };

  return (
    <div className="card p-6">
      <div className="flex items-center gap-2 mb-1">
        <Tags className="w-5 h-5 text-primary" />
        <h3 className="font-semibold text-textMain">{t('types.title')}</h3>
      </div>
      <p className="text-sm text-textMuted mb-5">{t('types.subtitle')}</p>

      {loading ? (
        <p className="text-sm text-textMuted">{t('common.loading')}</p>
      ) : (
        <div className="space-y-1 mb-5">
          {types.map((type) => (
            <div
              key={type.id}
              className={cn(
                'flex items-center gap-3 py-2 px-3 rounded-md border border-borderLight',
                !type.active && 'opacity-60',
              )}
            >
              <span
                className="font-mono text-sm font-bold text-primary bg-primary/10 rounded px-2 py-0.5 shrink-0
                           flex items-center gap-1"
                title={t('types.prefixLocked')}
              >
                {type.prefix}
                <Lock className="w-3 h-3 opacity-60" />
              </span>

              {editingId === type.id ? (
                <>
                  <input
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleRename(type);
                      if (e.key === 'Escape') setEditingId(null);
                    }}
                    autoFocus
                    className="input-field text-sm py-1 flex-1"
                  />
                  <button onClick={() => handleRename(type)} title={t('common.save')}
                          className="p-1 text-green-600 hover:bg-elevated rounded">
                    <Check className="w-4 h-4" />
                  </button>
                  <button onClick={() => setEditingId(null)} title={t('common.cancel')}
                          className="p-1 text-textMuted hover:bg-elevated rounded">
                    <X className="w-4 h-4" />
                  </button>
                </>
              ) : (
                <>
                  <span className="text-sm text-textMain flex-1 truncate">{type.name}</span>
                  <span className="text-xs text-textMuted whitespace-nowrap">
                    {t('types.inUse').replace('{n}', String(type.documentCount))}
                  </span>
                  {!type.active && (
                    <span className="text-[10px] uppercase font-semibold text-amber-700 bg-amber-50
                                     border border-amber-200 rounded px-1.5 py-0.5">
                      {t('types.inactive')}
                    </span>
                  )}
                  <button
                    onClick={() => { setEditingId(type.id); setEditName(type.name); }}
                    title={t('types.rename')}
                    className="p-1 text-textMuted hover:text-primary hover:bg-elevated rounded"
                  >
                    <Pencil className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => handleToggleActive(type)}
                    title={type.active ? t('types.deactivate') : t('types.activate')}
                    className="p-1 text-textMuted hover:text-primary hover:bg-elevated rounded"
                  >
                    {type.active ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                  <button
                    onClick={() => setToRemove(type)}
                    title={t('common.delete')}
                    className="p-1 text-textMuted hover:text-red-600 hover:bg-elevated rounded"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </>
              )}
            </div>
          ))}
          {types.length === 0 && <p className="text-sm text-textMuted">{t('types.empty')}</p>}
        </div>
      )}

      <div className="border-t border-borderLight pt-4">
        <label className="text-sm font-medium text-textMain block mb-2">{t('types.addTitle')}</label>
        <div className="flex items-start gap-2">
          <div className="w-28 shrink-0">
            <input
              value={newPrefix}
              onChange={(e) => setNewPrefix(e.target.value.toUpperCase())}
              maxLength={10}
              className="input-field text-sm py-1.5 uppercase font-mono"
              placeholder="CAL"
            />
            <p className="text-[11px] text-textMuted mt-1">{t('types.prefixHint')}</p>
          </div>
          <div className="flex-1">
            <input
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleCreate(); } }}
              className="input-field text-sm py-1.5"
              placeholder={t('types.namePlaceholder')}
            />
            <p className="text-[11px] text-textMuted mt-1">{t('types.nameHint')}</p>
          </div>
          <button
            onClick={handleCreate}
            disabled={!newPrefix.trim() || !newName.trim() || creating}
            className="btn-primary text-sm py-1.5 px-4 flex items-center gap-1.5 disabled:opacity-50"
          >
            <Plus className="w-4 h-4" />
            {creating ? t('types.adding') : t('types.add')}
          </button>
        </div>
      </div>

      <ConfirmModal
        isOpen={!!toRemove}
        onClose={() => setToRemove(null)}
        onConfirm={handleRemove}
        title={t('types.removeTitle')}
        // The consequence differs by whether the type is in use, and the user
        // cannot know which without being told.
        body={
          toRemove && toRemove.documentCount > 0
            ? t('types.removeInUse')
                .replace('{prefix}', toRemove.prefix)
                .replace('{n}', String(toRemove.documentCount))
            : t('types.removeUnused').replace('{prefix}', toRemove?.prefix ?? '')
        }
        confirmLabel={t('common.confirm')}
        danger={!!toRemove && toRemove.documentCount === 0}
      />
    </div>
  );
}
