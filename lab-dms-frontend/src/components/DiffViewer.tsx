import { useEffect, useMemo, useState, type ReactNode } from 'react';
import api from '../lib/api';
import { useI18n } from '../lib/i18n';
import { cn } from '../lib/utils';
import { highlightPair } from '../lib/diff';
import {
  FilePlus2, FileMinus2, FilePen, FileText, Columns2, Rows3, AlertTriangle, Image as ImageIcon,
} from 'lucide-react';

/* ------------------------------------------------------------------ */
/* Types mirroring DiffService on the server                           */
/* ------------------------------------------------------------------ */

export type ChangeStatus = 'ADDED' | 'REMOVED' | 'MODIFIED' | 'UNCHANGED';

export interface FileChange {
  path: string;
  status: ChangeStatus;
  isText: boolean;
  sizeBefore: number;
  sizeAfter: number;
  additions: number;
  deletions: number;
}

export interface TreeDiff {
  from: string | null;
  to: string | null;
  changes: FileChange[];
  added: number;
  removed: number;
  modified: number;
  unchanged: number;
  additions: number;
  deletions: number;
}

export interface DiffLine {
  kind: 'CONTEXT' | 'ADDED' | 'REMOVED';
  oldLine: number | null;
  newLine: number | null;
  text: string;
}

export interface Hunk {
  oldStart: number;
  oldCount: number;
  newStart: number;
  newCount: number;
  lines: DiffLine[];
}

export interface FileDiff {
  path: string;
  status: ChangeStatus;
  isText: boolean;
  truncated: boolean;
  additions: number;
  deletions: number;
  hunks: Hunk[];
}

interface Props {
  documentId: string;
  /** Base version. Omitted: the parent of `to`. */
  from?: string | null;
  /** Target version. Omitted: the document's current version. */
  to?: string | null;
  /** Already computed (ZIP dry run): nothing is fetched. */
  precomputed?: { tree: TreeDiff; files: Record<string, FileDiff> } | null;
  /** Opens on this file instead of the first changed one. */
  initialPath?: string | null;
  /** Height of the viewer; the two panes scroll inside it. */
  height?: string;
  /** Text shown above the file list (e.g. "v4 compared with v3"). */
  caption?: ReactNode;
  /** Called with the summary once loaded — lets a dialog adapt its buttons. */
  onLoaded?: (tree: TreeDiff) => void;
}

/* ------------------------------------------------------------------ */

const STATUS_ICON = {
  ADDED: FilePlus2,
  REMOVED: FileMinus2,
  MODIFIED: FilePen,
  UNCHANGED: FileText,
} as const;

const STATUS_COLOR: Record<ChangeStatus, string> = {
  ADDED: 'text-green-700',
  REMOVED: 'text-red-700',
  MODIFIED: 'text-amber-700',
  UNCHANGED: 'text-textMuted',
};

const STATUS_LETTER: Record<ChangeStatus, string> = {
  ADDED: 'A', REMOVED: 'D', MODIFIED: 'M', UNCHANGED: '=',
};

export function humanBytes(b: number) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(b < 10240 ? 1 : 0)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

function splitPath(path: string) {
  const i = path.lastIndexOf('/');
  return i < 0 ? { dir: '', name: path } : { dir: path.slice(0, i + 1), name: path.slice(i + 1) };
}

/** The +/− summary pill, GitHub-style. */
export function DiffStat({ additions, deletions, className }: { additions: number; deletions: number; className?: string }) {
  if (!additions && !deletions) return null;
  return (
    <span className={cn('inline-flex items-center gap-1 font-mono text-[11px] tabular-nums shrink-0', className)}>
      {additions > 0 && <span className="text-green-700">+{additions}</span>}
      {deletions > 0 && <span className="text-red-700">−{deletions}</span>}
    </span>
  );
}

/**
 * The GitHub Desktop layout: changed files on the left, the selected file's
 * diff on the right, with old/new line numbers in the gutter.
 *
 * Everything is computed on the server so this component is the same whether
 * it compares two saved versions, the working draft with the last version, or
 * a ZIP that has not been applied yet.
 */
