import { createContext, useContext, useEffect, useState, useCallback, type ReactNode } from 'react';
import api from './api';

export interface Branding {
  organisationName: string;
  logoDataUrl: string | null;
  primaryColor: string;
}

const DEFAULTS: Branding = {
  organisationName: 'Lab DMS',
  logoDataUrl: null,
  primaryColor: '#2563EB',
};

interface BrandingContextValue {
  branding: Branding;
  reload: () => Promise<void>;
  applyPreview: (partial: Partial<Branding>) => void;
}

const BrandingContext = createContext<BrandingContextValue>({
  branding: DEFAULTS,
  reload: async () => {},
  applyPreview: () => {},
});

/* ------------------------------ colour maths ------------------------------ */

function hexToRgb(hex: string): [number, number, number] {
  const clean = hex.replace('#', '');
  return [
    parseInt(clean.slice(0, 2), 16),
    parseInt(clean.slice(2, 4), 16),
    parseInt(clean.slice(4, 6), 16),
  ];
}

/**
 * Relative luminance, per WCAG. Used to decide whether text sitting ON the
 * accent should be white or near-black — a yellow brand and a navy brand need
 * opposite answers, and picking one for everyone makes buttons unreadable for
 * whoever chose the other.
 */
function luminance([r, g, b]: [number, number, number]): number {
  const channel = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function mix([r, g, b]: [number, number, number], target: number, amount: number): string {
  const m = (c: number) => Math.round(c + (target - c) * amount);
  return `${m(r)} ${m(g)} ${m(b)}`;
}

/**
 * Derives the accent shades from one colour and writes them as CSS variables.
 *
 * Only the accent is derived. The neutrals, spacing, typography and the light
 * and dark palettes stay exactly as designed, so an organisation can look like
 * itself without anyone being able to break legibility from a settings screen.
 */
export function applyPrimaryColor(hex: string, isDark = document.documentElement.classList.contains('dark')) {
  if (!/^#[0-9A-Fa-f]{6}$/.test(hex)) return;
  const rgb = hexToRgb(hex);
  const root = document.documentElement;

  // Writing these inline on <html> overrides BOTH the :root block and the .dark
  // block, so the dark palette's lighter accent would be lost. The dark variant
  // is therefore derived here instead: the stylesheet's own pairing goes from a
  // deep blue in light mode to a much lighter one in dark, and an accent that
  // keeps its light-mode darkness would sit unreadably on a dark surface.
  const base = isDark ? hexToRgb(lighten(hex, 0.45)) : rgb;

  root.style.setProperty('--color-primary', base.join(' '));
  root.style.setProperty('--color-primary-dark', mix(base, 0, isDark ? 0.15 : 0.3));
  root.style.setProperty('--color-primary-light', mix(base, 255, isDark ? 0.25 : 0.4));

  // Contrast-aware foreground for anything placed ON the accent. A yellow brand
  // and a navy brand need opposite answers; assuming white makes yellow buttons
  // illegible.
  root.style.setProperty('--color-on-primary', luminance(base) > 0.55 ? '17 24 39' : '255 255 255');
}

function lighten(hex: string, amount: number): string {
  const [r, g, b] = hexToRgb(hex);
  const m = (c: number) => Math.round(c + (255 - c) * amount);
  return `#${[m(r), m(g), m(b)].map((c) => c.toString(16).padStart(2, '0')).join('')}`;
}

/* -------------------------------- provider -------------------------------- */

export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(DEFAULTS);

  const reload = useCallback(async () => {
    try {
      const res = await api.get('/branding');
      const next: Branding = { ...DEFAULTS, ...res.data };
      setBranding(next);
      applyPrimaryColor(next.primaryColor);
      document.title = next.organisationName;
    } catch {
      // An unreachable branding endpoint must not stop the app from rendering:
      // it is decoration, not function.
      applyPrimaryColor(DEFAULTS.primaryColor);
    }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  // The accent has a light and a dark variant, so it has to be recomputed when
  // the theme flips — including the automatic flip when the operating system
  // switches at dusk, which no click in this app triggers.
  useEffect(() => {
    const root = document.documentElement;
    const observer = new MutationObserver(() => applyPrimaryColor(branding.primaryColor));
    observer.observe(root, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, [branding.primaryColor]);

  /** Live preview while the administrator is still choosing, before saving. */
  const applyPreview = useCallback((partial: Partial<Branding>) => {
    setBranding((prev) => {
      const next = { ...prev, ...partial };
      if (partial.primaryColor) applyPrimaryColor(partial.primaryColor);
      return next;
    });
  }, []);

  return (
    <BrandingContext.Provider value={{ branding, reload, applyPreview }}>
      {children}
    </BrandingContext.Provider>
  );
}

export function useBranding() {
  return useContext(BrandingContext);
}
