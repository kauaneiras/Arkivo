package com.labdms.application.dto.response;

import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.BranchStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for branch data.
 */
public record BranchResponse(
        UUID id,
        UUID documentId,
        String name,
        UUID headCommitId,
        boolean isDefault,
        BranchStatus status,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static BranchResponse from(Branch branch) {
        return new BranchResponse(
                branch.getId(),
                branch.getDocumentId(),
                branch.getName(),
                branch.getHeadCommitId(),
                branch.isDefault(),
                branch.getStatus(),
                branch.getCreatedBy(),
                branch.getCreatedAt(),
                branch.getUpdatedAt()
        );
    }
}
