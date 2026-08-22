package com.labdms.domain.port.out;

import com.labdms.domain.model.versioning.Branch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for branch persistence operations.
 */
public interface BranchRepository {

    Branch save(Branch branch);

    Optional<Branch> findById(UUID id);

    Optional<Branch> findDefaultByDocumentId(UUID documentId);

    List<Branch> findByDocumentId(UUID documentId);

    boolean existsByDocumentIdAndName(UUID documentId, String name);
}
