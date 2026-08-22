package com.labdms.infrastructure.adapter.out.persistence.mapper;

import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.BranchStatus;
import com.labdms.infrastructure.adapter.out.persistence.entity.BranchJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class BranchPersistenceMapper {

    /**
     * Tolerant status parsing. A single unrecognised value in the database used to
     * throw IllegalArgumentException from deep inside a repository call, surfacing
     * as an opaque 500 on unrelated endpoints. A branch whose status we cannot
     * interpret is treated as OPEN — the safe default, since OPEN grants no extra
     * rights (merging still requires an approved review).
     */
    private static BranchStatus parseStatus(String raw) {
        if (raw == null) return BranchStatus.OPEN;
        try {
            return BranchStatus.valueOf(raw);
        } catch (IllegalArgumentException unknownLegacyValue) {
            return BranchStatus.OPEN;
        }
    }

    public Branch toDomain(BranchJpaEntity entity) {
        if (entity == null) return null;

        return new Branch(
            entity.getId(),
            entity.getDocumentId(),
            entity.getName(),
            entity.getHeadCommitId(),
            entity.isDefault(),
            parseStatus(entity.getStatus()),
            entity.getCreatedBy(),
            entity.getCreatedAt() != null ? entity.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null,
            entity.getUpdatedAt() != null ? entity.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC) : null
        );
    }

    public BranchJpaEntity toJpaEntity(Branch domain) {
        if (domain == null) return null;

        BranchJpaEntity entity = new BranchJpaEntity();
        entity.setId(domain.getId());
        entity.setDocumentId(domain.getDocumentId());
        entity.setName(domain.getName());
        entity.setHeadCommitId(domain.getHeadCommitId());
        entity.setDefault(domain.isDefault());
        entity.setStatus(domain.getStatus().name());
        entity.setCreatedBy(domain.getCreatedBy());
        entity.setCreatedAt(domain.getCreatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getCreatedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setUpdatedAt(domain.getUpdatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getUpdatedAt(), java.time.ZoneOffset.UTC) : null);

        return entity;
    }
}
