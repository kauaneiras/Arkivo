/* i18n do Lab DMS. Os dicionários vivem em ./locales, um ficheiro por idioma. */
import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import pt from './locales/pt';
import en from './locales/en';
import fr from './locales/fr';
import de from './locales/de';
import zh from './locales/zh';
import es from './locales/es';

export type Lang = 'pt' | 'en' | 'fr' | 'de' | 'zh' | 'es';

const dictionaries: Record<Lang, Record<string, string>> = { pt, en, fr, de, zh, es };

/**
 * Os idiomas na ordem em que aparecem ao utilizador, com o nome escrito na
 * própria língua.
 *
 * Escrever "Deutsch" e não "Alemão" é o que permite a alguém que abriu o sistema
 * num idioma que não lê encontrar o seu: se a lista estiver traduzida para uma
 * língua que a pessoa não entende, ela não reconhece nenhuma das opções.
 */
export const LANGUAGES: { code: Lang; label: string; short: string }[] = [
  { code: 'pt', label: 'Português', short: 'PT' },
  { code: 'en', label: 'English', short: 'EN' },
  { code: 'es', label: 'Español', short: 'ES' },
  { code: 'fr', label: 'Français', short: 'FR' },
  { code: 'de', label: 'Deutsch', short: 'DE' },
  { code: 'zh', label: '中文', short: '中文' },
];

const STORAGE_KEY = 'lab_dms_lang';

function isLang(value: unknown): value is Lang {
  return typeof value === 'string' && value in dictionaries;
}

interface I18nContextValue {
  lang: Lang;
  setLang: (lang: Lang) => void;
  t: (key: string) => string;
}

const I18nContext = createContext<I18nContextValue>({
  lang: 'pt',
  setLang: () => {},
  t: (key) => key,
});

export function I18nProvider({ children }: { children: ReactNode }) {
  // O valor guardado no navegador é lido de imediato, para a página não abrir
  // num idioma e trocar logo a seguir. A conta é a fonte de verdade e chega um
  // instante depois, no efeito abaixo.
  const [lang, setLangState] = useState<Lang>(() => {
    const stored = localStorage.getItem(STORAGE_KEY);
    return isLang(stored) ? stored : 'pt';
  });

  const setLang = useCallback((newLang: Lang) => {
    localStorage.setItem(STORAGE_KEY, newLang);
    setLangState(newLang);
    // Gravar na conta é o que faz a escolha acompanhar a pessoa para outro
    // computador. Falhar aqui não desfaz a troca no ecrã: o idioma continua
    // aplicado localmente e volta a ser tentado na próxima mudança — perder a
    // preferência por causa de uma rede intermitente seria pior que o contrário.
    void (async () => {
      try {
        if (!localStorage.getItem('jwt_token')) return;
        const { default: api } = await import('./api');
        await api.put('/users/me/language', { language: newLang });
      } catch { /* fica só neste navegador desta vez */ }
    })();
  }, []);

  // Ao carregar com sessão aberta, a preferência da conta manda sobre a do
  // navegador: é ela que segue a pessoa entre máquinas.
  useEffect(() => {
    void (async () => {
      try {
        if (!localStorage.getItem('jwt_token')) return;
        const { default: api } = await import('./api');
        const res = await api.get('/users/me/language');
        const saved = res.data?.message;
        if (isLang(saved)) {
          localStorage.setItem(STORAGE_KEY, saved);
          setLangState(saved);
        }
      } catch { /* sem rede, fica o do navegador */ }
    })();
    // Só ao montar: depois disto, quem manda é a escolha do utilizador.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const t = useCallback(
    // Recair no português é deliberado: uma chave por traduzir aparece na língua
    // de origem, que alguém consegue ler, em vez do nome técnico da chave.
    (key: string) => dictionaries[lang][key] ?? dictionaries.pt[key] ?? key,
    [lang]
  );

  return <I18nContext.Provider value={{ lang, setLang, t }}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  return useContext(I18nContext);
}

/** Locale do sistema operativo para cada idioma, para datas e números. */
const LOCALES: Record<Lang, string> = {
  pt: 'pt-BR', en: 'en-US', fr: 'fr-FR', de: 'de-DE', zh: 'zh-CN', es: 'es-ES',
};

/** Formats a date according to the current language. */
export function formatDate(value: string | number | Date, lang: Lang, withTime = true): string {
  const date = new Date(value);
  return date.toLocaleString(LOCALES[lang] ?? 'pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    ...(withTime ? { hour: '2-digit', minute: '2-digit' } : {}),
  });
}
