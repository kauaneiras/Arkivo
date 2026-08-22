package com.labdms.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentJpaEntity {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "doc_type", nullable = false, length = 10)
    private String docType;

    @Column(name = "document_group_id")
    private UUID documentGroupId;

    @Column(name = "current_commit_id")
    private UUID currentCommitId;

    @Column(name = "official_commit_id")
    private UUID officialCommitId;

    @Column(name = "assigned_reviewer_id")
    private UUID assignedReviewerId;

    // ISO/IEC 17025 8.3.2 b) periodic review
    @Column(name = "review_interval_months")
    private Integer reviewIntervalMonths;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "last_reviewed_by")
    private UUID lastReviewedBy;

    @Column(name = "next_review_date")
    private java.time.LocalDate nextReviewDate;

    // ISO/IEC 17025 8.4 — retention period defined by the laboratory
    @Column(name = "retention_years")
    private Integer retentionYears;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String tags;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public UUID getDocumentGroupId() { return documentGroupId; }
    public void setDocumentGroupId(UUID documentGroupId) { this.documentGroupId = documentGroupId; }
    public UUID getCurrentCommitId() { return currentCommitId; }
    public void setCurrentCommitId(UUID currentCommitId) { this.currentCommitId = currentCommitId; }
    public UUID getOfficialCommitId() { return officialCommitId; }
    public void setOfficialCommitId(UUID officialCommitId) { this.officialCommitId = officialCommitId; }
    public UUID getAssignedReviewerId() { return assignedReviewerId; }
    public void setAssignedReviewerId(UUID assignedReviewerId) { this.assignedReviewerId = assignedReviewerId; }
    public Integer getReviewIntervalMonths() { return reviewIntervalMonths; }
    public void setReviewIntervalMonths(Integer reviewIntervalMonths) { this.reviewIntervalMonths = reviewIntervalMonths; }
    public LocalDateTime getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(LocalDateTime lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }
    public UUID getLastReviewedBy() { return lastReviewedBy; }
    public void setLastReviewedBy(UUID lastReviewedBy) { this.lastReviewedBy = lastReviewedBy; }
    public java.time.LocalDate getNextReviewDate() { return nextReviewDate; }
    public void setNextReviewDate(java.time.LocalDate nextReviewDate) { this.nextReviewDate = nextReviewDate; }
    public Integer getRetentionYears() { return retentionYears; }
    public void setRetentionYears(Integer retentionYears) { this.retentionYears = retentionYears; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
