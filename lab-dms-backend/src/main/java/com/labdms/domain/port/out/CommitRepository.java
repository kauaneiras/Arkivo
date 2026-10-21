package com.labdms.domain.port.out;

import com.labdms.domain.model.versioning.Commit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for commit persistence operations.
 */
public interface CommitRepository {

    Commit save(Commit commit);

    Optional<Commit> findById(UUID id);

    List<Commit> findByBranchId(UUID branchId);

    /** Newest first. */
    List<Commit> findByDocumentId(UUID documentId);

    Optional<Commit> findLatestByBranchId(UUID branchId);

    /**
     * Removes a commit row. Only ever called for a draft being discarded: a
     * closed commit is part of the history and is never deleted.
     */
    void delete(UUID id);
}
