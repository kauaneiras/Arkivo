/* Theme: light / dark / system, persisted per browser. */
import {
  createContext, useContext, useEffect, useState, useCallback, type ReactNode,
} from 'react';

export type Theme = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'lab_dms_theme';

interface ThemeContextValue {
  /** What the user chose — may be 'system'. */
  theme: Theme;
  /** What is actually on screen right now — never 'system'. */
  resolvedTheme: 'light' | 'dark';
  setTheme: (theme: Theme) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'system',
  resolvedTheme: 'light',
  setTheme: () => {},
});

export function useTheme() {
  return useContext(ThemeContext);
}

function prefersDark() {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-color-scheme: dark)').matches;
}

function readStoredTheme(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
}

function applyToDocument(resolved: 'light' | 'dark') {
  document.documentElement.classList.toggle('dark', resolved === 'dark');
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => readStoredTheme());
  const [resolvedTheme, setResolvedTheme] = useState<'light' | 'dark'>(
    () => (readStoredTheme() === 'system' ? (prefersDark() ? 'dark' : 'light') : readStoredTheme() as 'light' | 'dark'),
  );

  // Apply the class and keep following the OS while the choice is 'system'
  useEffect(() => {
    const resolve = (): 'light' | 'dark' =>
      theme === 'system' ? (prefersDark() ? 'dark' : 'light') : theme;

    const next = resolve();
    setResolvedTheme(next);
    applyToDocument(next);

    if (theme !== 'system') return;

    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => {
      const updated = prefersDark() ? 'dark' : 'light';
      setResolvedTheme(updated);
      applyToDocument(updated);
    };
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    localStorage.setItem(STORAGE_KEY, next);
    setThemeState(next);
  }, []);

  return (
    <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}
