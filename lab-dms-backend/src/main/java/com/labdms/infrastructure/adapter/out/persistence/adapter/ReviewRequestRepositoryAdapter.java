package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.review.ReviewRequest;
import com.labdms.domain.model.review.ReviewRequestStatus;
import com.labdms.domain.port.out.ReviewRequestRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.ReviewRequestJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.ReviewRequestJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReviewRequestRepositoryAdapter implements ReviewRequestRepository {

    private final ReviewRequestJpaRepository repository;

    public ReviewRequestRepositoryAdapter(ReviewRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReviewRequest save(ReviewRequest reviewRequest) {
        ReviewRequestJpaEntity entity = new ReviewRequestJpaEntity();
        entity.setId(reviewRequest.getId());
        entity.setDocumentId(reviewRequest.getDocumentId());
        entity.setTitle(reviewRequest.getTitle());
        entity.setDescription(reviewRequest.getDescription());
        entity.setRequestedBy(reviewRequest.getRequestedBy());
        entity.setSourceBranchId(reviewRequest.getSourceBranchId());
        entity.setTargetBranchId(reviewRequest.getSourceBranchId()); // dummy for now
        entity.setStatus(reviewRequest.getStatus().name());
        entity.setCreatedAt(LocalDateTime.ofInstant(reviewRequest.getCreatedAt(), ZoneOffset.UTC));
        entity.setResolvedAt(LocalDateTime.ofInstant(reviewRequest.getUpdatedAt(), ZoneOffset.UTC));
        ReviewRequestJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<ReviewRequest> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public List<ReviewRequest> findByDocumentId(UUID documentId) {
        return repository.findAll().stream().map(this::toDomain).filter(r -> r.getDocumentId().equals(documentId)).toList();
    }

    @Override
    public List<ReviewRequest> findByStatus(ReviewRequestStatus status) {
        return repository.findAll().stream().map(this::toDomain).filter(r -> r.getStatus() == status).toList();
    }

    @Override
    public Optional<ReviewRequest> findBySourceBranchIdAndStatus(UUID sourceBranchId, ReviewRequestStatus status) {
        return repository.findAll().stream().map(this::toDomain)
            .filter(r -> r.getSourceBranchId().equals(sourceBranchId) && r.getStatus() == status).findFirst();
    }

    private ReviewRequest toDomain(ReviewRequestJpaEntity entity) {
        return new ReviewRequest(
                entity.getId(),
                entity.getDocumentId(),
                entity.getSourceBranchId(),
                entity.getTitle(),
                entity.getDescription(),
                ReviewRequestStatus.valueOf(entity.getStatus()),
                entity.getRequestedBy(),
                entity.getCreatedAt().toInstant(ZoneOffset.UTC),
                entity.getResolvedAt() != null ? entity.getResolvedAt().toInstant(ZoneOffset.UTC) : entity.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
