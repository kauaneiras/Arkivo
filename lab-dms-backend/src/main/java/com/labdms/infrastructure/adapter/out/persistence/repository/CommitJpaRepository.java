package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.CommitJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommitJpaRepository extends JpaRepository<CommitJpaEntity, UUID> {
    List<CommitJpaEntity> findByBranchIdOrderByCreatedAtDesc(UUID branchId);
    List<CommitJpaEntity> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);
}
