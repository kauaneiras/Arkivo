package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.dto.command.CompileLatexCommand;
import com.labdms.application.dto.response.CompilationResultResponse;
import com.labdms.domain.port.in.CompileLatexUseCase;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/latex")
@Tag(name = "LaTeX", description = "LaTeX compilation engine")
public class LatexController {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(LatexController.class);

    private final CompileLatexUseCase compileLatexUseCase;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.labdms.application.service.AccessControlService accessControl;
    private final com.labdms.application.service.ProjectFileService projectFiles;
    private final com.labdms.application.service.DocumentVisibilityService visibility;

    public LatexController(CompileLatexUseCase compileLatexUseCase,
                           org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                           com.labdms.application.service.AccessControlService accessControl,
                           com.labdms.application.service.ProjectFileService projectFiles,
                           com.labdms.application.service.DocumentVisibilityService visibility) {
        this.compileLatexUseCase = compileLatexUseCase;
        this.jdbcTemplate = jdbcTemplate;
        this.accessControl = accessControl;
        this.projectFiles = projectFiles;
        this.visibility = visibility;
    }

    /**
     * Authorises access to whatever document a commit belongs to.
     *
     * <p>Every endpoint here is addressed by {@code commitId}, which is just an
     * opaque id supplied by the caller. Authentication alone only proves the
     * caller is *a* user of the system — it says nothing about whether this
     * particular document is theirs to see. Without this, any logged-in user
     * could read the PDF of any document in any group by guessing or harvesting
     * a commit id.
     *
     * <p>The check is here, in one method called by every handler, rather than
     * repeated per endpoint: an authorisation rule that is written out three
     * times is an authorisation rule that will be missing from the fourth
     * endpoint someone adds.
     *
     * @return the document id the commit belongs to
     */
    private UUID assertCanViewCommit(UUID commitId, UserPrincipal principal) {
        var rows = jdbcTemplate.queryForList("""
                SELECT d.id AS document_id, d.document_group_id
                  FROM commits c JOIN documents d ON d.id = c.document_id
                 WHERE c.id = ?
                """, commitId);
        if (rows.isEmpty()) {
            throw new com.labdms.domain.exception.DomainException("commit.not.found", commitId);
        }
        UUID documentId = (UUID) rows.get(0).get("document_id");
        // The document's own visibility rule, not just its sector: a RESTRICTED
        // document must stay closed to a colleague who simply happens to be
        // signed in, and that decision lives in one place.
        if (!visibility.canRead(principal.getId(), documentId)) {
            throw new com.labdms.domain.exception.UnauthorizedAccessException("view");
        }
        return documentId;
    }

    /**
     * Serves the PDF of a revision, watermarked when it must not be mistaken for
     * the approved version in force (ISO/IEC 17025 8.3.2 f).
     *
     * <p>The stored PDF is compiled once, at save time, when the document was
     * still a draft — so it carries no status marking. Marking has to happen when
     * the file is SERVED, because the status changes over the document's life:
     * the same revision is a draft today, in force next week and obsolete next
     * year. The watermarked variant is recompiled from source and lands in the
     * engine's content-hash cache, so repeat downloads are free.
     */
    private byte[] watermarkIfNeeded(UUID commitId, byte[] storedPdf) {
        try {
            // LEFT JOIN, not JOIN: V9 made content_snapshot_id nullable, so a
            // multi-file version legitimately has no single-string snapshot. An
            // inner join returned no row for those, queryForMap threw, and the
            // catch below turned a perfectly good PDF into a blocked download.
            var rows = jdbcTemplate.queryForList("""
                    SELECT d.status, (d.official_commit_id = c.id) AS is_in_force, s.latex_source
                      FROM commits c
                      JOIN documents d ON d.id = c.document_id
                      LEFT JOIN content_snapshots s ON s.id = c.content_snapshot_id
                     WHERE c.id = ?
                    """, commitId);
            if (rows.isEmpty()) return storedPdf;
            var row = rows.get(0);

            String status = (String) row.get("status");
            boolean inForce = Boolean.TRUE.equals(row.get("is_in_force"));
            var kind = com.labdms.shared.util.LatexWatermark.kindFor(status, inForce);
            if (kind == null) {
                return storedPdf;   // approved revision in force: nothing to mark
            }

            // A DRAFT does not get stamped here. The editor already says the
            // document is a draft on screen, and stamping meant recompiling the
            // whole project on EVERY preview load — slow, and a failure in that
            // recompilation blocked a PDF that was perfectly readable. The stamp
            // is a control against a document circulating as if approved, which
            // is what the download and the obsolete/superseded cases are for.
            if (kind == com.labdms.shared.util.LatexWatermark.Kind.DRAFT) {
                return storedPdf;
            }

            String source = (String) row.get("latex_source");
            if (source == null || source.isBlank()) {
                // Nothing to inject the watermark into. Better to serve the
                // document than to block it over a missing snapshot.
                logger.warn("Commit {} has no text snapshot to watermark; serving as stored", commitId);
                return storedPdf;
            }

            String marked = com.labdms.shared.util.LatexWatermark.apply(source, kind);

            // Compile the marked ROOT inside the version's own tree. Compiling it
            // standalone works only for single-file documents: a root that does
            // \input{fixos/pacotes} fails in an empty directory, and the fallback
            // below would then serve the stored PDF WITHOUT the mark. That turned
            // 8.3.2 f) off for exactly the documents that need it most — an
            // obsolete revision leaving the system looking approved.
            var result = compileLatexUseCase.previewProject(
                    commitId, projectFiles.rootPath(commitId), marked);

            if (result.success() && result.pdfBytes() != null && result.pdfBytes().length > 0) {
                return result.pdfBytes();
            }

            // Marking failed. For an obsolete or superseded revision, serving an
            // unmarked PDF is the outcome the control exists to prevent, so the
            // caller is told rather than quietly handed a misleading document.
            logger.error("Could not apply the {} watermark to commit {}; refusing to serve it unmarked",
                    kind, commitId);
            throw new IllegalStateException(
                    "This revision is not in force and could not be marked as such. "
                    + "Download was blocked to avoid it being mistaken for the approved version.");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Watermark check failed for commit {}", commitId, e);
            throw new IllegalStateException(
                    "Could not confirm this revision's status; download was blocked.");
        }
    }

