package com.labdms.domain.model.versioning;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single version (commit) of a document.
 * Similar to a Git commit: points to a content snapshot and optionally to a parent commit.
 */
public class Commit {

    private UUID id;
    private UUID documentId;
    private UUID branchId;
    private UUID parentCommitId;
    private UUID contentSnapshotId;
    private String sha256Hash;
    private String message;
    private UUID authorId;
    private Instant createdAt;
    /**
     * A working revision ("rascunho"): the only commit whose tree may be
     * rewritten in place. Everything else in the history is immutable.
     *
     * <p>Git keeps the working tree outside the history; here it lives in the
     * same table because the tree storage is keyed by commit. The flag is what
     * keeps the two meanings apart: a draft is where editing happens, a closed
     * commit is a quality record.
     */
    private boolean draft;
    /** ISO/IEC 17025 8.3.2 c) justification. Null while the revision is a draft. */
    private String changeReason;

    public Commit() {
    }

    public Commit(UUID id, UUID documentId, UUID branchId, UUID parentCommitId,
                  UUID contentSnapshotId, String sha256Hash, String message,
                  UUID authorId, Instant createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.branchId = branchId;
        this.parentCommitId = parentCommitId;
        this.contentSnapshotId = contentSnapshotId;
        this.sha256Hash = sha256Hash;
        this.message = message;
        this.authorId = authorId;
        this.createdAt = createdAt;
    }

    /**
     * Factory method to create a new commit.
     *
     * @param documentId       the document this commit belongs to
     * @param branchId         the branch this commit is on
     * @param parentCommitId   the parent commit (null for initial commit)
     * @param contentSnapshotId the content snapshot referenced by this commit
     * @param contentHash      SHA-256 hash of the content
     * @param message          commit message describing the change
     * @param authorId         the user who authored this commit
     */
    public static Commit create(UUID documentId, UUID branchId, UUID parentCommitId,
                                UUID contentSnapshotId, String contentHash,
                                String message, UUID authorId) {
        Commit commit = new Commit();
        commit.id = UUID.randomUUID();
        commit.documentId = documentId;
        commit.branchId = branchId;
        commit.parentCommitId = parentCommitId;
        commit.contentSnapshotId = contentSnapshotId;
        commit.message = message;
        commit.authorId = authorId;
        commit.createdAt = Instant.now();
        // The commit hash identifies the COMMIT, not the content (Git semantics).
        // Storing the content hash here would break the UNIQUE constraint whenever
        // two commits share content — e.g. two documents created from the same
        // template, or restoring an older version.
        commit.sha256Hash = computeCommitHash(commit, contentHash);
        return commit;
    }

    /**
     * Opens a working revision on top of {@code parentCommitId}.
     *
     * <p>The message is a placeholder: a draft has no message of its own until
     * it is closed by {@link #close}. The content hash is the parent's, which is
     * correct at the moment the draft is born — its tree IS the parent's tree —
     * and is recomputed when the draft is closed.
     */
    public static Commit openDraft(UUID documentId, UUID branchId, UUID parentCommitId,
                                   UUID contentSnapshotId, String contentHash, UUID authorId) {
        Commit commit = create(documentId, branchId, parentCommitId, contentSnapshotId,
                contentHash, "Alterações em andamento", authorId);
        commit.draft = true;
        return commit;
    }

    /**
     * Turns a draft into a closed, immutable version.
     *
     * <p>Author and time are those of the person who saves, not of whoever
     * opened the draft: the record is "who declared this revision done".
     */
    public void close(String message, String changeReason, UUID authorId,
                      UUID contentSnapshotId, String contentHash) {
        if (!draft) {
            throw new IllegalStateException("Only a draft can be closed");
        }
        this.message = message;
        this.changeReason = changeReason;
        this.authorId = authorId;
        this.contentSnapshotId = contentSnapshotId;
        this.createdAt = Instant.now();
        this.draft = false;
        this.sha256Hash = computeCommitHash(this, contentHash);
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    /** SHA-256 over the commit identity: content + parent + author + message + time. */
    private static String computeCommitHash(Commit commit, String contentHash) {
        String payload = String.join("\n",
                String.valueOf(commit.id),
                String.valueOf(commit.documentId),
                String.valueOf(commit.branchId),
                String.valueOf(commit.parentCommitId),
                String.valueOf(contentHash),
                String.valueOf(commit.message),
                String.valueOf(commit.authorId),
                String.valueOf(commit.createdAt));
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is always available; the UUID keeps uniqueness if it ever isn't
            return commit.id.toString().replace("-", "");
        }
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

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public UUID getParentCommitId() {
        return parentCommitId;
    }

    public void setParentCommitId(UUID parentCommitId) {
        this.parentCommitId = parentCommitId;
    }

    public UUID getContentSnapshotId() {
        return contentSnapshotId;
    }

    public void setContentSnapshotId(UUID contentSnapshotId) {
        this.contentSnapshotId = contentSnapshotId;
    }

    public String getSha256Hash() {
        return sha256Hash;
    }

    public void setSha256Hash(String sha256Hash) {
        this.sha256Hash = sha256Hash;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
