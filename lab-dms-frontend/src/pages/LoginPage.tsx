import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../lib/api';
import { useAuthStore } from '../store/authStore';
import { Lock, Mail, FlaskConical, ArrowLeft } from 'lucide-react';
import { motion } from 'framer-motion';
import { useI18n } from '../lib/i18n';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();
  const login = useAuthStore(state => state.login);
  const { t } = useI18n();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setIsLoading(true);

    try {
      const res = await api.post('/auth/login', { username: email, password });
      login(res.data.accessToken, res.data.user);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || t('auth.loginError'));
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <motion.div 
      initial={{ opacity: 0 }} 
      animate={{ opacity: 1 }} 
      exit={{ opacity: 0 }}
      className="min-h-screen bg-surface flex flex-col justify-center items-center p-4"
    >
      <motion.div 
        initial={{ y: 20, opacity: 0 }} 
        animate={{ y: 0, opacity: 1 }} 
        transition={{ delay: 0.1 }}
        className="w-full max-w-md card"
      >
        <div className="flex flex-col items-center mb-8">
          <div className="w-16 h-16 bg-primary rounded-xl flex items-center justify-center mb-4 shadow-sm">
            <FlaskConical className="text-white w-8 h-8" />
          </div>
          <h1 className="text-2xl font-bold text-textMain tracking-tight">{t('auth.loginTitle')}</h1>
          <p className="text-textMuted text-sm mt-1">{t('auth.loginSubtitle')}</p>
        </div>

        <form onSubmit={handleLogin} className="space-y-5">
          {error && (
            <div className="bg-red-50 text-red-600 p-3 rounded-md text-sm border border-red-100 flex items-center">
              <span>{error}</span>
            </div>
          )}

          <div className="space-y-1">
            <label className="text-sm font-medium text-textMain block">{t('auth.email')}</label>
            <div className="relative">
              <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-textMuted" />
              <input 
                type="email" 
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="input-field pl-10"
                placeholder="admin@email.com"
                required
                disabled={isLoading}
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-sm font-medium text-textMain block">{t('auth.password')}</label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-textMuted" />
              <input 
                type="password" 
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="input-field pl-10"
                placeholder="••••••••"
                required
                disabled={isLoading}
              />
            </div>
          </div>

          <button 
            type="submit" 
            className="w-full btn-primary py-2.5 mt-2 flex justify-center items-center"
            disabled={isLoading}
          >
            {isLoading ? t('auth.signingIn') : t('auth.login')}
          </button>

          <div className="text-center mt-4">
            <p className="text-sm text-textMuted">
              {t('auth.noAccount')}{' '}
              <button
                type="button"
                onClick={() => navigate('/register')}
                className="text-primary hover:underline font-medium focus:outline-none"
              >
                {t('auth.register')}
              </button>
            </p>
          </div>
        </form>

        <div className="mt-8 text-center border-t border-borderLight pt-6">
          <button onClick={() => navigate('/')} className="text-sm text-textMuted hover:text-primary flex items-center justify-center gap-2 mx-auto transition-colors">
            <ArrowLeft className="w-4 h-4" />
            Back to Public Portal
          </button>
        </div>
      </motion.div>
      
    </motion.div>
  );
}
