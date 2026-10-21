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

/**
 * Review requests, read by indexed queries.
 *
 * <p>Every lookup used to be {@code findAll()} filtered in Java — the whole
 * table on every "is there an open request for this document?" question.
 */
@Repository
public class ReviewRequestRepositoryAdapter implements ReviewRequestRepository {

    private final ReviewRequestJpaRepository repository;

    public ReviewRequestRepositoryAdapter(ReviewRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReviewRequest save(ReviewRequest reviewRequest) {
        ReviewRequestJpaEntity entity = repository.findById(reviewRequest.getId())
                .orElseGet(ReviewRequestJpaEntity::new);
        entity.setId(reviewRequest.getId());
        entity.setDocumentId(reviewRequest.getDocumentId());
        entity.setTitle(reviewRequest.getTitle());
        entity.setDescription(reviewRequest.getDescription());
        entity.setRequestedBy(reviewRequest.getRequestedBy());
        entity.setSourceBranchId(reviewRequest.getSourceBranchId());
        entity.setTargetBranchId(reviewRequest.getSourceBranchId()); // single-branch workflow
        entity.setCommitId(reviewRequest.getCommitId());
        entity.setStatus(reviewRequest.getStatus().name());
        entity.setCreatedAt(LocalDateTime.ofInstant(reviewRequest.getCreatedAt(), ZoneOffset.UTC));
        // resolved_at is the moment of the decision; an OPEN request has none.
        entity.setResolvedAt(reviewRequest.getStatus() == ReviewRequestStatus.OPEN ? null
                : LocalDateTime.ofInstant(reviewRequest.getUpdatedAt(), ZoneOffset.UTC));
        ReviewRequestJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<ReviewRequest> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public List<ReviewRequest> findByDocumentId(UUID documentId) {
        return repository.findByDocumentIdOrderByCreatedAtDesc(documentId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ReviewRequest> findByStatus(ReviewRequestStatus status) {
        return repository.findByStatusOrderByCreatedAtDesc(status.name()).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ReviewRequest> findBySourceBranchIdAndStatus(UUID sourceBranchId, ReviewRequestStatus status) {
        return repository.findFirstBySourceBranchIdAndStatusOrderByCreatedAtDesc(sourceBranchId, status.name())
                .map(this::toDomain);
    }

    @Override
    public Optional<ReviewRequest> findOpenByDocumentId(UUID documentId) {
        return repository.findFirstByDocumentIdAndStatus(documentId, ReviewRequestStatus.OPEN.name())
                .map(this::toDomain);
    }

    private ReviewRequest toDomain(ReviewRequestJpaEntity entity) {
        ReviewRequest rr = new ReviewRequest(
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
        rr.setCommitId(entity.getCommitId());
        return rr;
    }
}
