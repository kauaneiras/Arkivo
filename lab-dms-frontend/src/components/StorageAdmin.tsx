import { useCallback, useEffect, useState } from 'react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { useToast } from '../components/ui';
import { humanBytes } from './DiffViewer';
import { Database, HardDrive, Layers, Trash2, RefreshCw, FileText, Image as ImageIcon, Paperclip, GitBranch } from 'lucide-react';
import { cn } from '../lib/utils';

interface TableSize { table: string; rows: number; totalBytes: number; tableBytes: number; indexBytes: number }

interface Overview {
  databaseBytes: number;
  tables: TableSize[];
  documents: number; versions: number; drafts: number; fileRows: number;
  blobs: number; textBlobs: number; binaryBlobs: number;
  logicalBytes: number; uniqueBytes: number; storedBytes: number;
  textStoredBytes: number; binaryStoredBytes: number;
  compiledPdfBytes: number; compiledPdfs: number;
  attachmentBytes: number; attachments: number;
  orphanBlobs: number; orphanBytes: number; gcGraceHours: number;
  dedupSavingsPercent: number;
}

interface DocStorage {
  id: string; code: string; title: string; status: string;
  versions: number; hasDraft: boolean; filesInCurrent: number;
  currentBytes: number; logicalBytes: number; uniqueBytes: number;
  storedBytes: number; compiledPdfBytes: number; attachmentBytes: number;
}

/**
 * Where the bytes are, and what deduplication is saving.
 *
 * Three numbers tell the story of the storage model: what every version would
 * cost copied in full (logical), what the unique content weighs (unique), and
 * what is actually on disk after compression (stored). The gap between the
 * first and the last is the content-addressed design paying for itself.
 */
