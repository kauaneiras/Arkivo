package com.labdms.domain.model.versioning;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a branch in the document's version history.
 * Similar to Git branches: each branch has a head commit and can be merged.
 */
public class Branch {

    private UUID id;
    private UUID documentId;
    private String name;
    private UUID headCommitId;
    private boolean isDefault;
    private BranchStatus status;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Branch() {
    }

    public Branch(UUID id, UUID documentId, String name, UUID headCommitId,
                  boolean isDefault, BranchStatus status, UUID createdBy,
                  Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.documentId = documentId;
        this.name = name;
        this.headCommitId = headCommitId;
        this.isDefault = isDefault;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Factory method to create the default 'main' branch.
     */
    public static Branch createDefault(UUID documentId, UUID createdBy) {
        Branch branch = new Branch();
        branch.id = UUID.randomUUID();
        branch.documentId = documentId;
        branch.name = "main";
        branch.isDefault = true;
        branch.status = BranchStatus.OPEN;
        branch.createdBy = createdBy;
        branch.createdAt = Instant.now();
        branch.updatedAt = Instant.now();
        return branch;
    }

    /**
     * Factory method to create a feature branch from the current head of another branch.
     */
    public static Branch create(UUID documentId, String name, UUID headCommitId, UUID createdBy) {
        Branch branch = new Branch();
        branch.id = UUID.randomUUID();
        branch.documentId = documentId;
        branch.name = name;
        branch.headCommitId = headCommitId;
        branch.isDefault = false;
        branch.status = BranchStatus.OPEN;
        branch.createdBy = createdBy;
        branch.createdAt = Instant.now();
        branch.updatedAt = Instant.now();
        return branch;
    }

    /**
     * Advances the head commit pointer to a new commit.
     */
    public void advanceHead(UUID newCommitId) {
        this.headCommitId = newCommitId;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = BranchStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    public void markMerged() {
        this.status = BranchStatus.MERGED;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getHeadCommitId() {
        return headCommitId;
    }

    public void setHeadCommitId(UUID headCommitId) {
        this.headCommitId = headCommitId;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public BranchStatus getStatus() {
        return status;
    }

    public void setStatus(BranchStatus status) {
        this.status = status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
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
