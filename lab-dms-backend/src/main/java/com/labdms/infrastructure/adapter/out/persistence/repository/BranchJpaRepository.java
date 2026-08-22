package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.BranchJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BranchJpaRepository extends JpaRepository<BranchJpaEntity, UUID> {
    List<BranchJpaEntity> findByDocumentId(UUID documentId);
    Optional<BranchJpaEntity> findByDocumentIdAndName(UUID documentId, String name);
    Optional<BranchJpaEntity> findByDocumentIdAndIsDefaultTrue(UUID documentId);
}