export default function StorageAdmin() {
  const { t } = useI18n();
  const { toast } = useToast();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [docs, setDocs] = useState<DocStorage[]>([]);
  const [loading, setLoading] = useState(true);
  const [collecting, setCollecting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [o, d] = await Promise.all([api.get('/storage/overview'), api.get('/storage/documents?limit=200')]);
      setOverview(o.data);
      setDocs(d.data);
    } catch (err: any) {
      toast('error', err.response?.data?.message ?? t('common.error'));
    } finally {
      setLoading(false);
    }
  }, [toast, t]);

  useEffect(() => { load(); }, [load]);

  const runGc = async () => {
    setCollecting(true);
    try {
      const res = await api.post('/storage/gc');
      toast('success', t('storage.gcDone').replace('{n}', String(res.data.removed ?? 0)));
      await load();
    } catch (err: any) {
      toast('error', err.response?.data?.message ?? t('common.error'));
    } finally {
      setCollecting(false);
    }
  };

  if (loading && !overview) {
    return <div className="card h-40 animate-pulse bg-gray-50" />;
  }
  if (!overview) return null;

  const maxTable = Math.max(1, ...overview.tables.map((x) => x.totalBytes));
  const maxDoc = Math.max(1, ...docs.map((d) => d.storedBytes));

  const tiles = [
    { icon: Database, label: t('storage.database'), value: humanBytes(overview.databaseBytes), hint: t('storage.databaseHint') },
    { icon: HardDrive, label: t('storage.stored'), value: humanBytes(overview.storedBytes + overview.attachmentBytes),
      hint: t('storage.storedHint').replace('{text}', humanBytes(overview.textStoredBytes)).replace('{bin}', humanBytes(overview.binaryStoredBytes)).replace('{att}', humanBytes(overview.attachmentBytes)) },
    { icon: Layers, label: t('storage.dedup'), value: `${overview.dedupSavingsPercent}%`,
      hint: t('storage.dedupHint').replace('{logical}', humanBytes(overview.logicalBytes)).replace('{stored}', humanBytes(overview.storedBytes)) },
    { icon: GitBranch, label: t('storage.versions'), value: String(overview.versions),
      hint: t('storage.versionsHint').replace('{docs}', String(overview.documents)).replace('{drafts}', String(overview.drafts)).replace('{files}', String(overview.fileRows)) },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="font-semibold text-textMain flex items-center gap-2">
            <HardDrive className="w-4 h-4 text-primary" />
            {t('storage.title')}
          </h2>
          <p className="text-sm text-textMuted mt-1">{t('storage.subtitle')}</p>
        </div>
        <button onClick={load} disabled={loading} className="btn-outline py-1.5 px-3 text-sm flex items-center gap-2">
          <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} /> {t('storage.refresh')}
        </button>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {tiles.map((tile) => (
          <div key={tile.label} className="card p-4">
            <div className="flex items-center gap-2 text-xs text-textMuted"><tile.icon className="w-4 h-4 text-primary" />{tile.label}</div>
            <p className="text-2xl font-bold text-textMain mt-2 leading-none tabular-nums">{tile.value}</p>
            <p className="text-[11px] text-textMuted mt-2 leading-snug">{tile.hint}</p>
          </div>
        ))}
      </div>

      {/* What the bytes are */}
      <div className="card">
        <h3 className="font-semibold text-textMain mb-3">{t('storage.breakdown')}</h3>
        <div className="space-y-2 text-sm">
          {[
            { icon: FileText, label: t('storage.textBlobs').replace('{n}', String(overview.textBlobs)), bytes: overview.textStoredBytes },
            { icon: ImageIcon, label: t('storage.binaryBlobs').replace('{n}', String(overview.binaryBlobs)), bytes: overview.binaryStoredBytes },
            { icon: FileText, label: t('storage.compiledPdfs').replace('{n}', String(overview.compiledPdfs)), bytes: overview.compiledPdfBytes },
            { icon: Paperclip, label: t('storage.attachments').replace('{n}', String(overview.attachments)), bytes: overview.attachmentBytes },
          ].map((row) => {
            const total = overview.textStoredBytes + overview.binaryStoredBytes + overview.compiledPdfBytes + overview.attachmentBytes;
            const pct = total === 0 ? 0 : (row.bytes / total) * 100;
            return (
              <div key={row.label}>
                <div className="flex items-center justify-between mb-1">
                  <span className="flex items-center gap-2 text-textMain"><row.icon className="w-3.5 h-3.5 text-textMuted" />{row.label}</span>
                  <span className="text-textMuted tabular-nums">{humanBytes(row.bytes)}</span>
                </div>
                <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Garbage */}
      <div className={cn('card flex items-center justify-between gap-4', overview.orphanBlobs > 0 ? 'border-amber-200' : '')}>
        <div className="min-w-0">
          <h3 className="font-semibold text-textMain flex items-center gap-2"><Trash2 className="w-4 h-4 text-textMuted" />{t('storage.orphans')}</h3>
          <p className="text-sm text-textMuted mt-1">
            {t('storage.orphansHint').replace('{n}', String(overview.orphanBlobs)).replace('{bytes}', humanBytes(overview.orphanBytes)).replace('{h}', String(overview.gcGraceHours))}
          </p>
        </div>
        <button onClick={runGc} disabled={collecting || overview.orphanBlobs === 0} className="btn-outline py-2 px-4 text-sm whitespace-nowrap">
          {collecting ? t('common.loading') : t('storage.runGc')}
        </button>
      </div>

      {/* Tables */}
      <div className="card">
        <h3 className="font-semibold text-textMain mb-3">{t('storage.tables')}</h3>
        <table className="w-full text-sm">
          <thead className="text-xs uppercase text-textMuted">
            <tr>
              <th className="text-left py-1.5">{t('storage.table')}</th>
              <th className="text-right py-1.5">{t('storage.rows')}</th>
              <th className="text-right py-1.5">{t('storage.data')}</th>
              <th className="text-right py-1.5">{t('storage.indexes')}</th>
              <th className="text-right py-1.5 w-40">{t('storage.total')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-borderLight">
            {overview.tables.map((tb) => (
              <tr key={tb.table}>
                <td className="py-1.5 font-mono text-xs">{tb.table}</td>
                <td className="py-1.5 text-right tabular-nums text-textMuted">{tb.rows.toLocaleString()}</td>
                <td className="py-1.5 text-right tabular-nums text-textMuted">{humanBytes(tb.tableBytes)}</td>
                <td className="py-1.5 text-right tabular-nums text-textMuted">{humanBytes(tb.indexBytes)}</td>
                <td className="py-1.5 text-right">
                  <div className="flex items-center justify-end gap-2">
                    <div className="h-1.5 w-20 bg-gray-100 rounded-full overflow-hidden">
                      <div className="h-full bg-primary rounded-full" style={{ width: `${(tb.totalBytes / maxTable) * 100}%` }} />
                    </div>
                    <span className="tabular-nums w-16 text-right">{humanBytes(tb.totalBytes)}</span>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Per document */}
      <div className="card p-0 overflow-hidden">
        <div className="px-6 py-4 border-b border-borderLight">
          <h3 className="font-semibold text-textMain">{t('storage.perDocument')}</h3>
          <p className="text-xs text-textMuted mt-0.5">{t('storage.perDocumentHint')}</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-surface text-xs uppercase text-textMuted">
              <tr>
                <th className="text-left px-6 py-2">{t('docs.code')}</th>
                <th className="text-left px-6 py-2">{t('docs.docTitle')}</th>
                <th className="text-right px-6 py-2">{t('storage.versions')}</th>
                <th className="text-right px-6 py-2">{t('storage.files')}</th>
                <th className="text-right px-6 py-2">{t('storage.current')}</th>
                <th className="text-right px-6 py-2">{t('storage.logical')}</th>
                <th className="text-right px-6 py-2">{t('storage.storedCol')}</th>
                <th className="text-right px-6 py-2">{t('storage.pdfs')}</th>
                <th className="text-right px-6 py-2">{t('storage.attachmentsCol')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-borderLight">
              {docs.length === 0 && (
                <tr><td colSpan={9} className="px-6 py-6 text-center text-textMuted">{t('common.none')}</td></tr>
              )}
              {docs.map((d) => (
                <tr key={d.id} className="hover:bg-surface/50">
                  <td className="px-6 py-2 font-medium whitespace-nowrap">{d.code}</td>
                  <td className="px-6 py-2 text-textMain truncate max-w-xs" title={d.title}>{d.title}</td>
                  <td className="px-6 py-2 text-right tabular-nums">{d.versions}{d.hasDraft ? ' +' : ''}</td>
                  <td className="px-6 py-2 text-right tabular-nums">{d.filesInCurrent}</td>
                  <td className="px-6 py-2 text-right tabular-nums">{humanBytes(d.currentBytes)}</td>
                  <td className="px-6 py-2 text-right tabular-nums text-textMuted">{humanBytes(d.logicalBytes)}</td>
                  <td className="px-6 py-2 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <div className="h-1.5 w-16 bg-gray-100 rounded-full overflow-hidden">
                        <div className="h-full bg-secondary rounded-full" style={{ width: `${(d.storedBytes / maxDoc) * 100}%` }} />
                      </div>
                      <span className="tabular-nums w-16 text-right">{humanBytes(d.storedBytes)}</span>
                    </div>
                  </td>
                  <td className="px-6 py-2 text-right tabular-nums text-textMuted">{humanBytes(d.compiledPdfBytes)}</td>
                  <td className="px-6 py-2 text-right tabular-nums text-textMuted">{humanBytes(d.attachmentBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
