package com.labdms.application.dto.response;

import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for document data.
 */
public record DocumentResponse(
        UUID id,
        String code,
        String title,
        DocumentType docType,
        UUID documentGroupId,
        UUID currentCommitId,
        UUID officialCommitId,
        UUID assignedReviewerId,
        DocumentStatus status,
        UUID createdBy,
        List<String> tags,
        // ISO/IEC 17025 8.3.2 b) periodic review + 8.4 retention
        Integer reviewIntervalMonths,
        java.time.LocalDate nextReviewDate,
        Instant lastReviewedAt,
        boolean reviewOverdue,
        Integer retentionYears,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getCode().value(),
                doc.getTitle(),
                doc.getDocType(),
                doc.getDocumentGroupId(),
                doc.getCurrentCommitId(),
                doc.getOfficialCommitId(),
                doc.getAssignedReviewerId(),
                doc.getStatus(),
                doc.getCreatedBy(),
                doc.getTags(),
                doc.getReviewIntervalMonths(),
                doc.getNextReviewDate(),
                doc.getLastReviewedAt(),
                doc.isReviewOverdue(),
                doc.getRetentionYears(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}
