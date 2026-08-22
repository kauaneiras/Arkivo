package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.review.Review;
import com.labdms.domain.port.out.ReviewRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.ReviewJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.ReviewJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReviewRepositoryAdapter implements ReviewRepository {

    private final ReviewJpaRepository repository;

    public ReviewRepositoryAdapter(ReviewJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Review save(Review review) {
        ReviewJpaEntity entity = new ReviewJpaEntity();
        entity.setId(review.getId());
        entity.setReviewRequestId(review.getReviewRequestId());
        entity.setReviewerId(review.getReviewerId());
        entity.setDecision(review.getDecision().name());
        entity.setComments(review.getComments());
        entity.setCreatedAt(LocalDateTime.ofInstant(review.getCreatedAt(), ZoneOffset.UTC));
        ReviewJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Review> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Review> findByReviewRequestId(UUID reviewRequestId) {
        return repository.findAll().stream().map(this::toDomain).filter(r -> r.getReviewRequestId().equals(reviewRequestId)).toList();
    }

    private Review toDomain(ReviewJpaEntity saved) {
        return new Review(
            saved.getId(),
            saved.getReviewRequestId(),
            saved.getReviewerId(),
            com.labdms.domain.model.review.ReviewDecision.valueOf(saved.getDecision()),
            saved.getComments(),
            saved.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
