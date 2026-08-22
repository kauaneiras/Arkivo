import { useState, useMemo, useRef, type DragEvent } from 'react';
import {
  ChevronRight, ChevronDown, FileText, Image as ImageIcon, FileType, File as FileIcon,
  FolderPlus, FilePlus, Upload, Trash2, Pencil, FileCheck, BookMarked, Download,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { useI18n } from '../lib/i18n';

export interface FileNode {
  path: string;
  sizeBytes: number;
  isText: boolean;
  contentType: string;
  isRoot: boolean;
  derivedFrom: string | null;
}

interface TreeEntry {
  name: string;
  path: string;
  isFolder: boolean;
  file?: FileNode;
  children: TreeEntry[];
}

/**
 * Overleaf-style project file tree.
 *
 * The backend stores flat paths ("editaveis/introducao.tex") because that is
 * what makes a commit's tree cheap to read and compare. The nesting users
 * expect is derived here, at render time — no parent/child rows to keep
 * consistent, and a rename of a folder is one UPDATE on the server.
 */
/** Placeholder used to give an empty folder a row to exist on. */
const FOLDER_KEEPER = '.gitkeep';

function buildTree(files: FileNode[]): TreeEntry[] {
  const root: TreeEntry[] = [];

  const sorted = [...files]
    // The keeper is an implementation detail of "create an empty folder".
    // Showing it put a file called ".gitkeep" — developer jargon — inside every
    // folder a lab technician created, which they neither recognise nor dare
    // delete.
    .filter((f) => !f.path.endsWith(`/${FOLDER_KEEPER}`) && f.path !== FOLDER_KEEPER)
    .sort((a, b) => a.path.localeCompare(b.path, undefined, { numeric: true }));

  for (const file of sorted) {
    const parts = file.path.split('/');
    let level = root;
    let accumulated = '';

    parts.forEach((part, index) => {
      accumulated = accumulated ? `${accumulated}/${part}` : part;
      const isLeaf = index === parts.length - 1;
      let entry = level.find((e) => e.name === part && e.isFolder === !isLeaf);

      if (!entry) {
        entry = { name: part, path: accumulated, isFolder: !isLeaf, children: [] };
        if (isLeaf) entry.file = file;
        level.push(entry);
      }
      level = entry.children;
    });
  }

  // Folders first, then files — the convention every file explorer uses.
  const order = (entries: TreeEntry[]): TreeEntry[] =>
    entries
      // numeric: true here too, or cap10.tex sorts before cap2.tex
      .sort((a, b) => (a.isFolder === b.isFolder
        ? a.name.localeCompare(b.name, undefined, { numeric: true })
        : a.isFolder ? -1 : 1))
      .map((e) => ({ ...e, children: order(e.children) }));

  return order(root);
}

function iconFor(file?: FileNode) {
  if (!file) return null;
  const p = file.path.toLowerCase();
  if (p.endsWith('.tex')) return <FileText className="w-4 h-4 text-blue-500 shrink-0" />;
  if (p.endsWith('.bib')) return <BookMarked className="w-4 h-4 text-amber-500 shrink-0" />;
  if (/\.(png|jpe?g|gif|svg|eps)$/.test(p)) return <ImageIcon className="w-4 h-4 text-green-500 shrink-0" />;
  if (p.endsWith('.pdf')) return <FileType className="w-4 h-4 text-red-500 shrink-0" />;
  return <FileIcon className="w-4 h-4 text-textMuted shrink-0" />;
}

/** Kept in step with ProjectImportService.MAX_PROJECT_BYTES on the server. */
export const MAX_PROJECT_BYTES = 200 * 1024 * 1024;
export const MAX_FILE_BYTES = 50 * 1024 * 1024;

function humanSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

interface FileTreeProps {
  files: FileNode[];
  selectedPath: string | null;
  readOnly?: boolean;
  onSelect: (file: FileNode) => void;
  onUpload: (file: File, folder: string) => void;
  onCreateFile: (path: string) => void;
  onRename: (from: string, to: string) => void;
  onDelete: (path: string) => void;
  onSetRoot: (path: string) => void;
  onImportZip: (file: File) => void;
  onExportZip: () => void;
}

export default function FileTree({
  files, selectedPath, readOnly,
  onSelect, onUpload, onCreateFile, onRename, onDelete, onSetRoot, onImportZip, onExportZip,
}: FileTreeProps) {
  const { t } = useI18n();
  const tree = useMemo(() => buildTree(files), [files]);
  // Shown against the 200 MB ceiling so a user importing a figure-heavy thesis
  // learns where they stand before an upload is rejected.
  const totalBytes = useMemo(() => files.reduce((sum, f) => sum + f.sizeBytes, 0), [files]);
  // Folders start open: a thesis has its chapters two levels down, and making
  // the user expand every folder on each visit is friction for no benefit.
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [dragOver, setDragOver] = useState<string | null>(null);
  const uploadRef = useRef<HTMLInputElement | null>(null);
  const zipRef = useRef<HTMLInputElement | null>(null);
  const [uploadFolder, setUploadFolder] = useState('');

  const toggle = (path: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      next.has(path) ? next.delete(path) : next.add(path);
      return next;
    });

  const handleDrop = (e: DragEvent, folder: string) => {
    e.preventDefault();
    e.stopPropagation();
    setDragOver(null);
    if (readOnly) return;
    Array.from(e.dataTransfer.files).forEach((f) => {
      if (f.name.toLowerCase().endsWith('.zip')) onImportZip(f);
      else onUpload(f, folder);
    });
  };

  const renderEntry = (entry: TreeEntry, depth: number) => {
    const isCollapsed = collapsed.has(entry.path);
    const indent = { paddingLeft: `${depth * 12 + 8}px` };

    if (entry.isFolder) {
      return (
        <div key={entry.path}>
          <div
            style={indent}
            role="treeitem"
            aria-expanded={!isCollapsed}
            tabIndex={0}
            onClick={() => toggle(entry.path)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(entry.path); }
              if (e.key === 'ArrowRight' && isCollapsed) toggle(entry.path);
              if (e.key === 'ArrowLeft' && !isCollapsed) toggle(entry.path);
            }}
            // stopPropagation: without it the root handler fired right after and
            // reset the highlight, so the user never saw which folder the file
            // was about to land in.
            onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); setDragOver(entry.path); }}
            onDragLeave={(e) => { e.stopPropagation(); setDragOver(null); }}
            onDrop={(e) => handleDrop(e, entry.path)}
            className={cn(
              'flex items-center gap-1 py-1 pr-2 text-sm cursor-pointer select-none group',
              'hover:bg-elevated transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
              dragOver === entry.path && 'bg-primary/10 ring-1 ring-primary/40',
            )}
          >
            {isCollapsed
              ? <ChevronRight className="w-3.5 h-3.5 text-textMuted shrink-0" />
              : <ChevronDown className="w-3.5 h-3.5 text-textMuted shrink-0" />}
            <span className="truncate font-medium text-textMain">{entry.name}</span>
            {!readOnly && (
              <button
                onClick={(e) => { e.stopPropagation(); setUploadFolder(entry.path); uploadRef.current?.click(); }}
                title={t('tree.uploadHere')}
                className="ml-auto opacity-0 group-hover:opacity-100 text-textMuted hover:text-primary transition-opacity"
              >
                <Upload className="w-3.5 h-3.5" />
              </button>
            )}
          </div>
          {!isCollapsed && entry.children.map((c) => renderEntry(c, depth + 1))}
        </div>
      );
    }

    const file = entry.file!;
    const selected = selectedPath === file.path;
    return (
      <div
        key={entry.path}
        style={indent}
        role="treeitem"
        aria-selected={selected}
        tabIndex={0}
        onClick={() => onSelect(file)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(file); }
        }}
        className={cn(
          // Rows are focusable and respond to Enter/Space: they used to be plain
          // divs with onClick, so no file in the project could be opened without
          // a mouse.
          'flex items-center gap-1.5 py-1 pr-2 text-sm cursor-pointer select-none group',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
          // Theme tokens, not hardcoded blues: the app ships a dark mode and
          // bg-blue-50 stays a pale blue in it, with the selected row unreadable.
          selected
            ? 'bg-primary/10 text-primary border-l-2 border-primary'
            : 'hover:bg-elevated border-l-2 border-transparent',
        )}
      >
        {iconFor(file)}
        <span className="truncate" title={file.path}>{entry.name}</span>

        {file.isRoot && (
          <span
            title={t('tree.rootFileHint')}
            className="text-[9px] font-bold uppercase bg-primary/10 text-primary rounded px-1 shrink-0"
          >
            {t('tree.root')}
          </span>
        )}
        {file.derivedFrom && (
          <span title={t('tree.convertedFrom').replace('{from}', file.derivedFrom)}>
            <FileType className="w-3 h-3 text-amber-600 shrink-0" />
          </span>
        )}

        <span className="ml-auto flex items-center gap-0.5 shrink-0">
          <span className="text-[11px] text-textMuted hidden sm:inline group-hover:hidden group-focus-within:hidden">
            {humanSize(file.sizeBytes)}
          </span>
          {!readOnly && (
            // Visible on hover AND on focus, and always visible on touch, where
            // there is no hover at all. These were display:none until hover,
            // which put rename and delete out of reach of both keyboard users
            // and anyone on a tablet — the whole file tree was read-only for
            // them with no indication why.
            <span className="flex items-center gap-0.5 opacity-100 sm:opacity-0
                             sm:group-hover:opacity-100 sm:group-focus-within:opacity-100
                             transition-opacity">
              {!file.isRoot && file.path.toLowerCase().endsWith('.tex') && (
                <button
                  onClick={(e) => { e.stopPropagation(); onSetRoot(file.path); }}
                  title={t('tree.setRoot')}
                  aria-label={`${t('tree.setRoot')}: ${entry.name}`}
                  className="p-1 rounded text-textMuted hover:text-primary hover:bg-elevated
                             focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <FileCheck className="w-3.5 h-3.5" />
                </button>
              )}
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  const to = window.prompt(t('tree.renamePrompt'), file.path);
                  if (to && to !== file.path) onRename(file.path, to);
                }}
                title={t('tree.rename')}
                aria-label={`${t('tree.rename')}: ${entry.name}`}
                className="p-1 rounded text-textMuted hover:text-primary hover:bg-elevated
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                <Pencil className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); onDelete(file.path); }}
                title={t('common.delete')}
                aria-label={`${t('common.delete')}: ${entry.name}`}
                className="p-1 ml-0.5 rounded text-textMuted hover:text-red-600 hover:bg-elevated
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            </span>
          )}
        </span>
      </div>
    );
  };

  return (
    <div
      className="flex flex-col h-full"
      onDragOver={(e) => { e.preventDefault(); setDragOver(''); }}
      onDragLeave={() => setDragOver(null)}
      onDrop={(e) => handleDrop(e, '')}
    >
      <div className="p-2 border-b border-borderLight flex items-center gap-1 sticky top-0 bg-surface z-10">
        <span className="text-sm font-semibold text-textMain mr-auto pl-1">{t('tree.title')}</span>
        {!readOnly && (
          <>
            <button
              onClick={() => {
                const path = window.prompt(t('tree.newFilePrompt'), t('tree.newFileDefault'));
                if (path) onCreateFile(path);
              }}
              title={t('tree.newFile')}
              className="p-1 text-textMuted hover:text-primary hover:bg-elevated rounded transition-colors"
            >
              <FilePlus className="w-4 h-4" />
            </button>
            <button
              onClick={() => {
                const folder = window.prompt(t('tree.newFolderPrompt'), t('tree.newFolderDefault'));
                if (folder) onCreateFile(`${folder.replace(/\/+$/, '')}/${FOLDER_KEEPER}`);
              }}
              title={t('tree.newFolder')}
              className="p-1 text-textMuted hover:text-primary hover:bg-elevated rounded transition-colors"
            >
              <FolderPlus className="w-4 h-4" />
            </button>
            <button
              onClick={() => { setUploadFolder(''); uploadRef.current?.click(); }}
              title={t('tree.upload')}
              className="p-1 text-textMuted hover:text-primary hover:bg-elevated rounded transition-colors"
            >
              <Upload className="w-4 h-4" />
            </button>
          </>
        )}
        <button
          onClick={onExportZip}
          title={t('tree.exportZip')}
          className="p-1 text-textMuted hover:text-primary hover:bg-elevated rounded transition-colors"
        >
          <Download className="w-4 h-4" />
        </button>
      </div>

      <div className={cn('flex-1 overflow-y-auto py-1', dragOver === '' && 'bg-primary/5')}>
        {tree.length === 0 ? (
          <p className="text-xs text-textMuted text-center px-3 py-6">{t('tree.empty')}</p>
        ) : (
          tree.map((e) => renderEntry(e, 0))
        )}
      </div>

      {!readOnly && (
        <div className="p-2 border-t border-borderLight">
          <button
            onClick={() => zipRef.current?.click()}
            className="w-full btn-outline text-xs py-1.5 flex items-center justify-center gap-1.5"
          >
            <Upload className="w-3.5 h-3.5" />
            {t('tree.importZip')}
          </button>
          <p className="text-[10px] text-textMuted mt-1.5 text-center">
            {t('tree.dropHint')}
            {files.length > 0 && ` · ${humanSize(totalBytes)} / ${humanSize(MAX_PROJECT_BYTES)}`}
          </p>
        </div>
      )}

      <input
        ref={uploadRef}
        type="file"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onUpload(f, uploadFolder);
          if (uploadRef.current) uploadRef.current.value = '';
        }}
      />
      <input
        ref={zipRef}
        type="file"
        accept=".zip"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onImportZip(f);
          if (zipRef.current) zipRef.current.value = '';
        }}
      />
    </div>
  );
}
