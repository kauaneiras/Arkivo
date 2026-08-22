import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import api from '../../lib/api';
import CodeMirror from '@uiw/react-codemirror';
import { StreamLanguage } from '@codemirror/language';
import { stex } from '@codemirror/legacy-modes/mode/stex';
import { Document as PdfDocument, Page, pdfjs } from 'react-pdf';
import {
  ArrowLeft, Save, Play, CheckCircle, FileText, History,
  GitCommitHorizontal, Diff, Download, Lock, AlertTriangle, RotateCcw,
  Paperclip, Upload, Trash2, File as FileIcon, BadgeCheck, FolderTree,
  ChevronUp, ChevronDown,
} from 'lucide-react';
import { useI18n, formatDate } from '../../lib/i18n';
import { Modal, ConfirmModal, DocStatusBadge, useToast } from '../../components/ui';
import FileTree, { type FileNode } from '../../components/FileTree';
import { diffLines, collapseDiff } from '../../lib/diff';
import { cn } from '../../lib/utils';

pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString();

interface Commit {
  id: string;
  message: string;
  authorName: string;
  createdAt: string;
  revision: number;
  isCurrent: boolean;
  isOfficial: boolean;
  branchName: string | null;
  parentCommitId: string | null;
  sizeBytes: number;
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

export default function DocumentEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t, lang } = useI18n();
  const { toast } = useToast();

  const [doc, setDoc] = useState<any>(null);
  const [content, setContent] = useState('');
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
  const [numPages, setNumPages] = useState<number | null>(null);
  const [isCompiling, setIsCompiling] = useState(false);
  const [compileError, setCompileError] = useState<string | null>(null);
  // The errors live in a badge that opens on demand, so a long pdflatex log
  // does not push the document itself off the screen.
  const [errorsOpen, setErrorsOpen] = useState(false);
  // True when the PDF on screen is from an earlier, successful compilation.
  const [pdfIsStale, setPdfIsStale] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [commits, setCommits] = useState<Commit[]>([]);
  const [selectedCommitId, setSelectedCommitId] = useState<string | null>(null);
  // "?panel=history" lets the documents list jump straight into the history,
  // which is where people go to inspect or change the version in force.
  const [searchParams] = useSearchParams();
  // The file tree is open by default: a LaTeX project is a tree, and hiding it
  // is what made the editor feel like a textarea instead of Overleaf.
  const [sidebarView, setSidebarView] = useState<'none' | 'files' | 'history' | 'attachments'>(
    searchParams.get('panel') === 'history' ? 'history'
      : searchParams.get('panel') === 'attachments' ? 'attachments' : 'files',
  );

  // Multi-file project state
  const [projectFiles, setProjectFiles] = useState<FileNode[]>([]);
  const [openPath, setOpenPath] = useState<string | null>(null);
  const [binaryPreviewUrl, setBinaryPreviewUrl] = useState<string | null>(null);
  const [binaryPreviewType, setBinaryPreviewType] = useState<string>('');
  const binaryUrlRef = useRef<string | null>(null);
  // Whether the file currently open is editable text. A binary file (a figure)
  // is shown, never edited — and its path must never receive the editor buffer.
  const openIsTextRef = useRef(true);
  // Tracks unsaved edits so we can warn instead of discarding them.
  const [isDirty, setIsDirty] = useState(false);
  const isDirtyRef = useRef(false);
  const markDirty = useCallback((dirty: boolean) => {
    isDirtyRef.current = dirty;
    setIsDirty(dirty);
  }, []);
  const showHistory = sidebarView === 'history';
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [isUploadingAttachment, setIsUploadingAttachment] = useState(false);
  const [attachmentToDelete, setAttachmentToDelete] = useState<Attachment | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Modals
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [commitMessage, setCommitMessage] = useState('');
  // ISO 17025 8.3.2 c) — formal justification for the revision
  const [changeReason, setChangeReason] = useState('');
  // ISO 17025 8.3.2 d) — read acknowledgement of the version in force
  const [ackStatus, setAckStatus] = useState<{ required: boolean; acknowledged: boolean; acknowledgedAt: string | null } | null>(null);
  const [periodicModalOpen, setPeriodicModalOpen] = useState(false);
  const [periodicNotes, setPeriodicNotes] = useState('');
  const [isRecordingReview, setIsRecordingReview] = useState(false);
  const [submitModalOpen, setSubmitModalOpen] = useState(false);
  const [reviewTitle, setReviewTitle] = useState('');
  const [reviewDescription, setReviewDescription] = useState('');
  const [isSubmittingReview, setIsSubmittingReview] = useState(false);
  const [diffData, setDiffData] = useState<{ commit: Commit } | null>(null);
  const [diffResult, setDiffResult] = useState<ReturnType<typeof collapseDiff> | null>(null);
  const [restoreTarget, setRestoreTarget] = useState<Commit | null>(null);
  const [isRestoring, setIsRestoring] = useState(false);
  const [officialTarget, setOfficialTarget] = useState<Commit | null>(null);
  const [isSettingOfficial, setIsSettingOfficial] = useState(false);

  const blobUrlRef = useRef<string | null>(null);
  // Mirrors `content` so callbacks/shortcuts always compile the latest text
  const contentRef = useRef('');
  contentRef.current = content;
  // Source of the PDF currently on screen — lets auto-compile skip work when
  // nothing actually changed (and the engine cache make repeats free anyway).
  const lastCompiledRef = useRef<string | null>(null);

  const isLocked = doc?.status === 'IN_REVIEW' || doc?.status === 'OBSOLETE';
  const latexExtensions = useMemo(() => [StreamLanguage.define(stex)], []);

  const setPdfUrl = useCallback((url: string | null) => {
    if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
    blobUrlRef.current = url;
    setPdfBlobUrl(url);
  }, []);

  const fetchDocument = useCallback(async () => {
    try {
      const res = await api.get(`/documents/${id}`);
      setDoc(res.data.data || res.data);
    } catch (err) {
      console.error(err);
    }
  }, [id]);

  const loadPdf = useCallback(async (commitId: string, silent = true) => {
    try {
      const res = await api.get(`/latex/pdf/${commitId}`, {
        responseType: 'blob',
        // Without this axios asks for JSON, and a content-negotiating backend may
        // answer with a base64-encoded PDF — text the viewer cannot open.
        headers: { Accept: 'application/pdf' },
      });
      // Never hand an unverified blob to the PDF viewer. An empty body, a JSON
      // error, or an HTML proxy page would all produce "Failed to load PDF file"
      // with no way to recover. Returning false instead makes the caller compile
      // a fresh preview.
      if (!(await isPdfBlob(res.data))) {
        setPdfUrl(null);
        return false;
      }
      setPdfUrl(URL.createObjectURL(res.data));
      return true;
    } catch {
      // 404 = this version was never compiled. Expected, not an error.
      if (!silent) toast('info', t('editor.pdfError'));
      setPdfUrl(null);
      return false;
    }
  }, [setPdfUrl, toast, t]);

  const loadCommitContent = useCallback(async (commitId: string): Promise<string> => {
    try {
      const res = await api.get(`/documents/${id}/versioning/commits/${commitId}/content`);
      const source: string = res.data.content ?? '';
      setContent(source);
      contentRef.current = source;
      setSelectedCommitId(commitId);
      return source;
    } catch (err) {
      console.error(err);
      return '';
    }
  }, [id]);

  /* -------------------- Multi-file project (Overleaf) ------------------ */

  const setBinaryUrl = useCallback((url: string | null, type = '') => {
    if (binaryUrlRef.current) URL.revokeObjectURL(binaryUrlRef.current);
    binaryUrlRef.current = url;
    setBinaryPreviewUrl(url);
    setBinaryPreviewType(type);
  }, []);

  const fetchProjectFiles = useCallback(async (commitId: string) => {
    try {
      const res = await api.get(`/documents/${id}/files`, { params: { commitId } });
      // The endpoint returns the array itself, not an envelope.
      const list: FileNode[] = Array.isArray(res.data) ? res.data : [];
      setProjectFiles(list);
      return list;
    } catch (err) {
      console.error(err);
      setProjectFiles([]);
      return [];
    }
  }, [id]);

  /**
   * Persists the open file so a compile or a version save sees the edit.
   *
   * Refuses to write when the open file is binary. `openPath` follows the tree
   * selection while `content` still holds the last .tex, so without this guard
   * opening a figure and pressing Save wrote LaTeX source over the image and
   * destroyed it.
   */
  const persistOpenFile = useCallback(async () => {
    if (!openPath || !openIsTextRef.current) return;
    // No commitId on writes: the server picks the editable version and refuses
    // if the document is locked, so the client cannot target an approved one.
    await api.put(`/documents/${id}/files/content`, {
      path: openPath, content: contentRef.current,
    });
    markDirty(false);
  }, [id, openPath, markDirty]);

  /** Opens a file from the tree: text goes into the editor, images render inline. */
  const openFile = useCallback(async (file: FileNode) => {
    if (!selectedCommitId) return;
    if (file.path === openPath) return;

    // Save what is in the editor BEFORE loading another file. Switching used to
    // overwrite the buffer, so every edit made since the last version save was
    // discarded the moment the user clicked another file in the tree — silent
    // loss in the most ordinary action there is in a multi-file editor.
    if (isDirtyRef.current && openIsTextRef.current && !isLocked) {
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
      // Binary: don't put bytes in a text editor. Show the image/PDF instead.
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
  }, [id, selectedCommitId, openPath, isLocked, persistOpenFile, setBinaryUrl, markDirty, toast, t]);

  const refreshTree = useCallback(async () => {
    if (selectedCommitId) await fetchProjectFiles(selectedCommitId);
    await fetchDocument();
  }, [selectedCommitId, fetchProjectFiles, fetchDocument]);

  const handleUploadProjectFile = useCallback(async (file: File, folder: string) => {
    const form = new FormData();
    form.append('file', file);
    if (folder) form.append('folder', folder);
    try {
      await api.post(`/documents/${id}/files/upload`, form,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      toast('success', t('tree.uploadSuccess'));
      await refreshTree();
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('tree.uploadError'));
    }
  }, [id, refreshTree, toast, t]);

  const handleImportZip = useCallback(async (file: File) => {
    if (!selectedCommitId) return;
    const form = new FormData();
    form.append('file', file);
    setIsCompiling(true);
    try {
      const res = await api.post(`/documents/${id}/files/import-zip`, form,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      const list = await fetchProjectFiles(selectedCommitId);
      await fetchDocument();
      toast('success', t('tree.importSuccess')
        .replace('{n}', String(res.data.filesImported ?? list.length))
        .replace('{root}', res.data.rootPath ?? '—'));
      if ((res.data.rootCandidates?.length ?? 0) > 1) {
        // Ambiguous root (the reference project has main.tex AND bkpmain.tex):
        // say so instead of silently compiling a guess.
        toast('info', t('tree.multipleRoots'));
      }
      const root = list.find((f) => f.isRoot);
      if (root) await openFile(root);
    } catch (err: any) {
      toast('error', err.response?.data?.message || t('tree.importError'));
    } finally {
      setIsCompiling(false);
    }
  }, [id, selectedCommitId, fetchProjectFiles, fetchDocument, openFile, toast, t]);

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
  /* Defined before fetchCommits: the first render auto-compiles a preview. */

  /**
   * @param ctx lets a caller that has just fetched the tree pass it in
   *        explicitly. Without this the first automatic preview of a multi-file
   *        project used the `projectFiles` captured when the callback was
   *        created — still empty — so it compiled the root file ALONE, with no
   *        \input and no \includegraphics, and failed every time a document was
   *        opened. Reading it from state here would always be one render behind.
   */
  const handleCompile = useCallback(async (
    source?: string,
    ctx?: { files?: FileNode[]; commitId?: string | null },
  ) => {
    // Overleaf-style: compiles the CURRENT editor content via /latex/preview,
    // no commit required — works before the document is ever saved.
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
        // Both types on purpose: success returns a PDF, failure returns the
        // compilation log as JSON (422). Asking only for PDF would make Spring
        // answer 406 on the error path and we would lose the error message.
        headers: { Accept: 'application/pdf, application/json' },
      };
      // A project must be compiled as a tree — \input and \includegraphics only
      // resolve when the sibling files are on disk. The unsaved buffer of the
      // open file rides along so the preview reflects what is on screen.
      // The overlay is only sent for a TEXT file. Sending it while a figure was
      // open made the server write the editor's LaTeX over the image inside the
      // compile directory, so the figure disappeared from the rendered PDF.
      const overlayPath = openPath && openIsTextRef.current ? openPath : null;
      const res = isProject
        ? await api.post(
            `/latex/preview/project/${commitId}`,
            { path: overlayPath, content: overlayPath ? latexSource : null },
            options,
          )
        : await api.post('/latex/preview', { latexSource }, options);
      if (!(await isPdfBlob(res.data))) {
        // 200 but not a PDF. Keep whatever is on screen: a stale preview the
        // user can still read beats an empty panel.
        setCompileError(t('editor.compileError'));
        setPdfIsStale(true);
        return;
      }
      setPdfUrl(URL.createObjectURL(res.data));
      setPdfIsStale(false);
      lastCompiledRef.current = latexSource;

      // A LaTeX document very often renders WITH errors, so the errors are shown
      // beside the PDF rather than instead of it — the same thing Overleaf does.
      const problems = Number(res.headers['x-latex-problems'] ?? 0);
      if (problems > 0) {
        const parsed = res.headers['x-latex-error-list'];
        setCompileError(parsed ? decodeURIComponent(parsed) : t('editor.compiledWithProblems').replace('{n}', String(problems)));
      } else {
        setCompileError(null);
      }
    } catch (err: any) {
      console.error(err);
      // The response may be a Blob (responseType: 'blob') carrying JSON with
      // either a pdflatex log or a security-rule message.
      let payload: any = err.response?.data;
      if (payload instanceof Blob) {
        try { payload = JSON.parse(await payload.text()); } catch { payload = null; }
      }

      const log: string = payload?.log ?? '';
      const message: string = payload?.message ?? '';
      const parsed: string[] = Array.isArray(payload?.errors) ? payload.errors : [];

      if (parsed.length > 0) {
        // Backend already parsed the engine log into actionable lines
        setCompileError(parsed.join('\n'));
        toast('error', t('editor.compileError'));
      } else if (log) {
        // Show the most relevant lines of the pdflatex log (Overleaf-style)
        const errorLines = log.split('\n').filter((l) => l.startsWith('!') || /^l\.\d+/.test(l));
        const detail = (errorLines.length ? errorLines : log.split('\n').filter((l) => l.trim()).slice(-8)).join('\n');
        setCompileError(detail);
        toast('error', t('editor.compileError'));
      } else {
        setCompileError(message || t('editor.compileError'));
        toast('error', message || t('editor.compileError'));
      }
      // Deliberately NOT clearing the PDF. A failed compile used to blank the
      // preview, so one typo replaced the whole document with an empty panel and
      // the user lost their reference for fixing it. The last good render stays,
      // flagged as out of date.
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
        // Load the file tree before deciding what to show: a project opens on
        // its root file, a single-file document on its only content.
        const files = await fetchProjectFiles(list[0].id);
        const root = files.find((f) => f.isRoot) ?? files.find((f) => f.path.endsWith('.tex'));
        if (root) setOpenPath(root.path);

        // Show a PDF right away: use the stored one, otherwise compile a
        // preview automatically (Overleaf-style first render).
        const hasPdf = await loadPdf(list[0].id);
        if (hasPdf) {
          // The stored PDF matches this source — don't recompile it.
          lastCompiledRef.current = source;
        } else if (source || files.length > 0) {
          // Pass the tree we just fetched: state has not re-rendered yet.
          handleCompile(source, { files, commitId: list[0].id });
        }
      }
      return list;
    } catch (err) {
      console.error(err);
      return [];
    }
  }, [id, loadCommitContent, loadPdf, handleCompile, fetchProjectFiles]);

  useEffect(() => {
    fetchDocument();
    fetchCommits();
    return () => {
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
      if (binaryUrlRef.current) URL.revokeObjectURL(binaryUrlRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Live preview: recompiles a little after typing stops, like Overleaf's
  // auto-compile. Debounced so it never fires per keystroke, skipped while a
  // compilation is running or when the text has not changed since the last one.
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

  // Saving a version is a deliberate act here (it needs a message and a change
  // reason for 8.3.2 c), so people accumulate real work between saves. Closing
  // the tab must not throw that away without a word.
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
        if (!isLocked) { setCommitMessage(''); setSaveModalOpen(true); }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [handleCompile, isLocked]);

  /* ------------------------------- Save ------------------------------- */

  const handleSave = async () => {
    if (!commitMessage.trim()) return;
    // 8.3.2 c) — an assessor asks "what changed and why"; a free-form commit
    // message is not that answer, so the reason is required by the UI.
    if (!changeReason.trim()) {
      toast('error', t('editor.changeReasonRequired'));
      return;
    }
    setIsSaving(true);
    try {
      // Write the open file into the tree BEFORE creating the version. The new
      // version copies the tree from the current one, so an edit that is still
      // only in the editor buffer would not be part of the version saved.
      if (projectFiles.length > 0) await persistOpenFile();

      await api.post(`/documents/${id}/versioning/commits`, {
        latexSource: content,
        message: commitMessage.trim(),
        changeReason: changeReason.trim(),
      });
      toast('success', t('editor.saveSuccess'));
      setSaveModalOpen(false);
      setCommitMessage('');
      setChangeReason('');
      // The brand new version has no stored PDF yet, so the preview is rebuilt
      // from the source instead of showing an empty viewer.
      lastCompiledRef.current = null;
      fetchCommits();
      fetchDocument();
    } catch (err) {
      console.error(err);
      toast('error', t('editor.saveError'));
    } finally {
      setIsSaving(false);
    }
  };

  /* --------------------------- Submit review -------------------------- */

  const handleSubmitReview = async () => {
    if (!reviewTitle.trim()) return;
    setIsSubmittingReview(true);
    try {
      await api.post(`/documents/${id}/reviews`, {
        title: reviewTitle.trim(),
        description: reviewDescription.trim() || null,
      });
      toast('success', t('editor.submitSuccess'));
      setSubmitModalOpen(false);
      setReviewTitle('');
      setReviewDescription('');
      fetchDocument();
    } catch (err) {
      console.error(err);
      toast('error', t('editor.submitError'));
    } finally {
      setIsSubmittingReview(false);
    }
  };

  /* ------------------------------ Restore ----------------------------- */

  const handleRestore = async () => {
    if (!restoreTarget) return;
    setIsRestoring(true);
    try {
      // Git-style revert: creates a NEW version, history stays immutable
      await api.post(`/documents/${id}/versioning/commits/${restoreTarget.id}/restore`);
      toast('success', t('editor.restoreSuccess'));
      setRestoreTarget(null);
      await fetchCommits();
      fetchDocument();
    } catch (err) {
      console.error(err);
      toast('error', t('editor.restoreError'));
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
      setAckStatus(null);   // never block the editor over this
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
      // Elects this revision as the one in force. Nothing is deleted — any other
      // revision can be elected later.
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

  /* -------------------------------- Diff ------------------------------ */

  const handleShowDiff = async (commit: Commit) => {
    const idx = commits.findIndex((c) => c.id === commit.id);
    const parent = commits[idx + 1];
    try {
      const [currentRes, parentRes] = await Promise.all([
        api.get(`/documents/${id}/versioning/commits/${commit.id}/content`),
        parent
          ? api.get(`/documents/${id}/versioning/commits/${parent.id}/content`)
          : Promise.resolve({ data: { content: '' } }),
      ]);
      const lines = diffLines(parentRes.data.content ?? '', currentRes.data.content ?? '');
      setDiffResult(collapseDiff(lines));
      setDiffData({ commit });
    } catch (err) {
      console.error(err);
      toast('error', t('common.error'));
    }
  };

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
      const message = err.response?.data?.message;
      toast('error', message || t('editor.attachmentUploadError'));
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

  const hasChanges = diffResult?.some((l) => l.kind === 'added' || l.kind === 'removed');

  // One error per line, blank lines dropped, so the badge count matches what
  // the panel actually lists.
  const errorLines = (compileError ?? '').split('\n').map((l) => l.trim()).filter(Boolean);

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
              <Lock className="w-3 h-3" /> {t('editor.readOnly')}
            </span>
          )}
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={() => setSidebarView((v) => (v === 'files' ? 'none' : 'files'))}
            className={cn('btn-outline flex items-center gap-2 py-1.5 px-3 text-sm', sidebarView === 'files' && 'bg-surface')}
          >
            <FolderTree className="w-4 h-4" />
            <span className="hidden xl:inline">{t('tree.title')}</span>
            {projectFiles.length > 0 && (
              <span className="text-[10px] font-semibold bg-surface text-textMuted rounded-full px-1.5">
                {projectFiles.length}
              </span>
            )}
          </button>
          <button
            onClick={() => setSidebarView((v) => (v === 'history' ? 'none' : 'history'))}
            className={cn('btn-outline flex items-center gap-2 py-1.5 px-3 text-sm', showHistory && 'bg-surface')}
          >
            <History className="w-4 h-4" />
            <span className="hidden xl:inline">{t('editor.history')}</span>
          </button>
          <button
            onClick={() => setSidebarView((v) => (v === 'attachments' ? 'none' : 'attachments'))}
            className={cn('btn-outline flex items-center gap-2 py-1.5 px-3 text-sm', sidebarView === 'attachments' && 'bg-surface')}
          >
            <Paperclip className="w-4 h-4" />
            <span className="hidden xl:inline">{t('editor.attachments')}</span>
          </button>
          <button
            onClick={() => { setCommitMessage(''); setSaveModalOpen(true); }}
            disabled={isSaving || isLocked}
            className="btn-outline flex items-center gap-2 py-1.5 px-3 text-sm"
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
            disabled={isLocked}
            className="btn-primary flex items-center gap-2 py-1.5 px-3 text-sm"
          >
            <CheckCircle className="w-4 h-4" />
            <span className="hidden xl:inline">{t('editor.submitReview')}</span>
          </button>
        </div>
      </div>

      {/* ISO compliance banners: overdue periodic review (8.3.2 b) and pending
          read acknowledgement (8.3.2 d). Shown above the editor because both are
          things the user must act on, not passive status. */}
      {doc?.reviewOverdue && (
        <div className="bg-red-50 border-b border-red-200 px-4 py-2.5 flex items-center justify-between gap-3 shrink-0">
          <span className="flex items-center gap-2 text-sm text-red-800">
            <AlertTriangle className="w-4 h-4 shrink-0" />
            <strong>{t('review.overdue')}</strong>
            {doc.nextReviewDate && (
              <span className="text-red-700">
                — {t('review.nextReview')}: {formatDate(doc.nextReviewDate, lang)}
              </span>
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
                           ackStatus.acknowledged
                             ? 'bg-green-50 border-green-200'
                             : 'bg-amber-50 border-amber-200')}>
          <span className={cn('flex items-center gap-2 text-sm',
                              ackStatus.acknowledged ? 'text-green-800' : 'text-amber-800')}>
            {ackStatus.acknowledged
              ? <BadgeCheck className="w-4 h-4 shrink-0" />
              : <AlertTriangle className="w-4 h-4 shrink-0" />}
            {ackStatus.acknowledged
              ? t('ack.confirmed').replace('{date}',
                  ackStatus.acknowledgedAt ? formatDate(ackStatus.acknowledgedAt, lang) : '')
              : t('ack.banner')}
          </span>
          {!ackStatus.acknowledged && (
            <button onClick={handleAcknowledge}
                    className="btn-primary py-1 px-3 text-sm whitespace-nowrap">
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
              // Also read-only while an OLD version is selected: the server
              // applies writes to the editable version, not the one on screen,
              // so a delete here would silently change a different revision.
              readOnly={isLocked || (!!commits[0] && selectedCommitId !== commits[0].id)}
              onSelect={openFile}
              onUpload={handleUploadProjectFile}
              onImportZip={handleImportZip}
              onExportZip={handleExportZip}
              onCreateFile={async (path) => {
                if (!selectedCommitId) return;
                try {
                  await api.put(`/documents/${id}/files/content`, { path, content: '' });
                  const list = await fetchProjectFiles(selectedCommitId);
                  const created = list.find((f) => f.path === path);
                  if (created) await openFile(created);
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.createError'));
                }
              }}
              onRename={async (from, to) => {
                if (!selectedCommitId) return;
                try {
                  await api.post(`/documents/${id}/files/rename`, { from, to });
                  if (openPath === from) setOpenPath(to);
                  await refreshTree();
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.renameError'));
                }
              }}
              onDelete={async (path) => {
                if (!selectedCommitId) return;
                if (!window.confirm(t('tree.deleteConfirm').replace('{name}', path))) return;
                try {
                  await api.delete(`/documents/${id}/files`, { params: { path } });
                  if (openPath === path) { setOpenPath(null); setContent(''); contentRef.current = ''; }
                  await refreshTree();
                } catch (err: any) {
                  toast('error', err.response?.data?.message || t('tree.deleteError'));
                }
              }}
              onSetRoot={async (path) => {
                if (!selectedCommitId) return;
                try {
                  // path is a query parameter here, not a body — sending it as
                  // JSON would make Spring reject the request with 400.
                  await api.post(`/documents/${id}/files/root`, null, { params: { path } });
                  await refreshTree();
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

        {/* Version History Sidebar */}
        {showHistory && (
          <div className="w-72 border-r border-borderLight bg-surface overflow-y-auto flex flex-col shrink-0">
            <div className="p-3 border-b border-borderLight font-semibold text-sm flex justify-between items-center sticky top-0 bg-surface z-10">
              <span className="flex items-center gap-2">
                <GitCommitHorizontal className="w-4 h-4 text-primary" />
                {t('editor.commitHistory')}
              </span>
              <button onClick={() => setSidebarView('none')} className="text-textMuted hover:text-textMain text-xs">
                {t('common.close')}
              </button>
            </div>
            <div className="flex-1 divide-y divide-borderLight">
              {commits.map((commit, idx) => (
                <div
                  key={commit.id}
                  className={cn(
                    'p-3 cursor-pointer group transition-colors',
                    selectedCommitId === commit.id ? 'bg-blue-50 border-l-2 border-primary' : 'hover:bg-gray-50'
                  )}
                  onClick={async () => {
                    const source = await loadCommitContent(commit.id);
                    // Load THAT version's tree too. Without this the panel kept
                    // showing the latest version's files while the selected
                    // version was an older one, so clicking a file that did not
                    // exist back then failed, and every size and badge shown
                    // described the wrong revision.
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
                  }}
                >
                  <div className="flex items-center gap-2">
                    {/* Revision number, GitHub-style version marker */}
                    <span className="text-[10px] font-bold text-primary bg-blue-100 rounded px-1.5 py-0.5 shrink-0">
                      v{commit.revision}
                    </span>
                    <div className="text-sm font-medium text-textMain truncate flex-1" title={commit.message}>
                      {commit.message}
                    </div>
                    {doc?.officialCommitId === commit.id && (
                      <span
                        className="flex items-center gap-1 text-[10px] font-semibold text-green-700 bg-green-50 border border-green-200 rounded-full px-2 py-0.5 uppercase shrink-0"
                        title={t('editor.official')}
                      >
                        <BadgeCheck className="w-3 h-3" /> {t('editor.official')}
                      </span>
                    )}
                    {doc?.officialCommitId !== commit.id && (commit.isCurrent || idx === 0) && (
                      <span className="text-[10px] font-semibold text-textMuted bg-surface border border-borderLight rounded-full px-2 py-0.5 uppercase shrink-0">
                        {t('editor.current')}
                      </span>
                    )}
                  </div>
                  {commit.changeReason && (
                    <p className="text-xs text-textMuted mt-1 italic line-clamp-2"
                       title={commit.changeReason}>
                      {t('editor.changeReason')}: {commit.changeReason}
                    </p>
                  )}
                  <div className="text-xs text-textMuted mt-1 flex items-center gap-1.5">
                    <span className="truncate">{commit.authorName}</span>
                    {commit.sizeBytes > 0 && (
                      <span className="text-textMuted/70">· {(commit.sizeBytes / 1024).toFixed(1)} KB</span>
                    )}
                  </div>
                  <div className="text-xs text-textMuted">{formatDate(commit.createdAt, lang)}</div>
                  <div className="flex items-center gap-3 mt-1.5 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                    <button
                      onClick={(e) => { e.stopPropagation(); handleShowDiff(commit); }}
                      className="flex items-center gap-1 text-[11px] text-primary hover:underline"
                      title={t('editor.diff')}
                    >
                      <Diff className="w-3 h-3" /> {t('editor.diff')}
                    </button>
                    {doc?.officialCommitId !== commit.id && (
                      <button
                        onClick={(e) => { e.stopPropagation(); setOfficialTarget(commit); }}
                        className="flex items-center gap-1 text-[11px] text-green-700 hover:underline"
                        title={t('editor.makeOfficial')}
                      >
                        <BadgeCheck className="w-3 h-3" /> {t('editor.makeOfficial')}
                      </button>
                    )}
                    {!isLocked && !commit.isCurrent && (
                      <button
                        onClick={(e) => { e.stopPropagation(); setRestoreTarget(commit); }}
                        className="flex items-center gap-1 text-[11px] text-amber-700 hover:underline"
                        title={t('editor.restore')}
                      >
                        <RotateCcw className="w-3 h-3" /> {t('editor.restore')}
                      </button>
                    )}
                  </div>
                </div>
              ))}
              {commits.length === 0 && (
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

            {!isLocked && (
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
                      <div className="text-xs text-textMuted mt-0.5">
                        {formatFileSize(a.sizeBytes)} · {a.uploadedByName}
                      </div>
                      <div className="text-xs text-textMuted">{formatDate(a.uploadedAt, lang)}</div>
                    </div>
                  </div>
                  <div className="flex items-center gap-3 mt-1.5 pl-6 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                    <button
                      onClick={() => handleDownloadAttachment(a)}
                      className="flex items-center gap-1 text-[11px] text-primary hover:underline"
                    >
                      <Download className="w-3 h-3" /> {t('editor.attachmentDownload')}
                    </button>
                    {!isLocked && (
                      <button
                        onClick={() => setAttachmentToDelete(a)}
                        className="flex items-center gap-1 text-[11px] text-red-600 hover:underline"
                      >
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
            {/* The open file's path replaces the generic "source" label: in a
                42-file project, knowing which file you are editing matters more. */}
            <span className="truncate normal-case font-mono text-[11px]" title={openPath ?? undefined}>
              {openPath ?? t('editor.source')}
            </span>
            {selectedCommitId && commits[0] && selectedCommitId !== commits[0].id && (
              <span className="text-amber-600 normal-case font-normal shrink-0 ml-2">{t('editor.viewingOldVersion')}</span>
            )}
          </div>
          <div className="flex-1 overflow-auto">
            {binaryPreviewUrl ? (
              // A binary file opened from the tree: show it instead of dumping
              // bytes into a text editor. Branching on the type matters because
              // converted EPS figures are stored as PDF — the headline artefact
              // of this feature — and <img> renders those as a broken icon.
              <div className="h-full flex flex-col items-center justify-center gap-3 p-4 bg-surface">
                {binaryPreviewType.startsWith('image/') ? (
                  <img
                    src={binaryPreviewUrl}
                    alt={openPath ?? ''}
                    className="max-w-full max-h-[80%] object-contain border border-borderLight rounded bg-white"
                  />
                ) : binaryPreviewType === 'application/pdf' ? (
                  <iframe
                    src={binaryPreviewUrl}
                    title={openPath ?? 'PDF'}
                    className="w-full h-[85%] border border-borderLight rounded bg-white"
                  />
                ) : (
                  <div className="flex flex-col items-center gap-2 text-textMuted">
                    <FileIcon className="w-10 h-10" />
                    <span className="text-sm font-mono">{openPath}</span>
                  </div>
                )}
                <a
                  href={binaryPreviewUrl}
                  download={openPath?.split('/').pop()}
                  className="text-xs text-primary hover:underline flex items-center gap-1"
                >
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
                readOnly={isLocked}
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

              {/* The "corner that tells you there are errors". Compact by
                  default; the details open only when asked for. */}
              {compileError && !isCompiling && (
                <button
                  onClick={() => setErrorsOpen((v) => !v)}
                  className={cn(
                    'flex items-center gap-1.5 normal-case rounded px-2 py-0.5 border transition-colors',
                    'bg-red-50 border-red-200 text-red-700 hover:bg-red-100',
                  )}
                  aria-expanded={errorsOpen}
                >
                  <AlertTriangle className="w-3.5 h-3.5" />
                  {t('editor.problemsBadge').replace('{n}', String(errorLines.length || 1))}
                  {errorsOpen ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                </button>
              )}

              {pdfBlobUrl && (
                <button
                  onClick={handleDownloadPdf}
                  className="flex items-center gap-1 text-primary hover:underline normal-case"
                >
                  <Download className="w-3.5 h-3.5" /> {t('editor.downloadPdf')}
                </button>
              )}
            </div>
          </div>

          {/* Details, opened from the badge. Each line that names a file and a
              line number is clickable: in a 42-file project the error is often
              in a file that is not even open, and hunting for it by hand is the
              single most tedious part of fixing a build. */}
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
                              const target = projectFiles.find(
                                (f) => f.path === at[1] || f.path.endsWith(`/${at[1]}`),
                              );
                              if (target) { setSidebarView('files'); openFile(target); }
                            }}
                            className="text-primary hover:underline mr-1"
                            title={t('editor.openFileAtLine')}
                          >
                            {at[1]}:{at[2]}
                          </button>
                        )}
                        <span className="whitespace-pre-wrap break-words">
                          {at ? line.slice(at[0].length).replace(/^:\s*/, '') : line}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <pre className="text-[11px] leading-4 font-mono text-red-700 whitespace-pre-wrap break-words">
                  {compileError}
                </pre>
              )}
            </div>
          )}

          {pdfIsStale && pdfBlobUrl && (
            <div className="border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-[11px] text-amber-800 shrink-0">
              {t('editor.previewStale')}
            </div>
          )}

          <div className="flex-1 overflow-auto p-6 flex flex-col items-center gap-4">
            {pdfBlobUrl ? (
              <PdfDocument
                file={pdfBlobUrl}
                onLoadSuccess={({ numPages }) => setNumPages(numPages)}
                loading={<div className="p-12 text-textMuted">{t('common.loading')}</div>}
              >
                {Array.from({ length: numPages ?? 0 }).map((_, i) => (
                  <div key={i} className="bg-white shadow-lg border border-gray-200 mb-4">
                    <Page pageNumber={i + 1} renderTextLayer={false} renderAnnotationLayer={false} width={560} />
                  </div>
                ))}
              </PdfDocument>
            ) : (
              <div className="flex flex-col items-center justify-center text-textMuted h-full opacity-50">
                <FileText className="w-16 h-16 mb-4" />
                <p>{t('editor.compileHint')}</p>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Save (commit message) modal */}
      <Modal
        isOpen={saveModalOpen}
        onClose={() => setSaveModalOpen(false)}
        title={t('editor.commitModalTitle')}
        footer={
          <>
            <button onClick={() => setSaveModalOpen(false)} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button onClick={handleSave} disabled={isSaving || !commitMessage.trim()} className="btn-primary py-2 px-4 text-sm">
              {isSaving ? t('editor.saving') : t('common.save')}
            </button>
          </>
        }
      >
        <div className="space-y-1.5">
          <label className="text-sm font-medium text-textMain">{t('editor.commitMessage')}</label>
          <input
            type="text"
            value={commitMessage}
            onChange={(e) => setCommitMessage(e.target.value)}
            className="input-field"
            placeholder={t('editor.commitMessagePlaceholder')}
            autoFocus
          />
        </div>
        <div className="space-y-1.5 mt-4">
          <label className="text-sm font-medium text-textMain">{t('editor.changeReason')}</label>
          <textarea
            value={changeReason}
            onChange={(e) => setChangeReason(e.target.value)}
            rows={2}
            className="input-field resize-none"
            placeholder={t('editor.changeReasonPlaceholder')}
          />
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
            <button onClick={() => setPeriodicModalOpen(false)}
                    className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
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
            <textarea value={periodicNotes} onChange={(e) => setPeriodicNotes(e.target.value)}
                      rows={2} className="input-field resize-none" />
          </div>
        </div>
      </Modal>

      {/* Submit for review modal */}
      <Modal
        isOpen={submitModalOpen}
        onClose={() => setSubmitModalOpen(false)}
        title={t('editor.submitModalTitle')}
        footer={
          <>
            <button onClick={() => setSubmitModalOpen(false)} className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors">
              {t('common.cancel')}
            </button>
            <button onClick={handleSubmitReview} disabled={isSubmittingReview || !reviewTitle.trim()} className="btn-primary py-2 px-4 text-sm">
              {t('editor.submitReview')}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <p className="text-sm text-textMuted">{t('editor.submitModalBody')}</p>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('editor.reviewTitle')}</label>
            <input
              type="text"
              value={reviewTitle}
              onChange={(e) => setReviewTitle(e.target.value)}
              className="input-field"
              placeholder={t('editor.reviewTitlePlaceholder')}
              autoFocus
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('editor.reviewDescription')}</label>
            <textarea
              value={reviewDescription}
              onChange={(e) => setReviewDescription(e.target.value)}
              rows={3}
              className="input-field resize-none"
              placeholder={t('editor.reviewDescriptionPlaceholder')}
            />
          </div>
        </div>
      </Modal>

      {/* Restore version confirmation */}
      <ConfirmModal
        isOpen={!!restoreTarget}
        onClose={() => setRestoreTarget(null)}
        onConfirm={handleRestore}
        title={`${t('editor.restoreConfirmTitle')} v${restoreTarget?.revision ?? ''}`}
        body={t('editor.restoreConfirmBody')}
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

      {/* Diff modal */}
      <Modal
        isOpen={!!diffData}
        onClose={() => { setDiffData(null); setDiffResult(null); }}
        title={`${t('editor.diffTitle')} — ${diffData?.commit.message ?? ''}`}
        wide
      >
        {diffResult && hasChanges ? (
          <pre className="text-xs font-mono leading-5 overflow-x-auto rounded-lg border border-borderLight">
            {diffResult.map((line, i) =>
              line.kind === 'skip' ? (
                <div key={i} className="px-3 py-1 bg-gray-50 text-textMuted italic border-y border-borderLight">
                  ··· {line.count} ···
                </div>
              ) : (
                <div
                  key={i}
                  className={cn(
                    'px-3 whitespace-pre-wrap break-all',
                    line.kind === 'added' && 'bg-green-50 text-green-800',
                    line.kind === 'removed' && 'bg-red-50 text-red-800 line-through decoration-red-300',
                  )}
                >
                  <span className="select-none inline-block w-4 text-textMuted">
                    {line.kind === 'added' ? '+' : line.kind === 'removed' ? '-' : ' '}
                  </span>
                  {line.text || ' '}
                </div>
              )
            )}
          </pre>
        ) : (
          <p className="text-sm text-textMuted text-center py-8">{t('editor.noDiff')}</p>
        )}
      </Modal>
    </div>
  );
}
