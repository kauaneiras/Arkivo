package com.labdms.application.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * In-app notifications for the ISO review workflow: a reviewer is notified when a
 * document is submitted for approval, and the author is notified of the decision.
 *
 * Kept as a thin JdbcTemplate service (same pragmatic style as the other read-heavy
 * controllers in this codebase) rather than a full hexagonal port: notifications are
 * a side effect of the domain operations, not domain logic themselves.
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Notification types understood by the frontend (icon/color mapping). */
    public static final String TYPE_REVIEW_SUBMITTED = "REVIEW_SUBMITTED";
    public static final String TYPE_REVIEW_APPROVED = "REVIEW_APPROVED";
    public static final String TYPE_REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String TYPE_DOCUMENT_OBSOLETED = "DOCUMENT_OBSOLETED";
    public static final String TYPE_USER_APPROVED = "USER_APPROVED";
    /** ISO/IEC 17025 8.3.2 b) — periodic review overdue or coming up. */
    public static final String TYPE_REVIEW_DUE = "REVIEW_DUE";

    public void notify(UUID userId, String type, String title, String message, String link) {
        if (userId == null) return;
        jdbcTemplate.update(
                "INSERT INTO notifications (id, user_id, type, title, message, link, is_read, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, false, ?)",
                UUID.randomUUID(), userId, type, title, message, link, LocalDateTime.now());
    }

    /**
     * Notifies every ACTIVE user who can approve reviews (ROLE_REVIEWER or ROLE_ADMIN).
     * The review workflow is not group-scoped today, so all reviewers are notified;
     * this mirrors who {@code AccessControlService#assertCanApprove} already allows.
     */
    public void notifyReviewers(String title, String message, String link) {
        List<UUID> reviewerIds = jdbcTemplate.query(
                "SELECT DISTINCT u.id FROM users u " +
                "JOIN user_roles ur ON ur.user_id = u.id " +
                "JOIN roles r ON r.id = ur.role_id " +
                "WHERE r.name IN ('ROLE_REVIEWER', 'ROLE_ADMIN') AND u.status = 'ACTIVE'",
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        for (UUID reviewerId : reviewerIds) {
            notify(reviewerId, TYPE_REVIEW_SUBMITTED, title, message, link);
        }
    }
}
