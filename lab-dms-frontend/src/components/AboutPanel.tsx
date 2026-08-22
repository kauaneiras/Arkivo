import { ShieldCheck, BookOpen, History } from 'lucide-react';
import { useI18n } from '../lib/i18n';
import { useBranding } from '../lib/branding';

/**
 * What this system claims to do, and against which standards.
 *
 * This page exists so the interface can stop explaining itself. Every screen
 * used to carry its own footnote ("ISO 17025 8.4 — the standard requires the
 * laboratory to define the period"), which turned normal fields into a lecture
 * and still left nobody with the whole picture. The reasoning belongs in one
 * place a person reads once.
 */

interface Clause {
  ref: string;
  title: string;
  how: string;
}

export default function AboutPanel() {
  const { t } = useI18n();
  const { branding } = useBranding();

  const clauses: Clause[] = [
    { ref: 'ISO 9000 / 9001 §7.5', title: t('about.c.9001.title'), how: t('about.c.9001.how') },
    { ref: 'ISO/IEC 17025 §8.3.1', title: t('about.c.831.title'), how: t('about.c.831.how') },
    { ref: 'ISO/IEC 17025 §8.3.2 a)', title: t('about.c.832a.title'), how: t('about.c.832a.how') },
    { ref: 'ISO/IEC 17025 §8.3.2 b)', title: t('about.c.832b.title'), how: t('about.c.832b.how') },
    { ref: 'ISO/IEC 17025 §8.3.2 c)', title: t('about.c.832c.title'), how: t('about.c.832c.how') },
    { ref: 'ISO/IEC 17025 §8.3.2 d)', title: t('about.c.832d.title'), how: t('about.c.832d.how') },
    { ref: 'ISO/IEC 17025 §8.3.2 f)', title: t('about.c.832f.title'), how: t('about.c.832f.how') },
    { ref: 'ISO/IEC 17025 §8.4', title: t('about.c.84.title'), how: t('about.c.84.how') },
  ];

  return (
    <div className="space-y-6">
      <div className="card p-6">
        <div className="flex items-center gap-2 mb-1">
          <BookOpen className="w-5 h-5 text-primary" />
          <h3 className="font-semibold text-textMain">{t('about.whatTitle')}</h3>
        </div>
        <p className="text-sm text-textMuted leading-relaxed">
          {t('about.whatBody').replace('{org}', branding.organisationName)}
        </p>
      </div>

      <div className="card p-6">
        <div className="flex items-center gap-2 mb-1">
          <ShieldCheck className="w-5 h-5 text-primary" />
          <h3 className="font-semibold text-textMain">{t('about.standardsTitle')}</h3>
        </div>
        <p className="text-sm text-textMuted mb-4">{t('about.standardsIntro')}</p>

        <div className="divide-y divide-borderLight border border-borderLight rounded-lg overflow-hidden">
          {clauses.map((c) => (
            <div key={c.ref} className="p-3 sm:flex sm:gap-4">
              <div className="sm:w-56 shrink-0">
                <span className="text-xs font-mono font-semibold text-primary">{c.ref}</span>
                <p className="text-sm font-medium text-textMain">{c.title}</p>
              </div>
              <p className="text-sm text-textMuted mt-1 sm:mt-0">{c.how}</p>
            </div>
          ))}
        </div>

        {/* An honest boundary. Software supports conformity; it does not confer
            it, and a laboratory that believed otherwise would be unpleasantly
            surprised at its assessment. */}
        <p className="text-xs text-textMuted mt-4 leading-relaxed border-l-2 border-amber-300 pl-3">
          {t('about.disclaimer')}
        </p>
      </div>

      <div className="card p-6">
        <div className="flex items-center gap-2 mb-1">
          <History className="w-5 h-5 text-primary" />
          <h3 className="font-semibold text-textMain">{t('about.releasesTitle')}</h3>
        </div>
        <p className="text-sm text-textMuted mb-4">{t('about.releasesIntro')}</p>

        <div className="border border-borderLight rounded-lg p-4">
          <div className="flex items-baseline gap-3 mb-2">
            <span className="text-sm font-bold text-textMain">1.0</span>
            <span className="text-xs text-textMuted">{t('about.releaseCurrent')}</span>
          </div>
          <ul className="text-sm text-textMuted space-y-1 list-disc list-inside">
            <li>{t('about.r1.a')}</li>
            <li>{t('about.r1.b')}</li>
            <li>{t('about.r1.c')}</li>
            <li>{t('about.r1.d')}</li>
            <li>{t('about.r1.e')}</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