export default function DiffViewer({
  documentId, from, to, precomputed, initialPath, height = '60vh', caption, onLoaded,
}: Props) {
  const { t } = useI18n();
  const [tree, setTree] = useState<TreeDiff | null>(precomputed?.tree ?? null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(initialPath ?? null);
  const [fileDiff, setFileDiff] = useState<FileDiff | null>(null);
  const [loadingFile, setLoadingFile] = useState(false);
  const [split, setSplit] = useState<boolean>(() => {
    try { return localStorage.getItem('labdms.diffSplit') === '1'; } catch { return false; }
  });
  const [showUnchanged, setShowUnchanged] = useState(false);
  const fileCache = useMemo(() => new Map<string, FileDiff>(Object.entries(precomputed?.files ?? {})), [precomputed]);

  useEffect(() => {
    try { localStorage.setItem('labdms.diffSplit', split ? '1' : '0'); } catch { /* private mode */ }
  }, [split]);

  // Tree summary
  useEffect(() => {
    if (precomputed) { setTree(precomputed.tree); onLoaded?.(precomputed.tree); return; }
    if (!to && !from) return;
    let cancelled = false;
    const params = new URLSearchParams();
    if (to) params.append('to', to);
    if (from) params.append('from', from);
    setTree(null); setError(null);
    api.get(`/documents/${documentId}/files/diff?${params}`)
      .then((r) => { if (!cancelled) { setTree(r.data); onLoaded?.(r.data); } })
      .catch((e) => { if (!cancelled) setError(e.response?.data?.message ?? t('common.error')); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId, to, from, precomputed]);

  const changed = useMemo(() => {
    const weight: Record<ChangeStatus, number> = { REMOVED: 0, MODIFIED: 1, ADDED: 2, UNCHANGED: 3 };
    return (tree?.changes ?? [])
      .filter((c) => showUnchanged || c.status !== 'UNCHANGED')
      .sort((a, b) => weight[a.status] - weight[b.status] || a.path.localeCompare(b.path));
  }, [tree, showUnchanged]);

  // Pick the first changed file once the tree arrives
  useEffect(() => {
    if (!tree) return;
    const first = changed.find((c) => c.status !== 'UNCHANGED');
    if (selected && tree.changes.some((c) => c.path === selected)) return;
    setSelected(first?.path ?? null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tree]);

  // Selected file's hunks
  useEffect(() => {
    if (!selected || !tree) { setFileDiff(null); return; }
    const change = tree.changes.find((c) => c.path === selected);
    if (!change) { setFileDiff(null); return; }
    const cached = fileCache.get(selected);
    if (cached) { setFileDiff(cached); return; }
    if (precomputed) {
      // Dry run without a line diff for this file (binary, unchanged or over budget)
      setFileDiff({ path: selected, status: change.status, isText: change.isText, truncated: !change.isText ? false : change.status !== 'UNCHANGED', additions: change.additions, deletions: change.deletions, hunks: [] });
      return;
    }
    if (!change.isText || change.status === 'UNCHANGED') {
      setFileDiff({ path: selected, status: change.status, isText: change.isText, truncated: false, additions: 0, deletions: 0, hunks: [] });
      return;
    }
    let cancelled = false;
    setLoadingFile(true);
    const params = new URLSearchParams({ path: selected });
    if (tree.to) params.append('to', tree.to);
    if (tree.from) params.append('from', tree.from);
    api.get(`/documents/${documentId}/files/diff/file?${params}`)
      .then((r) => { if (!cancelled) { fileCache.set(selected, r.data); setFileDiff(r.data); } })
      .catch(() => { if (!cancelled) setFileDiff(null); })
      .finally(() => { if (!cancelled) setLoadingFile(false); });
    return () => { cancelled = true; };
  }, [selected, tree, documentId, fileCache, precomputed]);

  if (error) return <p className="text-sm text-red-700">{error}</p>;
  if (!tree) return <p className="text-sm text-textMuted py-6 text-center">{t('common.loading')}</p>;

  const nothing = tree.added + tree.removed + tree.modified === 0;
  const selectedChange = tree.changes.find((c) => c.path === selected) ?? null;

  return (
    <div className="flex flex-col border border-borderLight rounded-lg overflow-hidden bg-elevated" style={{ height }}>
      {/* Summary bar */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 border-b border-borderLight bg-surface text-xs">
        {caption && <span className="text-textMuted mr-auto">{caption}</span>}
        {!caption && (
          <span className="text-textMain font-medium mr-auto">
            {nothing ? t('diff.nothing') : t('diff.filesChanged').replace('{n}', String(tree.added + tree.removed + tree.modified))}
          </span>
        )}
        {tree.removed > 0 && <span className="px-1.5 py-0.5 rounded border border-red-200 bg-red-50 text-red-700">{t('diff.removed').replace('{n}', String(tree.removed))}</span>}
        {tree.added > 0 && <span className="px-1.5 py-0.5 rounded border border-green-200 bg-green-50 text-green-700">{t('diff.added').replace('{n}', String(tree.added))}</span>}
        {tree.modified > 0 && <span className="px-1.5 py-0.5 rounded border border-amber-200 bg-amber-50 text-amber-700">{t('diff.modified').replace('{n}', String(tree.modified))}</span>}
        <DiffStat additions={tree.additions} deletions={tree.deletions} />
        {tree.unchanged > 0 && (
          <button type="button" onClick={() => setShowUnchanged((v) => !v)}
                  className="px-1.5 py-0.5 rounded border border-borderLight text-textMuted hover:border-primary/40">
            {showUnchanged ? t('diff.hideUnchanged') : t('diff.unchanged').replace('{n}', String(tree.unchanged))}
          </button>
        )}
      </div>

      {tree.removed > 0 && (
        <p className="text-xs text-red-800 bg-red-50 border-b border-red-200 px-3 py-1.5 flex items-start gap-2">
          <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
          <span>{t('diff.removalWarning')}</span>
        </p>
      )}

      {nothing && !showUnchanged ? (
        <div className="flex-1 flex items-center justify-center text-sm text-textMuted">{t('diff.nothing')}</div>
      ) : (
        <div className="flex-1 flex min-h-0">
          {/* File list */}
          <ul className="w-64 shrink-0 border-r border-borderLight overflow-y-auto text-sm" role="listbox">
            {changed.map((c) => {
              const Icon = STATUS_ICON[c.status];
              const { dir, name } = splitPath(c.path);
              const active = selected === c.path;
              return (
                <li key={c.path}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    onClick={() => setSelected(c.path)}
                    title={c.path}
                    className={cn(
                      'w-full flex items-center gap-2 px-2.5 py-1.5 text-left border-l-2',
                      active ? 'bg-primary/10 border-primary' : 'border-transparent hover:bg-surface',
                    )}
                  >
                    <Icon className={cn('w-3.5 h-3.5 shrink-0', STATUS_COLOR[c.status])} />
                    <span className="min-w-0 flex-1 truncate font-mono text-[12px]">
                      {dir && <span className="text-textMuted">{dir}</span>}
                      <span className={cn(c.status === 'REMOVED' && 'line-through decoration-red-300')}>{name}</span>
                    </span>
                    {c.isText
                      ? <DiffStat additions={c.additions} deletions={c.deletions} />
                      : <span className="text-[10px] text-textMuted shrink-0">{STATUS_LETTER[c.status]}</span>}
                  </button>
                </li>
              );
            })}
          </ul>

          {/* Diff pane */}
          <div className="flex-1 min-w-0 flex flex-col">
            {selectedChange ? (
              <>
                <div className="flex items-center gap-2 px-3 py-1.5 border-b border-borderLight bg-surface text-xs">
                  <span className={cn('px-1.5 py-0.5 rounded border font-semibold uppercase text-[10px]',
                    selectedChange.status === 'ADDED' && 'border-green-200 bg-green-50 text-green-700',
                    selectedChange.status === 'REMOVED' && 'border-red-200 bg-red-50 text-red-700',
                    selectedChange.status === 'MODIFIED' && 'border-amber-200 bg-amber-50 text-amber-700',
                    selectedChange.status === 'UNCHANGED' && 'border-borderLight text-textMuted')}>
                    {t(`diff.status.${selectedChange.status}`)}
                  </span>
                  <span className="font-mono truncate text-textMain">{selectedChange.path}</span>
                  <span className="ml-auto text-textMuted whitespace-nowrap">
                    {selectedChange.status === 'ADDED' ? humanBytes(selectedChange.sizeAfter)
                      : selectedChange.status === 'REMOVED' ? humanBytes(selectedChange.sizeBefore)
                      : `${humanBytes(selectedChange.sizeBefore)} → ${humanBytes(selectedChange.sizeAfter)}`}
                  </span>
                  {selectedChange.isText && (
                    <button type="button" onClick={() => setSplit((v) => !v)}
                            title={split ? t('diff.unified') : t('diff.split')}
                            className="p-1 rounded text-textMuted hover:text-primary hover:bg-elevated">
                      {split ? <Rows3 className="w-3.5 h-3.5" /> : <Columns2 className="w-3.5 h-3.5" />}
                    </button>
                  )}
                </div>
                <div className="flex-1 overflow-auto bg-elevated">
                  {!selectedChange.isText ? (
                    <div className="flex flex-col items-center justify-center gap-2 py-12 text-textMuted text-sm">
                      <ImageIcon className="w-8 h-8 opacity-40" />
                      <span>{t('diff.binary')}</span>
                    </div>
                  ) : selectedChange.status === 'UNCHANGED' ? (
                    <div className="py-12 text-center text-sm text-textMuted">{t('diff.fileUnchanged')}</div>
                  ) : loadingFile || !fileDiff ? (
                    <div className="py-12 text-center text-sm text-textMuted">{t('common.loading')}</div>
                  ) : fileDiff.truncated ? (
                    <div className="py-12 text-center text-sm text-textMuted">{t('diff.tooLarge')}</div>
                  ) : fileDiff.hunks.length === 0 ? (
                    <div className="py-12 text-center text-sm text-textMuted">{t('diff.fileUnchanged')}</div>
                  ) : split ? (
                    <SplitDiff hunks={fileDiff.hunks} />
                  ) : (
                    <UnifiedDiff hunks={fileDiff.hunks} />
                  )}
                </div>
              </>
            ) : (
              <div className="flex-1 flex items-center justify-center text-sm text-textMuted">{t('diff.pickFile')}</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Renderers                                                           */
/* ------------------------------------------------------------------ */

function HunkHeader({ h }: { h: Hunk }) {
  return (
    <tr className="bg-blue-50/70">
      <td colSpan={3} className="px-3 py-1 font-mono text-[11px] text-primary select-none">
        @@ −{h.oldStart},{h.oldCount} +{h.newStart},{h.newCount} @@
      </td>
    </tr>
  );
}

/**
 * Pairs removed/added runs so intra-line changes can be highlighted — the
 * detail that turns "this line changed" into "this word changed".
 */
function pairRuns(lines: DiffLine[]) {
  const out: { line: DiffLine; hl?: [number, number] | null }[] = [];
  let i = 0;
  while (i < lines.length) {
    if (lines[i].kind === 'REMOVED') {
      let j = i;
      while (j < lines.length && lines[j].kind === 'REMOVED') j++;
      let k = j;
      while (k < lines.length && lines[k].kind === 'ADDED') k++;
      const removed = lines.slice(i, j);
      const added = lines.slice(j, k);
      const pairs = Math.min(removed.length, added.length);
      for (let p = 0; p < removed.length; p++) {
        const partner = p < pairs ? added[p] : null;
        out.push({ line: removed[p], hl: partner ? highlightPair(removed[p].text, partner.text)[0] : null });
      }
      for (let p = 0; p < added.length; p++) {
        const partner = p < pairs ? removed[p] : null;
        out.push({ line: added[p], hl: partner ? highlightPair(partner.text, added[p].text)[1] : null });
      }
      i = k;
    } else {
      out.push({ line: lines[i] });
      i++;
    }
  }
  return out;
}

function LineText({ text, hl, kind }: { text: string; hl?: [number, number] | null; kind: DiffLine['kind'] }) {
  if (!hl || hl[0] >= hl[1]) return <>{text || ' '}</>;
  const mark = kind === 'ADDED' ? 'bg-green-200/80 dark:bg-green-700/50' : 'bg-red-200/80 dark:bg-red-700/50';
  return (
    <>
      {text.slice(0, hl[0])}
      <span className={cn('rounded-sm', mark)}>{text.slice(hl[0], hl[1])}</span>
      {text.slice(hl[1])}
    </>
  );
}

const ROW_BG: Record<DiffLine['kind'], string> = {
  CONTEXT: '',
  ADDED: 'bg-green-50 dark:bg-green-900/20',
  REMOVED: 'bg-red-50 dark:bg-red-900/20',
};

function UnifiedDiff({ hunks }: { hunks: Hunk[] }) {
  return (
    <table className="w-full border-collapse font-mono text-[12px] leading-5">
      <tbody>
        {hunks.map((h, hi) => (
          <HunkRows key={hi} h={h} />
        ))}
      </tbody>
    </table>
  );
}

function HunkRows({ h }: { h: Hunk }) {
  const rows = useMemo(() => pairRuns(h.lines), [h]);
  return (
    <>
      <HunkHeader h={h} />
      {rows.map(({ line, hl }, i) => (
        <tr key={i} className={ROW_BG[line.kind]}>
          <td className="w-10 pr-2 text-right text-textMuted select-none border-r border-borderLight/60 tabular-nums">{line.oldLine ?? ''}</td>
          <td className="w-10 pr-2 text-right text-textMuted select-none border-r border-borderLight/60 tabular-nums">{line.newLine ?? ''}</td>
          <td className="px-2 whitespace-pre-wrap break-all">
            <span className={cn('inline-block w-3 select-none', line.kind === 'ADDED' ? 'text-green-700' : line.kind === 'REMOVED' ? 'text-red-700' : 'text-textMuted')}>
              {line.kind === 'ADDED' ? '+' : line.kind === 'REMOVED' ? '−' : ' '}
            </span>
            <LineText text={line.text} hl={hl} kind={line.kind} />
          </td>
        </tr>
      ))}
    </>
  );
}

/** Side by side: old on the left, new on the right, changes aligned. */
function SplitDiff({ hunks }: { hunks: Hunk[] }) {
  return (
    <table className="w-full border-collapse font-mono text-[12px] leading-5 table-fixed">
      <tbody>
        {hunks.map((h, hi) => (
          <SplitHunkRows key={hi} h={h} />
        ))}
      </tbody>
    </table>
  );
}

function SplitHunkRows({ h }: { h: Hunk }) {
  const rows = useMemo(() => {
    const paired = pairRuns(h.lines);
    const out: { left?: typeof paired[number]; right?: typeof paired[number] }[] = [];
    let i = 0;
    while (i < paired.length) {
      const cur = paired[i];
      if (cur.line.kind === 'CONTEXT') { out.push({ left: cur, right: cur }); i++; continue; }
      const removed: typeof paired = [];
      const added: typeof paired = [];
      while (i < paired.length && paired[i].line.kind === 'REMOVED') removed.push(paired[i++]);
      while (i < paired.length && paired[i].line.kind === 'ADDED') added.push(paired[i++]);
      const n = Math.max(removed.length, added.length);
      for (let k = 0; k < n; k++) out.push({ left: removed[k], right: added[k] });
    }
    return out;
  }, [h]);

  const cell = (side?: { line: DiffLine; hl?: [number, number] | null }, which: 'old' | 'new' = 'old') => {
    if (!side) return (<><td className="w-10 border-r border-borderLight/60" /><td className="w-1/2 bg-gray-50/60 dark:bg-neutral-800/30" /></>);
    const n = which === 'old' ? side.line.oldLine : side.line.newLine;
    return (
      <>
        <td className="w-10 pr-2 text-right text-textMuted select-none border-r border-borderLight/60 tabular-nums">{n ?? ''}</td>
        <td className={cn('w-1/2 px-2 whitespace-pre-wrap break-all border-r border-borderLight/60', ROW_BG[side.line.kind])}>
          <LineText text={side.line.text} hl={side.hl} kind={side.line.kind} />
        </td>
      </>
    );
  };

  return (
    <>
      <tr className="bg-blue-50/70">
        <td colSpan={4} className="px-3 py-1 font-mono text-[11px] text-primary select-none">
          @@ −{h.oldStart},{h.oldCount} +{h.newStart},{h.newCount} @@
        </td>
      </tr>
      {rows.map((r, i) => (
        <tr key={i}>
          {cell(r.left, 'old')}
          {cell(r.right, 'new')}
        </tr>
      ))}
    </>
  );
}
