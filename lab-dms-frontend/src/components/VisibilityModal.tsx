import { useState, useEffect, useCallback, useMemo } from 'react';
import { Globe, Building2, Lock, X, Plus, Search, AlertTriangle } from 'lucide-react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { Modal, useToast } from './ui';
import { cn } from '../lib/utils';

type Level = 'PUBLIC' | 'PRIVATE' | 'RESTRICTED';

interface Grantee {
  groupId: string | null;
  groupName: string | null;
  userId: string | null;
  userName: string | null;
}

interface Props {
  documentId: string | null;
  documentCode: string;
  onClose: () => void;
  onSaved?: (level: Level) => void;
}

/**
 * Who can read this document, changed after it exists.
 *
 * The three levels are shown as full options with their consequence spelled
 * out, not as a dropdown of nouns: "Public" and "Restricted" mean opposite
 * things to a reader, and the difference between them is exactly what somebody
 * gets wrong when the label is all they see.
 */
export default function VisibilityModal({ documentId, documentCode, onClose, onSaved }: Props) {
  const { t } = useI18n();
  const { toast } = useToast();

  const [level, setLevel] = useState<Level>('PRIVATE');
  const [grantees, setGrantees] = useState<Grantee[]>([]);
  const [groups, setGroups] = useState<any[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!documentId) return;
    setLoading(true);
    try {
      const [visRes, groupsRes, usersRes] = await Promise.all([
        api.get(`/documents/${documentId}/visibility`),
        api.get('/documents/groups'),
        api.get('/users?status=ACTIVE'),
      ]);
      setLevel((visRes.data?.visibility as Level) ?? 'PRIVATE');
      setGrantees(visRes.data?.grantees ?? []);
      setGroups(groupsRes.data ?? []);
      setUsers(usersRes.data ?? []);
    } catch (err) {
      console.error(err);
      toast('error', t('privacy.loadError'));
    } finally {
      setLoading(false);
    }
  }, [documentId, toast, t]);

  useEffect(() => { load(); }, [load]);

  const chosenGroupIds = useMemo(
    () => new Set(grantees.filter((g) => g.groupId).map((g) => g.groupId)), [grantees]);
  const chosenUserIds = useMemo(
    () => new Set(grantees.filter((g) => g.userId).map((g) => g.userId)), [grantees]);

  const q = search.trim().toLowerCase();
  const groupOptions = groups.filter((g) => !chosenGroupIds.has(g.id))
    .filter((g) => !q || g.name?.toLowerCase().includes(q));
  const userOptions = users.filter((u) => !chosenUserIds.has(u.id))
    .filter((u) => !q || u.fullName?.toLowerCase().includes(q) || u.email?.toLowerCase().includes(q))
    .slice(0, 30);

  const save = async () => {
    if (!documentId) return;
    setSaving(true);
    try {
      await api.put(`/documents/${documentId}/visibility`, {
        visibility: level,
        grantees: level === 'RESTRICTED'
          ? grantees.map((g) => ({ groupId: g.groupId, userId: g.userId }))
          : [],
      });
      toast('success', t('privacy.saved'));
      onSaved?.(level);
      onClose();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('privacy.saveError'));
    } finally {
      setSaving(false);
    }
  };

  const options: { id: Level; Icon: typeof Globe }[] = [
    { id: 'PUBLIC', Icon: Globe },
    { id: 'PRIVATE', Icon: Building2 },
    { id: 'RESTRICTED', Icon: Lock },
  ];

  return (
    <Modal
      isOpen={!!documentId}
      onClose={onClose}
      title={`${t('privacy.label')} — ${documentCode}`}
      footer={
        <>
          <button onClick={onClose} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-elevated rounded-md">
            {t('common.cancel')}
          </button>
          <button onClick={save} disabled={saving || loading} className="btn-primary py-2 px-5 disabled:opacity-50">
            {saving ? t('branding.saving') : t('common.save')}
          </button>
        </>
      }
    >
      {loading ? (
        <p className="text-sm text-textMuted">{t('common.loading')}</p>
      ) : (
        <div className="space-y-4">
          <div className="space-y-2">
            {options.map(({ id, Icon }) => (
              <button
                key={id}
                onClick={() => setLevel(id)}
                className={cn(
                  'w-full flex items-start gap-3 p-3 rounded-lg border-2 text-left transition-all',
                  level === id
                    ? 'border-primary bg-primary/10'
                    : 'border-borderLight hover:border-primary/40',
                )}
              >
                <Icon className={cn('w-5 h-5 mt-0.5 shrink-0', level === id ? 'text-primary' : 'text-textMuted')} />
                <span className="min-w-0">
                  <span className={cn('block text-sm font-medium', level === id ? 'text-primary' : 'text-textMain')}>
                    {t(`privacy.${id}`)}
                  </span>
                  <span className="block text-xs text-textMuted">{t(`privacy.${id}.hint`)}</span>
                </span>
              </button>
            ))}
          </div>

          {level === 'PUBLIC' && (
            <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded px-3 py-2 flex items-start gap-2">
              <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
              <span>{t('privacy.publicWarningLong')}</span>
            </p>
          )}

          {level === 'RESTRICTED' && (
            <div className="space-y-3 border-t border-borderLight pt-4">
              {/* Who has been granted access so far */}
              <div className="flex flex-wrap gap-1.5">
                {grantees.length === 0 ? (
                  <p className="text-xs text-textMuted">{t('privacy.noGrantees')}</p>
                ) : (
                  grantees.map((g) => (
                    <span
                      key={g.groupId ?? g.userId}
                      className="inline-flex items-center gap-1.5 text-xs bg-primary/10 text-primary rounded-full pl-2.5 pr-1 py-1"
                    >
                      {g.groupId ? <Building2 className="w-3 h-3" /> : <Lock className="w-3 h-3" />}
                      {g.groupName ?? g.userName}
                      <button
                        onClick={() => setGrantees((prev) => prev.filter(
                          (x) => (x.groupId ?? x.userId) !== (g.groupId ?? g.userId)))}
                        aria-label={`${t('common.delete')}: ${g.groupName ?? g.userName}`}
                        className="p-0.5 rounded-full hover:bg-primary/20"
                      >
                        <X className="w-3 h-3" />
                      </button>
                    </span>
                  ))
                )}
              </div>

              <div className="relative">
                <Search className="w-4 h-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-textMuted" />
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder={t('privacy.searchPlaceholder')}
                  className="input-field text-sm py-1.5 pl-8"
                />
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <h4 className="text-xs uppercase tracking-wide text-textMuted font-semibold mb-1.5">
                    {t('privacy.addGroup')}
                  </h4>
                  <div className="border border-borderLight rounded-lg divide-y divide-borderLight max-h-40 overflow-y-auto">
                    {groupOptions.length === 0 ? (
                      <p className="text-xs text-textMuted p-2.5">{t('privacy.noneLeft')}</p>
                    ) : groupOptions.map((g) => (
                      <button key={g.id}
                        onClick={() => setGrantees((prev) => [...prev,
                          { groupId: g.id, groupName: g.name, userId: null, userName: null }])}
                        className="w-full flex items-center gap-2 p-2 text-left text-sm hover:bg-elevated">
                        <Plus className="w-3.5 h-3.5 text-primary shrink-0" />
                        <span className="truncate">{g.name}</span>
                      </button>
                    ))}
                  </div>
                </div>

                <div>
                  <h4 className="text-xs uppercase tracking-wide text-textMuted font-semibold mb-1.5">
                    {t('privacy.addPerson')}
                  </h4>
                  <div className="border border-borderLight rounded-lg divide-y divide-borderLight max-h-40 overflow-y-auto">
                    {userOptions.length === 0 ? (
                      <p className="text-xs text-textMuted p-2.5">{t('privacy.noneLeft')}</p>
                    ) : userOptions.map((u) => (
                      <button key={u.id}
                        onClick={() => setGrantees((prev) => [...prev,
                          { groupId: null, groupName: null, userId: u.id, userName: u.fullName }])}
                        className="w-full flex items-center gap-2 p-2 text-left hover:bg-elevated">
                        <Plus className="w-3.5 h-3.5 text-primary shrink-0" />
                        <span className="min-w-0">
                          <span className="block text-sm text-textMain truncate">{u.fullName}</span>
                          <span className="block text-xs text-textMuted truncate">{u.email}</span>
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              </div>

              <p className="text-xs text-textMuted">{t('privacy.authorNote')}</p>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
