package com.labdms.infrastructure.adapter.out.persistence.mapper;

import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentCode;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;
import com.labdms.infrastructure.adapter.out.persistence.entity.DocumentJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
public class DocumentPersistenceMapper {

    public Document toDomain(DocumentJpaEntity entity) {
        if (entity == null) return null;

        List<String> parsedTags = Collections.emptyList();
        if (entity.getTags() != null && !entity.getTags().isBlank()) {
            // Very simple JSON array parsing for this example
            String clean = entity.getTags().replace("[", "").replace("]", "").replace("\"", "");
            if (!clean.isBlank()) {
                parsedTags = Arrays.asList(clean.split(","));
            }
        }

        Document document = new Document(
            entity.getId(),
            new DocumentCode(entity.getCode()),
            entity.getTitle(),
            DocumentType.valueOf(entity.getDocType()),
            entity.getDocumentGroupId(),
            entity.getCurrentCommitId(),
            DocumentStatus.valueOf(entity.getStatus()),
            entity.getCreatedBy(),
            parsedTags,
            entity.getCreatedAt() != null ? entity.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null,
            entity.getUpdatedAt() != null ? entity.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC) : null
        );
        document.setOfficialCommitId(entity.getOfficialCommitId());
        document.setAssignedReviewerId(entity.getAssignedReviewerId());
        document.setReviewIntervalMonths(entity.getReviewIntervalMonths());
        document.setLastReviewedAt(entity.getLastReviewedAt() != null
                ? entity.getLastReviewedAt().toInstant(java.time.ZoneOffset.UTC) : null);
        document.setLastReviewedBy(entity.getLastReviewedBy());
        document.setNextReviewDate(entity.getNextReviewDate());
        document.setRetentionYears(entity.getRetentionYears());
        return document;
    }

    public DocumentJpaEntity toJpaEntity(Document domain) {
        if (domain == null) return null;

        DocumentJpaEntity entity = new DocumentJpaEntity();
        entity.setId(domain.getId());
        entity.setCode(domain.getCode().value());
        entity.setTitle(domain.getTitle());
        entity.setDocType(domain.getDocType().name());
        entity.setDocumentGroupId(domain.getDocumentGroupId());
        entity.setCurrentCommitId(domain.getCurrentCommitId());
        entity.setOfficialCommitId(domain.getOfficialCommitId());
        entity.setAssignedReviewerId(domain.getAssignedReviewerId());
        entity.setReviewIntervalMonths(domain.getReviewIntervalMonths());
        entity.setLastReviewedAt(domain.getLastReviewedAt() != null
                ? java.time.LocalDateTime.ofInstant(domain.getLastReviewedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setLastReviewedBy(domain.getLastReviewedBy());
        entity.setNextReviewDate(domain.getNextReviewDate());
        entity.setRetentionYears(domain.getRetentionYears());
        entity.setStatus(domain.getStatus().name());
        entity.setCreatedBy(domain.getCreatedBy());
        
        // Simple JSON array serialization
        String tagsJson = "[]";
        if (domain.getTags() != null && !domain.getTags().isEmpty()) {
            tagsJson = "[\"" + String.join("\",\"", domain.getTags()) + "\"]";
        }
        entity.setTags(tagsJson);
        
        entity.setCreatedAt(domain.getCreatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getCreatedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setUpdatedAt(domain.getUpdatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getUpdatedAt(), java.time.ZoneOffset.UTC) : null);

        return entity;
    }
}
