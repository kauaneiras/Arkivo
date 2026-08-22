package com.labdms.application.dto.command;

import com.labdms.domain.model.review.ReviewDecision;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for approving or rejecting a review request.
 */
public record ApproveReviewCommand(
        @NotNull UUID userId,
        @NotNull UUID reviewRequestId,
        @NotNull ReviewDecision decision,
        String comments
) {}
