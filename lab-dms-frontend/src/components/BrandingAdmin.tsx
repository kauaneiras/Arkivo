import { useState, useEffect, useRef } from 'react';
import { Palette, Upload, Trash2, Check } from 'lucide-react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { useBranding, applyPrimaryColor } from '../lib/branding';
import { useToast } from './ui';
import { cn } from '../lib/utils';

/** Starting points, not a limit — the colour input accepts anything. */
const SUGGESTED = ['#2563EB', '#0F766E', '#B91C1C', '#B45309', '#7C3AED', '#0F172A'];

/** Roughly the point where a logo stops being a logo and starts being a payload. */
const MAX_LOGO_BYTES = 400 * 1024;

export default function BrandingAdmin() {
  const { t } = useI18n();
  const { toast } = useToast();
  const { branding, reload, applyPreview } = useBranding();

  const [name, setName] = useState(branding.organisationName);
  const [color, setColor] = useState(branding.primaryColor);
  const [logo, setLogo] = useState<string | null>(branding.logoDataUrl);
  const [saving, setSaving] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    setName(branding.organisationName);
    setColor(branding.primaryColor);
    setLogo(branding.logoDataUrl);
  }, [branding]);

  const handleLogo = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    // SVG is refused on the server because it can carry script and the logo is
    // shown before login; saying so here avoids an upload that will be rejected.
    if (!/^image\/(png|jpeg|gif|webp)$/.test(file.type)) {
      toast('error', t('branding.logoType'));
      return;
    }
    if (file.size > MAX_LOGO_BYTES) {
      toast('error', t('branding.logoTooBig').replace('{max}', `${MAX_LOGO_BYTES / 1024} KB`));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      setLogo(dataUrl);
      applyPreview({ logoDataUrl: dataUrl });
    };
    reader.readAsDataURL(file);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await api.put('/branding', {
        organisationName: name.trim(),
        primaryColor: color,
        logoDataUrl: logo,
        removeLogo: logo === null,
      });
      await reload();
      toast('success', t('branding.saved'));
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('branding.saveError'));
      // Put back what is actually stored, so the screen never shows a colour
      // the server rejected as if it had been applied.
      await reload();
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="card p-6 space-y-5">
      <div>
        <div className="flex items-center gap-2 mb-1">
          <Palette className="w-5 h-5 text-primary" />
          <h3 className="font-semibold text-textMain">{t('branding.title')}</h3>
        </div>
        <p className="text-sm text-textMuted">{t('branding.subtitle')}</p>
      </div>

      <div className="space-y-1.5">
        <label className="text-sm font-medium text-textMain">{t('branding.name')}</label>
        <input
          value={name}
          onChange={(e) => { setName(e.target.value); applyPreview({ organisationName: e.target.value }); }}
          className="input-field"
          maxLength={120}
          placeholder="Lab DMS"
        />
        <p className="text-xs text-textMuted">{t('branding.nameHint')}</p>
      </div>

      <div className="space-y-1.5">
        <label className="text-sm font-medium text-textMain">{t('branding.logo')}</label>
        <div className="flex items-center gap-3">
          <div className="w-32 h-14 rounded border border-borderLight bg-surface flex items-center justify-center overflow-hidden shrink-0">
            {logo
              ? <img src={logo} alt={t('branding.logo')} className="max-w-full max-h-full object-contain" />
              : <span className="text-xs text-textMuted">{t('branding.noLogo')}</span>}
          </div>
          <button onClick={() => fileRef.current?.click()}
                  className="btn-outline text-sm py-1.5 px-3 flex items-center gap-1.5">
            <Upload className="w-4 h-4" /> {t('branding.chooseLogo')}
          </button>
          {logo && (
            <button onClick={() => { setLogo(null); applyPreview({ logoDataUrl: null }); }}
                    className="text-sm text-textMuted hover:text-red-600 flex items-center gap-1.5">
              <Trash2 className="w-4 h-4" /> {t('common.delete')}
            </button>
          )}
          <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp"
                 className="hidden" onChange={handleLogo} />
        </div>
        <p className="text-xs text-textMuted">{t('branding.logoHint')}</p>
      </div>

      <div className="space-y-1.5">
        <label className="text-sm font-medium text-textMain">{t('branding.color')}</label>
        <div className="flex items-center gap-2 flex-wrap">
          {SUGGESTED.map((c) => (
            <button
              key={c}
              onClick={() => { setColor(c); applyPrimaryColor(c); applyPreview({ primaryColor: c }); }}
              style={{ backgroundColor: c }}
              aria-label={c}
              className={cn(
                'w-8 h-8 rounded-full border-2 transition-transform hover:scale-110',
                color.toUpperCase() === c ? 'border-textMain scale-110' : 'border-transparent',
              )}
            >
              {color.toUpperCase() === c && <Check className="w-4 h-4 text-white mx-auto drop-shadow" />}
            </button>
          ))}
          <input
            type="color"
            value={color}
            onChange={(e) => { setColor(e.target.value); applyPrimaryColor(e.target.value); applyPreview({ primaryColor: e.target.value }); }}
            className="w-8 h-8 rounded cursor-pointer border border-borderLight bg-transparent"
            title={t('branding.customColor')}
          />
          <span className="text-xs font-mono text-textMuted">{color.toUpperCase()}</span>
        </div>
        <p className="text-xs text-textMuted">{t('branding.colorHint')}</p>
      </div>

      {/* Shows the accent in the two places it actually appears, on this page,
          before the change is committed. */}
      <div className="rounded-lg border border-borderLight p-4 bg-surface">
        <p className="text-xs uppercase tracking-wide text-textMuted mb-3">{t('branding.preview')}</p>
        <div className="flex items-center gap-3 flex-wrap">
          <button className="btn-primary py-1.5 px-4 text-sm">{t('common.save')}</button>
          <button className="btn-outline py-1.5 px-4 text-sm">{t('common.cancel')}</button>
          <span className="text-primary font-medium text-sm">{name || 'Lab DMS'}</span>
          <span className="text-xs font-bold uppercase bg-primary/10 text-primary rounded px-2 py-1">
            {t('branding.badgeExample')}
          </span>
        </div>
      </div>

      <div className="flex justify-end">
        <button onClick={handleSave} disabled={saving || !name.trim()}
                className="btn-primary py-2 px-5 disabled:opacity-50">
          {saving ? t('branding.saving') : t('common.save')}
        </button>
      </div>
    </div>
  );
}
