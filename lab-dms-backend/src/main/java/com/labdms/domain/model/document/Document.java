package com.labdms.domain.model.document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core domain entity representing a laboratory document.
 * A document holds metadata and points to its current commit (version).
 */
public class Document {

    private UUID id;
    private DocumentCode code;
    private String title;
    private DocumentType docType;
    private UUID documentGroupId;
    private UUID currentCommitId;
    /** The revision currently IN FORCE — may be an older one than currentCommitId. */
    private UUID officialCommitId;
    /** Who is accountable for approving this document (ISO nominal responsibility). */
    private UUID assignedReviewerId;
    /** ISO/IEC 17025 8.3.2 b — how often this document must be revisited. */
    private Integer reviewIntervalMonths;
    private Instant lastReviewedAt;
    private UUID lastReviewedBy;
    private java.time.LocalDate nextReviewDate;
    /** ISO/IEC 17025 8.4 — retention period defined by the laboratory. */
    private Integer retentionYears;
    private DocumentStatus status;
    private UUID createdBy;
    private List<String> tags;
    private Instant createdAt;
    private Instant updatedAt;

    public Document() {
        this.tags = new ArrayList<>();
    }

    public Document(UUID id, DocumentCode code, String title, DocumentType docType,
                    UUID documentGroupId, UUID currentCommitId, DocumentStatus status,
                    UUID createdBy, List<String> tags, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.title = title;
        this.docType = docType;
        this.documentGroupId = documentGroupId;
        this.currentCommitId = currentCommitId;
        this.status = status;
        this.createdBy = createdBy;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Factory method to create a new document in DRAFT status.
     */
    public static Document create(DocumentCode code, String title, DocumentType docType,
                                  UUID documentGroupId, UUID createdBy, List<String> tags) {
        Document doc = new Document();
        doc.id = UUID.randomUUID();
        doc.code = code;
        doc.title = title;
        doc.docType = docType;
        doc.documentGroupId = documentGroupId;
        doc.status = DocumentStatus.DRAFT;
        doc.createdBy = createdBy;
        doc.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();
        return doc;
    }

    public void updateCurrentCommit(UUID commitId) {
        this.currentCommitId = commitId;
        this.updatedAt = Instant.now();
    }

    /**
     * Elects a revision as the official (in-force) one. Nothing is deleted: the
     * previous official revision stays in the history and can be elected again.
     */
    public void makeOfficial(UUID commitId) {
        this.officialCommitId = commitId;
        this.updatedAt = Instant.now();
    }

    public UUID getOfficialCommitId() {
        return officialCommitId;
    }

    public void setOfficialCommitId(UUID officialCommitId) {
        this.officialCommitId = officialCommitId;
    }

    public UUID getAssignedReviewerId() {
        return assignedReviewerId;
    }

    public void setAssignedReviewerId(UUID assignedReviewerId) {
        this.assignedReviewerId = assignedReviewerId;
    }

    /**
     * Records a formal periodic review and schedules the next one.
     *
     * <p>Concluding "still valid, no change needed" IS a review under
     * 8.3.2 b) — the evidence auditors ask for is that the review happened, not
     * that the document changed. So this deliberately does not touch the content
     * or create a new version.
     */
    public void recordPeriodicReview(UUID reviewerId, Instant when) {
        this.lastReviewedAt = when;
        this.lastReviewedBy = reviewerId;
        this.nextReviewDate = computeNextReviewDate(when);
        this.updatedAt = Instant.now();
    }

    /** Null interval means the document is not subject to periodic review. */
    private java.time.LocalDate computeNextReviewDate(Instant from) {
        if (reviewIntervalMonths == null || reviewIntervalMonths <= 0) return null;
        return from.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusMonths(reviewIntervalMonths);
    }

    /** True when the scheduled review date has passed (8.3.2 b non-conformity). */
    public boolean isReviewOverdue() {
        return nextReviewDate != null && nextReviewDate.isBefore(java.time.LocalDate.now());
    }

    public Integer getReviewIntervalMonths() { return reviewIntervalMonths; }

    public void setReviewIntervalMonths(Integer reviewIntervalMonths) {
        this.reviewIntervalMonths = reviewIntervalMonths;
    }

    public Instant getLastReviewedAt() { return lastReviewedAt; }

    public void setLastReviewedAt(Instant lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }

    public UUID getLastReviewedBy() { return lastReviewedBy; }

    public void setLastReviewedBy(UUID lastReviewedBy) { this.lastReviewedBy = lastReviewedBy; }

    public java.time.LocalDate getNextReviewDate() { return nextReviewDate; }

    public void setNextReviewDate(java.time.LocalDate nextReviewDate) {
        this.nextReviewDate = nextReviewDate;
    }

    public Integer getRetentionYears() { return retentionYears; }

    public void setRetentionYears(Integer retentionYears) { this.retentionYears = retentionYears; }

    public void transitionTo(DocumentStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public DocumentCode getCode() {
        return code;
    }

    public void setCode(DocumentCode code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DocumentType getDocType() {
        return docType;
    }

    public void setDocType(DocumentType docType) {
        this.docType = docType;
    }

    public UUID getDocumentGroupId() {
        return documentGroupId;
    }

    public void setDocumentGroupId(UUID documentGroupId) {
        this.documentGroupId = documentGroupId;
    }

    public UUID getCurrentCommitId() {
        return currentCommitId;
    }

    public void setCurrentCommitId(UUID currentCommitId) {
        this.currentCommitId = currentCommitId;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
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
