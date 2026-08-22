package com.labdms.domain.port.out;

import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for document persistence operations.
 */
public interface DocumentRepository {

    Document save(Document document);

    Optional<Document> findById(UUID id);

    Optional<Document> findByCode(String code);

    boolean existsByCode(String code);

    List<Document> search(String title, String code, DocumentType docType,
                          DocumentStatus status, List<String> tags,
                          UUID documentGroupId, int page, int size);

    long count(String title, String code, DocumentType docType,
               DocumentStatus status, List<String> tags, UUID documentGroupId);

    /**
     * ACTIVE documents whose periodic review is due on or before {@code limit}
     * (ISO/IEC 17025 8.3.2 b). Ordered by how overdue they are.
     */
    List<Document> findDueForReview(java.time.LocalDate limit);

    void deleteById(UUID id);
}
