package com.labdms.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregated quality metrics for the dashboard (ISO 9000 management overview).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Quality system overview metrics")
public class DashboardController {

    private final JdbcTemplate jdbcTemplate;

    public DashboardController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ActivityDto(String userName, String action, String entityType,
                              UUID entityId, String details, Instant createdAt) {}

    public record DashboardStatsDto(
            long totalDocuments,
            Map<String, Long> documentsByStatus,
            Map<String, Long> documentsByType,
            long openReviews,
            long totalUsers,
            long pendingUsers,
            long totalCommits,
            List<ActivityDto> recentActivity
    ) {}

    @GetMapping("/stats")
    @Operation(summary = "Get quality system statistics")
    public ResponseEntity<DashboardStatsDto> getStats() {
        long totalDocuments = count("SELECT COUNT(*) FROM documents");

        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT status, COUNT(*) AS c FROM documents GROUP BY status",
                rs -> { byStatus.put(rs.getString("status"), rs.getLong("c")); });

        Map<String, Long> byType = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT doc_type, COUNT(*) AS c FROM documents GROUP BY doc_type",
                rs -> { byType.put(rs.getString("doc_type"), rs.getLong("c")); });

        long openReviews = count("SELECT COUNT(*) FROM review_requests WHERE status = 'OPEN'");
        long totalUsers = count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'");
        long pendingUsers = count("SELECT COUNT(*) FROM users WHERE status = 'PENDING'");
        long totalCommits = count("SELECT COUNT(*) FROM commits");

        List<ActivityDto> recentActivity = jdbcTemplate.query(
                "SELECT u.full_name AS user_name, a.action, a.entity_type, a.entity_id, a.details, a.created_at " +
                "FROM audit_log a LEFT JOIN users u ON u.id = a.user_id " +
                "ORDER BY a.created_at DESC LIMIT 10",
                (rs, rowNum) -> new ActivityDto(
                        rs.getString("user_name"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("details"),
                        rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)
                )
        );

        return ResponseEntity.ok(new DashboardStatsDto(
                totalDocuments, byStatus, byType, openReviews,
                totalUsers, pendingUsers, totalCommits, recentActivity
        ));
    }

    private long count(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result != null ? result : 0;
    }
}
