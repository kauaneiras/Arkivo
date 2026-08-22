package com.labdms.application.dto.response;

import com.labdms.domain.model.versioning.Commit;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for commit data.
 */
public record CommitResponse(
        UUID id,
        UUID documentId,
        UUID branchId,
        UUID parentCommitId,
        UUID contentSnapshotId,
        String sha256Hash,
        String message,
        UUID authorId,
        Instant createdAt
) {
    public static CommitResponse from(Commit commit) {
        return new CommitResponse(
                commit.getId(),
                commit.getDocumentId(),
                commit.getBranchId(),
                commit.getParentCommitId(),
                commit.getContentSnapshotId(),
                commit.getSha256Hash(),
                commit.getMessage(),
                commit.getAuthorId(),
                commit.getCreatedAt()
        );
    }
}
