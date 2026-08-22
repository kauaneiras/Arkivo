import { useState, useEffect, useMemo } from 'react';
import api from '../../lib/api';
import GroupMembersModal from '../../components/GroupMembersModal';
import { Check, Clock, UserCheck, Search, UserPlus, Layers, Plus, Pencil, Trash2, Users } from 'lucide-react';
import { useI18n } from '../../lib/i18n';
import { EmptyState, TableSkeleton, Modal, ConfirmModal, useToast } from '../../components/ui';
import { cn } from '../../lib/utils';

interface UserDto {
  id: string;
  email: string;
  fullName: string;
  status: string;
  role?: string;
}

interface RoleDto {
  id: string;
  name: string;
}

interface GroupDto {
  id: string;
  name: string;
  description: string | null;
}

export default function UsersPage() {
  const [pendingUsers, setPendingUsers] = useState<UserDto[]>([]);
  const [activeUsers, setActiveUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [selectedRoles, setSelectedRoles] = useState<Record<string, string>>({});
  const [userSearch, setUserSearch] = useState('');
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [newUser, setNewUser] = useState({ fullName: '', email: '', password: '', roleName: '' });
  const [creating, setCreating] = useState(false);
  const { t } = useI18n();
  const { toast } = useToast();

  // Groups/sectors
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState<GroupDto | null>(null);
  const [groupForm, setGroupForm] = useState({ name: '', description: '' });
  const [savingGroup, setSavingGroup] = useState(false);
  const [groupToDelete, setGroupToDelete] = useState<GroupDto | null>(null);
  // Which sector's membership is open. Membership decides who can read the
  // documents shared with the group, so it sits next to the group itself.
  const [membersOf, setMembersOf] = useState<GroupDto | null>(null);

  useEffect(() => {
    fetchData();
    fetchGroups();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [pendingRes, activeRes, rolesRes] = await Promise.all([
        api.get('/users?status=PENDING'),
        api.get('/users?status=ACTIVE'),
        api.get('/users/roles'),
      ]);
      setPendingUsers(pendingRes.data);
      setActiveUsers(activeRes.data);
      setRoles(rolesRes.data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const fetchGroups = async () => {
    try {
      const res = await api.get('/documents/groups');
      setGroups(res.data);
    } catch (err) {
      console.error(err);
    }
  };

  const openCreateGroup = () => {
    setEditingGroup(null);
    setGroupForm({ name: '', description: '' });
    setGroupModalOpen(true);
  };

  const openEditGroup = (g: GroupDto) => {
    setEditingGroup(g);
    setGroupForm({ name: g.name, description: g.description ?? '' });
    setGroupModalOpen(true);
  };

  const handleSaveGroup = async () => {
    if (!groupForm.name.trim()) {
      toast('error', t('groups.nameRequired'));
      return;
    }
    setSavingGroup(true);
    try {
      if (editingGroup) {
        await api.put(`/documents/groups/${editingGroup.id}`, groupForm);
      } else {
        await api.post('/documents/groups', groupForm);
      }
      toast('success', t('groups.saveSuccess'));
      setGroupModalOpen(false);
      fetchGroups();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('groups.saveError'));
    } finally {
      setSavingGroup(false);
    }
  };

  const handleDeleteGroup = async () => {
    if (!groupToDelete) return;
    try {
      await api.delete(`/documents/groups/${groupToDelete.id}`);
      toast('success', t('groups.deleteSuccess'));
      setGroupToDelete(null);
      fetchGroups();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('groups.deleteError'));
      setGroupToDelete(null);
    }
  };

  const handleApprove = async (id: string) => {
    const roleName = selectedRoles[id];
    if (!roleName) {
      toast('error', t('users.selectRoleFirst'));
      return;
    }
    try {
      await api.post(`/users/${id}/approve`, { roleName });
      toast('success', t('users.approveSuccess'));
      fetchData();
    } catch (err) {
      console.error(err);
      toast('error', t('users.approveError'));
    }
  };

  /**
   * Roles are cumulative — a writer can also be a reviewer. Toggling sends the
   * FULL resulting set, so the backend never has to guess what was removed.
   */
  const handleToggleRole = async (user: UserDto, roleName: string) => {
    const current = (user.role ?? '').split(',').map((r) => r.trim()).filter(Boolean);
    const next = current.includes(roleName)
      ? current.filter((r) => r !== roleName)
      : [...current, roleName];

    if (next.length === 0) {
      toast('error', t('users.rolesRequired'));
      return;
    }
    try {
      await api.put(`/users/${user.id}/roles`, { roleNames: next });
      toast('success', t('users.rolesSaved'));
      fetchData();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('users.rolesError'));
    }
  };

  const handleCreateUser = async () => {
    if (!newUser.fullName.trim() || !newUser.email.trim() || newUser.password.length < 6 || !newUser.roleName) {
      toast('error', t('users.createError'));
      return;
    }
    setCreating(true);
    try {
      await api.post('/users', newUser);
      toast('success', t('users.createSuccess'));
      setAddModalOpen(false);
      setNewUser({ fullName: '', email: '', password: '', roleName: '' });
      fetchData();
    } catch (err) {
      console.error(err);
      toast('error', t('users.createError'));
    } finally {
      setCreating(false);
    }
  };

  const filteredActive = useMemo(() => {
    if (!userSearch.trim()) return activeUsers;
    const q = userSearch.toLowerCase();
    return activeUsers.filter(
      (u) => u.fullName?.toLowerCase().includes(q) || u.email?.toLowerCase().includes(q)
    );
  }, [activeUsers, userSearch]);

  const roleLabel = (name: string) => {
    const translated = t(`role.${name}`);
    return translated === `role.${name}` ? name.replace('ROLE_', '') : translated;
  };

  return (
    <div className="flex flex-col h-full gap-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-textMain">{t('users.title')}</h1>
          <p className="text-textMuted text-sm mt-1">{t('users.subtitle')}</p>
        </div>
        <button
          onClick={() => setAddModalOpen(true)}
          className="btn-primary flex items-center gap-2"
        >
          <UserPlus className="w-4 h-4" />
          {t('users.addUser')}
        </button>
      </div>

      <div className="grid grid-cols-1 gap-6">
        {/* Pending Requests */}
        <div className="card flex flex-col p-0 overflow-hidden">
          <div className="p-4 border-b border-borderLight flex items-center justify-between bg-yellow-50/30">
            <h2 className="font-semibold text-textMain flex items-center gap-2">
              <Clock className="w-5 h-5 text-yellow-600" />
              {t('users.pendingRequests')}
            </h2>
            <span className="bg-yellow-100 text-yellow-800 text-xs font-medium px-2.5 py-0.5 rounded-full">
              {pendingUsers.length} {t('users.waiting')}
            </span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold">
                <tr>
                  <th className="px-6 py-4">{t('users.name')}</th>
                  <th className="px-6 py-4">{t('users.email')}</th>
                  <th className="px-6 py-4">{t('users.assignRole')}</th>
                  <th className="px-6 py-4 text-right">{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-borderLight">
                {loading ? (
                  <TableSkeleton cols={4} rows={2} />
                ) : pendingUsers.length === 0 ? (
                  <tr><td colSpan={4}><EmptyState message={t('users.noPending')} /></td></tr>
                ) : (
                  pendingUsers.map((user) => (
                    <tr key={user.id} className="hover:bg-surface/50 transition-colors">
                      <td className="px-6 py-4 font-medium text-textMain">{user.fullName}</td>
                      <td className="px-6 py-4 text-textMuted">{user.email}</td>
                      <td className="px-6 py-4">
                        <select
                          className="input-field text-sm h-8 py-0 px-2 w-44"
                          value={selectedRoles[user.id] ?? ''}
                          onChange={(e) => setSelectedRoles((prev) => ({ ...prev, [user.id]: e.target.value }))}
                        >
                          <option value="" disabled>{t('users.selectRole')}</option>
                          {roles.map((r) => (
                            <option key={r.id} value={r.name}>{roleLabel(r.name)}</option>
                          ))}
                        </select>
                      </td>
                      <td className="px-6 py-4 text-right">
                        <button
                          onClick={() => handleApprove(user.id)}
                          className="btn-secondary py-1 px-3 text-sm flex items-center justify-center gap-1.5 ml-auto"
                        >
                          <Check className="w-4 h-4" /> {t('users.approve')}
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Active Users */}
        <div className="card flex flex-col p-0 overflow-hidden">
          <div className="p-4 border-b border-borderLight flex items-center justify-between bg-blue-50/30">
            <h2 className="font-semibold text-textMain flex items-center gap-2">
              <UserCheck className="w-5 h-5 text-primary" />
              {t('users.activeUsers')}
            </h2>
            <div className="relative w-64">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-textMuted" />
              <input
                type="text"
                placeholder={t('users.searchPlaceholder')}
                className="input-field pl-9 h-8 text-sm"
                value={userSearch}
                onChange={(e) => setUserSearch(e.target.value)}
              />
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold">
                <tr>
                  <th className="px-6 py-4">{t('users.name')}</th>
                  <th className="px-6 py-4">{t('users.email')}</th>
                  <th className="px-6 py-4">{t('docs.status')}</th>
                  <th className="px-6 py-4">
                    {t('users.roles')}
                    <span className="block normal-case font-normal text-[11px] text-textMuted mt-0.5">
                      {t('users.rolesHint')}
                    </span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-borderLight">
                {loading ? (
                  <TableSkeleton cols={4} />
                ) : filteredActive.length === 0 ? (
                  <tr><td colSpan={4}><EmptyState message={t('users.noActive')} /></td></tr>
                ) : (
                  filteredActive.map((user) => (
                    <tr key={user.id} className="hover:bg-surface/50 transition-colors">
                      <td className="px-6 py-4 font-medium text-textMain">{user.fullName}</td>
                      <td className="px-6 py-4 text-textMuted">{user.email}</td>
                      <td className="px-6 py-4">
                        <span className="px-2.5 py-1 rounded-full text-xs font-medium border bg-green-50 text-green-700 border-green-200">
                          {user.status}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex flex-wrap gap-1.5">
                          {roles.map((r) => {
                            const active = (user.role ?? '').split(',').map((x) => x.trim()).includes(r.name);
                            return (
                              <button
                                key={r.id}
                                onClick={() => handleToggleRole(user, r.name)}
                                aria-pressed={active}
                                title={r.name}
                                className={cn(
                                  'px-2 py-1 rounded-full text-xs font-medium border transition-colors',
                                  active
                                    ? 'bg-blue-50 text-primary border-blue-200'
                                    : 'bg-surface text-textMuted border-borderLight hover:border-primary/40'
                                )}
                              >
                                {roleLabel(r.name)}
                              </button>
                            );
                          })}
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Groups / sectors */}
        <div className="card flex flex-col p-0 overflow-hidden">
          <div className="p-4 border-b border-borderLight flex items-center justify-between bg-purple-50/30">
            <h2 className="font-semibold text-textMain flex items-center gap-2">
              <Layers className="w-5 h-5 text-purple-600" />
              {t('groups.title')}
            </h2>
            <button onClick={openCreateGroup} className="btn-primary py-1.5 px-3 text-sm flex items-center gap-1.5">
              <Plus className="w-4 h-4" /> {t('groups.addGroup')}
            </button>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold">
                <tr>
                  <th className="px-6 py-4">{t('groups.name')}</th>
                  <th className="px-6 py-4">{t('groups.description')}</th>
                  <th className="px-6 py-4 text-right">{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-borderLight">
                {groups.length === 0 ? (
                  <tr><td colSpan={3}><EmptyState message={t('groups.empty')} /></td></tr>
                ) : (
                  groups.map((g) => (
                    <tr key={g.id} className="hover:bg-surface/50 transition-colors">
                      <td className="px-6 py-4 font-medium text-textMain">{g.name}</td>
                      <td className="px-6 py-4 text-textMuted">{g.description || '—'}</td>
                      <td className="px-6 py-4 text-right">
                        <div className="flex items-center justify-end gap-3">
                          <button onClick={() => setMembersOf(g)} className="text-textMuted hover:text-primary transition-colors" title={t('members.manage')}>
                            <Users className="w-4 h-4" />
                          </button>
                          <button onClick={() => openEditGroup(g)} className="text-textMuted hover:text-primary transition-colors" title={t('common.edit')}>
                            <Pencil className="w-4 h-4" />
                          </button>
                          <button onClick={() => setGroupToDelete(g)} className="text-textMuted hover:text-red-600 transition-colors" title={t('common.delete')}>
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
      </div>

      <GroupMembersModal
        groupId={membersOf?.id ?? null}
        groupName={membersOf?.name ?? ''}
        onClose={() => setMembersOf(null)}
      />

      {/* Add user modal */}
      <Modal
        isOpen={addModalOpen}
        onClose={() => setAddModalOpen(false)}
        title={
          <span className="flex items-center gap-2">
            <UserPlus className="w-5 h-5 text-primary" />
            {t('users.addUser')}
          </span>
        }
        footer={
          <>
            <button
              onClick={() => setAddModalOpen(false)}
              className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors"
            >
              {t('common.cancel')}
            </button>
            <button
              onClick={handleCreateUser}
              disabled={creating}
              className="btn-primary py-2 px-6"
            >
              {t('users.addUser')}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('users.name')}</label>
            <input
              type="text"
              value={newUser.fullName}
              onChange={(e) => setNewUser((p) => ({ ...p, fullName: e.target.value }))}
              className="input-field"
              autoFocus
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('users.email')}</label>
            <input
              type="email"
              value={newUser.email}
              onChange={(e) => setNewUser((p) => ({ ...p, email: e.target.value }))}
              className="input-field"
              placeholder="user@email.com"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('users.password')}</label>
              <input
                type="password"
                value={newUser.password}
                onChange={(e) => setNewUser((p) => ({ ...p, password: e.target.value }))}
                className="input-field"
                placeholder={t('users.passwordHint')}
                minLength={6}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('users.role')}</label>
              <select
                value={newUser.roleName}
                onChange={(e) => setNewUser((p) => ({ ...p, roleName: e.target.value }))}
                className="input-field"
              >
                <option value="" disabled>{t('users.selectRole')}</option>
                {roles.map((r) => (
                  <option key={r.id} value={r.name}>{roleLabel(r.name)}</option>
                ))}
              </select>
            </div>
          </div>
        </div>
      </Modal>

      {/* Add/edit group modal */}
      <Modal
        isOpen={groupModalOpen}
        onClose={() => setGroupModalOpen(false)}
        title={
          <span className="flex items-center gap-2">
            <Layers className="w-5 h-5 text-purple-600" />
            {editingGroup ? t('groups.editGroup') : t('groups.addGroup')}
          </span>
        }
        footer={
          <>
            <button
              onClick={() => setGroupModalOpen(false)}
              className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors"
            >
              {t('common.cancel')}
            </button>
            <button onClick={handleSaveGroup} disabled={savingGroup} className="btn-primary py-2 px-6">
              {t('common.save')}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('groups.name')}</label>
            <input
              type="text"
              value={groupForm.name}
              onChange={(e) => setGroupForm((p) => ({ ...p, name: e.target.value }))}
              className="input-field"
              placeholder={t('groups.namePlaceholder')}
              autoFocus
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('groups.description')}</label>
            <textarea
              value={groupForm.description}
              onChange={(e) => setGroupForm((p) => ({ ...p, description: e.target.value }))}
              rows={2}
              className="input-field resize-none"
              placeholder={t('groups.descriptionPlaceholder')}
            />
          </div>
        </div>
      </Modal>

      {/* Delete group confirmation */}
      <ConfirmModal
        isOpen={!!groupToDelete}
        onClose={() => setGroupToDelete(null)}
        onConfirm={handleDeleteGroup}
        title={t('groups.deleteConfirmTitle')}
        body={t('groups.deleteConfirmBody').replace('{name}', groupToDelete?.name ?? '')}
        confirmLabel={t('common.delete')}
        danger
      />
    </div>
  );
}
