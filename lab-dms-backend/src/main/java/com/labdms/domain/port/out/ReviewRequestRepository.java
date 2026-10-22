package com.labdms.domain.port.out;

import com.labdms.domain.model.review.ReviewRequest;
import com.labdms.domain.model.review.ReviewRequestStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for review request persistence.
 */
public interface ReviewRequestRepository {

    ReviewRequest save(ReviewRequest reviewRequest);

    Optional<ReviewRequest> findById(UUID id);

    List<ReviewRequest> findByDocumentId(UUID documentId);

    List<ReviewRequest> findByStatus(ReviewRequestStatus status);

    Optional<ReviewRequest> findBySourceBranchIdAndStatus(UUID sourceBranchId, ReviewRequestStatus status);

    /** The request awaiting a decision for this document, if any. */
    Optional<ReviewRequest> findOpenByDocumentId(UUID documentId);
}
