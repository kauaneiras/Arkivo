package com.labdms.infrastructure.adapter.in.web;

import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Register of EXTERNAL documents — ISO/IEC 17025:2017 clause 8.3.1.
 *
 * <p>The clause covers every document the laboratory depends on, not only the
 * ones it writes. In an electronics lab that means instrument manuals, reference
 * standards (IPC-A-610, IEC 61010, JEDEC), calibration tables and conversion
 * charts. A superseded edition of a standard sitting at the bench is one of the
 * most common assessment findings.
 *
 * <p>These are deliberately NOT modelled as versioned documents: the laboratory
 * does not author them, so there is no revision history to keep. What is
 * controlled is which EDITION is in force, who owns it, and evidence that
 * somebody confirmed it is still current.
 */
@RestController
@RequestMapping("/api/v1/external-documents")
@Tag(name = "External documents", description = "ISO/IEC 17025 8.3.1 — control of external documents")
public class ExternalDocumentController {

    private static final List<String> KINDS =
            List.of("STANDARD", "MANUAL", "CALIBRATION_TABLE", "REGULATION", "DATASHEET", "OTHER");
    private static final List<String> STATUSES = List.of("ACTIVE", "SUPERSEDED", "WITHDRAWN");

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogPort auditLogPort;

    public ExternalDocumentController(JdbcTemplate jdbcTemplate, AuditLogPort auditLogPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogPort = auditLogPort;
    }

    public record ExternalDocumentDto(
            UUID id, String code, String title, String issuer, String edition, String docKind,
            LocalDate publicationDate, Instant lastVerifiedAt, String lastVerifiedByName,
            Integer verificationIntervalMonths, LocalDate nextVerificationDate,
            boolean verificationOverdue,
            UUID ownerId, String ownerName, String location, String notes, String status,
            Instant createdAt, Instant updatedAt) {}

    public record SaveExternalDocumentRequest(
            String code, String title, String issuer, String edition, String docKind,
            LocalDate publicationDate, Integer verificationIntervalMonths,
            UUID ownerId, String location, String notes, String status) {}

    /* ------------------------------- Read ------------------------------- */

