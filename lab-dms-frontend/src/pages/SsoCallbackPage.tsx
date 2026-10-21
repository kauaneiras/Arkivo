import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { FlaskConical, AlertTriangle } from 'lucide-react';
import api from '../lib/api';
import { finishSsoLogin } from '../lib/sso';
import { useAuthStore } from '../store/authStore';
import { useI18n } from '../lib/i18n';

/**
 * Where the identity provider sends the browser back.
 *
 * Exchanges the code for a token, asks the server who that token is, and
 * opens the session. The server is the one that creates or links the local
 * account; this page only carries the token.
 */
export default function SsoCallbackPage() {
  const navigate = useNavigate();
  const login = useAuthStore((s) => s.login);
  const { t } = useI18n();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { accessToken } = await finishSsoLogin(window.location.search);
        // The store's interceptor reads jwt_token; set it before /auth/me.
        localStorage.setItem('jwt_token', accessToken);
        const me = await api.get('/auth/me');
        if (cancelled) return;
        login(accessToken, me.data);
        navigate('/dashboard', { replace: true });
      } catch (e: any) {
        if (cancelled) return;
        localStorage.removeItem('jwt_token');
        const detail = e?.response?.data?.message || e?.message || '';
        setError(detail);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}
                className="min-h-screen bg-surface flex flex-col justify-center items-center p-4">
      <div className="w-full max-w-md card text-center">
        <div className="w-14 h-14 bg-primary rounded-xl flex items-center justify-center mx-auto mb-4 shadow-sm">
          <FlaskConical className="text-white w-7 h-7" />
        </div>
        {error ? (
          <>
            <div className="flex items-center justify-center gap-2 text-red-700 mb-2">
              <AlertTriangle className="w-5 h-5" />
              <h1 className="text-lg font-bold">{t('auth.ssoFailed')}</h1>
            </div>
            <p className="text-sm text-textMuted break-words">{error}</p>
            <button onClick={() => navigate('/login', { replace: true })} className="btn-primary mt-6 py-2 px-6">
              {t('auth.backToLogin')}
            </button>
          </>
        ) : (
          <>
            <h1 className="text-lg font-bold text-textMain">{t('auth.ssoFinishing')}</h1>
            <p className="text-sm text-textMuted mt-1">{t('auth.ssoFinishingHint')}</p>
          </>
        )}
      </div>
    </motion.div>
  );
}
