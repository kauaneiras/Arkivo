import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import LandingPage from './pages/LandingPage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import SsoCallbackPage from './pages/SsoCallbackPage';
import DashboardLayout from './pages/dashboard/DashboardLayout';
import DashboardHomePage from './pages/dashboard/DashboardHomePage';
import DocumentsPage from './pages/dashboard/DocumentsPage';
import DocumentEditorPage from './pages/dashboard/DocumentEditorPage';
import ReviewsPage from './pages/dashboard/ReviewsPage';
import AuditPage from './pages/dashboard/AuditPage';
import UsersPage from './pages/dashboard/UsersPage';
import ExternalDocumentsPage from './pages/dashboard/ExternalDocumentsPage';
import SettingsPage from './pages/dashboard/SettingsPage';
import { AnimatePresence } from 'framer-motion';
import { I18nProvider } from './lib/i18n';
import { ThemeProvider } from './lib/theme';
import { BrandingProvider } from './lib/branding';
import { ToastProvider } from './components/ui';

function App() {
  return (
    <ThemeProvider>
    <BrandingProvider>
    <I18nProvider>
      <ToastProvider>
        <Router>
          <AnimatePresence mode="wait">
            <Routes>
              <Route path="/" element={<LandingPage />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />
              <Route path="/auth/callback" element={<SsoCallbackPage />} />

              <Route path="/dashboard" element={<DashboardLayout />}>
                <Route index element={<DashboardHomePage />} />
                <Route path="documents" element={<DocumentsPage />} />
                <Route path="documents/:id/edit" element={<DocumentEditorPage />} />
                <Route path="external-documents" element={<ExternalDocumentsPage />} />
                <Route path="reviews" element={<ReviewsPage />} />
                <Route path="audit" element={<AuditPage />} />
                <Route path="users" element={<UsersPage />} />
                <Route path="settings" element={<SettingsPage />} />
              </Route>

              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </AnimatePresence>
        </Router>
      </ToastProvider>
    </I18nProvider>
    </BrandingProvider>
    </ThemeProvider>
  );
}

export default App;