    @GetMapping
    @Operation(summary = "List external documents",
               description = "Optional filters: status, docKind, and text (code/title/issuer).")
    public ResponseEntity<List<ExternalDocumentDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String docKind,
            @RequestParam(required = false) String search) {

        // Explicit CAST on every parameter: PostgreSQL cannot infer the type of a
        // bare "? IS NULL", and an untyped null there aborts the whole statement.
        String sql = """
                SELECT e.*, uo.full_name AS owner_name, uv.full_name AS verified_by_name
                  FROM external_documents e
                  LEFT JOIN users uo ON uo.id = e.owner_id
                  LEFT JOIN users uv ON uv.id = e.last_verified_by
                 WHERE (CAST(? AS text) IS NULL OR e.status = CAST(? AS text))
                   AND (CAST(? AS text) IS NULL OR e.doc_kind = CAST(? AS text))
                   AND (CAST(? AS text) IS NULL
                        OR LOWER(e.code)   LIKE LOWER('%' || CAST(? AS text) || '%')
                        OR LOWER(e.title)  LIKE LOWER('%' || CAST(? AS text) || '%')
                        OR LOWER(COALESCE(e.issuer, '')) LIKE LOWER('%' || CAST(? AS text) || '%'))
                 ORDER BY e.code
                """;
        String s = blankToNull(status), k = blankToNull(docKind), q = blankToNull(search);
        return ResponseEntity.ok(jdbcTemplate.query(sql, this::mapRow, s, s, k, k, q, q, q, q));
    }

    @GetMapping("/due-for-verification")
    @Operation(summary = "External documents whose edition check is overdue or coming up",
               description = "This is what proves clause 8.3.1 is actually operated: evidence "
                           + "that each external document was confirmed to be the current edition.")
    public ResponseEntity<List<ExternalDocumentDto>> dueForVerification(
            @RequestParam(defaultValue = "30") int withinDays) {
        LocalDate limit = LocalDate.now().plusDays(Math.max(0, withinDays));
        return ResponseEntity.ok(jdbcTemplate.query("""
                SELECT e.*, uo.full_name AS owner_name, uv.full_name AS verified_by_name
                  FROM external_documents e
                  LEFT JOIN users uo ON uo.id = e.owner_id
                  LEFT JOIN users uv ON uv.id = e.last_verified_by
                 WHERE e.status = 'ACTIVE'
                   AND e.next_verification_date IS NOT NULL
                   AND e.next_verification_date <= CAST(? AS date)
                 ORDER BY e.next_verification_date
                """, this::mapRow, limit));
    }

    /* ------------------------------ Write ------------------------------- */

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'EDITOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an external document")
    public ResponseEntity<ExternalDocumentDto> create(
            @RequestBody SaveExternalDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        validate(request);
        UUID id = UUID.randomUUID();
        Integer interval = request.verificationIntervalMonths() == null
                ? 12 : request.verificationIntervalMonths();

        try {
            jdbcTemplate.update("""
                    INSERT INTO external_documents
                      (id, code, title, issuer, edition, doc_kind, publication_date,
                       verification_interval_months, next_verification_date,
                       owner_id, location, notes, status, created_at, created_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, NOW())
                    """,
                    id, request.code().trim().toUpperCase(), request.title().trim(),
                    request.issuer(), request.edition(), request.docKind(),
                    request.publicationDate(), interval,
                    // Newly registered means just verified — the act of registering it
                    // IS the confirmation that this is the edition in force today.
                    LocalDate.now().plusMonths(interval),
                    request.ownerId(), request.location(), request.notes(),
                    request.status() == null ? "ACTIVE" : request.status(),
                    principal.getId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("An external document with this code already exists: "
                    + request.code());
        }

        jdbcTemplate.update("UPDATE external_documents SET last_verified_at = NOW(), last_verified_by = ? WHERE id = ?",
                principal.getId(), id);

        audit(principal.getId(), "EXTERNAL_DOCUMENT_CREATED", id,
                Map.of("code", request.code(), "edition", String.valueOf(request.edition())));

        return ResponseEntity.status(HttpStatus.CREATED).body(findById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'EDITOR')")
    @Operation(summary = "Update an external document")
    public ResponseEntity<ExternalDocumentDto> update(
            @PathVariable UUID id,
            @RequestBody SaveExternalDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        validate(request);
        int updated = jdbcTemplate.update("""
                UPDATE external_documents
                   SET code = ?, title = ?, issuer = ?, edition = ?, doc_kind = ?,
                       publication_date = ?, verification_interval_months = ?,
                       owner_id = ?, location = ?, notes = ?, status = ?, updated_at = NOW()
                 WHERE id = ?
                """,
                request.code().trim().toUpperCase(), request.title().trim(), request.issuer(),
                request.edition(), request.docKind(), request.publicationDate(),
                request.verificationIntervalMonths(), request.ownerId(), request.location(),
                request.notes(), request.status() == null ? "ACTIVE" : request.status(), id);

        if (updated == 0) return ResponseEntity.notFound().build();

        audit(principal.getId(), "EXTERNAL_DOCUMENT_UPDATED", id,
                Map.of("code", request.code(), "edition", String.valueOf(request.edition())));
        return ResponseEntity.ok(findById(id));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER', 'EDITOR')")
    @Operation(summary = "Confirm this is still the edition in force (8.3.1)",
               description = "Records who checked and when, and reschedules the next check. "
                           + "This is the evidence an assessor asks for.")
    public ResponseEntity<ExternalDocumentDto> verify(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        Integer interval = jdbcTemplate.queryForObject(
                "SELECT COALESCE(verification_interval_months, 12) FROM external_documents WHERE id = ?",
                Integer.class, id);
        if (interval == null) return ResponseEntity.notFound().build();

        jdbcTemplate.update("""
                UPDATE external_documents
                   SET last_verified_at = NOW(), last_verified_by = ?,
                       next_verification_date = CAST(? AS date), updated_at = NOW()
                 WHERE id = ?
                """, principal.getId(), LocalDate.now().plusMonths(interval), id);

        audit(principal.getId(), "EXTERNAL_DOCUMENT_VERIFIED", id, Map.of());
        return ResponseEntity.ok(findById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove an external document from the register")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        int deleted = jdbcTemplate.update("DELETE FROM external_documents WHERE id = ?", id);
        if (deleted == 0) return ResponseEntity.notFound().build();
        audit(principal.getId(), "EXTERNAL_DOCUMENT_DELETED", id, Map.of());
        return ResponseEntity.noContent().build();
    }

    /* ----------------------------- Helpers ------------------------------ */

    private void validate(SaveExternalDocumentRequest r) {
        if (r.code() == null || r.code().isBlank()) {
            throw new IllegalArgumentException("Code is required");
        }
        if (r.title() == null || r.title().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (r.docKind() == null || !KINDS.contains(r.docKind())) {
            throw new IllegalArgumentException("Invalid kind. Use one of: " + String.join(", ", KINDS));
        }
        if (r.status() != null && !STATUSES.contains(r.status())) {
            throw new IllegalArgumentException("Invalid status. Use one of: " + String.join(", ", STATUSES));
        }
    }

    private ExternalDocumentDto findById(UUID id) {
        List<ExternalDocumentDto> found = jdbcTemplate.query("""
                SELECT e.*, uo.full_name AS owner_name, uv.full_name AS verified_by_name
                  FROM external_documents e
                  LEFT JOIN users uo ON uo.id = e.owner_id
                  LEFT JOIN users uv ON uv.id = e.last_verified_by
                 WHERE e.id = ?
                """, this::mapRow, id);
        return found.isEmpty() ? null : found.get(0);
    }

    private ExternalDocumentDto mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        LocalDate next = rs.getObject("next_verification_date", LocalDate.class);
        return new ExternalDocumentDto(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("issuer"),
                rs.getString("edition"),
                rs.getString("doc_kind"),
                rs.getObject("publication_date", LocalDate.class),
                toInstant(rs.getTimestamp("last_verified_at")),
                rs.getString("verified_by_name"),
                (Integer) rs.getObject("verification_interval_months"),
                next,
                next != null && next.isBefore(LocalDate.now()),
                rs.getObject("owner_id", UUID.class),
                rs.getString("owner_name"),
                rs.getString("location"),
                rs.getString("notes"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private void audit(UUID userId, String action, UUID entityId, Map<String, Object> details) {
        auditLogPort.log(AuditEntry.create(userId, action, "ExternalDocument", entityId, details, null));
    }

    private static Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
