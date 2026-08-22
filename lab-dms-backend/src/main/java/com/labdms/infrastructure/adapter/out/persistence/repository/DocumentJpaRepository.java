package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.DocumentJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentJpaRepository extends JpaRepository<DocumentJpaEntity, UUID> {

    Optional<DocumentJpaEntity> findByCode(String code);

    /**
     * ACTIVE documents due for periodic review (ISO/IEC 17025 8.3.2 b).
     * Only ACTIVE ones matter: a draft is not in force, and an obsolete document
     * is by definition no longer subject to review.
     */
    @Query("""
            SELECT d FROM DocumentJpaEntity d
             WHERE d.status = 'ACTIVE'
               AND d.nextReviewDate IS NOT NULL
               AND d.nextReviewDate <= :limit
             ORDER BY d.nextReviewDate ASC
            """)
    java.util.List<DocumentJpaEntity> findDueForReview(@Param("limit") java.time.LocalDate limit);

    /**
     * Optional-filter search. Every filter is nullable: a null value means
     * "don't filter by this".
     *
     * <p>Written as a NATIVE query with explicit {@code CAST(... AS text/uuid)}
     * around each parameter, and this is not cosmetic. The previous JPQL version
     *
     * <pre>(:title IS NULL OR LOWER(d.title) LIKE ...)</pre>
     *
     * made Hibernate emit a standalone <code>$1 IS NULL</code> with no inferable
     * type, and PostgreSQL rejects the whole statement with
     * <em>"could not determine data type of parameter $1"</em>. That turned every
     * document listing into a 500, so the UI showed an empty list forever.
     * The casts give PostgreSQL the type it needs even when the value is null.
     *
     * <p>Ordering is fixed in SQL (not via {@code Pageable#getSort()}) because a
     * native query would receive the JPA property name (<code>updatedAt</code>)
     * rather than the column name (<code>updated_at</code>) and fail again.
     */
    @Query(value = """
            SELECT * FROM documents d
            WHERE (CAST(:title AS text) IS NULL
                   OR LOWER(d.title) LIKE LOWER('%' || CAST(:title AS text) || '%'))
              AND (CAST(:code AS text) IS NULL
                   OR LOWER(d.code) LIKE LOWER('%' || CAST(:code AS text) || '%'))
              AND (CAST(:docType AS text) IS NULL OR d.doc_type = CAST(:docType AS text))
              AND (CAST(:status AS text) IS NULL OR d.status = CAST(:status AS text))
              AND (CAST(:documentGroupId AS uuid) IS NULL
                   OR d.document_group_id = CAST(:documentGroupId AS uuid))
            ORDER BY d.updated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM documents d
            WHERE (CAST(:title AS text) IS NULL
                   OR LOWER(d.title) LIKE LOWER('%' || CAST(:title AS text) || '%'))
              AND (CAST(:code AS text) IS NULL
                   OR LOWER(d.code) LIKE LOWER('%' || CAST(:code AS text) || '%'))
              AND (CAST(:docType AS text) IS NULL OR d.doc_type = CAST(:docType AS text))
              AND (CAST(:status AS text) IS NULL OR d.status = CAST(:status AS text))
              AND (CAST(:documentGroupId AS uuid) IS NULL
                   OR d.document_group_id = CAST(:documentGroupId AS uuid))
            """,
            nativeQuery = true)
    Page<DocumentJpaEntity> searchDocuments(
            @Param("title") String title,
            @Param("code") String code,
            @Param("docType") String docType,
            @Param("status") String status,
            @Param("documentGroupId") UUID documentGroupId,
            Pageable pageable
    );
}
