package com.labdms.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Request body for submitting a document for review.
 * sourceBranchId is optional: when omitted, the document's default branch is used.
 */
public record SubmitReviewRequest(
    UUID sourceBranchId,
    @NotBlank String title,
    String description
) {}
