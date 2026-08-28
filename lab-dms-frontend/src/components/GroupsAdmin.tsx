import { useState, useEffect, useCallback } from 'react';
import { Layers, Plus, Pencil, Trash2, Users, Check, X } from 'lucide-react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { useToast, ConfirmModal } from './ui';
import GroupMembersModal from './GroupMembersModal';

interface Group {
  id: string;
  name: string;
  description: string | null;
  memberCount?: number;
}

/**
 * Sectors of the laboratory, and who belongs to them.
 *
 * This lived as a cramped inline field inside the document creation form, where
 * it competed for attention with the document being written and was the wrong
 * thing to be doing at that moment. Managing the organisation's structure is
 * configuration, done once by an administrator; writing a document is daily
 * work. They belong in different places.
 */
export default function GroupsAdmin() {
  const { t } = useI18n();
  const { toast } = useToast();

  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [toRemove, setToRemove] = useState<Group | null>(null);
  const [membersOf, setMembersOf] = useState<Group | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/documents/groups');
      setGroups(res.data ?? []);
    } catch {
      toast('error', t('groupsAdmin.loadError'));
    } finally {
      setLoading(false);
    }
  }, [toast, t]);

  useEffect(() => { load(); }, [load]);

  const create = async () => {
    if (!name.trim()) return;
    setCreating(true);
    try {
      await api.post('/documents/groups', {
        name: name.trim(),
        description: description.trim() || null,
      });
      setName('');
      setDescription('');
      toast('success', t('groupsAdmin.created'));
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('groupsAdmin.createError'));
    } finally {
      setCreating(false);
    }
  };

  const rename = async (group: Group) => {
    if (!editName.trim() || editName.trim() === group.name) { setEditingId(null); return; }
    try {
      await api.put(`/documents/groups/${group.id}`, { name: editName.trim() });
      setEditingId(null);
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('groupsAdmin.updateError'));
    }
  };

  const remove = async () => {
    if (!toRemove) return;
    try {
      await api.delete(`/documents/groups/${toRemove.id}`);
      toast('success', t('groupsAdmin.deleted'));
      setToRemove(null);
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('groupsAdmin.deleteError'));
      setToRemove(null);
    }
  };

  return (
    <div className="card p-6">
      <div className="flex items-center gap-2 mb-1">
        <Layers className="w-5 h-5 text-primary" />
        <h3 className="font-semibold text-textMain">{t('groupsAdmin.title')}</h3>
      </div>
      <p className="text-sm text-textMuted mb-5">{t('groupsAdmin.subtitle')}</p>

      {loading ? (
        <p className="text-sm text-textMuted">{t('common.loading')}</p>
      ) : (
        <div className="space-y-1 mb-5">
          {groups.length === 0 && (
            <p className="text-sm text-textMuted">{t('groupsAdmin.empty')}</p>
          )}
          {groups.map((g) => (
            <div key={g.id}
                 className="flex items-center gap-3 py-2 px-3 rounded-md border border-borderLight">
              {editingId === g.id ? (
                <>
                  <input
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') rename(g);
                      if (e.key === 'Escape') setEditingId(null);
                    }}
                    autoFocus
                    className="input-field text-sm py-1 flex-1"
                  />
                  <button onClick={() => rename(g)} title={t('common.save')}
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
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-textMain truncate">{g.name}</p>
                    {g.description && (
                      <p className="text-xs text-textMuted truncate">{g.description}</p>
                    )}
                  </div>
                  <button
                    onClick={() => setMembersOf(g)}
                    title={t('members.manage')}
                    className="p-1 rounded text-textMuted hover:text-primary hover:bg-elevated
                               focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  >
                    <Users className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => { setEditingId(g.id); setEditName(g.name); }}
                    title={t('common.edit')}
                    className="p-1 rounded text-textMuted hover:text-primary hover:bg-elevated"
                  >
                    <Pencil className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => setToRemove(g)}
                    title={t('common.delete')}
                    className="p-1 rounded text-textMuted hover:text-red-600 hover:bg-elevated"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
      )}

      <div className="border-t border-borderLight pt-4">
        <label className="text-sm font-medium text-textMain block mb-2">{t('groupsAdmin.add')}</label>
        <div className="flex items-start gap-2">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); create(); } }}
            placeholder={t('groupsAdmin.namePlaceholder')}
            className="input-field text-sm py-1.5 w-48"
          />
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); create(); } }}
            placeholder={t('groupsAdmin.descPlaceholder')}
            className="input-field text-sm py-1.5 flex-1"
          />
          <button
            onClick={create}
            disabled={!name.trim() || creating}
            className="btn-primary text-sm py-1.5 px-4 flex items-center gap-1.5 disabled:opacity-50"
          >
            <Plus className="w-4 h-4" />
            {creating ? t('types.adding') : t('types.add')}
          </button>
        </div>
      </div>

      <GroupMembersModal
        groupId={membersOf?.id ?? null}
        groupName={membersOf?.name ?? ''}
        onClose={() => setMembersOf(null)}
      />

      <ConfirmModal
        isOpen={!!toRemove}
        onClose={() => setToRemove(null)}
        onConfirm={remove}
        title={t('groupsAdmin.removeTitle')}
        body={t('groupsAdmin.removeBody').replace('{name}', toRemove?.name ?? '')}
        confirmLabel={t('common.confirm')}
        danger
      />
    </div>
  );
}
