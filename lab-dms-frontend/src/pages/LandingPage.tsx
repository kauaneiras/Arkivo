import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../lib/api';
import { FileText, ArrowRight, ShieldCheck, Database, BookOpen } from 'lucide-react';
import { motion } from 'framer-motion';

export default function LandingPage() {
  const navigate = useNavigate();
  const [publicDocs, setPublicDocs] = useState<any[]>([]);

  useEffect(() => {
    api.get('/documents/public')
      .then(res => setPublicDocs(res.data.content || res.data.data || []))
      .catch(err => console.error('Failed to fetch public docs', err));
  }, []);

  return (
    <motion.div 
      initial={{ opacity: 0 }} 
      animate={{ opacity: 1 }} 
      exit={{ opacity: 0 }}
      className="min-h-screen bg-background flex flex-col"
    >
      {/* Header */}
      <header className="bg-white border-b border-borderLight px-6 py-4 flex justify-between items-center sticky top-0 z-10 shadow-sm">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-primary rounded-lg flex items-center justify-center shadow-sm">
            <FileText className="text-white w-5 h-5" />
          </div>
          <span className="font-bold text-xl text-primary">Lab DMS</span>
        </div>
        <button 
          onClick={() => navigate('/login')}
          className="btn-primary"
        >
          Sign In
        </button>
      </header>

      {/* Hero Section */}
      <section className="bg-white px-6 py-24 flex flex-col items-center text-center">
        <motion.h1 
          initial={{ y: 20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          transition={{ delay: 0.1 }}
          className="text-5xl font-extrabold text-textMain tracking-tight max-w-3xl leading-tight"
        >
          Modern Document Management for <span className="text-primary">ISO/IEC 17025</span>
        </motion.h1>
        <motion.p 
          initial={{ y: 20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          transition={{ delay: 0.2 }}
          className="mt-6 text-xl text-textMuted max-w-2xl"
        >
          A secure, Git-like version control system designed specifically for laboratories to maintain, track, and approve standard operating procedures with full traceability.
        </motion.p>
        
        <motion.div 
          initial={{ y: 20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          transition={{ delay: 0.3 }}
          className="mt-10 flex gap-4"
        >
          <button onClick={() => navigate('/login')} className="btn-primary px-8 py-3 text-lg flex items-center gap-2">
            Access Portal <ArrowRight className="w-5 h-5" />
          </button>
        </motion.div>
      </section>

      {/* Features */}
      <section className="py-16 px-6 bg-surface border-y border-borderLight">
        <div className="max-w-6xl mx-auto grid grid-cols-1 md:grid-cols-3 gap-8">
          <motion.div whileHover={{ y: -5 }} className="card text-center items-center flex flex-col">
            <div className="w-14 h-14 bg-blue-50 text-primary rounded-full flex items-center justify-center mb-6">
              <ShieldCheck className="w-7 h-7" />
            </div>
            <h3 className="font-bold text-lg mb-2 text-textMain">ISO Compliant</h3>
            <p className="text-textMuted text-sm">Strict versioning and approval workflows to meet rigorous auditing standards.</p>
          </motion.div>
          <motion.div whileHover={{ y: -5 }} className="card text-center items-center flex flex-col">
            <div className="w-14 h-14 bg-green-50 text-secondary rounded-full flex items-center justify-center mb-6">
              <Database className="w-7 h-7" />
            </div>
            <h3 className="font-bold text-lg mb-2 text-textMain">Immutable History</h3>
            <p className="text-textMuted text-sm">Every change is committed and stored securely. Nothing is ever truly deleted.</p>
          </motion.div>
          <motion.div whileHover={{ y: -5 }} className="card text-center items-center flex flex-col">
            <div className="w-14 h-14 bg-purple-50 text-purple-600 rounded-full flex items-center justify-center mb-6">
              <FileText className="w-7 h-7" />
            </div>
            <h3 className="font-bold text-lg mb-2 text-textMain">Live LaTeX Engine</h3>
            <p className="text-textMuted text-sm">Write your documents in LaTeX and see the compiled PDF in real-time.</p>
          </motion.div>
        </div>
      </section>

      {/* Public Documents */}
      <section className="flex-1 max-w-6xl w-full mx-auto p-6 py-16">
        <div className="mb-8">
          <h2 className="text-2xl font-bold text-textMain flex items-center gap-2">
            <BookOpen className="w-6 h-6 text-primary" />
            Public Laboratory Documents
          </h2>
          <p className="text-textMuted mt-1">Official, approved procedures currently in effect.</p>
        </div>

        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-surface text-textMuted border-b border-borderLight uppercase text-xs font-semibold">
                <tr>
                  <th className="px-6 py-4">Document Code</th>
                  <th className="px-6 py-4">Title</th>
                  <th className="px-6 py-4">Type</th>
                  <th className="px-6 py-4">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-borderLight">
                {publicDocs.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="px-6 py-12 text-center text-textMuted">
                      No public documents available yet.
                    </td>
                  </tr>
                ) : (
                  publicDocs.map(doc => (
                    <tr key={doc.id} className="hover:bg-surface transition-colors">
                      <td className="px-6 py-4 font-medium text-textMain">{doc.code}</td>
                      <td className="px-6 py-4 text-textMain">{doc.title}</td>
                      <td className="px-6 py-4 text-textMuted">{doc.docType}</td>
                      <td className="px-6 py-4">
                        <span className="px-2.5 py-1 rounded-full text-xs font-medium bg-green-50 text-secondary border border-green-200">
                          {doc.status}
                        </span>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <footer className="bg-white border-t border-borderLight py-8 text-center text-textMuted text-sm">
        <p>© 2026 Lab DMS. ISO/IEC 17025 Compliance System.</p>
      </footer>
    </motion.div>
  );
}
