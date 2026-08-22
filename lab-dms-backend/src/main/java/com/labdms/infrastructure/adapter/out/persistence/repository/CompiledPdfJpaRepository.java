package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.CompiledPdfJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompiledPdfJpaRepository extends JpaRepository<CompiledPdfJpaEntity, UUID> {
    Optional<CompiledPdfJpaEntity> findByCommitId(UUID commitId);

    void deleteByCommitId(UUID commitId);
}
