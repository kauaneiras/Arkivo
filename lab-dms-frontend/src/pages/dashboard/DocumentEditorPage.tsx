import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import api from '../../lib/api';
import CodeMirror from '@uiw/react-codemirror';
import { StreamLanguage } from '@codemirror/language';
import { stex } from '@codemirror/legacy-modes/mode/stex';
import { Document as PdfDocument, Page, pdfjs } from 'react-pdf';
// react-pdf ships its own stylesheets and expects them to be imported. We render
// with the text and annotation layers off, so the pages draw without them — but
// leaving them out means any future change that turns a layer on produces
// invisible or misplaced content, which is a confusing way to find out.
import 'react-pdf/dist/Page/AnnotationLayer.css';
import 'react-pdf/dist/Page/TextLayer.css';
import {
  ArrowLeft, Save, Play, CheckCircle, FileText, History,
  GitCommitHorizontal, Diff, Download, Lock, AlertTriangle, RotateCcw,
  Paperclip, Upload, Trash2, File as FileIcon, BadgeCheck, FolderTree,
  ChevronUp, ChevronDown, GitBranch, Undo2, Cloud, CloudOff, Loader2, Eye,
} from 'lucide-react';
import { useI18n, formatDate } from '../../lib/i18n';
import { Modal, ConfirmModal, DocStatusBadge, useToast } from '../../components/ui';
import FileTree, { type FileNode } from '../../components/FileTree';
import DiffViewer, { DiffStat, type TreeDiff, type FileDiff } from '../../components/DiffViewer';
import { cn } from '../../lib/utils';
import { porqueNaoPodeEditar, porqueNaoPodeAprovar } from '../../lib/permissoes';
import { useAuthStore } from '../../store/authStore';

pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString();

interface Commit {
  id: string;
  message: string;
  authorName: string;
  createdAt: string;
  /** 0 for the working draft, v1..vN for closed versions. */
  revision: number;
  isCurrent: boolean;
  isOfficial: boolean;
  /** Unsaved changes — the working revision, not a version. */
  isDraft: boolean;
  branchName: string | null;
  parentCommitId: string | null;
  fileCount: number;
  treeBytes: number;
  /** ISO 17025 8.3.2 c) — why this revision was made. */
  changeReason: string | null;
}

/**
 * A real PDF starts with the bytes "%PDF". Checking the magic number catches
 * every way a non-PDF can arrive with a 200: an empty body, a JSON error
 * serialized into the blob, a base64 string, or a proxy's HTML error page.
 */
async function isPdfBlob(blob: Blob): Promise<boolean> {
  if (!blob || blob.size < 5) return false;
  try {
    const head = new Uint8Array(await blob.slice(0, 5).arrayBuffer());
    return head[0] === 0x25 && head[1] === 0x50 && head[2] === 0x44 && head[3] === 0x46; // %PDF
  } catch {
    return false;
  }
}

interface Attachment {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  description: string | null;
  uploadedByName: string;
  uploadedAt: string;
}

interface ReviewItem {
  id: string;
  status: string;
  title: string;
  requestedByName: string;
  createdAt: string;
}

type SidebarView = 'none' | 'files' | 'changes' | 'history' | 'attachments';
type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';

/** How long after the last keystroke the open file is written to the draft. */
const AUTOSAVE_DELAY_MS = 1500;

