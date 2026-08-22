package com.labdms.infrastructure.adapter.in.web;

import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who belongs to each sector.
 *
 * <p>Membership is what makes a RESTRICTED document reachable by a team rather
 * than by a list of names that has to be maintained per document, so changing it
 * changes who can read things — hence ADMIN for every write, and an audit entry
 * for each one.
 */
@RestController
@RequestMapping("/api/v1/documents/groups/{groupId}/members")
@Tag(name = "Group members", description = "Membership of document groups/sectors")
public class GroupMemberController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogPort auditLog;

    public GroupMemberController(JdbcTemplate jdbcTemplate, AuditLogPort auditLog) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLog = auditLog;
    }

    public record MemberDto(UUID userId, String fullName, String email, String addedAt) {}

    @GetMapping
    @Operation(summary = "List the people in a group")
    public ResponseEntity<List<MemberDto>> list(@PathVariable UUID groupId) {
        return ResponseEntity.ok(jdbcTemplate.query("""
                SELECT u.id, u.full_name, u.email, m.added_at
                  FROM document_group_members m
                  JOIN users u ON u.id = m.user_id
                 WHERE m.group_id = ?
                 ORDER BY u.full_name
                """, (rs, n) -> new MemberDto(
                        rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("email"), String.valueOf(rs.getTimestamp("added_at").toInstant())),
                groupId));
    }

    public record AddMemberRequest(UUID userId) {}

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a person to a group (Admin only)")
    public ResponseEntity<Map<String, Object>> add(
            @PathVariable UUID groupId,
            @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (request.userId() == null) {
            throw new IllegalArgumentException("A user must be given");
        }
        // ON CONFLICT: adding somebody twice is the user pressing a button
        // twice, not an error worth a 500.
        int inserted = jdbcTemplate.update("""
                INSERT INTO document_group_members (group_id, user_id, added_by)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, groupId, request.userId(), principal.getId());

        if (inserted > 0) {
            audit(principal.getId(), "GROUP_MEMBER_ADDED", groupId,
                    Map.of("userId", request.userId().toString()));
        }
        return ResponseEntity.ok(Map.of("added", inserted > 0));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a person from a group (Admin only)",
               description = "They immediately lose access to restricted documents shared with "
                           + "this group, unless another grant still covers them.")
    public ResponseEntity<Void> remove(
            @PathVariable UUID groupId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {

        int removed = jdbcTemplate.update(
                "DELETE FROM document_group_members WHERE group_id = ? AND user_id = ?",
                groupId, userId);
        if (removed == 0) return ResponseEntity.notFound().build();

        audit(principal.getId(), "GROUP_MEMBER_REMOVED", groupId,
                Map.of("userId", userId.toString()));
        return ResponseEntity.noContent().build();
    }

    private void audit(UUID actorId, String action, UUID groupId, Map<String, Object> details) {
        auditLog.log(AuditEntry.create(actorId, action, "DocumentGroup", groupId, details, null));
    }
}
