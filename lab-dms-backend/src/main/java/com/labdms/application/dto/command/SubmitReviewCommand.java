package com.labdms.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for submitting a review request.
 */
public record SubmitReviewCommand(
        @NotNull UUID userId,
        @NotNull UUID documentId,
        @NotNull UUID sourceBranchId,
        @NotBlank String title,
        String description
) {}
