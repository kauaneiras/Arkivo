package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.document.Document;
import com.labdms.domain.port.out.DocumentRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.DocumentJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.mapper.DocumentPersistenceMapper;
import com.labdms.infrastructure.adapter.out.persistence.repository.DocumentJpaRepository;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class DocumentRepositoryAdapter implements DocumentRepository {

    private final DocumentJpaRepository repository;
    private final DocumentPersistenceMapper mapper;

    public DocumentRepositoryAdapter(DocumentJpaRepository repository, DocumentPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Document save(Document document) {
        DocumentJpaEntity entity = mapper.toJpaEntity(document);
        DocumentJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Document> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Document> findByCode(String code) {
        return repository.findByCode(code).map(mapper::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return repository.findByCode(code).isPresent();
    }

    @Override
    public java.util.List<Document> search(String title, String code, com.labdms.domain.model.document.DocumentType docType,
                          com.labdms.domain.model.document.DocumentStatus status, java.util.List<String> tags,
                          UUID documentGroupId, int page, int size) {
        // Unsorted on purpose: searchDocuments is a native query that already ends
        // in "ORDER BY d.updated_at DESC". Passing a Sort here would make Spring
        // Data append a second ORDER BY using the JPA property name (updatedAt),
        // which is not a real column — the statement would fail.
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);

        return repository.searchDocuments(
                        emptyToNull(title),
                        emptyToNull(code),
                        docType != null ? docType.name() : null,
                        status != null ? status.name() : null,
                        documentGroupId,
                        pageable)
                .map(mapper::toDomain)
                .getContent();
    }

    @Override
    public long count(String title, String code, com.labdms.domain.model.document.DocumentType docType,
               com.labdms.domain.model.document.DocumentStatus status, java.util.List<String> tags, UUID documentGroupId) {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 1);
        return repository.searchDocuments(
                        emptyToNull(title),
                        emptyToNull(code),
                        docType != null ? docType.name() : null,
                        status != null ? status.name() : null,
                        documentGroupId,
                        pageable)
                .getTotalElements();
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    @Override
    public java.util.List<Document> findDueForReview(java.time.LocalDate limit) {
        return repository.findDueForReview(limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }
}
