package com.labdms.application.service;

import com.labdms.domain.exception.DomainException;
import com.labdms.domain.model.document.DocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The laboratory's document taxonomy.
 *
 * <p>Types used to be a Java enum, so a lab that needed "CAL" for calibration
 * records had to wait for a release. They are rows now, maintained by an
 * administrator.
 *
 * <p>Two rules protect traceability, and both are enforced here rather than in
 * the UI, because the UI is not the only way in:
 * <ul>
 *   <li><b>The prefix never changes once a document uses it.</b> A code like
 *       IT-001 is written on paper records and quoted in audit findings; letting
 *       it mean something else afterwards would invalidate that trail.</li>
 *   <li><b>A type in use is deactivated, never deleted.</b> Deleting it would
 *       leave existing documents pointing at a type that no longer resolves.</li>
 * </ul>
 */
@Service
public class DocumentTypeService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentTypeService.class);

    private final JdbcTemplate jdbcTemplate;

    public DocumentTypeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TypeDto(UUID id, String prefix, String name, String description,
                           boolean active, Integer defaultReviewIntervalMonths,
                           Integer defaultRetentionYears, int sortOrder,
                           long documentCount) {}

    /* -------------------------------- read -------------------------------- */

    /**
     * @param includeInactive true for the administration screen, false for the
     *        pickers that create new documents — a retired type must not be
     *        offered for new work, while old documents keep resolving.
     */
    @Transactional(readOnly = true)
    public List<TypeDto> list(boolean includeInactive) {
        String where = includeInactive ? "" : "WHERE t.is_active";
        return jdbcTemplate.query("""
                SELECT t.id, t.prefix, t.name, t.description, t.is_active,
                       t.default_review_interval_months, t.default_retention_years, t.sort_order,
                       (SELECT COUNT(*) FROM documents d WHERE d.doc_type = t.prefix) AS document_count
                  FROM document_types t
                """ + where + " ORDER BY t.sort_order, t.prefix",
                (rs, n) -> new TypeDto(
                        rs.getObject("id", UUID.class), rs.getString("prefix"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("is_active"),
                        (Integer) rs.getObject("default_review_interval_months"),
                        (Integer) rs.getObject("default_retention_years"),
                        rs.getInt("sort_order"), rs.getLong("document_count")));
    }

    /**
     * Asserts the type exists and may be used for a NEW document.
     *
     * <p>{@link com.labdms.domain.model.document.DocumentCode} only validates the
     * shape of a code, so without this any well-formed prefix would create a
     * document of a type the laboratory never defined — the taxonomy would drift
     * silently, one typo at a time.
     */
    @Transactional(readOnly = true)
    public void assertUsableForNewDocument(DocumentType type) {
        if (type == null) throw new DomainException("document.type.required");
        List<Boolean> active = jdbcTemplate.query(
                "SELECT is_active FROM document_types WHERE prefix = ?",
                (rs, n) -> rs.getBoolean(1), type.name());

        if (active.isEmpty()) {
            throw new DomainException("document.type.unknown", type.name());
        }
        if (!active.get(0)) {
            throw new DomainException("document.type.inactive", type.name());
        }
    }

    /* -------------------------------- write ------------------------------- */

    public record CreateTypeCommand(String prefix, String name, String description,
                                     Integer reviewIntervalMonths, Integer retentionYears,
                                     Integer sortOrder) {}

    @Transactional
    public TypeDto create(CreateTypeCommand command, UUID actorId) {
        // Normalised through the value object so a type can never be created in a
        // shape that would later fail to parse out of a document code.
        DocumentType type = DocumentType.of(command.prefix());

        if (command.name() == null || command.name().isBlank()) {
            throw new DomainException("document.type.name.required");
        }

        UUID id = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO document_types
                        (id, prefix, name, description, default_review_interval_months,
                         default_retention_years, sort_order, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, type.name(), command.name().trim(), command.description(),
                    command.reviewIntervalMonths(), command.retentionYears(),
                    command.sortOrder() == null ? 100 : command.sortOrder(), actorId);
        } catch (DuplicateKeyException e) {
            throw new DomainException("document.type.duplicate", type.name());
        }

        logger.info("Document type {} created by {}", type.name(), actorId);
        return list(true).stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new DomainException("document.type.unknown", type.name()));
    }

    public record UpdateTypeCommand(String name, String description,
                                     Integer reviewIntervalMonths, Integer retentionYears,
                                     Integer sortOrder, Boolean active) {}

    /**
     * Updates everything about a type EXCEPT its prefix.
     *
     * <p>There is deliberately no way to change a prefix through this service.
     * Renaming "IT" to "INS" would either leave IT-001 meaning a type that no
     * longer exists, or rewrite identifiers on approved records — both are
     * traceability failures. An administrator who needs a different prefix
     * creates the new type and deactivates the old one, which keeps history
     * readable and makes the change visible.
     */
    @Transactional
    public TypeDto update(UUID id, UpdateTypeCommand command, UUID actorId) {
        if (command.name() != null && command.name().isBlank()) {
            throw new DomainException("document.type.name.required");
        }

        int updated = jdbcTemplate.update("""
                UPDATE document_types SET
                    name = COALESCE(?, name),
                    description = COALESCE(?, description),
                    default_review_interval_months = COALESCE(?, default_review_interval_months),
                    default_retention_years = COALESCE(?, default_retention_years),
                    sort_order = COALESCE(?, sort_order),
                    is_active = COALESCE(?, is_active),
                    updated_at = NOW()
                 WHERE id = ?
                """, command.name() == null ? null : command.name().trim(), command.description(),
                command.reviewIntervalMonths(), command.retentionYears(), command.sortOrder(),
                command.active(), id);

        if (updated == 0) throw new DomainException("document.type.not.found", id);

        logger.info("Document type {} updated by {}", id, actorId);
        return list(true).stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new DomainException("document.type.not.found", id));
    }

    /**
     * Removes a type that was never used; deactivates one that was.
     *
     * @return true when the row was deleted, false when it was deactivated
     */
    @Transactional
    public boolean deleteOrDeactivate(UUID id, UUID actorId) {
        List<String> prefixes = jdbcTemplate.query(
                "SELECT prefix FROM document_types WHERE id = ?", (rs, n) -> rs.getString(1), id);
        if (prefixes.isEmpty()) throw new DomainException("document.type.not.found", id);

        Integer inUse = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE doc_type = ?", Integer.class, prefixes.get(0));

        if (inUse != null && inUse > 0) {
            jdbcTemplate.update(
                    "UPDATE document_types SET is_active = false, updated_at = NOW() WHERE id = ?", id);
            logger.info("Document type {} deactivated by {} ({} documents use it)",
                    prefixes.get(0), actorId, inUse);
            return false;
        }

        jdbcTemplate.update("DELETE FROM document_types WHERE id = ?", id);
        logger.info("Document type {} deleted by {} (never used)", prefixes.get(0), actorId);
        return true;
    }
}
