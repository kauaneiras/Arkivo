package com.labdms.infrastructure.config;

import com.labdms.application.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reminds the people responsible when a document's periodic review falls due
 * (ISO/IEC 17025 8.3.2 b).
 *
 * <p>Without this the review dates are only ever seen by someone who happens to
 * open the right screen — and "review cycles that are defined but not followed"
 * is one of the findings assessors raise most often. The reminder goes to the
 * document's assigned reviewer, falling back to its author when none was named.
 *
 * <p>Runs once a day. Notifications are deduplicated by looking for one already
 * sent for the same document today, so a restart does not spam anyone.
 */
@Component
@Profile("!test")
public class ReviewReminderJob {

    private static final Logger logger = LoggerFactory.getLogger(ReviewReminderJob.class);

    /** How far ahead to warn, so a review can be planned rather than fire-fought. */
    private static final int WARN_AHEAD_DAYS = 15;

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;

    public ReviewReminderJob(JdbcTemplate jdbcTemplate, NotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
    }

    /**
     * Runs before the laboratory's working hours so the notice is already waiting
     * when people log in.
     *
     * <p>The zone is explicit: the container runs in UTC, so a bare "07:00" would
     * fire at 04:00 in Brazil — the reminder would land in the middle of the
     * night and look like it never ran. Both the schedule and the zone are
     * properties so a lab in another timezone does not need a rebuild.
     */
    @Scheduled(cron = "${labdms.review-reminder.cron:0 0 7 * * *}",
               zone = "${labdms.review-reminder.zone:America/Sao_Paulo}")
    public void scheduledRun() {
        int sent = notifyDueReviews();
        if (sent > 0) {
            logger.info("Periodic review reminders sent for {} document(s)", sent);
        }
    }

    /**
     * @return how many reminders were sent — returned rather than logged only, so
     *         the manual trigger can report a concrete result instead of "ok".
     */
    public int notifyDueReviews() {
        try {
            var due = jdbcTemplate.queryForList("""
                    SELECT d.id, d.code, d.title, d.next_review_date,
                           COALESCE(d.assigned_reviewer_id, d.created_by) AS notify_user_id,
                           (d.next_review_date < CURRENT_DATE) AS overdue
                      FROM documents d
                     WHERE d.status = 'ACTIVE'
                       AND d.next_review_date IS NOT NULL
                       AND d.next_review_date <= CURRENT_DATE + CAST(? AS integer)
                       AND COALESCE(d.assigned_reviewer_id, d.created_by) IS NOT NULL
                       AND NOT EXISTS (
                           SELECT 1 FROM notifications n
                            WHERE n.user_id = COALESCE(d.assigned_reviewer_id, d.created_by)
                              AND n.type = 'REVIEW_DUE'
                              AND n.link = '/dashboard/documents/' || d.id || '/edit'
                              AND n.created_at >= CURRENT_DATE)
                    """, WARN_AHEAD_DAYS);

            for (var row : due) {
                UUID userId = (UUID) row.get("notify_user_id");
                String code = (String) row.get("code");
                String title = (String) row.get("title");
                boolean overdue = Boolean.TRUE.equals(row.get("overdue"));
                Object dueDate = row.get("next_review_date");

                notificationService.notify(
                        userId,
                        NotificationService.TYPE_REVIEW_DUE,
                        overdue ? "Revisão periódica vencida" : "Revisão periódica a vencer",
                        "\"" + title + "\" (" + code + ") "
                                + (overdue ? "está com a revisão vencida desde " : "deve ser revisado até ")
                                + dueDate + ".",
                        "/dashboard/documents/" + row.get("id") + "/edit");
            }

            return due.size();
        } catch (Exception e) {
            // A failing reminder must never take the application down.
            logger.error("Could not send periodic review reminders", e);
            return 0;
        }
    }
}
