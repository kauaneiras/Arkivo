import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../lib/api';
import { Lock, Mail, User, FlaskConical, ArrowLeft } from 'lucide-react';
import { motion } from 'framer-motion';
import { useI18n } from '../lib/i18n';

export default function RegisterPage() {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();
  const { t } = useI18n();

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setIsLoading(true);

    try {
      await api.post('/auth/register', { email, fullName, password });
      setSuccess(true);
    } catch (err: any) {
      setError(err.response?.data?.message || t('auth.registerError'));
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
          <h1 className="text-2xl font-bold text-textMain tracking-tight">{t('auth.registerTitle')}</h1>
          <p className="text-textMuted text-sm mt-1">{t('auth.registerSubtitle')}</p>
        </div>

        {success ? (
          <div className="bg-green-50 text-green-700 p-4 rounded-md text-sm border border-green-200 text-center space-y-3">
            <p className="font-semibold text-lg">{t('auth.registerSuccess')}</p>
            <button
              onClick={() => navigate('/login')}
              className="mt-4 btn-outline py-2 w-full justify-center text-primary"
            >
              {t('auth.login')}
            </button>
          </div>
        ) : (
          <form onSubmit={handleRegister} className="space-y-5">
            {error && (
              <div className="bg-red-50 text-red-600 p-3 rounded-md text-sm border border-red-100 flex items-center">
                <span>{error}</span>
              </div>
            )}

            <div className="space-y-1">
              <label className="text-sm font-medium text-textMain block">{t('auth.fullName')}</label>
              <div className="relative">
                <User className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-textMuted" />
                <input 
                  type="text" 
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  className="input-field pl-10"
                  placeholder="John Doe"
                  required
                  disabled={isLoading}
                />
              </div>
            </div>

            <div className="space-y-1">
              <label className="text-sm font-medium text-textMain block">{t('auth.email')}</label>
              <div className="relative">
                <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-textMuted" />
                <input 
                  type="email" 
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="input-field pl-10"
                  placeholder="john.doe@email.com"
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
                  minLength={6}
                />
              </div>
            </div>

            <button 
              type="submit" 
              className="w-full btn-primary py-2.5 mt-2 flex justify-center items-center"
              disabled={isLoading}
            >
              {isLoading ? t('auth.registering') : t('auth.register')}
            </button>
          </form>
        )}

        <div className="mt-8 text-center border-t border-borderLight pt-6">
          <button onClick={() => navigate('/login')} className="text-sm text-textMuted hover:text-primary flex items-center justify-center gap-2 mx-auto transition-colors">
            <ArrowLeft className="w-4 h-4" />
            {t('auth.login')}
          </button>
        </div>
      </motion.div>
    </motion.div>
  );
}
