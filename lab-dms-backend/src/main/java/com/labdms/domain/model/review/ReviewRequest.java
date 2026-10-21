package com.labdms.domain.model.review;

import java.time.Instant;
import java.util.UUID;

/**
 * A review request for merging a branch into the main branch.
 * ISO 17025 requires formal review before document changes go live.
 */
public class ReviewRequest {

    private UUID id;
    private UUID documentId;
    private UUID sourceBranchId;
    /**
     * The exact version submitted for approval.
     *
     * <p>A review decides on a version, not on "whatever the document points
     * to when the reviewer gets round to it". Approval makes THIS commit the
     * one in force, and the diff shown to the reviewer is this commit against
     * the version in force at submission time.
     */
    private UUID commitId;
    private String title;
    private String description;
    private ReviewRequestStatus status;
    private UUID requestedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public ReviewRequest() {
    }

    public ReviewRequest(UUID id, UUID documentId, UUID sourceBranchId, String title,
                         String description, ReviewRequestStatus status, UUID requestedBy,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.documentId = documentId;
        this.sourceBranchId = sourceBranchId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Factory method to create a new review request.
     */
    public static ReviewRequest create(UUID documentId, UUID sourceBranchId,
                                       String title, String description, UUID requestedBy) {
        return create(documentId, sourceBranchId, null, title, description, requestedBy);
    }

    public static ReviewRequest create(UUID documentId, UUID sourceBranchId, UUID commitId,
                                       String title, String description, UUID requestedBy) {
        ReviewRequest rr = new ReviewRequest();
        rr.id = UUID.randomUUID();
        rr.documentId = documentId;
        rr.sourceBranchId = sourceBranchId;
        rr.commitId = commitId;
        rr.title = title;
        rr.description = description;
        rr.status = ReviewRequestStatus.OPEN;
        rr.requestedBy = requestedBy;
        rr.createdAt = Instant.now();
        rr.updatedAt = Instant.now();
        return rr;
    }

    public void approve() {
        this.status = ReviewRequestStatus.APPROVED;
        this.updatedAt = Instant.now();
    }

    public void reject() {
        this.status = ReviewRequestStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    public void markMerged() {
        this.status = ReviewRequestStatus.MERGED;
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getSourceBranchId() {
        return sourceBranchId;
    }

    public void setSourceBranchId(UUID sourceBranchId) {
        this.sourceBranchId = sourceBranchId;
    }

    public UUID getCommitId() {
        return commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ReviewRequestStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewRequestStatus status) {
        this.status = status;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
