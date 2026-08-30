package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.service.AccessControlService;
import com.labdms.application.service.DocumentService;
import com.labdms.application.service.ProjectFileService;
import com.labdms.application.service.ProjectImportService;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.adapter.out.storage.BlobStore;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The file tree of a project version — the Overleaf-equivalent surface.
 *
 * <p>Everything here operates on a specific commit, because a version is a
 * complete tree. Editing writes into the document's working commit; saving a
 * version freezes it and starts a new one.
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/files")
@Tag(name = "Project files", description = "Multi-file LaTeX projects: tree, upload, ZIP import/export")
public class ProjectFileController {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectFileService projectFiles;
    private final ProjectImportService importService;
    private final BlobStore blobStore;
    private final DocumentService documentService;
    private final AccessControlService accessControl;
    private final AuditLogPort auditLog;
    private final com.labdms.application.service.DocumentVisibilityService visibility;
    private final com.labdms.domain.port.in.CommitDocumentUseCase commitUseCase;

    public ProjectFileController(JdbcTemplate jdbcTemplate, ProjectFileService projectFiles,
                                  ProjectImportService importService, BlobStore blobStore,
                                  DocumentService documentService, AccessControlService accessControl,
                                  AuditLogPort auditLog,
                                  com.labdms.application.service.DocumentVisibilityService visibility,
                                  com.labdms.domain.port.in.CommitDocumentUseCase commitUseCase) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectFiles = projectFiles;
        this.importService = importService;
        this.blobStore = blobStore;
        this.documentService = documentService;
        this.accessControl = accessControl;
        this.auditLog = auditLog;
        this.visibility = visibility;
        this.commitUseCase = commitUseCase;
    }

    public record FileNodeDto(String path, long sizeBytes, boolean isText, String contentType,
                               boolean isRoot, String derivedFrom) {}

    public record FileContentDto(String path, String content, boolean isText) {}

    public record ImportResultDto(int filesImported, int filesSkipped, long totalBytes,
                                   String rootPath, List<String> rootCandidates) {}

    /* -------------------------------- read -------------------------------- */

    @GetMapping
    @Operation(summary = "List the project's file tree for a version",
               description = "Defaults to the version in force; pass commitId to inspect another.")
    public ResponseEntity<List<FileNodeDto>> tree(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanRead(documentId, principal);

        UUID target = commitToRead(documentId, commitId);
        return ResponseEntity.ok(projectFiles.listFiles(target).stream()
                .map(f -> new FileNodeDto(f.path(), f.sizeBytes(), f.isText(),
                        f.contentType(), f.isRoot(), f.derivedFrom()))
                .toList());
    }

    @GetMapping("/content")
    @Operation(summary = "Read one text file from the tree")
    public ResponseEntity<FileContentDto> content(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @RequestParam(required = false) UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanRead(documentId, principal);

        UUID target = commitToRead(documentId, commitId);
        return ResponseEntity.ok(new FileContentDto(path,
                projectFiles.readTextFile(target, path), true));
    }

    @GetMapping(value = "/raw", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Download a binary file (image, PDF) from the tree",
               description = "Used by the editor to preview figures without loading them as text.")
    public ResponseEntity<Resource> raw(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @RequestParam(required = false) UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanRead(documentId, principal);

        UUID target = commitToRead(documentId, commitId);
        byte[] bytes = projectFiles.readBinaryFile(target, path);

        // Only formats a browser cannot be tricked into executing may render
        // inline. An SVG is an XML document that can carry <script>, so serving
        // an uploaded one as image/svg+xml on this origin turns a project file
        // into stored XSS against every user who previews it. Anything else is
        // sent as an opaque download with a neutral type.
        String guessed = ProjectImportService.guessContentType(path);
        boolean inlineSafe = guessed.equals("image/png") || guessed.equals("image/jpeg")
                || guessed.equals("image/gif") || guessed.equals("application/pdf");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                inlineSafe ? guessed : MediaType.APPLICATION_OCTET_STREAM_VALUE));
        headers.setContentLength(bytes.length);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                (inlineSafe ? "inline" : "attachment") + "; filename=\""
                        + fileNameOf(path).replace("\"", "") + "\"");
        // Stops a browser from second-guessing the type we just declared.
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("Content-Security-Policy", "default-src 'none'; sandbox");

        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /* -------------------------------- write ------------------------------- */

    public record SaveFileRequest(String path, String content) {}

    @PutMapping("/content")
    @Operation(summary = "Create or overwrite a text file")
    public ResponseEntity<FileNodeDto> saveText(
            @PathVariable UUID documentId,
            @RequestBody SaveFileRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);
        byte[] bytes = request.content().getBytes(StandardCharsets.UTF_8);
        assertFitsInProject(documentId, commit, request.path(), bytes.length);

        projectFiles.putFile(commit, request.path(), bytes,
                ProjectImportService.guessContentType(request.path()), false);
        refreshProjectSize(documentId, commit);

        // Diz em que versão a escrita entrou.
        //
        // O cliente não envia o commit — o servidor é que escolhe qual é
        // editável. Quando essa escolha ABRE uma revisão nova (primeira edição
        // depois de uma aprovação), o editor continuaria a apontar para a
        // anterior e a pré-visualizar a árvore errada. Devolver o identificador
        // deixa-o seguir, sem lhe dar o poder de o escolher.
        return ResponseEntity.ok()
                .header("X-Working-Commit", commit.toString())
                .header("Access-Control-Expose-Headers", "X-Working-Commit")
                .body(new FileNodeDto(request.path(), bytes.length, true,
                        ProjectImportService.guessContentType(request.path()), false, null));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a file into the project (image, PDF, .tex...)",
               description = "EPS is converted to PDF on the server, so shell-escape stays disabled.")
    public ResponseEntity<FileNodeDto> upload(
            @PathVariable UUID documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String folder,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);

        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        if (file.getSize() > ProjectImportService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("The file exceeds "
                    + (ProjectImportService.MAX_FILE_BYTES / 1024 / 1024) + " MB");
        }

        String name = file.getOriginalFilename() == null ? "arquivo" : file.getOriginalFilename();
        String path = (folder == null || folder.isBlank() ? "" : folder.replaceAll("/+$", "") + "/") + name;
        byte[] content = file.getBytes();
        assertFitsInProject(documentId, commit, path, content.length);

        String contentType = ProjectImportService.guessContentType(path);
        String derivedFrom = null;
        if (path.toLowerCase().endsWith(".eps")) {
            byte[] pdf = importService.convertEps(content, path);
            if (pdf != null) {
                derivedFrom = path;
                path = path.substring(0, path.length() - 4) + ".pdf";
                content = pdf;
                contentType = "application/pdf";
            }
        }

        projectFiles.putFile(commit, path, content, contentType, false);
        refreshProjectSize(documentId, commit);
        audit(principal.getId(), "PROJECT_FILE_UPLOADED", documentId, Map.of("path", path));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FileNodeDto(path, content.length, false, contentType, false, derivedFrom));
    }

    public record RenameRequest(String from, String to) {}

    @PostMapping("/rename")
    @Operation(summary = "Rename or move a file or folder")
    public ResponseEntity<Map<String, Object>> rename(
            @PathVariable UUID documentId,
            @RequestBody RenameRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);
        int affected = projectFiles.renamePath(commit, request.from(), request.to());
        if (affected == 0) throw new IllegalArgumentException("Path not found: " + request.from());
        audit(principal.getId(), "PROJECT_FILE_RENAMED", documentId,
                Map.of("from", request.from(), "to", request.to()));
        return ResponseEntity.ok(Map.of("affected", affected));
    }

    @DeleteMapping
    @Operation(summary = "Delete a file from the project")
    public ResponseEntity<Void> delete(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);
        projectFiles.deleteFile(commit, path);
        refreshProjectSize(documentId, commit);
        audit(principal.getId(), "PROJECT_FILE_DELETED", documentId, Map.of("path", path));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/root")
    @Operation(summary = "Choose which file is compiled",
               description = "A project can legitimately have several files with \\documentclass "
                           + "(a main and a backup), so the root is chosen, not guessed.")
    public ResponseEntity<Void> setRoot(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);
        projectFiles.setRoot(commit, path);
        jdbcTemplate.update("UPDATE documents SET root_file_path = ? WHERE id = ?", path, documentId);
        audit(principal.getId(), "PROJECT_ROOT_CHANGED", documentId, Map.of("path", path));
        return ResponseEntity.ok().build();
    }

    /* ------------------------------ ZIP in/out ----------------------------- */

    @PostMapping(value = "/import-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import an Overleaf ZIP export",
               description = "Replaces the project's file tree. Build artefacts are skipped and "
                           + "EPS figures are converted to PDF.")
    public ResponseEntity<ImportResultDto> importZip(
            @PathVariable UUID documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String rootPath,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);

        // Paths and root come back already stripped of the wrapper folder, so
        // the root that is reported is one that actually exists in the tree.
        var imported = importService.importZip(file.getBytes(), rootPath);
        String root = imported.rootPath();

        int stored = projectFiles.replaceTree(commit,
                imported.files().stream()
                        .map(f -> new ProjectFileService.NewFile(f.path(), f.sha256(), f.sizeBytes(),
                                f.isText(), f.contentType(), f.derivedFrom()))
                        .toList(),
                root);

        jdbcTemplate.update("UPDATE documents SET root_file_path = ? WHERE id = ?", root, documentId);
        refreshProjectSize(documentId, commit);
        audit(principal.getId(), "PROJECT_ZIP_IMPORTED", documentId,
                Map.of("files", String.valueOf(stored), "root", root));

        return ResponseEntity.ok(new ImportResultDto(stored, imported.skipped(),
                imported.totalBytes(), root, imported.rootCandidates()));
    }

    /**
     * The wrapper that turns an uploaded PDF into a compilable document.
     *
     * <p>Many controlled documents are not written here — a supplier manual, a
     * scanned calibration certificate, a form produced in Word. They still need
     * versions, review, approval and an obsolete watermark. Rather than build a
     * second pipeline for "PDF documents", the PDF becomes a file in the project
     * and this one-page root includes it. Everything downstream — compiling,
     * versioning, the tree, the 8.3.2 f) watermark — then works unchanged.
     */
    private static String pdfWrapperSource(String pdfPath) {
        return """
                %% Generated by Lab DMS: this document is an uploaded PDF.
                %% Editing this file changes how the PDF is presented, not the PDF.
                \\documentclass{article}
                \\usepackage[utf8]{inputenc}
                \\usepackage{pdfpages}
                \\begin{document}
                \\includepdf[pages=-,fitpaper=true]{__PDF__}
                \\end{document}
                """.replace("__PDF__", pdfPath);
    }

    public static final String PDF_WRAPPER_ROOT = "main.tex";

    @PostMapping(value = "/import-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Use an uploaded PDF as the document's content",
               description = "Stores the PDF and generates a root .tex that includes it, so a "
                           + "PDF-based document still gets versions, review and status watermarks.")
    public ResponseEntity<ImportResultDto> importPdf(
            @PathVariable UUID documentId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        UUID commit = assertCanEditAndGetCommit(documentId, principal);

        byte[] bytes = file.getBytes();
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            // Trusting the extension would let a renamed file through to the
            // compiler, where the failure is a wall of TeX log instead of a
            // sentence the user can act on.
            throw new IllegalArgumentException("This file is not a PDF.");
        }
        if (bytes.length > ProjectImportService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("The file exceeds "
                    + (ProjectImportService.MAX_FILE_BYTES / 1024 / 1024) + " MB");
        }

        String name = file.getOriginalFilename() == null ? "documento.pdf" : file.getOriginalFilename();
        String pdfPath = ProjectFileService.normalisePath(
                name.toLowerCase().endsWith(".pdf") ? name : name + ".pdf");

        var blob = blobStore.store(bytes, pdfPath, "application/pdf");
        byte[] wrapper = pdfWrapperSource(pdfPath).getBytes(StandardCharsets.UTF_8);
        var wrapperBlob = blobStore.store(wrapper, PDF_WRAPPER_ROOT, "text/x-tex");

        int stored = projectFiles.replaceTree(commit, List.of(
                new ProjectFileService.NewFile(pdfPath, blob.sha256(), blob.sizeBytes(),
                        false, "application/pdf", null),
                new ProjectFileService.NewFile(PDF_WRAPPER_ROOT, wrapperBlob.sha256(),
                        wrapperBlob.sizeBytes(), true, "text/x-tex", null)),
                PDF_WRAPPER_ROOT);

        jdbcTemplate.update("UPDATE documents SET root_file_path = ? WHERE id = ?",
                PDF_WRAPPER_ROOT, documentId);
        refreshProjectSize(documentId, commit);
        audit(principal.getId(), "PROJECT_PDF_IMPORTED", documentId, Map.of("path", pdfPath));

        return ResponseEntity.ok(new ImportResultDto(stored, 0, bytes.length,
                PDF_WRAPPER_ROOT, List.of(PDF_WRAPPER_ROOT)));
    }

    @GetMapping(value = "/export-zip", produces = "application/zip")
    @Operation(summary = "Export the project as a ZIP, the same shape Overleaf produces")
    public ResponseEntity<Resource> exportZip(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        assertCanRead(documentId, principal);

        UUID target = commitToRead(documentId, commitId);
        var files = projectFiles.listFiles(target);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var f : files) {
                zip.putNextEntry(new ZipEntry(f.path()));
                zip.write(blobStore.read(f.sha256()));
                zip.closeEntry();
            }
        }
        byte[] bytes = out.toByteArray();
        String fileName = documentService.findById(documentId).getCode().value() + ".zip";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    /* ------------------------------- helpers ------------------------------- */

    /**
     * Resolves which version to read, binding a caller-supplied commit to the
     * document in the path.
     *
     * <p>Authorisation is checked against the document in the URL, but the files
     * were then read from whatever {@code commitId} the caller passed in the
     * query string. Those are two different objects: a user could authorise
     * against a document they legitimately own and read the tree of a document
     * in a group closed to them. Every read goes through here so the id that was
     * authorised and the id that is read are always the same document.
     */
    /** One read rule for the whole file surface: tree, content, raw and export. */
    private void assertCanRead(UUID documentId, UserPrincipal principal) {
        if (!visibility.canRead(principal.getId(), documentId)) {
            throw new com.labdms.domain.exception.UnauthorizedAccessException("view");
        }
    }

    private UUID commitToRead(UUID documentId, UUID requestedCommitId) {
        if (requestedCommitId == null) return currentCommit(documentId);
        List<UUID> owner = jdbcTemplate.queryForList(
                "SELECT document_id FROM commits WHERE id = ?", UUID.class, requestedCommitId);
        if (owner.isEmpty() || !documentId.equals(owner.get(0))) {
            throw new IllegalArgumentException("This version does not belong to this document");
        }
        return requestedCommitId;
    }

    private UUID currentCommit(UUID documentId) {
        // queryForList, not queryForObject: the latter throws
        // EmptyResultDataAccessException when the query returns NO row, which
        // would turn a missing document into a 500 instead of a clean error.
        List<UUID> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(current_commit_id, official_commit_id) FROM documents WHERE id = ?",
                UUID.class, documentId);
        UUID commit = rows.isEmpty() ? null : rows.get(0);
        if (commit == null) throw new IllegalStateException("This document has no version yet");
        return commit;
    }

    private UUID assertCanEditAndGetCommit(UUID documentId, UserPrincipal principal) {
        Document doc = documentService.findById(documentId);
        accessControl.assertCanEdit(principal.getId(), doc);
        if (doc.getStatus() == com.labdms.domain.model.document.DocumentStatus.IN_REVIEW
                || doc.getStatus() == com.labdms.domain.model.document.DocumentStatus.OBSOLETE) {
            throw new IllegalStateException("This document is locked for editing (" + doc.getStatus() + ")");
        }

        UUID commit = currentCommit(documentId);

        // The revision in force is a quality record, not a working copy. Once a
        // revision is approved, current_commit_id and official_commit_id point at
        // the SAME commit, so writing to "the current commit" was writing into the
        // approved record: files changed with no new version, no review, no change
        // reason and no diff — while commits.sha256_hash still described the old
        // content. ISO/IEC 17025 8.3.2 exists to prevent exactly that.
        //
        // Editing is not blocked, only editing IN PLACE: the user saves a new
        // version (which copies the tree) and edits that.
        //
        // The status check matters as much as the pointer check. A brand new
        // document is created with current_commit_id == official_commit_id, so
        // testing the pointer alone refused the very first upload into a
        // document that had just been created — the user could not import their
        // Overleaf ZIP until they had saved a second version, for no reason.
        // What must not be rewritten is an APPROVED record; a draft has never
        // been approved, and editing it is ordinary work.
        if (doc.getStatus() == com.labdms.domain.model.document.DocumentStatus.ACTIVE
                && commit.equals(doc.getOfficialCommitId())) {
            return openWorkingRevision(documentId, principal.getId(), doc.getOfficialCommitId());
        }
        return commit;
    }

    /**
     * Abre uma revisão de trabalho a partir da versão em vigor.
     *
     * <p>Isto respondia 409 com "salve uma nova versão e edite os arquivos dela"
     * — um conselho impossível de seguir. Gravar uma versão começa por escrever
     * o ficheiro aberto na árvore, e era precisamente essa escrita que estava a
     * ser recusada: para gravar era preciso escrever, e para escrever era
     * preciso ter gravado. O editor ficava trancado num círculo, e a mensagem
     * mandava o utilizador pela porta fechada.
     *
     * <p>A regra que a trava protege continua inteira: a revisão aprovada não é
     * tocada. O que muda é que a primeira alteração deixa de ser recusada e
     * passa a fazer o que a mensagem pedia — criar a nova revisão, copiando a
     * árvore — e a escrita entra nessa. É o que "editar um documento aprovado"
     * significa num sistema versionado: não se altera o que foi aprovado,
     * começa-se o próximo.
     *
     * <p>Acontece uma vez por ciclo: a partir daqui a versão atual já não é a
     * vigente, e as alterações seguintes vão todas para o mesmo rascunho, sem
     * criar versão nenhuma.
     */
    private UUID openWorkingRevision(UUID documentId, UUID userId, UUID officialCommitId) {
        List<UUID> branch = jdbcTemplate.queryForList(
                "SELECT id FROM branches WHERE document_id = ? AND is_default = true",
                UUID.class, documentId);
        if (branch.isEmpty()) {
            throw new IllegalStateException("This document has no default branch");
        }

        // O texto da raiz vem da versão em vigor. Num projeto multi-ficheiro o
        // serviço relê-o da árvore e ignora este valor; num documento de
        // ficheiro único é ele que forma o conteúdo da nova versão.
        List<String> source = jdbcTemplate.queryForList("""
                SELECT s.latex_source FROM content_snapshots s
                  JOIN commits c ON c.content_snapshot_id = s.id
                 WHERE c.id = ?
                """, String.class, officialCommitId);

        var created = commitUseCase.execute(new com.labdms.application.dto.command.CommitDocumentCommand(
                userId, documentId, branch.get(0),
                source.isEmpty() ? null : source.get(0),
                "Nova revisão aberta para edição"));

        jdbcTemplate.update("UPDATE commits SET change_reason = ? WHERE id = ?",
                "Revisão iniciada a partir da versão em vigor.", created.getId());

        // Fica registado que uma revisão nasceu de uma edição, e não de alguém a
        // carregar em "Salvar Versão": numa auditoria, uma versão sem origem
        // explicada é uma pergunta.
        audit(userId, "WORKING_REVISION_OPENED", documentId,
                Map.of("commitId", created.getId().toString(),
                       "fromOfficial", String.valueOf(officialCommitId)));

        return created.getId();
    }

    /** Enforces the per-project ceiling, discounting the file being replaced. */
    private void assertFitsInProject(UUID documentId, UUID commit, String path, long incoming) {
        // A NEW file has no row at all, and COALESCE does not help with zero
        // rows — only with a NULL value. queryForObject would throw here on
        // every first upload of a file, which is the normal case.
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT size_bytes FROM project_files WHERE commit_id = ? AND path = ?",
                Long.class, commit, ProjectFileService.normalisePath(path));
        long existing = rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
        long current = projectFiles.totalSize(commit);
        long projected = current - existing + incoming;
        if (projected > ProjectImportService.MAX_PROJECT_BYTES) {
            throw new IllegalArgumentException("The project would exceed "
                    + (ProjectImportService.MAX_PROJECT_BYTES / 1024 / 1024) + " MB");
        }
    }

    private void refreshProjectSize(UUID documentId, UUID commit) {
        jdbcTemplate.update("UPDATE documents SET project_size_bytes = ? WHERE id = ?",
                projectFiles.totalSize(commit), documentId);
    }

    private void audit(UUID userId, String action, UUID documentId, Map<String, Object> details) {
        auditLog.log(AuditEntry.create(userId, action, "Document", documentId, details, null));
    }
}
