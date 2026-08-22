package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.ContentSnapshotJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContentSnapshotJpaRepository extends JpaRepository<ContentSnapshotJpaEntity, UUID> {
    Optional<ContentSnapshotJpaEntity> findByContentHash(String contentHash);
}
