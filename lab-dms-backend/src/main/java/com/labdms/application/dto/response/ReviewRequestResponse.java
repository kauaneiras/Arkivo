package com.labdms.application.dto.response;

import com.labdms.domain.model.review.ReviewRequest;
import com.labdms.domain.model.review.ReviewRequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for review request data.
 */
public record ReviewRequestResponse(
        UUID id,
        UUID documentId,
        UUID sourceBranchId,
        String title,
        String description,
        ReviewRequestStatus status,
        UUID requestedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReviewRequestResponse from(ReviewRequest rr) {
        return new ReviewRequestResponse(
                rr.getId(),
                rr.getDocumentId(),
                rr.getSourceBranchId(),
                rr.getTitle(),
                rr.getDescription(),
                rr.getStatus(),
                rr.getRequestedBy(),
                rr.getCreatedAt(),
                rr.getUpdatedAt()
        );
    }
}
