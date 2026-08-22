import { useState, useEffect, useCallback, useMemo } from 'react';
import { UserPlus, X, Search, Users } from 'lucide-react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { Modal, useToast } from './ui';

interface Member {
  userId: string;
  fullName: string;
  email: string;
}

interface UserOption {
  id: string;
  fullName: string;
  email: string;
}

interface Props {
  groupId: string | null;
  groupName: string;
  onClose: () => void;
}

/**
 * Who belongs to a sector.
 *
 * Membership is not decoration: it decides who can open the documents shared
 * with this group, so the dialog shows the consequence rather than presenting
 * it as a filing exercise. Removal takes effect immediately, which is why it
 * says so instead of quietly succeeding.
 */
export default function GroupMembersModal({ groupId, groupName, onClose }: Props) {
  const { t } = useI18n();
  const { toast } = useToast();

  const [members, setMembers] = useState<Member[]>([]);
  const [allUsers, setAllUsers] = useState<UserOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [busyUserId, setBusyUserId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!groupId) return;
    setLoading(true);
    try {
      const [membersRes, usersRes] = await Promise.all([
        api.get(`/documents/groups/${groupId}/members`),
        api.get('/users?status=ACTIVE'),
      ]);
      setMembers(membersRes.data ?? []);
      setAllUsers(usersRes.data ?? []);
    } catch (err) {
      console.error(err);
      toast('error', t('members.loadError'));
    } finally {
      setLoading(false);
    }
  }, [groupId, toast, t]);

  useEffect(() => { load(); }, [load]);

  const memberIds = useMemo(() => new Set(members.map((m) => m.userId)), [members]);

  // Only people who are NOT in the group yet, filtered by the search box. A
  // list that shows people already added invites clicking something that does
  // nothing.
  const candidates = useMemo(() => {
    const q = search.trim().toLowerCase();
    return allUsers
      .filter((u) => !memberIds.has(u.id))
      .filter((u) => !q || u.fullName?.toLowerCase().includes(q) || u.email?.toLowerCase().includes(q))
      .slice(0, 50);
  }, [allUsers, memberIds, search]);

  const add = async (user: UserOption) => {
    if (!groupId) return;
    setBusyUserId(user.id);
    try {
      await api.post(`/documents/groups/${groupId}/members`, { userId: user.id });
      // Optimistic on success only — the list stays truthful if the call failed.
      setMembers((prev) => [...prev, { userId: user.id, fullName: user.fullName, email: user.email }]
        .sort((a, b) => (a.fullName || '').localeCompare(b.fullName || '')));
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('members.addError'));
    } finally {
      setBusyUserId(null);
    }
  };

  const remove = async (member: Member) => {
    if (!groupId) return;
    setBusyUserId(member.userId);
    try {
      await api.delete(`/documents/groups/${groupId}/members/${member.userId}`);
      setMembers((prev) => prev.filter((m) => m.userId !== member.userId));
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('members.removeError'));
    } finally {
      setBusyUserId(null);
    }
  };

  return (
    <Modal
      isOpen={!!groupId}
      onClose={onClose}
      title={t('members.title').replace('{group}', groupName)}
      footer={<button onClick={onClose} className="btn-primary py-2 px-5">{t('common.close')}</button>}
    >
      <p className="text-sm text-textMuted mb-4">{t('members.subtitle')}</p>

      {loading ? (
        <p className="text-sm text-textMuted">{t('common.loading')}</p>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {/* Current members */}
          <div>
            <h4 className="text-xs uppercase tracking-wide text-textMuted font-semibold mb-2 flex items-center gap-1.5">
              <Users className="w-3.5 h-3.5" />
              {t('members.current').replace('{n}', String(members.length))}
            </h4>
            <div className="border border-borderLight rounded-lg divide-y divide-borderLight max-h-64 overflow-y-auto">
              {members.length === 0 ? (
                <p className="text-sm text-textMuted p-3">{t('members.none')}</p>
              ) : (
                members.map((m) => (
                  <div key={m.userId} className="flex items-center gap-2 p-2.5">
                    <div className="min-w-0 flex-1">
                      <p className="text-sm text-textMain truncate">{m.fullName}</p>
                      <p className="text-xs text-textMuted truncate">{m.email}</p>
                    </div>
                    <button
                      onClick={() => remove(m)}
                      disabled={busyUserId === m.userId}
                      title={t('members.remove')}
                      aria-label={`${t('members.remove')}: ${m.fullName}`}
                      className="p-1 rounded text-textMuted hover:text-red-600 hover:bg-elevated disabled:opacity-40
                                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>

          {/* People who can be added */}
          <div>
            <h4 className="text-xs uppercase tracking-wide text-textMuted font-semibold mb-2">
              {t('members.add')}
            </h4>
            <div className="relative mb-2">
              <Search className="w-4 h-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-textMuted" />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('members.searchPlaceholder')}
                className="input-field text-sm py-1.5 pl-8"
              />
            </div>
            <div className="border border-borderLight rounded-lg divide-y divide-borderLight max-h-52 overflow-y-auto">
              {candidates.length === 0 ? (
                <p className="text-sm text-textMuted p-3">{t('members.noCandidates')}</p>
              ) : (
                candidates.map((u) => (
                  <button
                    key={u.id}
                    onClick={() => add(u)}
                    disabled={busyUserId === u.id}
                    className="w-full flex items-center gap-2 p-2.5 text-left hover:bg-elevated disabled:opacity-40
                               focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
                  >
                    <UserPlus className="w-4 h-4 text-primary shrink-0" />
                    <div className="min-w-0">
                      <p className="text-sm text-textMain truncate">{u.fullName}</p>
                      <p className="text-xs text-textMuted truncate">{u.email}</p>
                    </div>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      <p className="text-xs text-textMuted mt-4 border-l-2 border-amber-300 pl-3">
        {t('members.effectNote')}
      </p>
    </Modal>
  );
}
