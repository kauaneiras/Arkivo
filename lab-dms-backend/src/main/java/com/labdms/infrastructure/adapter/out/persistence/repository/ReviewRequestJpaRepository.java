package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.ReviewRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRequestJpaRepository extends JpaRepository<ReviewRequestJpaEntity, UUID> {
    List<ReviewRequestJpaEntity> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);
    List<ReviewRequestJpaEntity> findByStatusOrderByCreatedAtDesc(String status);
    Optional<ReviewRequestJpaEntity> findFirstBySourceBranchIdAndStatusOrderByCreatedAtDesc(UUID sourceBranchId, String status);
    Optional<ReviewRequestJpaEntity> findFirstByDocumentIdAndStatus(UUID documentId, String status);
}
