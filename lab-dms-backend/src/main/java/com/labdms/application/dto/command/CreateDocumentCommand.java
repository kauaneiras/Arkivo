package com.labdms.application.dto.command;

import com.labdms.domain.model.document.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Command record for creating a new document.
 */
public record CreateDocumentCommand(
        @NotNull UUID userId,
        String code,            // optional: generated from the category when omitted
        @NotBlank String title,
        @NotNull DocumentType docType,
        UUID documentGroupId,   // optional: documents may exist without a group/sector
        @NotBlank String initialLatexSource,
        List<String> tags,
        UUID assignedReviewerId,        // optional: who must approve it (must hold REVIEW_APPROVE)
        Integer reviewIntervalMonths,   // ISO 8.3.2 b) — null falls back to 24 months
        Integer retentionYears,         // ISO 8.4     — null falls back to 5 years
        Boolean requiresAcknowledgement // ISO 8.3.2 d) — null means not required
) {}
