package com.labdms.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only access to the immutable audit trail (ISO 9000 / ISO IEC 17025 traceability).
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Immutable audit trail for compliance")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;

    public AuditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AuditEntryDto(
            Long id,
            String userName,
            String userEmail,
            String action,
            String entityType,
            UUID entityId,
            String details,
            Instant createdAt
    ) {}

    public record AuditPageDto(List<AuditEntryDto> entries, long totalElements, int totalPages, int page) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "Browse the audit trail",
               description = "Paginated, newest first. Optional filters: action, entityType, entityId.")
    public ResponseEntity<AuditPageDto> getAuditTrail(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = ? ");
            args.add(action);
        }
        if (entityType != null && !entityType.isBlank()) {
            where.append(" AND a.entity_type = ? ");
            args.add(entityType);
        }
        if (entityId != null) {
            where.append(" AND a.entity_id = ? ");
            args.add(entityId);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log a" + where, Long.class, args.toArray());
        long totalElements = total != null ? total : 0;

        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add(page * size);

        List<AuditEntryDto> entries = jdbcTemplate.query(
                "SELECT a.id, u.full_name AS user_name, u.email AS user_email, a.action, " +
                "a.entity_type, a.entity_id, a.details, a.created_at " +
                "FROM audit_log a LEFT JOIN users u ON u.id = a.user_id" +
                where +
                " ORDER BY a.created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AuditEntryDto(
                        rs.getLong("id"),
                        rs.getString("user_name"),
                        rs.getString("user_email"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("details"),
                        rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)
                ),
                queryArgs.toArray()
        );

        int totalPages = (int) Math.ceil((double) totalElements / size);
        return ResponseEntity.ok(new AuditPageDto(entries, totalElements, totalPages, page));
    }

    @GetMapping("/actions")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    @Operation(summary = "List distinct audit actions (for filter dropdowns)")
    public ResponseEntity<List<String>> getActions() {
        return ResponseEntity.ok(jdbcTemplate.queryForList(
                "SELECT DISTINCT action FROM audit_log ORDER BY action", String.class));
    }
}
