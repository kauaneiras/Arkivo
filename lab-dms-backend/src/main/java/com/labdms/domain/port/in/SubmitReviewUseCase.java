package com.labdms.domain.port.in;

import com.labdms.domain.model.review.ReviewRequest;

import java.util.UUID;

import com.labdms.application.dto.command.SubmitReviewCommand;

/**
 * Use case for submitting a review request for a branch.
 */
public interface SubmitReviewUseCase {

    ReviewRequest execute(SubmitReviewCommand command);
}
