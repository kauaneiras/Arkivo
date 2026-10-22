package com.labdms.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "commits")
public class CommitJpaEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "parent_commit_id")
    private UUID parentCommitId;

    // Nullable since V9: a multi-file version has no single-string snapshot.
    @Column(name = "content_snapshot_id")
    private UUID contentSnapshotId;

    @Column(name = "is_draft", nullable = false)
    private boolean draft;

    @Column(name = "change_reason", columnDefinition = "TEXT")
    private String changeReason;

    @Column(name = "sha256_hash", unique = true, nullable = false, length = 64)
    private String sha256Hash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    // Updatable: closing a draft stamps it with the moment it was saved as a
    // version, which is the date the history shows and the order it sorts by.
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public UUID getBranchId() { return branchId; }
    public void setBranchId(UUID branchId) { this.branchId = branchId; }
    public UUID getParentCommitId() { return parentCommitId; }
    public void setParentCommitId(UUID parentCommitId) { this.parentCommitId = parentCommitId; }
    public UUID getContentSnapshotId() { return contentSnapshotId; }
    public void setContentSnapshotId(UUID contentSnapshotId) { this.contentSnapshotId = contentSnapshotId; }
    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID authorId) { this.authorId = authorId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
