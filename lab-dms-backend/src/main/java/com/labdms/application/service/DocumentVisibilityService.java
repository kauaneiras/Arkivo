package com.labdms.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Decides who may read a document.
 *
 * <p>One class, one query, so the rule cannot be half-implemented in five
 * controllers. Everything that serves document content asks here.
 *
 * <p>The three levels the laboratory chose:
 * <ul>
 *   <li>{@code PUBLIC} — no sign-in at all. Deliberately narrow: only the
 *       revision in force, only as a rendered PDF, never the source tree and
 *       never a draft. A published procedure is a document; the project that
 *       produced it is not.</li>
 *   <li>{@code PRIVATE} — any authenticated user of this installation.</li>
 *   <li>{@code RESTRICTED} — only the groups and people listed for it.</li>
 * </ul>
 *
 * <p>The author and anyone holding {@code DOCUMENT_VIEW_ALL} always retain
 * access, otherwise a restricted document could be made unreachable by its own
 * owner and there would be no way back.
 */
@Service
public class DocumentVisibilityService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentVisibilityService.class);

    public static final String PUBLIC = "PUBLIC";
    public static final String PRIVATE = "PRIVATE";
    public static final String RESTRICTED = "RESTRICTED";

    private final JdbcTemplate jdbcTemplate;

    public DocumentVisibilityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** True when the document may be read WITHOUT signing in. */
    @Transactional(readOnly = true)
    public boolean isPublic(UUID documentId) {
        List<String> v = jdbcTemplate.query(
                "SELECT visibility FROM documents WHERE id = ?", (rs, n) -> rs.getString(1), documentId);
        return !v.isEmpty() && PUBLIC.equals(v.get(0));
    }

    /**
     * The single read rule.
     *
     * @param userId the signed-in user, or {@code null} for an anonymous request
     */
    @Transactional(readOnly = true)
    public boolean canRead(UUID userId, UUID documentId) {
        List<Row> rows = jdbcTemplate.query("""
                SELECT visibility, document_group_id, created_by
                  FROM documents WHERE id = ?
                """, (rs, n) -> new Row(rs.getString(1),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), documentId);

        if (rows.isEmpty()) return false;
        Row doc = rows.get(0);

        if (PUBLIC.equals(doc.visibility())) return true;

        // Everything below requires an identity.
        if (userId == null) return false;

        if (userId.equals(doc.createdBy())) return true;
        if (hasPermission(userId, "DOCUMENT_VIEW_ALL")) return true;

        if (PRIVATE.equals(doc.visibility())) return true;

        if (RESTRICTED.equals(doc.visibility())) {
            Integer granted = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM document_access da
                     WHERE da.document_id = ?
                       AND (da.user_id = ?
                            OR da.group_id IN (SELECT group_id FROM document_group_members
                                                WHERE user_id = ?))
                    """, Integer.class, documentId, userId, userId);
            if (granted != null && granted > 0) return true;

            // The document's own sector still counts as a grantee: putting a
            // document in a sector is itself a statement about who works on it,
            // and requiring it to be re-listed would surprise everyone.
            if (doc.groupId() != null && isMember(userId, doc.groupId())) return true;
            return false;
        }

        logger.warn("Document {} has an unknown visibility '{}'; denying", documentId, doc.visibility());
        return false;
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID userId, UUID groupId) {
        if (userId == null || groupId == null) return false;
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_group_members WHERE user_id = ? AND group_id = ?",
                Integer.class, userId, groupId);
        return n != null && n > 0;
    }

    /**
     * The permissions table keys on {@code code}, not {@code name}.
     *
     * <p>This said {@code p.name}, which does not exist — so the query did not
     * return false, it threw a SQL grammar error. Because this is on the read
     * path of every document, opening or reviewing a document created by
     * somebody else failed with a 500: the author short-circuits above and never
     * reaches here, which is why it worked on your own documents and only broke
     * on other people's.
     */
    private boolean hasPermission(UUID userId, String permission) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur
                  JOIN role_permissions rp ON rp.role_id = ur.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                 WHERE ur.user_id = ? AND p.code = ?
                """, Integer.class, userId, permission);
        return n != null && n > 0;
    }

    /* ----------------------------- maintenance ----------------------------- */

    public record Grantee(UUID groupId, UUID userId) {}

    /**
     * Replaces the access list of a RESTRICTED document.
     *
     * <p>Replace rather than merge: the caller sends the list it wants to be
     * true, which is how the screen behaves, and a partial update would leave
     * grantees the user believed they had removed.
     */
    @Transactional
    public void replaceAccessList(UUID documentId, List<Grantee> grantees, UUID actorId) {
        jdbcTemplate.update("DELETE FROM document_access WHERE document_id = ?", documentId);
        for (Grantee g : grantees) {
            if ((g.groupId() == null) == (g.userId() == null)) {
                throw new IllegalArgumentException("Each entry must name either a group or a person");
            }
            jdbcTemplate.update("""
                    INSERT INTO document_access (id, document_id, group_id, user_id, granted_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), documentId, g.groupId(), g.userId(), actorId);
        }
        logger.info("Access list of document {} replaced with {} entries by {}",
                documentId, grantees.size(), actorId);
    }

    public record AccessEntryDto(UUID groupId, String groupName, UUID userId, String userName) {}

    @Transactional(readOnly = true)
    public List<AccessEntryDto> accessList(UUID documentId) {
        return jdbcTemplate.query("""
                SELECT da.group_id, g.name AS group_name, da.user_id, u.full_name AS user_name
                  FROM document_access da
                  LEFT JOIN document_groups g ON g.id = da.group_id
                  LEFT JOIN users u ON u.id = da.user_id
                 WHERE da.document_id = ?
                 ORDER BY g.name NULLS LAST, u.full_name
                """, (rs, n) -> new AccessEntryDto(
                        rs.getObject("group_id", UUID.class), rs.getString("group_name"),
                        rs.getObject("user_id", UUID.class), rs.getString("user_name")),
                documentId);
    }

    private record Row(String visibility, UUID groupId, UUID createdBy) {}
}
