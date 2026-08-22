package com.labdms.domain.model.review;

import java.time.Instant;
import java.util.UUID;

/**
 * An individual review (decision) submitted by a reviewer on a review request.
 */
public class Review {

    private UUID id;
    private UUID reviewRequestId;
    private UUID reviewerId;
    private ReviewDecision decision;
    private String comments;
    private Instant createdAt;

    public Review() {
    }

    public Review(UUID id, UUID reviewRequestId, UUID reviewerId,
                  ReviewDecision decision, String comments, Instant createdAt) {
        this.id = id;
        this.reviewRequestId = reviewRequestId;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.comments = comments;
        this.createdAt = createdAt;
    }

    /**
     * Factory method to create a new review.
     */
    public static Review create(UUID reviewRequestId, UUID reviewerId,
                                ReviewDecision decision, String comments) {
        Review review = new Review();
        review.id = UUID.randomUUID();
        review.reviewRequestId = reviewRequestId;
        review.reviewerId = reviewerId;
        review.decision = decision;
        review.comments = comments;
        review.createdAt = Instant.now();
        return review;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReviewRequestId() {
        return reviewRequestId;
    }

    public void setReviewRequestId(UUID reviewRequestId) {
        this.reviewRequestId = reviewRequestId;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(UUID reviewerId) {
        this.reviewerId = reviewerId;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public void setDecision(ReviewDecision decision) {
        this.decision = decision;
    }

    public String getComments() {
        return comments;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
