package com.labdms.infrastructure.adapter.out.persistence.mapper;

import com.labdms.domain.model.versioning.Commit;
import com.labdms.infrastructure.adapter.out.persistence.entity.CommitJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class CommitPersistenceMapper {

    public Commit toDomain(CommitJpaEntity entity) {
        if (entity == null) return null;

        return new Commit(
            entity.getId(),
            entity.getDocumentId(),
            entity.getBranchId(),
            entity.getParentCommitId(),
            entity.getContentSnapshotId(),
            entity.getSha256Hash(),
            entity.getMessage(),
            entity.getAuthorId(),
            entity.getCreatedAt() != null ? entity.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null
        );
    }

    public CommitJpaEntity toJpaEntity(Commit domain) {
        if (domain == null) return null;

        CommitJpaEntity entity = new CommitJpaEntity();
        entity.setId(domain.getId());
        entity.setDocumentId(domain.getDocumentId());
        entity.setBranchId(domain.getBranchId());
        entity.setParentCommitId(domain.getParentCommitId());
        entity.setContentSnapshotId(domain.getContentSnapshotId());
        entity.setSha256Hash(domain.getSha256Hash());
        entity.setMessage(domain.getMessage());
        entity.setAuthorId(domain.getAuthorId());
        entity.setCreatedAt(domain.getCreatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getCreatedAt(), java.time.ZoneOffset.UTC) : null);

        return entity;
    }
}
