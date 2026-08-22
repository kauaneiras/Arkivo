package com.labdms.domain.port.out;

import com.labdms.domain.model.review.Review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for review persistence.
 */
public interface ReviewRepository {

    Review save(Review review);

    Optional<Review> findById(UUID id);

    List<Review> findByReviewRequestId(UUID reviewRequestId);
}
