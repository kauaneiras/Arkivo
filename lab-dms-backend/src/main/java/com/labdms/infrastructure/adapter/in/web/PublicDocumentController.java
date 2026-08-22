package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.service.DocumentVisibilityService;
import com.labdms.domain.port.in.CompileLatexUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only surface reachable without signing in.
 *
 * <p>Anonymous access is a real risk in a document system, so this controller is
 * deliberately the narrowest thing that satisfies "public documents can be read
 * by anyone":
 * <ul>
 *   <li>Only documents explicitly marked {@code PUBLIC} — checked per request,
 *       never inferred.</li>
 *   <li>Only the revision <b>in force</b>. A draft is work in progress and an
 *       obsolete revision must not circulate as if it were current.</li>
 *   <li>Only the rendered PDF and a title. No source, no file tree, no version
 *       history, no author details, no comments.</li>
 *   <li>No listing endpoint that could be walked, and no search. A reader
 *       follows a link they were given.</li>
 * </ul>
 *
 * <p>Everything else, including anything that writes, stays behind
 * authentication.
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Public", description = "Documents published for reading without signing in")
public class PublicDocumentController {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentVisibilityService visibility;
    private final CompileLatexUseCase compileLatexUseCase;

    public PublicDocumentController(JdbcTemplate jdbcTemplate,
                                     DocumentVisibilityService visibility,
                                     CompileLatexUseCase compileLatexUseCase) {
        this.jdbcTemplate = jdbcTemplate;
        this.compileLatexUseCase = compileLatexUseCase;
        this.visibility = visibility;
    }

    public record PublicDocumentDto(UUID id, String code, String title, String docType,
                                     String status, String updatedAt) {}

    @GetMapping("/documents/{documentId}")
    @Operation(summary = "Read a published document's identification")
    public ResponseEntity<PublicDocumentDto> get(@PathVariable UUID documentId) {
        if (!visibility.isPublic(documentId)) {
            // 404, not 403: telling an anonymous caller that a document exists
            // but is closed to them is itself a disclosure.
            return ResponseEntity.notFound().build();
        }

        List<PublicDocumentDto> rows = jdbcTemplate.query("""
                SELECT id, code, title, doc_type, status, updated_at
                  FROM documents WHERE id = ? AND visibility = 'PUBLIC'
                """, (rs, n) -> new PublicDocumentDto(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("title"),
                        rs.getString("doc_type"), rs.getString("status"),
                        String.valueOf(rs.getTimestamp("updated_at").toInstant())), documentId);

        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.get(0));
    }

    @GetMapping(value = "/documents/{documentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download the in-force revision of a published document")
    public ResponseEntity<Resource> pdf(@PathVariable UUID documentId) {
        if (!visibility.isPublic(documentId)) {
            return ResponseEntity.notFound().build();
        }

        // The version in force, never "the latest". The latest may be an
        // unapproved draft, and publishing that would put unreviewed content
        // out under the laboratory's name.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT official_commit_id, status, code FROM documents
                 WHERE id = ? AND visibility = 'PUBLIC'
                """, documentId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();

        UUID officialCommit = (UUID) rows.get(0).get("official_commit_id");
        String status = (String) rows.get(0).get("status");
        if (officialCommit == null || !"ACTIVE".equals(status)) {
            // Published but nothing approved to show yet, or withdrawn.
            return ResponseEntity.notFound().build();
        }

        byte[] pdf = compileLatexUseCase.findPdf(officialCommit);
        if (pdf == null || pdf.length == 0) return ResponseEntity.notFound().build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(pdf.length);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + rows.get(0).get("code") + ".pdf\"");
        headers.add("X-Content-Type-Options", "nosniff");
        // A published document is a snapshot of what is in force right now, and
        // "in force" can change today. Caching it for long would keep a
        // withdrawn revision circulating.
        headers.setCacheControl("public, max-age=300");
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(pdf));
    }
}
