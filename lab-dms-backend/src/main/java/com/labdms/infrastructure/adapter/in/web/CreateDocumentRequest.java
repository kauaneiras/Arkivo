package com.labdms.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public record CreateDocumentRequest(
    String code,            // optional: generated from the category when omitted
    @NotBlank String title,
    @NotBlank String docType,
    UUID documentGroupId,   // optional: documents may exist without a group/sector
    @NotBlank String initialLatexSource,
    List<String> tags,
    UUID assignedReviewerId,        // optional: who must approve it (must hold REVIEW_APPROVE)
    // ISO/IEC 17025 controls. All optional — the service applies laboratory
    // defaults (24 months / 5 years) when they are omitted.
    Integer reviewIntervalMonths,   // 8.3.2 b) how often it must be revisited
    Integer retentionYears,         // 8.4     how long it must be retained
    Boolean requiresAcknowledgement, // 8.3.2 d) readers must confirm they read it
    // Who may read it: PUBLIC (no sign-in), PRIVATE (any signed-in user) or
    // RESTRICTED (named groups and people). Optional; defaults to PRIVATE,
    // which is the level that exposes nothing new.
    String visibility
) {}