export default function DocumentEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t, lang } = useI18n();
  const { toast } = useToast();

  const authUser = useAuthStore((s) => s.user);
  const [doc, setDoc] = useState<any>(null);
  const [content, setContent] = useState('');
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
  const [numPages, setNumPages] = useState<number | null>(null);
  // True = the paged renderer (default), false = the browser's own PDF viewer.
  const [pagedView, setPagedView] = useState<boolean>(() => {
    try { return localStorage.getItem('labdms.pdfPagedView') !== '0'; } catch { return true; }
  });
  useEffect(() => {
    try { localStorage.setItem('labdms.pdfPagedView', pagedView ? '1' : '0'); } catch { /* private mode */ }
  }, [pagedView]);

  // Why the viewer failed, in the viewer's own words.
  const [viewerError, setViewerError] = useState<string | null>(null);
  const showPaged = pagedView && !viewerError;

  // The page is rendered at the width of the panel, not at a fixed size.
  const previewRef = useRef<HTMLDivElement>(null);
  const [previewWidth, setPreviewWidth] = useState(560);
  useEffect(() => {
    const el = previewRef.current;
    if (!el) return;
    const measure = () => setPreviewWidth(el.clientWidth);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [showPaged, pdfBlobUrl]);
  const pageWidth = Math.max(280, previewWidth - 48);

  const [isCompiling, setIsCompiling] = useState(false);
  const [compileError, setCompileError] = useState<string | null>(null);
  const [errorsOpen, setErrorsOpen] = useState(false);
  const [pdfIsStale, setPdfIsStale] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [commits, setCommits] = useState<Commit[]>([]);
  const [selectedCommitId, setSelectedCommitId] = useState<string | null>(null);
  const selectedCommitIdRef = useRef<string | null>(null);
  useEffect(() => { selectedCommitIdRef.current = selectedCommitId; }, [selectedCommitId]);
  const fetchCommitsRef = useRef<((selectLatest?: boolean) => Promise<Commit[]>) | null>(null);

  // "?panel=history" lets the documents list jump straight into the history.
  const [searchParams] = useSearchParams();
  const [sidebarView, setSidebarView] = useState<SidebarView>(() => {
    const p = searchParams.get('panel');
    return p === 'history' ? 'history' : p === 'attachments' ? 'attachments' : p === 'changes' ? 'changes' : 'files';
  });

  // Multi-file project state
  const [projectFiles, setProjectFiles] = useState<FileNode[]>([]);
  const [openPath, setOpenPath] = useState<string | null>(null);
  const [binaryPreviewUrl, setBinaryPreviewUrl] = useState<string | null>(null);
  const [binaryPreviewType, setBinaryPreviewType] = useState<string>('');
  const binaryUrlRef = useRef<string | null>(null);
  const openIsTextRef = useRef(true);

  // Unsaved edits in the buffer, and what the autosave is doing about them.
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);
  const isDirtyRef = useRef(false);
  const markDirty = useCallback((dirty: boolean) => {
    isDirtyRef.current = dirty;
    setSaveState((s) => (dirty ? 'dirty' : s === 'dirty' ? 'idle' : s));
  }, []);

  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [isUploadingAttachment, setIsUploadingAttachment] = useState(false);
  const [attachmentToDelete, setAttachmentToDelete] = useState<Attachment | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Working changes (draft vs last saved version) — the "Changes" tab.
  const [workingDiff, setWorkingDiff] = useState<TreeDiff | null>(null);
  const [openReview, setOpenReview] = useState<ReviewItem | null>(null);

  // Modals
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [commitMessage, setCommitMessage] = useState('');
  const [changeReason, setChangeReason] = useState('');
  const [ackStatus, setAckStatus] = useState<{ required: boolean; acknowledged: boolean; acknowledgedAt: string | null } | null>(null);
  const [periodicModalOpen, setPeriodicModalOpen] = useState(false);
  const [periodicNotes, setPeriodicNotes] = useState('');
  const [isRecordingReview, setIsRecordingReview] = useState(false);
  const [submitModalOpen, setSubmitModalOpen] = useState(false);
  const [reviewTitle, setReviewTitle] = useState('');
  const [reviewDescription, setReviewDescription] = useState('');
  const [isSubmittingReview, setIsSubmittingReview] = useState(false);
  const [diffModal, setDiffModal] = useState<{ from?: string | null; to?: string | null; caption: string; initialPath?: string | null } | null>(null);
  const [restoreTarget, setRestoreTarget] = useState<Commit | null>(null);
  const [isRestoring, setIsRestoring] = useState(false);
  const [officialTarget, setOfficialTarget] = useState<Commit | null>(null);
  const [isSettingOfficial, setIsSettingOfficial] = useState(false);
  const [discardOpen, setDiscardOpen] = useState(false);
  const [isDiscarding, setIsDiscarding] = useState(false);
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  // ZIP chosen and waiting for confirmation: the file and the server's preview.
  const [zipPendente, setZipPendente] = useState<{
    file: File; preview: TreeDiff; files: Record<string, FileDiff>; root: string | null;
  } | null>(null);
  const [zipSaveAsVersion, setZipSaveAsVersion] = useState(false);
  const [zipMessage, setZipMessage] = useState('');
  const [zipReason, setZipReason] = useState('');

  const blobUrlRef = useRef<string | null>(null);
  const contentRef = useRef('');
  contentRef.current = content;
  const lastCompiledRef = useRef<string | null>(null);

  /* ------------------------------------------------------------------ */
  /* Derived state                                                       */
  /* ------------------------------------------------------------------ */

  const isLocked = doc?.status === 'IN_REVIEW' || doc?.status === 'OBSOLETE';
  const draft = useMemo(() => commits.find((c) => c.isDraft) ?? null, [commits]);
  const headId = commits[0]?.id ?? null;
  const closedCommits = useMemo(() => commits.filter((c) => !c.isDraft), [commits]);
  const viewingOld = !!selectedCommitId && !!headId && selectedCommitId !== headId;
  const selectedCommit = useMemo(() => commits.find((c) => c.id === selectedCommitId) ?? null, [commits, selectedCommitId]);

  const recusaEditar = porqueNaoPodeEditar(authUser, {
    createdBy: doc?.createdBy,
    assignedReviewerId: doc?.assignedReviewerId,
    documentGroupId: doc?.documentGroupId,
    status: doc?.status,
  });
  const recusaAprovar = porqueNaoPodeAprovar(authUser, {
    createdBy: doc?.createdBy,
    assignedReviewerId: doc?.assignedReviewerId,
    documentGroupId: doc?.documentGroupId,
    status: doc?.status,
  });
  // Can this person write into THIS view right now?
  const canWrite = !isLocked && !recusaEditar && !viewingOld;
  const canWriteRef = useRef(canWrite);
  useEffect(() => { canWriteRef.current = canWrite; }, [canWrite]);
  const latexExtensions = useMemo(() => [StreamLanguage.define(stex)], []);
  const changedFileCount = workingDiff ? workingDiff.added + workingDiff.removed + workingDiff.modified : 0;

  /* ------------------------------------------------------------------ */
  /* Loading                                                             */
  /* ------------------------------------------------------------------ */

  const setPdfUrl = useCallback((url: string | null) => {
    if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
    blobUrlRef.current = url;
    setPdfBlobUrl(url);
    setNumPages(null);
    setViewerError(null);
  }, []);

  const fetchDocument = useCallback(async () => {
    try {
      const res = await api.get(`/documents/${id}`);
      setDoc(res.data.data || res.data);
    } catch (err) {
      console.error(err);
    }
  }, [id]);

  const fetchOpenReview = useCallback(async () => {
    try {
      const res = await api.get(`/documents/${id}/reviews`);
      const items: ReviewItem[] = res.data ?? [];
      setOpenReview(items.find((r) => r.status === 'OPEN') ?? null);
    } catch {
      setOpenReview(null);
    }
  }, [id]);

  const loadPdf = useCallback(async (commitId: string, silent = true) => {
    try {
      const res = await api.get(`/latex/pdf/${commitId}`, {
        responseType: 'blob',
        headers: { Accept: 'application/pdf' },
      });
      if (!(await isPdfBlob(res.data))) {
        setPdfUrl(null);
        return false;
      }
      setPdfUrl(URL.createObjectURL(res.data));
      return true;
    } catch {
      if (!silent) toast('info', t('editor.pdfError'));
      setPdfUrl(null);
      return false;
    }
  }, [setPdfUrl, toast, t]);

  /** The version's root text — from the TREE, so it is never stale. */
  const loadCommitContent = useCallback(async (commitId: string): Promise<string> => {
    try {
      const res = await api.get(`/documents/${id}/versioning/commits/${commitId}/content`);
      const source: string = res.data.content ?? '';
      setContent(source);
      contentRef.current = source;
      setSelectedCommitId(commitId);
      selectedCommitIdRef.current = commitId;
      return source;
    } catch (err) {
      console.error(err);
      return '';
    }
  }, [id]);

  const setBinaryUrl = useCallback((url: string | null, type = '') => {
    if (binaryUrlRef.current) URL.revokeObjectURL(binaryUrlRef.current);
    binaryUrlRef.current = url;
    setBinaryPreviewUrl(url);
    setBinaryPreviewType(type);
  }, []);

  const fetchProjectFiles = useCallback(async (commitId: string) => {
    try {
      const res = await api.get(`/documents/${id}/files`, { params: { commitId } });
      const list: FileNode[] = Array.isArray(res.data) ? res.data : [];
      setProjectFiles(list);
      return list;
    } catch (err) {
      console.error(err);
      setProjectFiles([]);
      return [];
    }
  }, [id]);

  /** What the draft changes against the last saved version. */
  const refreshWorkingDiff = useCallback(async (draftId: string | null) => {
    if (!draftId) { setWorkingDiff(null); return; }
    try {
      const res = await api.get(`/documents/${id}/files/diff`, { params: { to: draftId } });
      setWorkingDiff(res.data);
    } catch {
      setWorkingDiff(null);
    }
  }, [id]);

  useEffect(() => { refreshWorkingDiff(draft?.id ?? null); }, [draft?.id, refreshWorkingDiff]);

  /**
   * Writes the open file into the working draft.
   *
   * Refuses to write when the open file is binary, when the view is read-only,
   * or when an OLD version is on screen — the server would write into the
   * draft, silently replacing the newest text with an old one.
   */
  const persistOpenFile = useCallback(async () => {
    if (!openPath || !openIsTextRef.current) return;
    if (!canWriteRef.current) return;
    setSaveState('saving');
    try {
      const res = await api.put(`/documents/${id}/files/content`, {
        path: openPath, content: contentRef.current,
      });
      isDirtyRef.current = false;
      setSaveState('saved');
      setLastSavedAt(new Date());

      // The first write after a save OPENS a draft on the server. The editor
      // follows it, and the history gains the "unsaved changes" entry.
      const working: string | undefined = res.headers?.['x-working-commit'];
      if (working && working !== selectedCommitIdRef.current) {
        setSelectedCommitId(working);
        selectedCommitIdRef.current = working;
        await fetchCommitsRef.current?.(false);
      } else {
        refreshWorkingDiff(working ?? selectedCommitIdRef.current);
      }
    } catch (err: any) {
      setSaveState('error');
      throw err;
    }
  }, [id, openPath, refreshWorkingDiff]);

  /** Opens a file from the tree: text goes into the editor, images render inline. */
  const openFile = useCallback(async (file: FileNode) => {
    if (!selectedCommitId) return;
    if (file.path === openPath) return;

    if (isDirtyRef.current && openIsTextRef.current && canWriteRef.current) {
      try {
        await persistOpenFile();
      } catch (err: any) {
        toast('error', err.response?.data?.message || t('tree.saveFileError'));
        return;   // don't move away from unsaved work we could not store
      }
    }

    setOpenPath(file.path);
    openIsTextRef.current = file.isText;
    markDirty(false);

    if (file.isText) {
      setBinaryUrl(null);
      try {
        const res = await api.get(`/documents/${id}/files/content`, {
          params: { commitId: selectedCommitId, path: file.path },
        });
        const source: string = res.data.content ?? '';
        setContent(source);
        contentRef.current = source;
      } catch (err) {
        console.error(err);
      }
    } else {
      try {
        const res = await api.get(`/documents/${id}/files/raw`, {
          params: { commitId: selectedCommitId, path: file.path },
          responseType: 'blob',
        });
        setBinaryUrl(URL.createObjectURL(res.data), file.contentType || res.data.type || '');
      } catch (err) {
        console.error(err);
      }
    }
  }, [id, selectedCommitId, openPath, persistOpenFile, setBinaryUrl, markDirty, toast, t]);

  /**
   * After a write that may have opened a draft: follow the draft, reload the
   * tree, the document and the working diff.
   */
  const afterWrite = useCallback(async (workingCommit?: string | null) => {
    const target = workingCommit ?? selectedCommitIdRef.current;
    if (target && target !== selectedCommitIdRef.current) {
      setSelectedCommitId(target);
      selectedCommitIdRef.current = target;
      await fetchCommitsRef.current?.(false);
    }
    if (target) await fetchProjectFiles(target);
    await fetchDocument();
    refreshWorkingDiff(target ?? null);
  }, [fetchProjectFiles, fetchDocument, refreshWorkingDiff]);

  const handleUploadProjectFile = useCallback(async (file: File, folder: string) => {
    const form = new FormData();
    form.append('file', file);
    if (folder) form.append('folder', folder);
    try {
      const res = await api.post(`/documents/${id}/files/upload`, form,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      toast('success', t('tree.uploadSuccess'));
      await afterWrite(res.headers?.['x-working-commit']);
      lastCompiledRef.current = null;
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('tree.uploadError'));
    }
  }, [id, afterWrite, toast, t]);

  /** First step of a ZIP: ask what it WOULD do, writing nothing. */
  const handleZipEscolhido = useCallback(async (file: File) => {
    const form = new FormData();
    form.append('file', file);
    setIsCompiling(true);
    try {
      const res = await api.post(`/documents/${id}/files/import-zip?dryRun=true`, form,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      setZipSaveAsVersion(false);
      setZipMessage(t('zip.defaultMessage').replace('{name}', file.name));
      setZipReason('');
      setZipPendente({ file, preview: res.data.preview, files: res.data.previewDiffs ?? {}, root: res.data.rootPath });
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('zip.dryRunError'));
    } finally {
      setIsCompiling(false);
    }
  }, [id, toast, t]);

  const handleImportZip = useCallback(async () => {
    if (!zipPendente) return;
    const form = new FormData();
    form.append('file', zipPendente.file);
    setIsCompiling(true);
    try {
      const res = await api.post(`/documents/${id}/files/import-zip`, form,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      toast('success', t('tree.importSuccess')
        .replace('{n}', String(res.data.filesImported ?? 0))
        .replace('{root}', res.data.rootPath ?? '—'));
      if ((res.data.rootCandidates?.length ?? 0) > 1) {
        toast('info', t('tree.multipleRoots'));
      }

      if (zipSaveAsVersion && zipMessage.trim() && zipReason.trim()) {
        await api.post(`/documents/${id}/versioning/commits`, {
          latexSource: null,
          message: zipMessage.trim(),
          changeReason: zipReason.trim(),
        });
        toast('success', t('editor.saveSuccess'));
      } else {
        toast('info', t('zip.appliedToDraft'));
      }
      setZipPendente(null);
      lastCompiledRef.current = null;
      markDirty(false);
      setOpenPath(null);
      // Reload from the head: the ZIP landed in the draft (or became a version).
      await fetchCommitsRef.current?.(true);
      await fetchDocument();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('tree.importError'));
    } finally {
      setIsCompiling(false);
    }
  }, [id, zipPendente, zipSaveAsVersion, zipMessage, zipReason, markDirty, fetchDocument, toast, t]);

  const handleExportZip = useCallback(async () => {
    if (!selectedCommitId) return;
    const res = await api.get(`/documents/${id}/files/export-zip`, {
      params: { commitId: selectedCommitId }, responseType: 'blob',
    });
    const url = URL.createObjectURL(res.data);
    const a = window.document.createElement('a');
    a.href = url;
    a.download = `${doc?.code || 'projeto'}.zip`;
    a.click();
    URL.revokeObjectURL(url);
  }, [id, selectedCommitId, doc]);

  /* ------------------------------ Compile ----------------------------- */

  const handleCompile = useCallback(async (
    source?: string,
    ctx?: { files?: FileNode[]; commitId?: string | null },
  ) => {
    const latexSource = source ?? contentRef.current;
    const files = ctx?.files ?? projectFiles;
    const commitId = ctx?.commitId ?? selectedCommitId;
    const isProject = files.length > 0 && !!commitId;
    if (!isProject && !latexSource.trim()) return;
    setIsCompiling(true);
    setCompileError(null);
    try {
      const options = {
        responseType: 'blob' as const,
        headers: { Accept: 'application/pdf, application/json' },
      };
      const overlayPath = openPath && openIsTextRef.current ? openPath : null;
      const res = isProject
        ? await api.post(
            `/latex/preview/project/${commitId}`,
            { path: overlayPath, content: overlayPath ? latexSource : null },
            options,
          )
        : await api.post('/latex/preview', { latexSource }, options);
      if (!(await isPdfBlob(res.data))) {
        setCompileError(t('editor.compileError'));
        setPdfIsStale(true);
        return;
      }
      setPdfUrl(URL.createObjectURL(res.data));
      setPdfIsStale(false);
      lastCompiledRef.current = latexSource;

      const problems = Number(res.headers['x-latex-problems'] ?? 0);
      if (problems > 0) {
        const parsed = res.headers['x-latex-error-list'];
        setCompileError(parsed ? decodeURIComponent(parsed) : t('editor.compiledWithProblems').replace('{n}', String(problems)));
      } else {
        setCompileError(null);
      }
    } catch (err: any) {
      console.error(err);
      let payload: any = err.response?.data;
      if (payload instanceof Blob) {
        try { payload = JSON.parse(await payload.text()); } catch { payload = null; }
      }
      const log: string = payload?.log ?? '';
      const message: string = payload?.message ?? '';
      const parsed: string[] = Array.isArray(payload?.errors) ? payload.errors : [];
      if (parsed.length > 0) {
        setCompileError(parsed.join('\n'));
        toast('error', t('editor.compileError'));
      } else if (log) {
        const errorLines = log.split('\n').filter((l) => l.startsWith('!') || /^l\.\d+/.test(l));
        const detail = (errorLines.length ? errorLines : log.split('\n').filter((l) => l.trim()).slice(-8)).join('\n');
        setCompileError(detail);
        toast('error', t('editor.compileError'));
      } else {
        setCompileError(message || t('editor.compileError'));
        toast('error', message || t('editor.compileError'));
      }
      setPdfIsStale(true);
    } finally {
      setIsCompiling(false);
    }
  }, [setPdfUrl, toast, t, projectFiles, selectedCommitId, openPath]);

  const fetchCommits = useCallback(async (selectLatest = true) => {
    try {
      const res = await api.get(`/documents/${id}/versioning/commits`);
      const list: Commit[] = res.data;
      setCommits(list);
      if (list.length > 0 && selectLatest) {
        const source = await loadCommitContent(list[0].id);
        const files = await fetchProjectFiles(list[0].id);
        const root = files.find((f) => f.isRoot) ?? files.find((f) => f.path.endsWith('.tex'));
        setOpenPath(root ? root.path : null);
        openIsTextRef.current = true;
        setBinaryUrl(null);
        markDirty(false);

        const hasPdf = await loadPdf(list[0].id);
        if (hasPdf) {
          lastCompiledRef.current = source;
        } else if (source || files.length > 0) {
          handleCompile(source, { files, commitId: list[0].id });
        }
      }
      return list;
    } catch (err) {
      console.error(err);
      return [];
    }
  }, [id, loadCommitContent, loadPdf, handleCompile, fetchProjectFiles, setBinaryUrl, markDirty]);

  useEffect(() => { fetchCommitsRef.current = fetchCommits; }, [fetchCommits]);

  useEffect(() => {
    fetchDocument();
    fetchCommits();
    fetchOpenReview();
    return () => {
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
      if (binaryUrlRef.current) URL.revokeObjectURL(binaryUrlRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Live preview: recompiles a little after typing stops, like Overleaf.
  useEffect(() => {
    if (!content.trim()) return;
    if (content === lastCompiledRef.current) return;
    if (isCompiling) return;
    const timer = setTimeout(() => {
      if (contentRef.current === lastCompiledRef.current) return;
      handleCompile(contentRef.current);
    }, 2500);
    return () => clearTimeout(timer);
  }, [content, isCompiling, handleCompile]);

  /**
   * Autosave into the working draft.
   *
   * Saving a VERSION is a deliberate act (message + change reason, ISO 8.3.2 c).
   * Saving the FILE is not: it goes to the draft, which is the working tree and
   * never a quality record, so there is no reason to let edits sit only in the
   * browser where a closed tab loses them. The draft is what "Changes" shows.
   */
  useEffect(() => {
    if (saveState !== 'dirty') return;
    if (!canWrite || !openPath || !openIsTextRef.current) return;
    const timer = setTimeout(() => {
      if (!isDirtyRef.current) return;
      persistOpenFile().catch((err: any) => {
        toast('error', err.response?.data?.message || t('tree.saveFileError'));
      });
    }, AUTOSAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [saveState, content, canWrite, openPath, persistOpenFile, toast, t]);

  // Closing the tab with a write still pending (autosave not yet fired).
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (!isDirtyRef.current) return;
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, []);

  // Editor shortcuts: Ctrl/Cmd+Enter compiles, Ctrl/Cmd+S opens the save dialog
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      if (e.key === 'Enter') {
        e.preventDefault();
        handleCompile();
      } else if (e.key.toLowerCase() === 's') {
        e.preventDefault();
        if (canWriteRef.current) { setSaveModalOpen(true); }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [handleCompile]);

  /* ------------------------------- Save ------------------------------- */

  /** Closes the draft as a version: message + reason (ISO 8.3.2 c). */
  const saveVersion = useCallback(async (message: string, reason: string) => {
    if (!message.trim()) return false;
    if (!reason.trim()) {
      toast('error', t('editor.changeReasonRequired'));
      return false;
    }
    setIsSaving(true);
    try {
      // Write the open file into the draft BEFORE closing it.
      if (isDirtyRef.current) await persistOpenFile();
      await api.post(`/documents/${id}/versioning/commits`, {
        latexSource: contentRef.current,
        message: message.trim(),
        changeReason: reason.trim(),
      });
      toast('success', t('editor.saveSuccess'));
      lastCompiledRef.current = null;
      markDirty(false);
      setSaveState('idle');
      await fetchCommits();
      await fetchDocument();
      return true;
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message ?? t('editor.saveError'));
      return false;
    } finally {
      setIsSaving(false);
    }
  }, [id, persistOpenFile, fetchCommits, fetchDocument, markDirty, toast, t]);

  const handleSave = async () => {
    const ok = await saveVersion(commitMessage, changeReason);
    if (ok) {
      setSaveModalOpen(false);
      setCommitMessage('');
      setChangeReason('');
    }
  };

  const handleDiscardDraft = async () => {
    setIsDiscarding(true);
    try {
      await api.delete(`/documents/${id}/versioning/working`);
      toast('success', t('changes.discarded'));
      setDiscardOpen(false);
      isDirtyRef.current = false;
      setSaveState('idle');
      lastCompiledRef.current = null;
      await fetchCommits();
      await fetchDocument();
    } catch (err: any) {
      toast('error', err.response?.data?.message ?? t('common.error'));
    } finally {
      setIsDiscarding(false);
    }
  };

  /* --------------------------- Submit review -------------------------- */

  /**
   * Submits for approval — saving the draft as a version first when there is
   * one. The review decides on a SAVED version; the request's title and
   * description become that version's message and change reason.
   */
  const handleSubmitReview = async () => {
    if (!reviewTitle.trim()) return;
    setIsSubmittingReview(true);
    try {
      if (draft || isDirtyRef.current) {
        const ok = await saveVersion(
          t('editor.autoCommitMessage').replace('{title}', reviewTitle.trim()),
          reviewDescription.trim() || reviewTitle.trim(),
        );
        if (!ok) return;
        toast('info', t('editor.autoCommitDone'));
      }

      await api.post(`/documents/${id}/reviews`, {
        title: reviewTitle.trim(),
        description: reviewDescription.trim() || null,
      });
      toast('success', t('editor.submitSuccess'));
      setSubmitModalOpen(false);
      setReviewTitle('');
      setReviewDescription('');
      await fetchCommits(false);
      await fetchDocument();
      await fetchOpenReview();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message ?? t('editor.submitError'));
    } finally {
      setIsSubmittingReview(false);
    }
  };

  const handleWithdraw = async () => {
    if (!openReview) return;
    try {
      await api.post(`/reviews/${openReview.id}/withdraw`);
      toast('success', t('editor.withdrawSuccess'));
      setWithdrawOpen(false);
      await fetchDocument();
      await fetchOpenReview();
    } catch (err: any) {
      toast('error', err.response?.data?.message ?? t('common.error'));
    }
  };

  /* ------------------------------ Restore ----------------------------- */

  const handleRestore = async () => {
    if (!restoreTarget) return;
    setIsRestoring(true);
    try {
      await api.post(`/documents/${id}/versioning/commits/${restoreTarget.id}/restore`, {});
      toast('success', t('editor.restoreSuccess'));
      setRestoreTarget(null);
      lastCompiledRef.current = null;
      isDirtyRef.current = false;
      setSaveState('idle');
      await fetchCommits();
      await fetchDocument();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message ?? t('editor.restoreError'));
    } finally {
      setIsRestoring(false);
    }
  };

  /* ------------------- ISO 8.3.2 b) periodic review -------------------- */

  const handlePeriodicReview = async () => {
    setIsRecordingReview(true);
    try {
      await api.post(`/documents/${id}/periodic-review`, { notes: periodicNotes.trim() || null });
      toast('success', t('review.periodicSuccess'));
      setPeriodicModalOpen(false);
      setPeriodicNotes('');
      fetchDocument();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('review.periodicError'));
    } finally {
      setIsRecordingReview(false);
    }
  };

  /* ----------------- ISO 8.3.2 d) read acknowledgement ------------------ */

  const fetchAckStatus = useCallback(async () => {
    try {
      const res = await api.get(`/documents/${id}/acknowledgements/me`);
      setAckStatus(res.data);
    } catch {
      setAckStatus(null);
    }
  }, [id]);

  useEffect(() => { fetchAckStatus(); }, [fetchAckStatus]);

  const handleAcknowledge = async () => {
    try {
      await api.post(`/documents/${id}/acknowledgements`);
      toast('success', t('ack.success'));
      fetchAckStatus();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('ack.error'));
    }
  };

  /* --------------------------- Official version ------------------------ */

  const handleMakeOfficial = async () => {
    if (!officialTarget) return;
    setIsSettingOfficial(true);
    try {
      await api.put(`/documents/${id}/official-version/${officialTarget.id}`);
      toast('success', t('editor.makeOfficialSuccess'));
      setOfficialTarget(null);
      await fetchDocument();
      fetchCommits(false);
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('editor.makeOfficialError'));
    } finally {
      setIsSettingOfficial(false);
    }
  };

  /* ------------------------------ History ----------------------------- */

  const selectVersion = useCallback(async (commit: Commit) => {
    if (isDirtyRef.current && canWriteRef.current) {
      try { await persistOpenFile(); } catch { /* keep going; the warning toast was shown */ }
    }
    const source = await loadCommitContent(commit.id);
    const files = await fetchProjectFiles(commit.id);
    const root = files.find((f) => f.isRoot) ?? files.find((f) => f.path.endsWith('.tex'));
    setOpenPath(root ? root.path : null);
    openIsTextRef.current = true;
    setBinaryUrl(null);
    markDirty(false);

    const hasPdf = await loadPdf(commit.id);
    if (hasPdf) {
      lastCompiledRef.current = source;
    } else if (source || files.length > 0) {
      handleCompile(source, { files, commitId: commit.id });
    }
  }, [persistOpenFile, loadCommitContent, fetchProjectFiles, setBinaryUrl, markDirty, loadPdf, handleCompile]);

  const versionLabel = (c: Commit | null | undefined) =>
    !c ? '' : c.isDraft ? t('changes.title') : `v${c.revision}`;

  const handleDownloadPdf = () => {
    if (!pdfBlobUrl) return;
    const a = document.createElement('a');
    a.href = pdfBlobUrl;
    a.download = `${doc?.code ?? 'document'}.pdf`;
    a.click();
  };

  /* ----------------------------- Attachments --------------------------- */

  const fetchAttachments = useCallback(async () => {
    try {
      const res = await api.get(`/documents/${id}/attachments`);
      setAttachments(res.data);
    } catch (err) {
      console.error(err);
    }
  }, [id]);

  useEffect(() => {
    if (sidebarView === 'attachments') fetchAttachments();
  }, [sidebarView, fetchAttachments]);

  const handleUploadAttachment = async (file: File) => {
    setIsUploadingAttachment(true);
    try {
      const formData = new FormData();
      formData.append('file', file);
      await api.post(`/documents/${id}/attachments`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      toast('success', t('editor.attachmentUploadSuccess'));
      fetchAttachments();
    } catch (err: any) {
      console.error(err);
      toast('error', err.response?.data?.message || t('editor.attachmentUploadError'));
    } finally {
      setIsUploadingAttachment(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDownloadAttachment = async (attachment: Attachment) => {
    try {
      const res = await api.get(`/documents/${id}/attachments/${attachment.id}/download`, { responseType: 'blob' });
      const url = URL.createObjectURL(res.data);
      const a = document.createElement('a');
      a.href = url;
      a.download = attachment.fileName;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error(err);
      toast('error', t('common.error'));
    }
  };

  const handleDeleteAttachment = async () => {
    if (!attachmentToDelete) return;
    try {
      await api.delete(`/documents/${id}/attachments/${attachmentToDelete.id}`);
      toast('success', t('editor.attachmentDeleteSuccess'));
      setAttachmentToDelete(null);
      fetchAttachments();
    } catch (err) {
      console.error(err);
      toast('error', t('common.error'));
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  if (!doc) return <div className="p-8 text-center text-textMuted">{t('editor.loading')}</div>;

  const errorLines = (compileError ?? '').split('\n').map((l) => l.trim()).filter(Boolean);
  const officialCommit = commits.find((c) => c.isOfficial) ?? null;

  const sidebarButton = (view: SidebarView, Icon: typeof FolderTree, label: string, badge?: number | null, accent?: boolean) => (
    <button
      onClick={() => setSidebarView((v) => (v === view ? 'none' : view))}
      className={cn('btn-outline flex items-center gap-2 py-1.5 px-3 text-sm', sidebarView === view && 'bg-surface')}
      title={label}
    >
      <Icon className={cn('w-4 h-4', accent && 'text-amber-600')} />
      <span className="hidden xl:inline">{label}</span>
      {!!badge && (
        <span className={cn('text-[10px] font-semibold rounded-full px-1.5',
          accent ? 'bg-amber-100 text-amber-800' : 'bg-surface text-textMuted')}>
          {badge}
        </span>
      )}
    </button>
  );

  return (
    <div className="flex flex-col h-[calc(100vh-7.5rem)] -m-8">
      {/* Top Bar */}
      <div className="bg-white border-b border-borderLight h-14 px-4 flex items-center justify-between shrink-0 gap-2">
        <div className="flex items-center gap-3 min-w-0">
          <button
            onClick={() => navigate('/dashboard/documents')}
            className="p-1.5 text-textMuted hover:text-textMain hover:bg-surface rounded-md transition-colors shrink-0"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div className="min-w-0">
            <h3 className="font-bold text-textMain leading-tight truncate">{doc.code}</h3>
            <p className="text-xs text-textMuted truncate">{doc.title}</p>
          </div>
          <DocStatusBadge status={doc.status} />
          {isLocked && (
            <span className="hidden lg:flex items-center gap-1.5 text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-full px-2.5 py-1">
              <Lock className="w-3 h-3" /> {doc.status === 'OBSOLETE' ? t('editor.obsoleteReadOnly') : t('editor.readOnly')}
            </span>
          )}
          {!isLocked && recusaEditar && (
            <span className="hidden lg:flex items-center gap-1.5 text-xs text-textMuted bg-surface border border-borderLight rounded-full px-2.5 py-1" title={t(recusaEditar)}>
              <Eye className="w-3 h-3" /> {t('docs.openReadOnly')}
            </span>
          )}
          {/* Autosave state — the draft is where the work lives. */}
          {!isLocked && !recusaEditar && !viewingOld && (
            <span className={cn('hidden md:flex items-center gap-1.5 text-[11px] rounded-full px-2 py-0.5 whitespace-nowrap',
              saveState === 'error' ? 'text-red-700 bg-red-50' : 'text-textMuted bg-surface')}>
              {saveState === 'saving' ? <Loader2 className="w-3 h-3 animate-spin" />
                : saveState === 'dirty' ? <CloudOff className="w-3 h-3" />
                : saveState === 'error' ? <AlertTriangle className="w-3 h-3" />
                : <Cloud className="w-3 h-3" />}
              {saveState === 'saving' ? t('editor.autosaveSaving')
                : saveState === 'dirty' ? t('editor.unsaved')
                : saveState === 'error' ? t('editor.autosaveError')
                : lastSavedAt ? t('editor.autosaveSavedAt').replace('{time}', lastSavedAt.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }))
                : draft ? t('editor.autosaveDraft') : t('editor.autosaveClean')}
            </span>
          )}
        </div>

        <div className="flex items-center gap-2 shrink-0">
          {sidebarButton('files', FolderTree, t('tree.title'), projectFiles.length || null)}
          {sidebarButton('changes', GitBranch, t('changes.title'), changedFileCount || null, changedFileCount > 0)}
          {sidebarButton('history', History, t('editor.history'), closedCommits.length || null)}
          {sidebarButton('attachments', Paperclip, t('editor.attachments'))}
          <button
            onClick={() => { setCommitMessage(''); setSaveModalOpen(true); }}
            disabled={isSaving || !canWrite || (!draft && !isDirtyRef.current)}
            title={recusaEditar ? t(recusaEditar) : (!draft && saveState !== 'dirty') ? t('changes.nothingToSave') : t('editor.saveDraft')}
            className={cn('btn-outline flex items-center gap-2 py-1.5 px-3 text-sm',
                          (recusaEditar || !canWrite) && 'opacity-50 cursor-not-allowed')}
          >
            <Save className="w-4 h-4" />
            <span className="hidden xl:inline">{isSaving ? t('editor.saving') : t('editor.saveDraft')}</span>
          </button>
          <button
            onClick={() => handleCompile()}
            disabled={isCompiling}
            title="Ctrl+Enter"
            className="btn-secondary flex items-center gap-2 py-1.5 px-3 text-sm"
          >
            <Play className="w-4 h-4" />
            <span className="hidden xl:inline">{isCompiling ? t('editor.compiling') : t('editor.compile')}</span>
          </button>
          <button
            onClick={() => setSubmitModalOpen(true)}
            disabled={isLocked || !!recusaEditar}
            title={recusaEditar ? t(recusaEditar) : undefined}
            className={cn('btn-primary flex items-center gap-2 py-1.5 px-3 text-sm',
                          recusaEditar && 'opacity-50 cursor-not-allowed')}
          >
            <CheckCircle className="w-4 h-4" />
            <span className="hidden xl:inline">{t('editor.submitReview')}</span>
          </button>
        </div>
      </div>

      {/* Banners the user must act on */}
      {doc.status === 'IN_REVIEW' && openReview && (
        <div className="bg-amber-50 border-b border-amber-200 px-4 py-2 flex items-center justify-between gap-3 shrink-0">
          <span className="flex items-center gap-2 text-sm text-amber-800 min-w-0">
            <Lock className="w-4 h-4 shrink-0" />
            <span className="truncate">
              {t('editor.inReviewBanner').replace('{title}', openReview.title).replace('{who}', openReview.requestedByName)}
            </span>
          </span>
          <div className="flex items-center gap-3 shrink-0">
            {!recusaAprovar && (
              <button onClick={() => navigate('/dashboard/reviews')} className="text-sm font-medium text-amber-900 hover:underline">
                {t('editor.goDecide')}
              </button>
            )}
            {!recusaEditar && (
              <button onClick={() => setWithdrawOpen(true)} className="text-sm font-medium text-amber-900 hover:underline">
                {t('editor.withdrawReview')}
              </button>
            )}
          </div>
        </div>
      )}

      {viewingOld && selectedCommit && (
        <div className="bg-blue-50 border-b border-blue-200 px-4 py-2 flex items-center justify-between gap-3 shrink-0">
          <span className="flex items-center gap-2 text-sm text-blue-800">
            <History className="w-4 h-4 shrink-0" />
            {t('editor.viewingVersionBanner').replace('{v}', versionLabel(selectedCommit)).replace('{date}', formatDate(selectedCommit.createdAt, lang))}
          </span>
          <div className="flex items-center gap-3 shrink-0">
            <button onClick={() => setDiffModal({ to: selectedCommit.id, caption: `${versionLabel(selectedCommit)} — ${t('diff.againstPrevious')}` })}
                    className="text-sm font-medium text-blue-900 hover:underline flex items-center gap-1">
              <Diff className="w-3.5 h-3.5" /> {t('editor.diff')}
            </button>
            <button onClick={() => { const head = commits[0]; if (head) selectVersion(head); }}
                    className="text-sm font-medium text-blue-900 hover:underline">
              {t('editor.backToLatest')}
            </button>
          </div>
        </div>
      )}

      {doc?.reviewOverdue && (
        <div className="bg-red-50 border-b border-red-200 px-4 py-2.5 flex items-center justify-between gap-3 shrink-0">
          <span className="flex items-center gap-2 text-sm text-red-800">
            <AlertTriangle className="w-4 h-4 shrink-0" />
            <strong>{t('review.overdue')}</strong>
            {doc.nextReviewDate && (
              <span className="text-red-700">— {t('review.nextReview')}: {formatDate(doc.nextReviewDate, lang)}</span>
            )}
          </span>
          <button onClick={() => setPeriodicModalOpen(true)}
                  className="text-sm font-medium text-red-800 hover:underline whitespace-nowrap">
            {t('review.doPeriodic')}
          </button>
        </div>
      )}

      {ackStatus?.required && (
        <div className={cn('border-b px-4 py-2.5 flex items-center justify-between gap-3 shrink-0',
                           ackStatus.acknowledged ? 'bg-green-50 border-green-200' : 'bg-amber-50 border-amber-200')}>
          <span className={cn('flex items-center gap-2 text-sm', ackStatus.acknowledged ? 'text-green-800' : 'text-amber-800')}>
            {ackStatus.acknowledged ? <BadgeCheck className="w-4 h-4 shrink-0" /> : <AlertTriangle className="w-4 h-4 shrink-0" />}
            {ackStatus.acknowledged
              ? t('ack.confirmed').replace('{date}', ackStatus.acknowledgedAt ? formatDate(ackStatus.acknowledgedAt, lang) : '')
              : t('ack.banner')}
          </span>
          {!ackStatus.acknowledged && (
            <button onClick={handleAcknowledge} className="btn-primary py-1 px-3 text-sm whitespace-nowrap">
              {t('ack.confirm')}
            </button>
          )}
        </div>
      )}

      {/* Editor & Preview Split */}
      <div className="flex-1 flex overflow-hidden">
        {/* Project file tree (Overleaf-style) */}
        {sidebarView === 'files' && (
          <div className="w-64 border-r border-borderLight bg-surface shrink-0">
            <FileTree
              files={projectFiles}
              selectedPath={openPath}
              readOnly={!canWrite}
              onSelect={openFile}
              onUpload={handleUploadProjectFile}
              onImportZip={handleZipEscolhido}
              onExportZip={handleExportZip}
              onCreateFile={async (path) => {
                if (!selectedCommitId) return;
                try {
                  const res = await api.put(`/documents/${id}/files/content`, { path, content: '' });
                  await afterWrite(res.headers?.['x-working-commit']);
                  const list = await fetchProjectFiles(selectedCommitIdRef.current!);
                  const created = list.find((f) => f.path === path);
                  if (created) await openFile(created);
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.createError'));
                }
              }}
              onRename={async (from, to) => {
                if (!selectedCommitId) return;
                try {
                  const res = await api.post(`/documents/${id}/files/rename`, { from, to });
                  if (openPath === from) setOpenPath(to);
                  await afterWrite(res.data?.workingCommit);
                  lastCompiledRef.current = null;
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.renameError'));
                }
              }}
              onDelete={async (path) => {
                if (!selectedCommitId) return;
                if (!window.confirm(t('tree.deleteConfirm').replace('{name}', path))) return;
                try {
                  const res = await api.delete(`/documents/${id}/files`, { params: { path } });
                  if (openPath === path) { setOpenPath(null); setContent(''); contentRef.current = ''; }
                  await afterWrite(res.headers?.['x-working-commit']);
                  lastCompiledRef.current = null;
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.deleteError'));
                }
              }}
              onSetRoot={async (path) => {
                if (!selectedCommitId) return;
                try {
                  const res = await api.post(`/documents/${id}/files/root`, null, { params: { path } });
                  await afterWrite(res.headers?.['x-working-commit']);
                  toast('info', t('tree.rootChanged'));
                  lastCompiledRef.current = null;
                  handleCompile();
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.rootError'));
                }
              }}
            />
          </div>
        )}

        {/* Changes (GitHub Desktop "Changes" tab): what the draft alters, and the commit box */}
        {sidebarView === 'changes' && (
          <div className="w-80 border-r border-borderLight bg-surface flex flex-col shrink-0">
            <div className="p-3 border-b border-borderLight font-semibold text-sm flex justify-between items-center">
              <span className="flex items-center gap-2">
                <GitBranch className="w-4 h-4 text-primary" />
                {t('changes.title')}
                {changedFileCount > 0 && (
                  <span className="text-[10px] font-semibold bg-amber-100 text-amber-800 rounded-full px-1.5">{changedFileCount}</span>
                )}
              </span>
              <button onClick={() => setSidebarView('none')} className="text-textMuted hover:text-textMain text-xs">{t('common.close')}</button>
            </div>

            <div className="flex-1 overflow-y-auto">
              {!draft ? (
                <div className="p-4 text-xs text-textMuted text-center space-y-2">
                  <CheckCircle className="w-8 h-8 mx-auto opacity-30" />
                  <p>{t('changes.none')}</p>
                  {closedCommits[0] && (
                    <p className="text-[11px]">
                      {t('changes.lastVersion').replace('{v}', `v${closedCommits[0].revision}`).replace('{date}', formatDate(closedCommits[0].createdAt, lang))}
                    </p>
                  )}
                </div>
              ) : !workingDiff ? (
                <p className="p-4 text-xs text-textMuted text-center">{t('common.loading')}</p>
              ) : changedFileCount === 0 ? (
                <div className="p-4 text-xs text-textMuted text-center space-y-2">
                  <p>{t('changes.draftIdentical')}</p>
                  <button onClick={() => setDiscardOpen(true)} className="text-primary hover:underline text-xs">{t('changes.discard')}</button>
                </div>
              ) : (
                <>
                  <div className="px-3 py-2 text-[11px] text-textMuted flex items-center justify-between border-b border-borderLight">
                    <span>{t('changes.vsLast').replace('{v}', closedCommits[0] ? `v${closedCommits[0].revision}` : '—')}</span>
                    <DiffStat additions={workingDiff.additions} deletions={workingDiff.deletions} />
                  </div>
                  <ul className="divide-y divide-borderLight">
                    {workingDiff.changes.filter((c) => c.status !== 'UNCHANGED')
                      .sort((a, b) => a.path.localeCompare(b.path))
                      .map((c) => (
                        <li key={c.path}>
                          <button
                            onClick={() => setDiffModal({ to: draft.id, caption: t('changes.title'), initialPath: c.path })}
                            className="w-full flex items-center gap-2 px-3 py-1.5 text-left hover:bg-elevated text-xs"
                            title={t('diff.openFile')}
                          >
                            <span className={cn('font-mono font-bold w-3 shrink-0',
                              c.status === 'ADDED' ? 'text-green-700' : c.status === 'REMOVED' ? 'text-red-700' : 'text-amber-700')}>
                              {c.status === 'ADDED' ? 'A' : c.status === 'REMOVED' ? 'D' : 'M'}
                            </span>
                            <span className={cn('font-mono truncate flex-1', c.status === 'REMOVED' && 'line-through decoration-red-300')} title={c.path}>{c.path}</span>
                            {c.isText ? <DiffStat additions={c.additions} deletions={c.deletions} /> : <span className="text-[10px] text-textMuted">bin</span>}
                          </button>
                        </li>
                      ))}
                  </ul>
                  <div className="px-3 py-2">
                    <button
                      onClick={() => setDiffModal({ to: draft.id, caption: t('changes.title') })}
                      className="text-xs text-primary hover:underline flex items-center gap-1"
                    >
                      <Diff className="w-3.5 h-3.5" /> {t('changes.viewAll')}
                    </button>
                  </div>
                </>
              )}
            </div>

            {/* Commit box */}
            {draft && canWrite && (
              <form
                className="p-3 border-t border-borderLight bg-elevated space-y-2"
                onSubmit={async (e) => {
                  e.preventDefault();
                  const ok = await saveVersion(commitMessage, changeReason);
                  if (ok) { setCommitMessage(''); setChangeReason(''); }
                }}
              >
                <input
                  type="text"
                  value={commitMessage}
                  onChange={(e) => setCommitMessage(e.target.value)}
                  className="input-field text-sm py-1.5"
                  placeholder={t('editor.commitMessagePlaceholder')}
                  maxLength={255}
                />
                <textarea
                  value={changeReason}
                  onChange={(e) => setChangeReason(e.target.value)}
                  rows={2}
                  className="input-field text-sm py-1.5 resize-none"
                  placeholder={t('editor.changeReasonPlaceholder')}
                />
                <div className="flex items-center gap-2">
                  <button
                    type="submit"
                    disabled={isSaving || !commitMessage.trim() || !changeReason.trim() || changedFileCount === 0}
                    className="btn-primary flex-1 py-1.5 text-sm flex items-center justify-center gap-2"
                  >
                    <GitCommitHorizontal className="w-4 h-4" />
                    {isSaving ? t('editor.saving') : t('changes.commit').replace('{n}', String(changedFileCount))}
                  </button>
                  <button
                    type="button"
                    onClick={() => setDiscardOpen(true)}
                    title={t('changes.discard')}
                    className="p-2 rounded-md text-textMuted hover:text-red-600 hover:bg-red-50"
                  >
                    <Undo2 className="w-4 h-4" />
                  </button>
                </div>
                <p className="text-[10px] text-textMuted">{t('changes.commitHint')}</p>
              </form>
            )}
          </div>
        )}

        {/* Version History (GitHub Desktop "History" tab) */}
        {sidebarView === 'history' && (
          <div className="w-80 border-r border-borderLight bg-surface overflow-y-auto flex flex-col shrink-0">
            <div className="p-3 border-b border-borderLight font-semibold text-sm flex justify-between items-center sticky top-0 bg-surface z-10">
              <span className="flex items-center gap-2">
                <GitCommitHorizontal className="w-4 h-4 text-primary" />
                {t('editor.commitHistory')}
                <span className="text-[10px] font-semibold bg-elevated border border-borderLight text-textMuted rounded-full px-1.5">{closedCommits.length}</span>
              </span>
              <button onClick={() => setSidebarView('none')} className="text-textMuted hover:text-textMain text-xs">
                {t('common.close')}
              </button>
            </div>

            {draft && (
              <button
                onClick={() => { setSidebarView('changes'); }}
                className={cn('text-left p-3 border-b border-borderLight border-l-2 hover:bg-elevated',
                  selectedCommitId === draft.id ? 'border-l-amber-500 bg-amber-50/40' : 'border-l-transparent')}
              >
                <div className="flex items-center gap-2">
                  <span className="text-[10px] font-bold text-amber-800 bg-amber-100 rounded px-1.5 py-0.5 shrink-0 uppercase">{t('changes.badge')}</span>
                  <span className="text-sm font-medium text-textMain truncate flex-1">{t('changes.title')}</span>
                  {changedFileCount > 0 && <span className="text-[11px] text-textMuted">{t('diff.filesChanged').replace('{n}', String(changedFileCount))}</span>}
                </div>
                <div className="text-xs text-textMuted mt-1">{t('changes.historyHint')}</div>
              </button>
            )}

            <div className="flex-1 divide-y divide-borderLight">
              {closedCommits.map((commit) => {
                const active = selectedCommitId === commit.id;
                return (
                  <div
                    key={commit.id}
                    className={cn('p-3 cursor-pointer group transition-colors border-l-2',
                      active ? 'bg-blue-50 border-l-primary' : 'border-l-transparent hover:bg-elevated')}
                    onClick={() => selectVersion(commit)}
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-primary bg-blue-100 rounded px-1.5 py-0.5 shrink-0">v{commit.revision}</span>
                      <div className="text-sm font-medium text-textMain truncate flex-1" title={commit.message}>{commit.message}</div>
                      {commit.isOfficial && (
                        <span className="flex items-center gap-1 text-[10px] font-semibold text-green-700 bg-green-50 border border-green-200 rounded-full px-2 py-0.5 uppercase shrink-0" title={t('editor.official')}>
                          <BadgeCheck className="w-3 h-3" /> {t('editor.official')}
                        </span>
                      )}
                      {!commit.isOfficial && commit.id === closedCommits[0]?.id && (
                        <span className="text-[10px] font-semibold text-textMuted bg-elevated border border-borderLight rounded-full px-2 py-0.5 uppercase shrink-0">{t('editor.current')}</span>
                      )}
                    </div>
                    {commit.changeReason && (
                      <p className="text-xs text-textMuted mt-1 italic line-clamp-2" title={commit.changeReason}>
                        {t('editor.changeReason')}: {commit.changeReason}
                      </p>
                    )}
                    <div className="text-xs text-textMuted mt-1 flex items-center gap-1.5">
                      <span className="truncate">{commit.authorName}</span>
                      <span className="text-textMuted/70">· {formatDate(commit.createdAt, lang)}</span>
                    </div>
                    <div className="text-[11px] text-textMuted/80 mt-0.5">
                      {t('editor.treeSummary').replace('{n}', String(commit.fileCount)).replace('{size}', formatFileSize(commit.treeBytes))}
                    </div>
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 mt-1.5 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                      <button
                        onClick={(e) => { e.stopPropagation(); setDiffModal({ to: commit.id, caption: `v${commit.revision} — ${t('diff.againstPrevious')}` }); }}
                        className="flex items-center gap-1 text-[11px] text-primary hover:underline"
                      >
                        <Diff className="w-3 h-3" /> {t('editor.diff')}
                      </button>
                      {officialCommit && !commit.isOfficial && (
                        <button
                          onClick={(e) => { e.stopPropagation(); setDiffModal({ from: officialCommit.id, to: commit.id, caption: `v${commit.revision} — ${t('diff.againstOfficial')} (v${officialCommit.revision})` }); }}
                          className="flex items-center gap-1 text-[11px] text-primary hover:underline"
                        >
                          <BadgeCheck className="w-3 h-3" /> {t('editor.diffOfficial')}
                        </button>
                      )}
                      {!commit.isOfficial && !recusaAprovar && (
                        <button
                          onClick={(e) => { e.stopPropagation(); setOfficialTarget(commit); }}
                          className="flex items-center gap-1 text-[11px] text-green-700 hover:underline"
                        >
                          <BadgeCheck className="w-3 h-3" /> {t('editor.makeOfficial')}
                        </button>
                      )}
                      {!isLocked && !recusaEditar && commit.id !== closedCommits[0]?.id && (
                        <button
                          onClick={(e) => { e.stopPropagation(); setRestoreTarget(commit); }}
                          className="flex items-center gap-1 text-[11px] text-amber-700 hover:underline"
                        >
                          <RotateCcw className="w-3 h-3" /> {t('editor.restore')}
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
              {closedCommits.length === 0 && (
                <div className="p-4 text-xs text-center text-textMuted">{t('editor.noCommits')}</div>
              )}
            </div>
          </div>
        )}

        {/* Attachments Sidebar */}
        {sidebarView === 'attachments' && (
          <div className="w-80 border-r border-borderLight bg-surface overflow-y-auto flex flex-col shrink-0">
            <div className="p-3 border-b border-borderLight font-semibold text-sm flex justify-between items-center sticky top-0 bg-surface z-10">
              <span className="flex items-center gap-2">
                <Paperclip className="w-4 h-4 text-primary" />
                {t('editor.attachments')}
              </span>
              <button onClick={() => setSidebarView('none')} className="text-textMuted hover:text-textMain text-xs">
                {t('common.close')}
              </button>
            </div>

            {!isLocked && !recusaEditar && (
              <div className="p-3 border-b border-borderLight">
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) handleUploadAttachment(f); }}
                />
                <button
                  onClick={() => fileInputRef.current?.click()}
                  disabled={isUploadingAttachment}
                  className="w-full btn-outline flex items-center justify-center gap-2 py-2 text-sm"
                >
                  <Upload className="w-4 h-4" />
                  {isUploadingAttachment ? t('editor.attachmentUploading') : t('editor.attachmentUpload')}
                </button>
                <p className="text-[11px] text-textMuted mt-1.5">{t('editor.attachmentHint')}</p>
              </div>
            )}

            <div className="flex-1 divide-y divide-borderLight">
              {attachments.map((a) => (
                <div key={a.id} className="p-3 group">
                  <div className="flex items-start gap-2">
                    <FileIcon className="w-4 h-4 text-textMuted shrink-0 mt-0.5" />
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-medium text-textMain truncate" title={a.fileName}>{a.fileName}</div>
                      <div className="text-xs text-textMuted mt-0.5">{formatFileSize(a.sizeBytes)} · {a.uploadedByName}</div>
                      <div className="text-xs text-textMuted">{formatDate(a.uploadedAt, lang)}</div>
                    </div>
                  </div>
                  <div className="flex items-center gap-3 mt-1.5 pl-6 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                    <button onClick={() => handleDownloadAttachment(a)} className="flex items-center gap-1 text-[11px] text-primary hover:underline">
                      <Download className="w-3 h-3" /> {t('editor.attachmentDownload')}
                    </button>
                    {!isLocked && !recusaEditar && (
                      <button onClick={() => setAttachmentToDelete(a)} className="flex items-center gap-1 text-[11px] text-red-600 hover:underline">
                        <Trash2 className="w-3 h-3" /> {t('common.delete')}
                      </button>
                    )}
                  </div>
                </div>
              ))}
              {attachments.length === 0 && (
                <div className="p-4 text-xs text-center text-textMuted">{t('editor.noAttachments')}</div>
              )}
            </div>
          </div>
        )}

        {/* Code Editor */}
        <div className="flex-1 border-r border-borderLight flex flex-col bg-white min-w-0">
          <div className="bg-surface px-4 py-2 border-b border-borderLight text-xs font-medium text-textMuted uppercase tracking-wider flex justify-between items-center">
            <span className="truncate normal-case font-mono text-[11px]" title={openPath ?? undefined}>
              {openPath ?? t('editor.source')}
            </span>
            {viewingOld && (
              <span className="text-amber-600 normal-case font-normal shrink-0 ml-2">{t('editor.viewingOldVersion')}</span>
            )}
          </div>
          <div className="flex-1 overflow-auto">
            {binaryPreviewUrl ? (
              <div className="h-full flex flex-col items-center justify-center gap-3 p-4 bg-surface">
                {binaryPreviewType.startsWith('image/') ? (
                  <img src={binaryPreviewUrl} alt={openPath ?? ''} className="max-w-full max-h-[80%] object-contain border border-borderLight rounded bg-white" />
                ) : binaryPreviewType === 'application/pdf' ? (
                  <iframe src={binaryPreviewUrl} title={openPath ?? 'PDF'} className="w-full h-[85%] border border-borderLight rounded bg-white" />
                ) : (
                  <div className="flex flex-col items-center gap-2 text-textMuted">
                    <FileIcon className="w-10 h-10" />
                    <span className="text-sm font-mono">{openPath}</span>
                  </div>
                )}
                <a href={binaryPreviewUrl} download={openPath?.split('/').pop()} className="text-xs text-primary hover:underline flex items-center gap-1">
                  <Download className="w-3.5 h-3.5" /> {t('tree.downloadFile')}
                </a>
              </div>
            ) : (
              <CodeMirror
                value={content}
                height="100%"
                extensions={latexExtensions}
                onChange={(value) => { setContent(value); markDirty(true); }}
                className="text-sm h-full"
                theme="light"
                readOnly={!canWrite}
                editable={canWrite}
              />
            )}
          </div>
        </div>

        {/* PDF Preview */}
        <div className="w-[45%] bg-gray-100 flex flex-col shrink-0">
          <div className="bg-surface px-4 py-2 border-b border-borderLight text-xs font-medium text-textMuted uppercase tracking-wider flex justify-between items-center">
            <span>{t('editor.preview')}</span>
            <div className="flex items-center gap-3">
              {isCompiling && <span className="text-primary animate-pulse normal-case">{t('editor.building')}</span>}
              {compileError && !isCompiling && (
                <button
                  onClick={() => setErrorsOpen((v) => !v)}
                  className="flex items-center gap-1.5 normal-case rounded px-2 py-0.5 border transition-colors bg-red-50 border-red-200 text-red-700 hover:bg-red-100"
                  aria-expanded={errorsOpen}
                >
                  <AlertTriangle className="w-3.5 h-3.5" />
                  {t('editor.problemsBadge').replace('{n}', String(errorLines.length || 1))}
                  {errorsOpen ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                </button>
              )}
              {pdfBlobUrl && (
                <button
                  onClick={() => { if (!showPaged) setViewerError(null); setPagedView(!showPaged); }}
                  className="flex items-center gap-1 text-textMuted hover:text-primary normal-case"
                  title={t('editor.viewerToggleHint')}
                >
                  <FileText className="w-3.5 h-3.5" />
                  {showPaged ? t('editor.viewerNative') : t('editor.viewerPaged')}
                </button>
              )}
              {pdfBlobUrl && (
                <button onClick={handleDownloadPdf} className="flex items-center gap-1 text-primary hover:underline normal-case">
                  <Download className="w-3.5 h-3.5" /> {t('editor.downloadPdf')}
                </button>
              )}
            </div>
          </div>

          {compileError && errorsOpen && (
            <div className="border-b border-red-200 bg-red-50 px-3 py-2 max-h-56 overflow-auto shrink-0">
              {errorLines.length > 0 ? (
                <ul className="space-y-1">
                  {errorLines.map((line, i) => {
                    const at = line.match(/^([^\s:][^:]*\.(?:tex|sty|cls|bib)):(\d+)/i);
                    return (
                      <li key={i} className="text-[11px] font-mono leading-4 text-red-800">
                        {at && (
                          <button
                            onClick={() => {
                              const target = projectFiles.find((f) => f.path === at[1] || f.path.endsWith(`/${at[1]}`));
                              if (target) { setSidebarView('files'); openFile(target); }
                            }}
                            className="text-primary hover:underline mr-1"
                            title={t('editor.openFileAtLine')}
                          >
                            {at[1]}:{at[2]}
                          </button>
                        )}
                        <span className="whitespace-pre-wrap break-words">{at ? line.slice(at[0].length).replace(/^:\s*/, '') : line}</span>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <pre className="text-[11px] leading-4 font-mono text-red-700 whitespace-pre-wrap break-words">{compileError}</pre>
              )}
            </div>
          )}

          {pdfIsStale && pdfBlobUrl && (
            <div className="border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-[11px] text-amber-800 shrink-0">
              {t('editor.previewStale')}
            </div>
          )}

          <div
            ref={previewRef}
            className={cn('flex-1 overflow-auto flex flex-col',
              showPaged ? 'items-center gap-4 p-6 bg-gray-100 dark:bg-neutral-900' : 'p-0')}
          >
            {!pdfBlobUrl ? (
              <div className="flex flex-col items-center justify-center text-textMuted h-full opacity-50">
                <FileText className="w-16 h-16 mb-4" />
                <p>{t('editor.compileHint')}</p>
              </div>
            ) : !showPaged ? (
              <>
                {viewerError && (
                  <div className="shrink-0 border-b border-amber-200 bg-amber-50 px-4 py-2">
                    <p className="text-[11px] text-amber-900">{t('editor.viewerFellBack')}</p>
                    <p className="text-[11px] font-mono text-amber-800 mt-1 break-words">{viewerError}</p>
                  </div>
                )}
                <object data={pdfBlobUrl} type="application/pdf" className="flex-1 w-full min-h-0" aria-label={t('editor.pdfPreview')}>
                  <div className="p-8 text-center text-textMuted">
                    <p>{t('editor.viewerNoNative')}</p>
                    <button onClick={handleDownloadPdf} className="text-primary hover:underline mt-2">{t('editor.downloadPdf')}</button>
                  </div>
                </object>
              </>
            ) : (
              <PdfDocument
                file={pdfBlobUrl}
                onLoadSuccess={({ numPages }) => { setNumPages(numPages); setViewerError(null); }}
                onLoadError={(e: Error) => setViewerError(e?.message || String(e))}
                onSourceError={(e: Error) => setViewerError(e?.message || String(e))}
                loading={<div className="p-12 text-textMuted">{t('common.loading')}</div>}
                error={<div className="p-12 text-textMuted">{t('editor.viewerFellBack')}</div>}
              >
                {Array.from({ length: numPages ?? 0 }).map((_, i) => (
                  <div key={i} className="bg-white shadow-lg border border-gray-200 mb-4">
                    <Page pageNumber={i + 1} renderTextLayer={false} renderAnnotationLayer={false} width={pageWidth}
                          onRenderError={(e: Error) => setViewerError(e?.message || String(e))} />
                  </div>
                ))}
              </PdfDocument>
            )}
          </div>
        </div>
      </div>

      {/* ZIP confirmation: what the archive would change, before anything is written */}
      <Modal
        isOpen={!!zipPendente}
        onClose={() => setZipPendente(null)}
        title={t('zip.confirmTitle')}
        wide
        footer={
          <div className="flex justify-end gap-2">
            <button onClick={() => setZipPendente(null)} className="btn-outline py-2 px-4 text-sm">{t('common.cancel')}</button>
            <button
              onClick={handleImportZip}
              disabled={isCompiling || (zipSaveAsVersion && (!zipMessage.trim() || !zipReason.trim()))}
              className={cn('py-2 px-4 text-sm rounded-lg font-medium text-white disabled:opacity-50',
                (zipPendente?.preview?.removed ?? 0) > 0 ? 'bg-red-600 hover:bg-red-700' : 'bg-primary hover:bg-primary-dark')}
            >
              {zipSaveAsVersion ? t('zip.applyAndSave') : t('zip.apply')}
            </button>
          </div>
        }
      >
        <div className="space-y-3">
          <p className="text-sm text-textMuted">{t('zip.confirmBody')}</p>
          {zipPendente?.root && (
            <p className="text-xs text-textMuted">{t('tree.rootFile')}: <span className="font-mono">{zipPendente.root}</span></p>
          )}
          {zipPendente && (
            <DiffViewer
              documentId={id!}
              precomputed={{ tree: zipPendente.preview, files: zipPendente.files }}
              height="48vh"
              caption={t('zip.previewCaption')}
            />
          )}
          <label className="flex items-start gap-2.5 cursor-pointer pt-1">
            <input type="checkbox" checked={zipSaveAsVersion} onChange={(e) => setZipSaveAsVersion(e.target.checked)}
                   className="mt-0.5 w-4 h-4 rounded border-borderLight text-primary focus:ring-primary" />
            <span>
              <span className="text-sm font-medium text-textMain">{t('zip.saveAsVersion')}</span>
              <span className="block text-xs text-textMuted">{t('zip.saveAsVersionHint')}</span>
            </span>
          </label>
          {zipSaveAsVersion && (
            <div className="grid grid-cols-1 gap-2 pl-6">
              <input type="text" value={zipMessage} onChange={(e) => setZipMessage(e.target.value)}
                     className="input-field text-sm" placeholder={t('editor.commitMessagePlaceholder')} />
              <textarea value={zipReason} onChange={(e) => setZipReason(e.target.value)} rows={2}
                        className="input-field text-sm resize-none" placeholder={t('editor.changeReasonPlaceholder')} />
            </div>
          )}
        </div>
      </Modal>

      {/* Save version (close the draft) */}
      <Modal
        isOpen={saveModalOpen}
        onClose={() => setSaveModalOpen(false)}
        title={t('editor.commitModalTitle')}
        footer={
          <>
            <button onClick={() => setSaveModalOpen(false)} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button onClick={handleSave} disabled={isSaving || !commitMessage.trim() || !changeReason.trim()} className="btn-primary py-2 px-4 text-sm">
              {isSaving ? t('editor.saving') : t('common.save')}
            </button>
          </>
        }
      >
        {workingDiff && changedFileCount > 0 && (
          <div className="mb-4 text-xs text-textMuted flex items-center gap-2">
            <GitBranch className="w-3.5 h-3.5 text-amber-600" />
            {t('diff.filesChanged').replace('{n}', String(changedFileCount))}
            <DiffStat additions={workingDiff.additions} deletions={workingDiff.deletions} />
            <button type="button" onClick={() => draft && setDiffModal({ to: draft.id, caption: t('changes.title') })} className="text-primary hover:underline ml-auto">
              {t('changes.viewAll')}
            </button>
          </div>
        )}
        <div className="space-y-1.5">
          <label className="text-sm font-medium text-textMain">{t('editor.commitMessage')}</label>
          <input type="text" value={commitMessage} onChange={(e) => setCommitMessage(e.target.value)} className="input-field"
                 placeholder={t('editor.commitMessagePlaceholder')} autoFocus maxLength={255} />
        </div>
        <div className="space-y-1.5 mt-4">
          <label className="text-sm font-medium text-textMain">{t('editor.changeReason')}</label>
          <textarea value={changeReason} onChange={(e) => setChangeReason(e.target.value)} rows={2} className="input-field resize-none"
                    placeholder={t('editor.changeReasonPlaceholder')} />
          <p className="text-xs text-textMuted">{t('editor.changeReasonHint')}</p>
        </div>
      </Modal>

      {/* Periodic review — ISO 17025 8.3.2 b) */}
      <Modal
        isOpen={periodicModalOpen}
        onClose={() => setPeriodicModalOpen(false)}
        title={t('review.periodicTitle')}
        footer={
          <>
            <button onClick={() => setPeriodicModalOpen(false)} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button onClick={handlePeriodicReview} disabled={isRecordingReview} className="btn-primary py-2 px-4 text-sm">
              {t('review.doPeriodic')}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <p className="text-sm text-textMuted">{t('review.periodicBody')}</p>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('review.periodicNotes')}</label>
            <textarea value={periodicNotes} onChange={(e) => setPeriodicNotes(e.target.value)} rows={2} className="input-field resize-none" />
          </div>
        </div>
      </Modal>

      {/* Submit for review */}
      <Modal
        isOpen={submitModalOpen}
        onClose={() => setSubmitModalOpen(false)}
        title={t('editor.submitModalTitle')}
        wide
        footer={
          <>
            <button onClick={() => setSubmitModalOpen(false)} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button onClick={handleSubmitReview} disabled={isSubmittingReview || !reviewTitle.trim()} className="btn-primary py-2 px-4 text-sm">
              {isSubmittingReview ? t('common.loading') : t('editor.submitReview')}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <p className="text-sm text-textMuted">{t('editor.submitModalBody')}</p>
          {(draft || saveState === 'dirty') && (
            <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded px-3 py-2">{t('editor.submitWillSave')}</p>
          )}
          {/* What is being proposed: the newest version against the one in force. */}
          {headId && doc?.officialCommitId && headId !== doc.officialCommitId && (
            <DiffViewer documentId={id!} from={doc.officialCommitId} to={headId} height="40vh"
                        caption={`${t('diff.title')} — ${t('diff.againstOfficial')}`} />
          )}
          <div className="grid grid-cols-1 gap-4">
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('editor.reviewTitle')}</label>
              <input type="text" value={reviewTitle} onChange={(e) => setReviewTitle(e.target.value)} className="input-field"
                     placeholder={t('editor.reviewTitlePlaceholder')} autoFocus />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium text-textMain">{t('editor.reviewDescription')}</label>
              <textarea value={reviewDescription} onChange={(e) => setReviewDescription(e.target.value)} rows={3} className="input-field resize-none"
                        placeholder={t('editor.reviewDescriptionPlaceholder')} />
            </div>
          </div>
        </div>
      </Modal>

      {/* Diff viewer — GitHub Desktop layout */}
      <Modal
        isOpen={!!diffModal}
        onClose={() => setDiffModal(null)}
        title={<span className="flex items-center gap-2"><Diff className="w-5 h-5 text-primary" />{t('diff.title')}</span>}
        wide
      >
        {diffModal && (
          <DiffViewer documentId={id!} from={diffModal.from} to={diffModal.to} initialPath={diffModal.initialPath}
                      caption={diffModal.caption} height="65vh" />
        )}
      </Modal>

      {/* Restore version confirmation */}
      <ConfirmModal
        isOpen={!!restoreTarget}
        onClose={() => setRestoreTarget(null)}
        onConfirm={handleRestore}
        title={`${t('editor.restoreConfirmTitle')} v${restoreTarget?.revision ?? ''}`}
        body={draft ? `${t('editor.restoreConfirmBody')} ${t('editor.restoreReplacesDraft')}` : t('editor.restoreConfirmBody')}
        confirmLabel={t('editor.restore')}
        loading={isRestoring}
      />

      {/* Make this revision the one in force */}
      <ConfirmModal
        isOpen={!!officialTarget}
        onClose={() => setOfficialTarget(null)}
        onConfirm={handleMakeOfficial}
        title={`${t('editor.makeOfficialConfirmTitle')} (v${officialTarget?.revision ?? ''})`}
        body={t('editor.makeOfficialConfirmBody')}
        confirmLabel={t('editor.makeOfficial')}
        loading={isSettingOfficial}
      />

      {/* Discard working changes */}
      <ConfirmModal
        isOpen={discardOpen}
        onClose={() => setDiscardOpen(false)}
        onConfirm={handleDiscardDraft}
        title={t('changes.discardTitle')}
        body={t('changes.discardBody').replace('{n}', String(changedFileCount))}
        confirmLabel={t('changes.discard')}
        danger
        loading={isDiscarding}
      />

      {/* Withdraw the open review request */}
      <ConfirmModal
        isOpen={withdrawOpen}
        onClose={() => setWithdrawOpen(false)}
        onConfirm={handleWithdraw}
        title={t('editor.withdrawTitle')}
        body={t('editor.withdrawBody')}
        confirmLabel={t('editor.withdrawReview')}
        danger
      />

      {/* Delete attachment confirmation */}
      <ConfirmModal
        isOpen={!!attachmentToDelete}
        onClose={() => setAttachmentToDelete(null)}
        onConfirm={handleDeleteAttachment}
        title={t('editor.attachmentDeleteConfirmTitle')}
        body={t('editor.attachmentDeleteConfirmBody').replace('{name}', attachmentToDelete?.fileName ?? '')}
        confirmLabel={t('common.delete')}
        danger
      />
    </div>
  );
}
