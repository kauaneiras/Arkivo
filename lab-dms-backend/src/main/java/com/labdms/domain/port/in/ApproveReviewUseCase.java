package com.labdms.domain.port.in;

import com.labdms.domain.model.review.Review;
import com.labdms.domain.model.review.ReviewDecision;

import java.util.UUID;

import com.labdms.application.dto.command.ApproveReviewCommand;

/**
 * Use case for approving or rejecting a review request.
 */
public interface ApproveReviewUseCase {

    Review execute(ApproveReviewCommand command);
}
