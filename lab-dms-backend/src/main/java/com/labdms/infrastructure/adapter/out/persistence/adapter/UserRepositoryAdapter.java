package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.user.User;
import com.labdms.domain.port.out.UserRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.mapper.UserPersistenceMapper;
import com.labdms.infrastructure.adapter.out.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository repository;
    private final UserPersistenceMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public UserRepositoryAdapter(UserJpaRepository repository, UserPersistenceMapper mapper,
                                 org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = mapper.toJpaEntity(user);
        UserJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return repository.findByUsername(username).map(mapper::toDomain);
    }
    
    @Override
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email).map(mapper::toDomain);
    }

    @Override
    public List<String> findUserPermissions(UUID userId) {
        return repository.findUserPermissions(userId);
    }

    @Override
    public boolean existsByUsername(String username) {
        return repository.findByUsername(username).isPresent();
    }

    @Override
    public boolean existsByEmail(String email) {
        return repository.findByEmail(email).isPresent();
    }

    /**
     * Whether the user belongs to the document's sector.
     *
     * <p>This returned a hardcoded {@code true}, which made every sector check
     * in the system pass: the column existed, the calls were there, and none of
     * it restricted anything. It is a real membership lookup now.
     *
     * <p>A document with NO sector is not restricted by sector, so that case
     * answers true — otherwise adopting this check would suddenly hide every
     * sector-less document from everyone without {@code DOCUMENT_VIEW_ALL}.
     * Whether such a document is visible at all is decided by its visibility
     * level, in {@code DocumentVisibilityService}.
     */
    @Override
    public boolean hasGroupAccess(UUID userId, UUID documentGroupId) {
        if (documentGroupId == null) return true;
        if (userId == null) return false;
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_group_members WHERE user_id = ? AND group_id = ?",
                Integer.class, userId, documentGroupId);
        return n != null && n > 0;
    }
}
