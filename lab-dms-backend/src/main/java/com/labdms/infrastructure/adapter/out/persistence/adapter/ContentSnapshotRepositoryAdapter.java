package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.domain.port.out.ContentSnapshotRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.ContentSnapshotJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.ContentSnapshotJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class ContentSnapshotRepositoryAdapter implements ContentSnapshotRepository {

    private final ContentSnapshotJpaRepository repository;

    public ContentSnapshotRepositoryAdapter(ContentSnapshotJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ContentSnapshot save(ContentSnapshot snapshot) {
        ContentSnapshotJpaEntity entity = new ContentSnapshotJpaEntity();
        entity.setId(snapshot.getId());
        entity.setLatexSource(snapshot.getLatexSource());
        entity.setContentHash(snapshot.getContentHash());
        entity.setSizeBytes(snapshot.getSizeBytes());
        entity.setCreatedAt(snapshot.getCreatedAt() != null ? java.time.LocalDateTime.ofInstant(snapshot.getCreatedAt(), java.time.ZoneOffset.UTC) : null);

        ContentSnapshotJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<ContentSnapshot> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<ContentSnapshot> findByContentHash(String contentHash) {
        return repository.findByContentHash(contentHash).map(this::toDomain);
    }

    private ContentSnapshot toDomain(ContentSnapshotJpaEntity entity) {
        if (entity == null) return null;
        return new ContentSnapshot(
            entity.getId(),
            entity.getLatexSource(),
            entity.getContentHash(),
            entity.getSizeBytes(),
            entity.getCreatedAt() != null ? entity.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null
        );
    }
}
