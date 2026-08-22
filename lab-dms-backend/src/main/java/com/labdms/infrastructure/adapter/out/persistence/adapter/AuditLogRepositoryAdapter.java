package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.adapter.out.persistence.entity.AuditLogJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.AuditLogJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
public class AuditLogRepositoryAdapter implements AuditLogPort {

    private final AuditLogJpaRepository repository;
    private final ObjectMapper objectMapper;

    public AuditLogRepositoryAdapter(AuditLogJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void log(AuditEntry entry) {
        AuditLogJpaEntity entity = new AuditLogJpaEntity();
        entity.setUserId(entry.getUserId());
        entity.setAction(entry.getAction());
        entity.setEntityType(entry.getEntityType());
        entity.setEntityId(entry.getEntityId());
        try {
            entity.setDetails(objectMapper.writeValueAsString(entry.getDetails()));
        } catch (Exception e) {
            entity.setDetails("{}");
        }
        entity.setIpAddress(entry.getIpAddress());
        entity.setCreatedAt(LocalDateTime.ofInstant(entry.getCreatedAt(), ZoneOffset.UTC));
        repository.save(entity);
    }
}
