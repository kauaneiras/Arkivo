import { useState, useEffect } from 'react';
import { FileText, Upload, FileCode, FileType, FileArchive } from 'lucide-react';
import api from '../../lib/api';
import { useNavigate } from 'react-router-dom';
import { useI18n } from '../../lib/i18n';
import { Modal } from '../../components/ui';
import { cn } from '../../lib/utils';

interface CreateDocumentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

interface DocType {
  id: string;
  prefix: string;
  name: string;
  active: boolean;
}

/** Matches ProjectImportService.MAX_FILE_BYTES on the server. */
const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

const BLANK_TEMPLATE = `\\documentclass{article}
\\usepackage[utf8]{inputenc}
\\usepackage[brazil]{babel}

\\title{Título do Documento}
\\author{Laboratório}
\\date{\\today}

\\begin{document}
\\maketitle

\\section{Objetivo}
% Descreva o objetivo deste documento...

\\section{Escopo}
% Defina o escopo de aplicação...

\\section{Procedimento}
% Detalhe o procedimento...

\\end{document}
`;

export default function CreateDocumentModal({ isOpen, onClose, onSuccess }: CreateDocumentModalProps) {
  const [code, setCode] = useState('');
  const [codeAuto, setCodeAuto] = useState(true);
  const [title, setTitle] = useState('');
  const [docType, setDocType] = useState('IT');
  const [groupId, setGroupId] = useState('');
  const [groups, setGroups] = useState<any[]>([]);
  const [reviewerId, setReviewerId] = useState('');
  const [reviewers, setReviewers] = useState<any[]>([]);
  // ISO/IEC 17025: 8.3.2 b) periodic review, 8.4 retention, 8.3.2 d) distribution
  const [reviewIntervalMonths, setReviewIntervalMonths] = useState(24);
  // Nem todo documento tem prazo. Um registro histórico ou um anexo de
  // referência não "vence" — forçar uma data faria o sistema cobrar uma revisão
  // que ninguém deve fazer, e alertas que se ignoram treinam a equipe a ignorar
  // todos os alertas.
  const [noReviewDeadline, setNoReviewDeadline] = useState(false);
  const [retentionYears, setRetentionYears] = useState(5);
  // Guardar para sempre por omissão. Um prazo curto demais só dá sinal quando o
  // documento já foi descartado, e nessa altura já não há nada a decidir.
  const [retainForever, setRetainForever] = useState(true);
  const [requiresAcknowledgement, setRequiresAcknowledgement] = useState(false);
  // Defaults to internal: the safe middle. Public would publish by accident and
  // restricted would lock colleagues out of routine documents.
  const [visibility, setVisibility] = useState<'PUBLIC' | 'PRIVATE' | 'RESTRICTED'>('PRIVATE');
  const [creationMode, setCreationMode] = useState<'blank' | 'upload'>('blank');
  const [fileContent, setFileContent] = useState<string>('');
  const [fileName, setFileName] = useState<string>('');
  // The uploaded file itself. A .zip or .pdf is binary, so unlike a .tex it
  // cannot be read into a string and posted as the document's source — it is
  // sent to the server after the document exists.
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadKind, setUploadKind] = useState<'tex' | 'zip' | 'pdf' | null>(null);
  const [docTypes, setDocTypes] = useState<DocType[]>([]);
  // Admin sees shortcuts to the screens that manage types and sectors; the
  // management itself lives in Settings, not in this form.
  const [isAdmin, setIsAdmin] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const navigate = useNavigate();
  const { t } = useI18n();

  useEffect(() => {
    if (isOpen) {
      fetchGroups();
      fetchReviewers();
      fetchDocTypes();
      const me = JSON.parse(localStorage.getItem('user_info') || '{}');
      const roles: string[] = me?.roles ?? (me?.role ? [me.role] : []);
      setIsAdmin(roles.some((r) => String(r).toUpperCase().includes('ADMIN')));
      setReviewerId('');
      setError('');
      setUploadFile(null);
      setUploadKind(null);
      setFileName('');
      setFileContent('');
    }
  }, [isOpen]);

  /**
   * Types come from the server now: they are maintained by an administrator
   * instead of being compiled into the app, so a lab can add the categories it
   * actually uses. Only active ones are returned, which keeps a retired type
   * out of new documents while old ones still resolve.
   */
  const fetchDocTypes = async () => {
    try {
      const res = await api.get('/document-types');
      const list: DocType[] = res.data ?? [];
      setDocTypes(list);
      if (list.length > 0 && !list.some((x) => x.prefix === docType)) {
        setDocType(list[0].prefix);
      }
    } catch (err) {
      console.error(err);
    }
  };

  // Preview of the code that will be assigned for the chosen category
  // (IT-001, IT-002, PQ-001...). This is only a preview: unless the user
  // explicitly opts into a manual code, the payload sends `code: null` and the
  // BACKEND assigns the number inside the transaction. That avoids two people
  // opening the dialog at the same time and both being handed "IT-004".
  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    api.get(`/documents/next-code?docType=${docType}`)
      .then((res) => { if (!cancelled) setCode(res.data.code); })
      .catch(() => { if (!cancelled) setCode(''); });
    return () => { cancelled = true; };
  }, [isOpen, docType]);

  // Reset to automatic whenever the dialog reopens
  useEffect(() => {
    if (isOpen) setCodeAuto(true);
  }, [isOpen]);

  const fetchGroups = async () => {
    try {
      const res = await api.get('/documents/groups');
      setGroups(res.data);
    } catch (err) {
      console.error(err);
    }
  };

  // Only users who actually hold the reviewer permission are offered, and the
  // current user is filtered out — nobody reviews their own document.
  const fetchReviewers = async () => {
    try {
      const res = await api.get('/users/reviewers');
      const me = JSON.parse(localStorage.getItem('user_info') || '{}');
      setReviewers((res.data as any[]).filter((r) => r.id !== me?.id));
    } catch (err) {
      console.error(err);
    }
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const lower = file.name.toLowerCase();
    const kind = lower.endsWith('.zip') ? 'zip'
      : lower.endsWith('.pdf') ? 'pdf'
      : lower.endsWith('.tex') ? 'tex'
      : null;

    if (!kind) {
      setError(t('create.unsupportedFile'));
      return;
    }
    // Checked here as well as on the server so the user is told immediately,
    // instead of after waiting for a large upload to be rejected.
    if (file.size > MAX_UPLOAD_BYTES) {
      setError(t('create.fileTooBig')
        .replace('{size}', `${(file.size / 1024 / 1024).toFixed(1)} MB`)
        .replace('{max}', `${MAX_UPLOAD_BYTES / 1024 / 1024} MB`));
      return;
    }

    setError('');
    setFileName(file.name);
    setUploadKind(kind);
    setUploadFile(file);

    if (kind === 'tex') {
      // A single .tex is the document's source and travels with the creation
      // request. A .zip or .pdf is binary and is uploaded afterwards.
      const reader = new FileReader();
      reader.onload = (event) => setFileContent(event.target?.result as string);
      reader.readAsText(file);
    } else {
      setFileContent('');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    // Um documento restrito sem setor não restringe nada: ninguém ficaria com
    // acesso e ele nasceria invisível para toda a gente menos para o autor.
    // Ou se escolhe quem vê, ou a visibilidade é outra.
    if (visibility === 'RESTRICTED' && !groupId) {
      setError(t('create.groupRequired'));
      return;
    }

    setLoading(true);

    // A .zip or .pdf has no LaTeX source to send up front — the document is
    // created with a placeholder and the real content is imported straight
    // after, which is also what gives us the file tree.
    const initialLatexSource =
      creationMode === 'upload' && uploadKind === 'tex' ? fileContent : BLANK_TEMPLATE;

    try {
      const payload = {
        // Automatic mode sends null so the backend assigns the number itself
        // (authoritative, race-free). Only a manual override sends a code.
        code: codeAuto ? null : (code.trim().toUpperCase() || null),
        title,
        docType,
        documentGroupId: groupId || null, // group/sector is optional
        assignedReviewerId: reviewerId || null, // optional: can be decided later
        reviewIntervalMonths: noReviewDeadline ? 0 : (Number(reviewIntervalMonths) || null),
        // 0 = para sempre, a mesma convenção que o prazo de revisão já usa.
        retentionYears: retainForever ? 0 : (Number(retentionYears) || null),
        requiresAcknowledgement,
        visibility,
        initialLatexSource,
        tags: [],
      };

      const res = await api.post('/documents', payload);
      const documentId = res.data.id;

      if (creationMode === 'upload' && uploadFile && uploadKind !== 'tex') {
        const form = new FormData();
        form.append('file', uploadFile);
        const endpoint = uploadKind === 'zip' ? 'import-zip' : 'import-pdf';
        try {
          await api.post(`/documents/${documentId}/files/${endpoint}`, form,
            { headers: { 'Content-Type': 'multipart/form-data' } });
        } catch (importErr: any) {
          // The document exists but is empty. Say so and still open it, rather
          // than leaving the user staring at an error with a document they do
          // not know was created.
          onSuccess();
          navigate(`/dashboard/documents/${documentId}/edit`);
          setError(importErr.response?.data?.message || t('create.importError'));
          return;
        }
      }

      onSuccess();
      navigate(`/dashboard/documents/${documentId}/edit`);
    } catch (err: any) {
      // Surface the real reason: backend message, validation map, or HTTP status
      const data = err.response?.data;
      const detail = data?.message
        || (data && typeof data === 'object' ? Object.values(data).filter((v) => typeof v === 'string').join('; ') : '');
      setError(detail || `${t('create.error')} (HTTP ${err.response?.status ?? '?'})`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={
        <span className="flex items-center gap-2">
          <FileText className="w-5 h-5 text-primary" />
          {t('create.title')}
        </span>
      }
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-textMain hover:bg-gray-100 rounded-md transition-colors"
          >
            {t('common.cancel')}
          </button>
          <button form="create-doc-form" type="submit" disabled={loading} className="btn-primary py-2 px-6">
            {loading ? t('create.submitting') : t('create.submit')}
          </button>
        </>
      }
    >
      {error && (
        <div className="bg-red-50 text-red-600 p-3 rounded-md text-sm mb-4 border border-red-100">
          {error}
        </div>
      )}

      <form id="create-doc-form" onSubmit={handleSubmit} className="space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('create.code')}</label>
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              readOnly={codeAuto}
              className={cn('input-field uppercase', codeAuto && 'bg-gray-50 text-textMuted cursor-not-allowed')}
              placeholder="IT-001"
              title={t('create.codeHint')}
            />
            {/* No modo automático não há explicação por baixo: o campo já está
                preenchido e desativado, o que mostra sozinho o que está a
                acontecer. A frase que estava aqui repetia o óbvio em cada
                criação. No modo manual a dica fica, porque aí há um formato a
                respeitar e o utilizador pode errá-lo. */}
            <div className="flex items-center justify-between gap-2">
              <p className="text-xs text-textMuted">
                {codeAuto ? '' : t('create.codeHint')}
              </p>
              <button
                type="button"
                onClick={() => setCodeAuto((v) => !v)}
                className="text-xs text-primary hover:underline whitespace-nowrap"
              >
                {codeAuto ? t('create.codeManual') : t('create.codeBackToAuto')}
              </button>
            </div>
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('create.docType')}</label>
            <select
              value={docType}
              onChange={(e) => setDocType(e.target.value)}
              className="input-field"
              required
            >
              {docTypes.length === 0 && <option value={docType}>{docType}</option>}
              {docTypes.map((dt) => (
                <option key={dt.id} value={dt.prefix}>{dt.prefix} — {dt.name}</option>
              ))}
            </select>
            {isAdmin && (
              <p className="text-xs text-textMuted">
                {t('create.manageTypesHint')}{' '}
                <button
                  type="button"
                  onClick={() => navigate('/dashboard/settings?tab=types')}
                  className="text-primary hover:underline"
                >
                  {t('create.manageTypes')}
                </button>
              </p>
            )}
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-textMain">{t('create.docTitle')}</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="input-field"
            placeholder={t('create.titlePlaceholder')}
            required
          />
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-textMain">{t('create.reviewer')}</label>
          <select
            value={reviewerId}
            onChange={(e) => setReviewerId(e.target.value)}
            className="input-field"
          >
            <option value="">{t('create.reviewerNone')}</option>
            {reviewers.map((r) => (
              <option key={r.id} value={r.id}>{r.fullName} ({r.email})</option>
            ))}
          </select>
          <p className="text-xs text-textMuted">{t('create.reviewerHint')}</p>
        </div>

        {/* ISO/IEC 17025 controls: periodic review (8.3.2 b), retention (8.4),
            controlled distribution (8.3.2 d). Defaults are the usual laboratory
            practice, so the common case is just "next". */}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('create.reviewInterval')}</label>
            <div className="flex items-center gap-2">
              <input
                type="number" min={0} max={120} value={reviewIntervalMonths}
                onChange={(e) => setReviewIntervalMonths(Number(e.target.value))}
                disabled={noReviewDeadline}
                className={cn('input-field', noReviewDeadline && 'opacity-40 cursor-not-allowed')}
              />
              <span className="text-sm text-textMuted whitespace-nowrap">{t('create.months')}</span>
            </div>
            {/* Desligar o prazo é uma escolha legítima para registros que não
                vencem. Fica junto do campo, e não escondido, para que quem
                desliga saiba exatamente o que desligou. */}
            <label className="flex items-center gap-2 cursor-pointer pt-0.5">
              <input
                type="checkbox"
                checked={noReviewDeadline}
                onChange={(e) => setNoReviewDeadline(e.target.checked)}
                className="w-4 h-4 rounded border-borderLight text-primary focus:ring-primary"
              />
              <span className="text-xs text-textMain">{t('create.noDeadline')}</span>
            </label>
            <p className="text-xs text-textMuted">
              {noReviewDeadline ? t('create.noDeadlineHint') : t('create.reviewIntervalHint')}
            </p>
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-textMain">{t('create.retention')}</label>
            <div className="flex items-center gap-2">
              <input
                type="number" min={1} max={50} value={retentionYears}
                onChange={(e) => setRetentionYears(Number(e.target.value))}
                disabled={retainForever}
                className={cn('input-field', retainForever && 'opacity-40 cursor-not-allowed')}
              />
              <span className="text-sm text-textMuted whitespace-nowrap">{t('create.years')}</span>
            </div>
            {/* Guardar para sempre é o padrão porque é a escolha que não perde
                nada: um prazo curto demais só se descobre quando o documento já
                foi descartado, e aí não há como voltar atrás. Encurtar é uma
                decisão deliberada de quem conhece a exigência legal do ensaio. */}
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={retainForever}
                onChange={(e) => setRetainForever(e.target.checked)}
                className="w-4 h-4 rounded border-borderLight text-primary focus:ring-primary"
              />
              <span className="text-xs text-textMain">{t('create.retainForever')}</span>
            </label>
            <p className="text-xs text-textMuted">
              {retainForever ? t('create.retainForeverHint') : t('create.retentionHint')}
            </p>
          </div>
        </div>

        {/* Who can read it. Placed with the other document properties rather
            than hidden behind an "advanced" toggle: choosing it later means
            somebody has to remember to, and the default would quietly stand. */}
        <div className="space-y-1.5 pt-1">
          <label className="text-sm font-medium text-textMain">{t('privacy.label')}</label>
          <div className="grid grid-cols-3 gap-2">
            {(['PRIVATE', 'RESTRICTED', 'PUBLIC'] as const).map((level) => (
              <button
                key={level}
                type="button"
                onClick={() => setVisibility(level)}
                className={cn(
                  'p-2.5 rounded-lg border-2 text-left transition-all',
                  visibility === level
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-borderLight text-textMuted hover:border-primary/40',
                )}
              >
                <span className="text-sm font-medium block">{t(`privacy.${level}`)}</span>
              </button>
            ))}
          </div>
          <p className="text-xs text-textMuted">{t(`privacy.${visibility}.hint`)}</p>
          {visibility === 'PUBLIC' && (
            // Publishing outside the login is the one choice here with
            // consequences beyond the laboratory, so it is stated plainly.
            <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1.5">
              {t('privacy.publicWarning')}
            </p>
          )}
          {/* O setor fica ENCOSTADO à escolha que o faz aparecer.
              Estava mais de cem linhas acima: carregava-se em "Restrito" no fim
              do formulário e o campo surgia lá em cima, muitas vezes fora do
              ecrã. Um campo obrigatório que aparece onde não se está a olhar é
              um campo que se descobre no momento em que a submissão é recusada.
              Ele só é perguntado aqui porque só aqui decide alguma coisa: num
              documento interno ou público, escolher um setor não muda quem vê. */}
          {visibility === 'RESTRICTED' && (
            <div className="space-y-1.5 pt-2 pl-3 border-l-2 border-primary/30">
              <label className="text-sm font-medium text-textMain">
                {t('create.group')} <span className="text-red-600">*</span>
              </label>
              {/* A bolha de validação do navegador é boa — aponta para o campo
                  certo e trava o envio — mas o texto dela vem do idioma do
                  NAVEGADOR, não do da aplicação: com o sistema em português dizia
                  "Please select an item in the list." E dizia pouco. Substituir a
                  mensagem mantém o comportamento nativo e põe lá a explicação, na
                  língua certa. A limpeza no onChange é obrigatória: uma validade
                  personalizada não vazia mantém o campo inválido para sempre,
                  mesmo depois de escolhido. */}
              <select
                value={groupId}
                onChange={(e) => {
                  e.currentTarget.setCustomValidity('');
                  setGroupId(e.target.value);
                }}
                onInvalid={(e) => e.currentTarget.setCustomValidity(t('create.groupRequired'))}
                required
                className="input-field"
              >
                {/* Sem opção "nenhum": o campo só existe quando a escolha é
                    obrigatória, e oferecer o vazio seria oferecer um estado que a
                    submissão vai recusar de seguida. */}
                <option value="">{t('create.groupChoose')}</option>
                {groups.map((g) => (
                  <option key={g.id} value={g.id}>{g.name}</option>
                ))}
              </select>
              {groups.length === 0 && (
                <p className="text-xs text-amber-700">{t('create.groupNoneExist')}</p>
              )}
              <p className="text-xs text-textMuted">
                {t('create.groupRestrictedHint')}{' '}
                {isAdmin && (
                  <button type="button"
                    onClick={() => navigate('/dashboard/settings?tab=groups')}
                    className="text-primary hover:underline">
                    {t('create.manageGroups')}
                  </button>
                )}
              </p>
              <p className="text-xs text-textMuted">{t('privacy.restrictedAfterCreate')}</p>
            </div>
          )}
        </div>

        <label className="flex items-start gap-2.5 cursor-pointer">
          <input
            type="checkbox"
            checked={requiresAcknowledgement}
            onChange={(e) => setRequiresAcknowledgement(e.target.checked)}
            className="mt-0.5 w-4 h-4 rounded border-borderLight text-primary focus:ring-primary"
          />
          <span>
            <span className="text-sm font-medium text-textMain">{t('create.requiresAck')}</span>
            <span className="block text-xs text-textMuted">{t('create.requiresAckHint')}</span>
          </span>
        </label>

        <div className="pt-2">
          <label className="text-sm font-medium text-textMain mb-2 block">{t('create.mode')}</label>
          <div className="grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={() => setCreationMode('blank')}
              className={cn(
                'flex flex-col items-center justify-center gap-2 p-4 rounded-lg border-2 transition-all',
                creationMode === 'blank'
                  ? 'border-primary bg-blue-50/50 text-primary'
                  : 'border-borderLight text-textMuted hover:border-gray-300'
              )}
            >
              <FileCode className="w-6 h-6" />
              <span className="text-sm font-medium">{t('create.blank')}</span>
            </button>
            <button
              type="button"
              onClick={() => setCreationMode('upload')}
              className={cn(
                'flex flex-col items-center justify-center gap-2 p-4 rounded-lg border-2 transition-all',
                creationMode === 'upload'
                  ? 'border-primary bg-blue-50/50 text-primary'
                  : 'border-borderLight text-textMuted hover:border-gray-300'
              )}
            >
              <Upload className="w-6 h-6" />
              <span className="text-sm font-medium">{t('create.upload')}</span>
            </button>
          </div>
        </div>

        {creationMode === 'upload' && (
          <div
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              const dropped = e.dataTransfer.files?.[0];
              if (dropped) {
                handleFileUpload({ target: { files: e.dataTransfer.files } } as any);
              }
            }}
            className="mt-4 p-4 border border-dashed border-borderLight rounded-lg bg-surface flex flex-col items-center justify-center text-center"
          >
            {uploadKind === 'pdf' ? <FileType className="w-8 h-8 text-red-500 mb-2" />
              : uploadKind === 'zip' ? <FileArchive className="w-8 h-8 text-primary mb-2" />
              : uploadKind === 'tex' ? <FileCode className="w-8 h-8 text-primary mb-2" />
              : <Upload className="w-8 h-8 text-textMuted mb-2" />}

            <p className="text-sm text-textMain font-medium mb-1">
              {fileName || t('create.uploadHint')}
            </p>

            {/* Says what will happen to THIS file, rather than a generic note.
                The three formats behave differently once uploaded. */}
            <p className="text-xs text-textMuted mb-3 max-w-sm">
              {uploadKind === 'zip' ? t('create.uploadZipNote')
                : uploadKind === 'pdf' ? t('create.uploadPdfNote')
                : uploadKind === 'tex' ? t('create.uploadTexNote')
                : t('create.uploadNote')}
            </p>

            <label className="btn-outline cursor-pointer text-sm py-1.5 px-4">
              <span>{t('create.browse')}</span>
              <input
                type="file"
                accept=".zip,.tex,.pdf"
                className="hidden"
                onChange={handleFileUpload}
              />
            </label>
          </div>
        )}
      </form>
    </Modal>
  );
}
