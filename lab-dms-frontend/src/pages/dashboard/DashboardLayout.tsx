import { useEffect } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import {
  FileText, LogOut, Settings, Users, LayoutDashboard,
  GitPullRequest, ScrollText, FlaskConical, BookMarked,
} from 'lucide-react';
import { motion } from 'framer-motion';
import { cn } from '../../lib/utils';
import { useBranding } from '../../lib/branding';
import { useI18n, type Lang } from '../../lib/i18n';
import NotificationBell from '../../components/NotificationBell';

export default function DashboardLayout() {
  const { user, isAuthenticated, logout } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const { t, lang, setLang } = useI18n();
  const { branding } = useBranding();

  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login');
    }
  }, [isAuthenticated, navigate]);

  if (!isAuthenticated) return null;

  const isAdmin = user?.roles?.includes('ROLE_ADMIN');
  const isReviewer = isAdmin || user?.roles?.includes('ROLE_REVIEWER');

  const navItems = [
    { name: t('nav.dashboard'), path: '/dashboard', icon: LayoutDashboard, exact: true },
    { name: t('nav.documents'), path: '/dashboard/documents', icon: FileText },
    // ISO 17025 8.3.1 covers external documents too — manuals, standards,
    // calibration tables — so they get their own place in the navigation.
    { name: t('nav.external'), path: '/dashboard/external-documents', icon: BookMarked },
    { name: t('nav.reviews'), path: '/dashboard/reviews', icon: GitPullRequest },
    ...(isReviewer ? [{ name: t('nav.audit'), path: '/dashboard/audit', icon: ScrollText }] : []),
    ...(isAdmin ? [{ name: t('nav.users'), path: '/dashboard/users', icon: Users }] : []),
    { name: t('nav.settings'), path: '/dashboard/settings', icon: Settings },
  ];

  const isActive = (item: { path: string; exact?: boolean }) =>
    item.exact ? location.pathname === item.path : location.pathname.startsWith(item.path);

  const currentItem = navItems.find(isActive);

  return (
    <div className="flex h-screen bg-surface">
      {/* Sidebar */}
      <aside className="w-64 bg-white border-r border-borderLight flex flex-col">
        <div className="p-6 flex items-center gap-3 min-w-0">
          {/* The organisation's own logo when it has uploaded one; the generic
              mark otherwise. object-contain, because a logo is whatever shape
              it is and cropping somebody's brand is not ours to do. */}
          {branding.logoDataUrl ? (
            <img
              src={branding.logoDataUrl}
              alt={branding.organisationName}
              className="w-9 h-9 rounded-lg object-contain shrink-0"
            />
          ) : (
            <div className="w-9 h-9 bg-primary rounded-lg flex items-center justify-center shrink-0">
              <FlaskConical className="w-5 h-5" style={{ color: 'rgb(var(--color-on-primary))' }} />
            </div>
          )}
          <span className="font-bold tracking-tight text-textMain text-lg leading-tight truncate"
                title={branding.organisationName}>
            {branding.organisationName}
          </span>
        </div>

        <nav className="flex-1 px-4 space-y-1 mt-2">
          {navItems.map((item) => (
            <button
              key={item.path}
              onClick={() => navigate(item.path)}
              className={cn(
                'w-full flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors duration-200',
                isActive(item)
                  ? 'bg-blue-50 text-primary'
                  : 'text-textMuted hover:bg-surface hover:text-textMain'
              )}
            >
              <item.icon className={cn('w-5 h-5', isActive(item) ? 'text-primary' : 'text-textMuted')} />
              {item.name}
            </button>
          ))}
        </nav>

        <div className="p-4 border-t border-borderLight">
          {/* Language switcher */}
          <div className="flex gap-1 mb-3 px-1">
            {(['pt', 'en'] as Lang[]).map((l) => (
              <button
                key={l}
                onClick={() => setLang(l)}
                className={cn(
                  'flex-1 text-xs font-semibold py-1.5 rounded-md border transition-colors uppercase',
                  lang === l
                    ? 'bg-blue-50 text-primary border-blue-200'
                    : 'text-textMuted border-transparent hover:bg-surface'
                )}
              >
                {l === 'pt' ? 'PT' : 'EN'}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-3 mb-3 px-2">
            <div className="w-8 h-8 bg-secondary/10 text-secondary rounded-full flex items-center justify-center font-bold text-sm">
              {user?.fullName?.charAt(0) || 'U'}
            </div>
            <div className="flex-1 overflow-hidden">
              <p className="text-sm font-medium text-textMain truncate">{user?.fullName}</p>
              <p className="text-xs text-textMuted truncate">
                {user?.roles?.filter((r) => r.startsWith('ROLE_')).map((r) => t(`role.${r}`)).join(', ') || user?.email}
              </p>
            </div>
          </div>
          <button
            onClick={() => { logout(); navigate('/'); }}
            className="w-full flex items-center gap-2 px-3 py-2 text-sm font-medium text-red-600 rounded-md hover:bg-red-50 transition-colors"
          >
            <LogOut className="w-4 h-4" />
            {t('nav.signout')}
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col overflow-hidden">
        <header className="h-14 bg-white border-b border-borderLight flex items-center justify-between px-8 shadow-sm z-10 shrink-0">
          <h2 className="text-base font-semibold text-textMain">
            {currentItem?.name ?? t('nav.documents')}
          </h2>
          <NotificationBell />
        </header>

        <div className="flex-1 overflow-auto p-8 relative">
          <motion.div
            key={location.pathname}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            className="max-w-7xl mx-auto h-full flex flex-col"
          >
            <Outlet />
          </motion.div>
        </div>
      </main>
    </div>
  );
}
