import { useState } from 'react';
import api from '../../lib/api';
import { useI18n, LANGUAGES } from '../../lib/i18n';
import { useTheme, type Theme } from '../../lib/theme';
import { useToast } from '../../components/ui';
import { Globe, KeyRound, Sun, Moon, Monitor, Palette, Tags, Info, Layers } from 'lucide-react';
import { cn } from '../../lib/utils';
import { useSearchParams } from 'react-router-dom';
import DocumentTypesAdmin from '../../components/DocumentTypesAdmin';
import BrandingAdmin from '../../components/BrandingAdmin';
import AboutPanel from '../../components/AboutPanel';
import GroupsAdmin from '../../components/GroupsAdmin';

/**
 * Settings are split into sections rather than stacked on one page: personal
 * preferences, installation identity and the document taxonomy are different
 * jobs done by different people at different times, and scrolling past an
 * organisation's logo settings to change your own password made none of them
 * easy to find. New sections slot into the list below without touching layout.
 */
type Section = 'appearance' | 'security' | 'branding' | 'types' | 'groups' | 'about';

export default function SettingsPage() {
  const { t, lang, setLang } = useI18n();
  const { theme, resolvedTheme, setTheme } = useTheme();
  const { toast } = useToast();

  // The taxonomy panel is admin-only on the server; hiding it for everyone else
  // avoids showing a section whose every action would come back 403.
  const me = JSON.parse(localStorage.getItem('user_info') || '{}');
  const roles: string[] = me?.roles ?? (me?.role ? [me.role] : []);
  const isAdmin = roles.some((r) => String(r).toUpperCase().includes('ADMIN'));

  const [searchParams, setSearchParams] = useSearchParams();
  // The tab lives in the URL so "Manage types" can link straight to it and a
  // reload keeps the person where they were.
  const active = (searchParams.get('tab') as Section) || 'appearance';
  const setActive = (s: Section) => setSearchParams({ tab: s }, { replace: true });

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [saving, setSaving] = useState(false);

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPassword.length < 6) {
      toast('error', t('settings.passwordTooShort'));
      return;
    }
    if (newPassword !== confirmPassword) {
      toast('error', t('settings.passwordMismatch'));
      return;
    }
    setSaving(true);
    try {
      await api.put('/users/me/password', { currentPassword, newPassword });
      toast('success', t('settings.passwordChanged'));
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      console.error(err);
      toast('error', t('settings.passwordError'));
    } finally {
      setSaving(false);
    }
  };

  const sections: { id: Section; label: string; Icon: typeof Globe }[] = [
    { id: 'appearance', label: t('settings.sectionAppearance'), Icon: Sun },
    { id: 'security', label: t('settings.sectionSecurity'), Icon: KeyRound },
    ...(isAdmin ? [
      { id: 'branding' as Section, label: t('settings.sectionBranding'), Icon: Palette },
      { id: 'types' as Section, label: t('settings.sectionTypes'), Icon: Tags },
      { id: 'groups' as Section, label: t('settings.sectionGroups'), Icon: Layers },
    ] : []),
    { id: 'about', label: t('settings.sectionAbout'), Icon: Info },
  ];

  return (
    <div className="flex flex-col gap-6 max-w-5xl">
      <div>
        <h1 className="text-2xl font-bold text-textMain">{t('settings.title')}</h1>
      </div>

      <div className="flex flex-col lg:flex-row gap-6">
        {/* Section list. Admin-only sections are hidden rather than disabled:
            every action inside them would come back 403. */}
        <nav className="lg:w-56 shrink-0 flex lg:flex-col gap-1 overflow-x-auto lg:overflow-visible"
             aria-label={t('settings.title')}>
          {sections.map((s) => (
            <button
              key={s.id}
              onClick={() => setActive(s.id)}
              aria-current={active === s.id}
              className={cn(
                'flex items-center gap-2.5 px-3 py-2 rounded-md text-sm text-left whitespace-nowrap transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                active === s.id
                  ? 'bg-primary/10 text-primary font-medium'
                  : 'text-textMuted hover:bg-elevated hover:text-textMain',
              )}
            >
              <s.Icon className="w-4 h-4 shrink-0" />
              {s.label}
            </button>
          ))}
        </nav>

        <div className="flex-1 min-w-0 space-y-6">

      {active === 'branding' && isAdmin && <BrandingAdmin />}
      {active === 'types' && isAdmin && <DocumentTypesAdmin />}
      {active === 'groups' && isAdmin && <GroupsAdmin />}
      {active === 'about' && <AboutPanel />}

      {/* Language */}
      {active === 'appearance' && (<>
      <div className="card">
        <h2 className="font-semibold text-textMain flex items-center gap-2 mb-4">
          <Globe className="w-4 h-4 text-primary" />
          {t('settings.language')}
        </h2>
        {/* Sem bandeiras, de propósito: uma bandeira é um país e um idioma não
            é. O espanhol não é da Espanha mais do que do México, e a bandeira
            do Brasil deixaria de fora quem fala português noutro sítio. O nome
            escrito na própria língua identifica sem excluir ninguém. */}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {LANGUAGES.map((option) => (
            <button
              key={option.code}
              onClick={() => setLang(option.code)}
              aria-pressed={lang === option.code}
              className={cn(
                'flex items-center gap-3 p-4 rounded-lg border-2 transition-all text-left',
                lang === option.code
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-borderLight text-textMuted hover:border-primary/40'
              )}
            >
              <span className="text-sm font-semibold w-10 shrink-0">{option.short}</span>
              <span className="text-sm font-medium">{option.label}</span>
            </button>
          ))}
        </div>
        <p className="text-xs text-textMuted mt-3">{t('settings.languageHint')}</p>
      </div>

      {/* Appearance */}
      <div className="card">
        <h2 className="font-semibold text-textMain flex items-center gap-2 mb-4">
          {resolvedTheme === 'dark'
            ? <Moon className="w-4 h-4 text-primary" />
            : <Sun className="w-4 h-4 text-primary" />}
          {t('settings.appearance')}
        </h2>
        <div className="grid grid-cols-3 gap-3">
          {([
            { code: 'light', label: t('settings.themeLight'), Icon: Sun },
            { code: 'dark', label: t('settings.themeDark'), Icon: Moon },
            { code: 'system', label: t('settings.themeSystem'), Icon: Monitor },
          ] as { code: Theme; label: string; Icon: typeof Sun }[]).map((option) => (
            <button
              key={option.code}
              onClick={() => setTheme(option.code)}
              aria-pressed={theme === option.code}
              className={cn(
                'flex flex-col items-center gap-2 p-4 rounded-lg border-2 transition-all',
                theme === option.code
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-borderLight text-textMuted hover:border-primary/40'
              )}
            >
              <option.Icon className="w-5 h-5" />
              <span className="text-sm font-medium">{option.label}</span>
            </button>
          ))}
        </div>
        {theme === 'system' && (
          <p className="text-xs text-textMuted mt-3">
            {t('settings.themeSystemHint').replace(
              '{mode}',
              resolvedTheme === 'dark' ? t('settings.themeDark') : t('settings.themeLight'),
            )}
          </p>
        )}
      </div>

      </>)}

      {/* Security — change own password */}
      {active === 'security' && (
      <div className="card">
        <h2 className="font-semibold text-textMain flex items-center gap-2 mb-4">
          <KeyRound className="w-4 h-4 text-primary" />
          {t('settings.changePassword')}
        </h2>
        <form onSubmit={handleChangePassword} className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('settings.currentPassword')}</label>
            <input
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              className="input-field"
              required
              autoComplete="current-password"
            />
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('settings.newPassword')}</label>
              <input
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                className="input-field"
                required
                minLength={6}
                autoComplete="new-password"
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('settings.confirmPassword')}</label>
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="input-field"
                required
                minLength={6}
                autoComplete="new-password"
              />
            </div>
          </div>
          <div className="flex justify-end">
            <button type="submit" disabled={saving} className="btn-primary py-2 px-6">
              {saving ? t('common.loading') : t('common.save')}
            </button>
          </div>
        </form>
      </div>
      )}
        </div>
      </div>
    </div>
  );
}
