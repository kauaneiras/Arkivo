package com.labdms.infrastructure.adapter.out.persistence.mapper;

import com.labdms.domain.model.user.User;
import com.labdms.domain.model.user.UserStatus;
import com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class UserPersistenceMapper {

    public User toDomain(UserJpaEntity entity) {
        if (entity == null) return null;

        return new User(
            entity.getId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getPasswordHash(),
            entity.getFullName(),
            UserStatus.valueOf(entity.getStatus()),
            entity.getCreatedAt() != null ? entity.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null,
            entity.getUpdatedAt() != null ? entity.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC) : null,
            entity.getLastLogin() != null ? entity.getLastLogin().toInstant(java.time.ZoneOffset.UTC) : null
        );
    }

    public UserJpaEntity toJpaEntity(User domain) {
        if (domain == null) return null;

        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(domain.getId());
        entity.setUsername(domain.getUsername());
        entity.setEmail(domain.getEmail());
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setFullName(domain.getFullName());
        entity.setStatus(domain.getStatus().name());
        entity.setCreatedAt(domain.getCreatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getCreatedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setUpdatedAt(domain.getUpdatedAt() != null ? java.time.LocalDateTime.ofInstant(domain.getUpdatedAt(), java.time.ZoneOffset.UTC) : null);
        entity.setLastLogin(domain.getLastLogin() != null ? java.time.LocalDateTime.ofInstant(domain.getLastLogin(), java.time.ZoneOffset.UTC) : null);

        return entity;
    }
}
