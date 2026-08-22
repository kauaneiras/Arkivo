package com.labdms.infrastructure.adapter.out.persistence.repository;

import com.labdms.infrastructure.adapter.out.persistence.entity.AuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, Long> {
    List<AuditLogJpaEntity> findByEntityIdOrderByCreatedAtDesc(UUID entityId);
    List<AuditLogJpaEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
