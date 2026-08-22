package com.labdms.infrastructure.adapter.in.web;

import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read receipts — ISO/IEC 17025:2017 clause 8.3.2 d).
 *
 * <p>The clause requires the current version to be available at the point of use
 * and its distribution to be controlled. In an electronic system, the evidence
 * that satisfies an assessor is a read receipt: WHO acknowledged WHICH revision
 * and when. Acknowledgements are bound to the commit, never to the document —
 * confirming v1 says nothing about whether the person has seen v3.
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/acknowledgements")
@Tag(name = "Acknowledgements", description = "ISO/IEC 17025 8.3.2 d — controlled distribution")
public class AcknowledgementController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogPort auditLogPort;

    public AcknowledgementController(JdbcTemplate jdbcTemplate, AuditLogPort auditLogPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogPort = auditLogPort;
    }

    public record AcknowledgementDto(UUID id, UUID commitId, UUID userId, String userName,
                                      String userEmail, Instant acknowledgedAt) {}

    /** What the editor needs to decide whether to show the "I have read" prompt. */
    public record MyAcknowledgementStatusDto(boolean required, boolean acknowledged,
                                              UUID officialCommitId, Instant acknowledgedAt) {}

    @GetMapping("/me")
    @Operation(summary = "Whether I still have to acknowledge the version in force")
    public ResponseEntity<MyAcknowledgementStatusDto> myStatus(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {

        Map<String, Object> doc = jdbcTemplate.queryForMap(
                "SELECT requires_acknowledgement, COALESCE(official_commit_id, current_commit_id) AS target "
                        + "FROM documents WHERE id = ?", documentId);

        boolean required = Boolean.TRUE.equals(doc.get("requires_acknowledgement"));
        UUID target = (UUID) doc.get("target");
        if (target == null) {
            return ResponseEntity.ok(new MyAcknowledgementStatusDto(required, false, null, null));
        }

        List<Instant> when = jdbcTemplate.query(
                "SELECT acknowledged_at FROM document_acknowledgements WHERE user_id = ? AND commit_id = ?",
                (rs, n) -> rs.getTimestamp("acknowledged_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                principal.getId(), target);

        return ResponseEntity.ok(new MyAcknowledgementStatusDto(
                required, !when.isEmpty(), target, when.isEmpty() ? null : when.get(0)));
    }

    @PostMapping
    @Operation(summary = "Acknowledge having read and understood the version in force",
               description = "Idempotent: acknowledging twice does not create a second record.")
    public ResponseEntity<Void> acknowledge(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        UUID target = jdbcTemplate.queryForObject(
                "SELECT COALESCE(official_commit_id, current_commit_id) FROM documents WHERE id = ?",
                UUID.class, documentId);
        if (target == null) {
            throw new IllegalStateException("This document has no version to acknowledge yet");
        }

        // ON CONFLICT DO NOTHING against the (user_id, commit_id) unique key:
        // re-reading a revision is not a new fact, and the FIRST acknowledgement
        // date is the one that matters as evidence.
        jdbcTemplate.update("""
                INSERT INTO document_acknowledgements
                  (id, document_id, commit_id, user_id, acknowledged_at, ip_address)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, commit_id) DO NOTHING
                """,
                UUID.randomUUID(), documentId, target, principal.getId(),
                LocalDateTime.now(), clientIp(httpRequest));

        auditLogPort.log(AuditEntry.create(principal.getId(), "DOCUMENT_ACKNOWLEDGED",
                "Document", documentId, Map.of("commitId", target.toString()), clientIp(httpRequest)));

        return ResponseEntity.ok().build();
    }

    @GetMapping
    @Operation(summary = "Who has acknowledged the version in force")
    public ResponseEntity<List<AcknowledgementDto>> list(@PathVariable UUID documentId) {
        return ResponseEntity.ok(jdbcTemplate.query("""
                SELECT a.id, a.commit_id, a.user_id, u.full_name, u.email, a.acknowledged_at
                  FROM document_acknowledgements a
                  JOIN users u ON u.id = a.user_id
                 WHERE a.document_id = ?
                   AND a.commit_id = (SELECT COALESCE(official_commit_id, current_commit_id)
                                        FROM documents WHERE id = ?)
                 ORDER BY a.acknowledged_at DESC
                """, (rs, n) -> new AcknowledgementDto(
                        rs.getObject("id", UUID.class),
                        rs.getObject("commit_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getTimestamp("acknowledged_at").toLocalDateTime().toInstant(ZoneOffset.UTC)),
                documentId, documentId));
    }

    public record PendingDto(UUID userId, String userName, String userEmail) {}

    @GetMapping("/pending")
    @Operation(summary = "Who has NOT yet acknowledged the version in force",
               description = "The list a quality manager chases before an assessment.")
    public ResponseEntity<List<PendingDto>> pending(@PathVariable UUID documentId) {
        // Active users who can see documents at all, minus those who already
        // acknowledged the version currently in force.
        return ResponseEntity.ok(jdbcTemplate.query("""
                SELECT u.id, u.full_name, u.email
                  FROM users u
                 WHERE u.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM document_acknowledgements a
                        WHERE a.user_id = u.id
                          AND a.commit_id = (SELECT COALESCE(official_commit_id, current_commit_id)
                                               FROM documents WHERE id = ?))
                 ORDER BY u.full_name
                """, (rs, n) -> new PendingDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email")),
                documentId));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
