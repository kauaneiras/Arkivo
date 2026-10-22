package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.service.DiffService;
import com.labdms.application.service.DocumentService;
import com.labdms.application.service.DocumentVisibilityService;
import com.labdms.application.service.ProjectFileService;
import com.labdms.application.service.ProjectImportService;
import com.labdms.application.service.VersioningService;
import com.labdms.domain.exception.UnauthorizedAccessException;
import com.labdms.domain.model.audit.AuditEntry;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The file tree of a project version — the Overleaf-equivalent surface.
 *
 * <p>Reads address any version. Writes never choose a version: the server
 * resolves the working draft (opening one when needed) and the write lands
 * there, so a closed version can never be edited through this surface.
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/files")
@Tag(name = "Project files", description = "Multi-file LaTeX projects: tree, upload, ZIP import/export, diffs")
public class ProjectFileController {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectFileService projectFiles;
    private final ProjectImportService importService;
    private final BlobStore blobStore;
    private final DocumentService documentService;
    private final AuditLogPort auditLog;
    private final DocumentVisibilityService visibility;
    private final VersioningService versioning;
    private final DiffService diffs;
    private final com.labdms.application.service.AccessControlService accessControl;

    public ProjectFileController(JdbcTemplate jdbcTemplate, ProjectFileService projectFiles,
                                  ProjectImportService importService, BlobStore blobStore,
                                  DocumentService documentService, AuditLogPort auditLog,
                                  DocumentVisibilityService visibility,
                                  VersioningService versioning, DiffService diffs,
                                  com.labdms.application.service.AccessControlService accessControl) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectFiles = projectFiles;
        this.importService = importService;
        this.blobStore = blobStore;
        this.documentService = documentService;
        this.auditLog = auditLog;
        this.visibility = visibility;
        this.versioning = versioning;
        this.diffs = diffs;
        this.accessControl = accessControl;
    }

    public record FileNodeDto(String path, long sizeBytes, boolean isText, String contentType,
                               boolean isRoot, String derivedFrom) {}

    public record FileContentDto(String path, String content, boolean isText) {}

    /**
     * Result of an import — or its preview, in dry-run mode.
     *
     * <p>{@code preview} is filled only in a dry run: what the ZIP WOULD do to
     * the current tree, with per-file line diffs for the text files so the
     * confirmation dialog is self-contained. {@code workingCommit} tells the
     * editor which version the files landed in.
     */
    public record ImportResultDto(int filesImported, int filesSkipped, long totalBytes,
                                   String rootPath, List<String> rootCandidates,
                                   DiffService.TreeDiff preview,
                                   Map<String, DiffService.FileDiff> previewDiffs,
                                   UUID workingCommit) {}

    /* ------------------------ compare two versions ------------------------ */

    /**
     * Compares the file trees of two versions, file by file.
     *
     * <p>Without {@code from}, the base is the version's parent — the natural
     * question when looking at a version in the history. With {@code to}
     * omitted the target is the document's current version, so
     * {@code ?from=<official>} answers "what is being proposed".
     */
    @GetMapping("/diff")
    @Operation(summary = "Compare the file trees of two versions (status and +/- per file)")
    public ResponseEntity<DiffService.TreeDiff> diff(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID from,
            @RequestParam(required = false) UUID to,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanRead(documentId, principal);
        UUID target = commitToRead(documentId, to);
        UUID base = from != null ? commitToRead(documentId, from) : parentOf(target);
        return ResponseEntity.ok(diffs.compareCommits(base, target));
    }

    @GetMapping("/diff/file")
    @Operation(summary = "Line diff of one text file between two versions")
    public ResponseEntity<DiffService.FileDiff> fileDiff(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @RequestParam(required = false) UUID from,
            @RequestParam(required = false) UUID to,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanRead(documentId, principal);
        UUID target = commitToRead(documentId, to);
        UUID base = from != null ? commitToRead(documentId, from) : parentOf(target);
        return ResponseEntity.ok(diffs.fileDiff(base, target, ProjectFileService.normalisePath(path)));
    }

    private UUID parentOf(UUID commitId) {
        List<UUID> rows = jdbcTemplate.queryForList(
                "SELECT parent_commit_id FROM commits WHERE id = ?", UUID.class, commitId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* -------------------------------- read -------------------------------- */

    @GetMapping
    @Operation(summary = "List the project's file tree for a version",
               description = "Defaults to the current version; pass commitId to inspect another.")
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
        // into stored XSS against every user who previews it.
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
    @Operation(summary = "Create or overwrite a text file in the working draft")
    public ResponseEntity<FileNodeDto> saveText(
            @PathVariable UUID documentId,
            @RequestBody SaveFileRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = workingCommit(documentId, principal);
        byte[] bytes = request.content().getBytes(StandardCharsets.UTF_8);
        assertFitsInProject(commit, request.path(), bytes.length);

        projectFiles.putFile(commit, request.path(), bytes,
                ProjectImportService.guessContentType(request.path()), false);
        refreshProjectSize(documentId, commit);

        // Which version the write landed in. The client never chooses it, but
        // when the write OPENED a draft the editor must follow, or it keeps
        // previewing the tree it was opened on.
        return ResponseEntity.ok()
                .header("X-Working-Commit", commit.toString())
                .header("Access-Control-Expose-Headers", "X-Working-Commit")
                .body(new FileNodeDto(request.path(), bytes.length, true,
                        ProjectImportService.guessContentType(request.path()), false, null));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a file into the working draft (image, PDF, .tex...)",
               description = "EPS is converted to PDF on the server, so shell-escape stays disabled.")
    public ResponseEntity<FileNodeDto> upload(
            @PathVariable UUID documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String folder,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        UUID commit = workingCommit(documentId, principal);

        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        if (file.getSize() > ProjectImportService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("The file exceeds "
                    + (ProjectImportService.MAX_FILE_BYTES / 1024 / 1024) + " MB");
        }

        String name = file.getOriginalFilename() == null ? "arquivo" : file.getOriginalFilename();
        String path = (folder == null || folder.isBlank() ? "" : folder.replaceAll("/+$", "") + "/") + name;
        byte[] content = file.getBytes();
        assertFitsInProject(commit, path, content.length);

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
                .header("X-Working-Commit", commit.toString())
                .header("Access-Control-Expose-Headers", "X-Working-Commit")
                .body(new FileNodeDto(path, content.length, false, contentType, false, derivedFrom));
    }

    public record RenameRequest(String from, String to) {}

    @PostMapping("/rename")
    @Operation(summary = "Rename or move a file or folder in the working draft")
    public ResponseEntity<Map<String, Object>> rename(
            @PathVariable UUID documentId,
            @RequestBody RenameRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = workingCommit(documentId, principal);
        int affected = projectFiles.renamePath(commit, request.from(), request.to());
        if (affected == 0) throw new IllegalArgumentException("Path not found: " + request.from());
        audit(principal.getId(), "PROJECT_FILE_RENAMED", documentId,
                Map.of("from", request.from(), "to", request.to()));
        return ResponseEntity.ok(Map.of("affected", affected, "workingCommit", commit));
    }

    @DeleteMapping
    @Operation(summary = "Delete a file from the working draft")
    public ResponseEntity<Void> delete(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = workingCommit(documentId, principal);
        projectFiles.deleteFile(commit, path);
        refreshProjectSize(documentId, commit);
        audit(principal.getId(), "PROJECT_FILE_DELETED", documentId, Map.of("path", path));
        return ResponseEntity.noContent()
                .header("X-Working-Commit", commit.toString())
                .header("Access-Control-Expose-Headers", "X-Working-Commit")
                .build();
    }

    @PostMapping("/root")
    @Operation(summary = "Choose which file is compiled",
               description = "A project can legitimately have several files with \\documentclass "
                           + "(a main and a backup), so the root is chosen, not guessed.")
    public ResponseEntity<Void> setRoot(
            @PathVariable UUID documentId,
            @RequestParam String path,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID commit = workingCommit(documentId, principal);
        projectFiles.setRoot(commit, path);
        jdbcTemplate.update("UPDATE documents SET root_file_path = ? WHERE id = ?", path, documentId);
        audit(principal.getId(), "PROJECT_ROOT_CHANGED", documentId, Map.of("path", path));
        return ResponseEntity.ok()
                .header("X-Working-Commit", commit.toString())
                .header("Access-Control-Expose-Headers", "X-Working-Commit")
                .build();
    }

    /* ------------------------------ ZIP in/out ----------------------------- */

    /** Above this many bytes of hunks the preview stops attaching line diffs. */
    private static final int MAX_PREVIEW_DIFF_LINES = 4000;

    @PostMapping(value = "/import-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import an Overleaf ZIP export into the working draft",
               description = "Replaces the draft's file tree. Build artefacts are skipped and EPS "
                           + "figures are converted to PDF. With dryRun=true nothing is written: the "
                           + "response describes what the ZIP would change, file by file.")
    public ResponseEntity<ImportResultDto> importZip(
            @PathVariable UUID documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String rootPath,
            @RequestParam(required = false) Boolean dryRun,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        // A ZIP replaces the whole tree, and what is not in it ceases to exist.
        // Exporting the wrong folder deletes chapters, and the mistake surfaces
        // weeks later when somebody looks for the file. Seeing the list first,
        // removals highlighted, turns a silent accident into a decision.
        if (Boolean.TRUE.equals(dryRun)) {
            // A dry run stores the ZIP's blobs so the preview can diff them, so
            // it is gated like a write — the tree is not touched, but a reader
            // could otherwise fill the blob store with previews.
            accessControl.assertCanEdit(principal.getId(), documentService.findById(documentId));
            var ensaio = importService.importZip(file.getBytes(), rootPath);
            UUID atual = currentCommit(documentId);

            Map<String, DiffService.Entry> candidate = new LinkedHashMap<>();
            ensaio.files().forEach(f -> candidate.put(ProjectFileService.normalisePath(f.path()),
                    new DiffService.Entry(f.sha256(), f.sizeBytes(), f.isText())));
            DiffService.TreeDiff preview = diffs.compareWithCandidate(atual, candidate);

            // Line diffs for the changed text files, so the dialog can show them
            // without a second request (the candidate tree exists nowhere else).
            Map<String, DiffService.Entry> before = new LinkedHashMap<>();
            projectFiles.listFiles(atual).forEach(f -> before.put(f.path(),
                    new DiffService.Entry(f.sha256(), f.sizeBytes(), f.isText())));
            Map<String, DiffService.FileDiff> previewDiffs = new LinkedHashMap<>();
            int budget = MAX_PREVIEW_DIFF_LINES;
            for (var change : preview.changes()) {
                if (!change.isText() || change.status() == DiffService.Status.UNCHANGED) continue;
                if (budget <= 0) break;
                var fd = diffs.fileDiff(before.get(change.path()), candidate.get(change.path()), change.path());
                previewDiffs.put(change.path(), fd);
                budget -= fd.hunks().stream().mapToInt(h -> h.lines().size()).sum();
            }

            return ResponseEntity.ok(new ImportResultDto(ensaio.files().size(), ensaio.skipped(),
                    ensaio.totalBytes(), ensaio.rootPath(), ensaio.rootCandidates(),
                    preview, previewDiffs, null));
        }

        UUID commit = workingCommit(documentId, principal);

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
                imported.totalBytes(), root, imported.rootCandidates(), null, null, commit));
    }

    /**
     * The wrapper that turns an uploaded PDF into a compilable document.
     *
     * <p>Many controlled documents are not written here — a supplier manual, a
     * scanned calibration certificate. The PDF becomes a file in the project
     * and this one-page root includes it, so versions, review, approval and the
     * 8.3.2 f) watermark all work unchanged.
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

    public static final String PDF_WRAPPER_ROOT = ProjectFileService.DEFAULT_ROOT;

    @PostMapping(value = "/import-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Use an uploaded PDF as the document's content",
               description = "Stores the PDF and generates a root .tex that includes it, so a "
                           + "PDF-based document still gets versions, review and status watermarks.")
    public ResponseEntity<ImportResultDto> importPdf(
            @PathVariable UUID documentId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) throws Exception {

        UUID commit = workingCommit(documentId, principal);

        byte[] bytes = file.getBytes();
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
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
                PDF_WRAPPER_ROOT, List.of(PDF_WRAPPER_ROOT), null, null, commit));
    }

    /**
     * Streams the project as a ZIP, one entry at a time.
     *
     * <p>Previously the whole archive was assembled in memory; at the 200 MB
     * project ceiling a handful of simultaneous exports held the heap hostage.
     */
    @GetMapping(value = "/export-zip", produces = "application/zip")
    @Operation(summary = "Export the project as a ZIP, the same shape Overleaf produces")
    public ResponseEntity<StreamingResponseBody> exportZip(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanRead(documentId, principal);
        UUID target = commitToRead(documentId, commitId);
        var files = projectFiles.listFiles(target);
        String fileName = documentService.findById(documentId).getCode().value() + ".zip";

        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (var f : files) {
                    zip.putNextEntry(new ZipEntry(f.path()));
                    zip.write(blobStore.read(f.sha256()));
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /* ------------------------------- helpers ------------------------------- */

    /** One read rule for the whole file surface: tree, content, raw, diff and export. */
    private void assertCanRead(UUID documentId, UserPrincipal principal) {
        if (!visibility.canRead(principal == null ? null : principal.getId(), documentId)) {
            throw new UnauthorizedAccessException("view");
        }
    }

    /**
     * Resolves which version to read, binding a caller-supplied commit to the
     * document in the path so the id that was authorised and the id that is
     * read are always the same document.
     */
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
        List<UUID> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(current_commit_id, official_commit_id) FROM documents WHERE id = ?",
                UUID.class, documentId);
        UUID commit = rows.isEmpty() ? null : rows.get(0);
        if (commit == null) throw new IllegalStateException("This document has no version yet");
        return commit;
    }

    /**
     * The commit every write goes to: the working draft, opened on demand.
     *
     * <p>Authorisation, the lock on documents in review and the "never rewrite
     * a closed version" rule all live in the versioning service, so this
     * controller cannot get them wrong.
     */
    private UUID workingCommit(UUID documentId, UserPrincipal principal) {
        return versioning.openWorkingRevision(documentId, principal.getId());
    }

    /** Enforces the per-project ceiling, discounting the file being replaced. */
    private void assertFitsInProject(UUID commit, String path, long incoming) {
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
        jdbcTemplate.update("UPDATE documents SET project_size_bytes = ?, updated_at = NOW() WHERE id = ?",
                projectFiles.totalSize(commit), documentId);
    }

    private void audit(UUID userId, String action, UUID documentId, Map<String, Object> details) {
        auditLog.log(AuditEntry.create(userId, action, "Document", documentId, details, null));
    }
}
