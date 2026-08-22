package com.labdms.infrastructure.adapter.in.web;

import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * In-app notifications: submitted-for-review, approved/rejected, obsoleted,
 * registration approved. Always scoped to the authenticated user.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notifications for the authenticated user")
public class NotificationController {

    private final JdbcTemplate jdbcTemplate;

    public NotificationController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record NotificationDto(UUID id, String type, String title, String message,
                                   String link, boolean isRead, Instant createdAt) {}

    public record UnreadCountDto(long count) {}

    @GetMapping
    @Operation(summary = "List my notifications", description = "Newest first, capped at 50")
    public ResponseEntity<List<NotificationDto>> list(@AuthenticationPrincipal UserPrincipal principal) {
        List<NotificationDto> notifications = jdbcTemplate.query(
                "SELECT id, type, title, message, link, is_read, created_at FROM notifications " +
                "WHERE user_id = ? ORDER BY created_at DESC LIMIT 50",
                (rs, rowNum) -> new NotificationDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("message"),
                        rs.getString("link"),
                        rs.getBoolean("is_read"),
                        rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)
                ),
                principal.getId());
        return ResponseEntity.ok(notifications);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count my unread notifications")
    public ResponseEntity<UnreadCountDto> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = false",
                Long.class, principal.getId());
        return ResponseEntity.ok(new UnreadCountDto(count != null ? count : 0));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE id = ? AND user_id = ?",
                id, principal.getId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        jdbcTemplate.update("UPDATE notifications SET is_read = true WHERE user_id = ? AND is_read = false",
                principal.getId());
        return ResponseEntity.ok().build();
    }
}
