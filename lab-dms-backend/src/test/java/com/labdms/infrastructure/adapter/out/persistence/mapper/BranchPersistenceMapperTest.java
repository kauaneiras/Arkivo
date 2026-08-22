package com.labdms.infrastructure.adapter.out.persistence.mapper;

import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.BranchStatus;
import com.labdms.infrastructure.adapter.out.persistence.entity.BranchJpaEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for the incident where the data seeder wrote
 * {@code branches.status = 'ACTIVE'} — a value that does not exist in
 * {@link BranchStatus} (OPEN / MERGED / CLOSED). Every attempt to save a new
 * version of a seeded document loaded that branch through JPA and blew up with
 * IllegalArgumentException, surfacing as an opaque 500.
 */
class BranchPersistenceMapperTest {

    private final BranchPersistenceMapper mapper = new BranchPersistenceMapper();

    private BranchJpaEntity entityWithStatus(String status) {
        BranchJpaEntity entity = new BranchJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setDocumentId(UUID.randomUUID());
        entity.setName("main");
        entity.setDefault(true);
        entity.setStatus(status);
        entity.setCreatedBy(UUID.randomUUID());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    @Test
    void legacyActiveStatusDoesNotBlowUpAndFallsBackToOpen() {
        BranchJpaEntity legacy = entityWithStatus("ACTIVE");

        assertThatCode(() -> mapper.toDomain(legacy)).doesNotThrowAnyException();
        assertThat(mapper.toDomain(legacy).getStatus()).isEqualTo(BranchStatus.OPEN);
    }

    @Test
    void nullStatusFallsBackToOpen() {
        assertThat(mapper.toDomain(entityWithStatus(null)).getStatus()).isEqualTo(BranchStatus.OPEN);
    }

    @Test
    void validStatusesArePreserved() {
        assertThat(mapper.toDomain(entityWithStatus("OPEN")).getStatus()).isEqualTo(BranchStatus.OPEN);
        assertThat(mapper.toDomain(entityWithStatus("MERGED")).getStatus()).isEqualTo(BranchStatus.MERGED);
        assertThat(mapper.toDomain(entityWithStatus("CLOSED")).getStatus()).isEqualTo(BranchStatus.CLOSED);
    }

    @Test
    void defaultBranchIsCreatedWithAStatusTheMapperCanRoundTrip() {
        // The contract the seeder must honour: whatever Branch.createDefault()
        // produces is exactly what belongs in the branches.status column.
        Branch created = Branch.createDefault(UUID.randomUUID(), UUID.randomUUID());
        assertThat(created.getStatus()).isEqualTo(BranchStatus.OPEN);

        BranchJpaEntity persisted = mapper.toJpaEntity(created);
        assertThat(persisted.getStatus()).isEqualTo("OPEN");
        assertThat(mapper.toDomain(persisted).getStatus()).isEqualTo(BranchStatus.OPEN);
    }
}