    @PostMapping("/compile/{commitId}")
    @Operation(summary = "Compile a commit's LaTeX source to PDF")
    public ResponseEntity<CompilationResultResponse> compile(
            @PathVariable UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanViewCommit(commitId, principal);
        CompileLatexCommand command = new CompileLatexCommand(principal.getId(), commitId);
        return ResponseEntity.ok(CompilationResultResponse.from(compileLatexUseCase.execute(command)));
    }

    public record PreviewRequest(@jakarta.validation.constraints.NotBlank String latexSource) {}
    public record PreviewError(String message, String log, java.util.List<String> errors) {}

    /**
     * Wraps the PDF so Spring always streams the raw bytes.
     *
     * <p>Returning a bare {@code byte[]} from a handler declared as
     * {@code ResponseEntity<?>} is unsafe: the generic type is erased, and with
     * the {@code Accept: application/json, text/plain, *&#47;*} header that
     * browsers/axios send by default, Spring can pick the Jackson converter and
     * emit the PDF as a base64 STRING. The browser then receives text, and the
     * viewer fails with "Failed to load PDF file". A Resource is only ever
     * handled by ResourceHttpMessageConverter, so the bytes stay bytes.
     */
    private static org.springframework.core.io.Resource asPdfResource(byte[] pdfBytes) {
        return new org.springframework.core.io.ByteArrayResource(pdfBytes);
    }

    @PostMapping(value = "/preview", produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(summary = "Compile LaTeX source WITHOUT saving (editor live preview)",
               description = "Returns the PDF bytes on success, or 422 with the compilation log on failure")
    public ResponseEntity<?> preview(
            @jakarta.validation.Valid @RequestBody PreviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return respond(compileLatexUseCase.preview(request.latexSource()));
    }

    public record ProjectPreviewRequest(String path, String content) {}

    @PostMapping(value = "/preview/project/{commitId}",
                 produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(summary = "Compile a multi-file project WITHOUT saving",
               description = "Materialises the version's file tree, writes the unsaved buffer over "
                           + "the file being edited, and compiles the project's root file. This is "
                           + "the only path that can resolve \\input and \\includegraphics.")
    public ResponseEntity<?> previewProject(
            @PathVariable UUID commitId,
            @RequestBody(required = false) ProjectPreviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanViewCommit(commitId, principal);
        return respond(compileLatexUseCase.previewProject(commitId,
                request == null ? null : request.path(),
                request == null ? null : request.content()));
    }

    /** One response shape for every compile path, so the editor handles one contract. */
    private ResponseEntity<?> respond(com.labdms.domain.port.out.LatexCompilerPort.LatexCompilationResult result) {
        if (!result.success() || result.pdfBytes() == null || result.pdfBytes().length == 0) {
            return ResponseEntity.unprocessableEntity()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PreviewError(result.errorSummary().isBlank()
                            ? "LaTeX compilation failed" : result.errorSummary(),
                            result.log(), result.errors()));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(result.pdfBytes().length);
        headers.add("X-Pdf-Pages", String.valueOf(result.pages()));
        // A PDF can be produced even with recoverable errors — tell the editor
        headers.add("X-Latex-Problems", String.valueOf(result.errors().size()));

        // The messages travel with the successful response too, so the editor
        // can show "rendered, with 3 problems" and list them next to the PDF
        // instead of only reporting errors when nothing rendered at all.
        // Percent-encoded because a TeX message is not header-safe: it carries
        // accents, newlines and quotes.
        if (!result.errors().isEmpty()) {
            String joined = String.join("\n", result.errors());
            if (joined.length() > 3000) joined = joined.substring(0, 3000) + "\n…";
            headers.add("X-Latex-Error-List",
                    java.net.URLEncoder.encode(joined, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("+", "%20"));
        }
        headers.setAccessControlExposeHeaders(java.util.List.of(
                "X-Pdf-Pages", "X-Latex-Problems", "X-Latex-Error-List"));
        return ResponseEntity.ok().headers(headers).body(asPdfResource(result.pdfBytes()));
    }

    @GetMapping(value = "/pdf/{commitId}", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download the stored PDF of a version",
               description = "404 when that version has not been compiled yet — the editor "
                           + "uses that to decide it must generate a preview instead.")
    public ResponseEntity<org.springframework.core.io.Resource> downloadPdf(
            @PathVariable UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertCanViewCommit(commitId, principal);
        byte[] pdfBytes = compileLatexUseCase.findPdf(commitId);

        // "Not compiled yet" is a normal state (a version saved a second ago),
        // not an error. It must NOT be a 200 with an empty body: the editor would
        // hand that to the PDF viewer and render a broken preview.
        if (pdfBytes == null || pdfBytes.length == 0) {
            return ResponseEntity.notFound().build();
        }

        // 8.3.2 f) — an obsolete or non-current revision must never leave the
        // system looking like the approved one.
        pdfBytes = watermarkIfNeeded(commitId, pdfBytes);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(pdfBytes.length);
        headers.setContentDispositionFormData("filename", "document-" + commitId + ".pdf");

        return ResponseEntity.ok().headers(headers).body(asPdfResource(pdfBytes));
    }
}
