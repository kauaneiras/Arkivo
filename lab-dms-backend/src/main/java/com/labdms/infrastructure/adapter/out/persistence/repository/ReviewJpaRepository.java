package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.ReviewJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewJpaRepository extends JpaRepository<ReviewJpaEntity, UUID> {
    List<ReviewJpaEntity> findByReviewRequestIdOrderByCreatedAtDesc(UUID reviewRequestId);
}
