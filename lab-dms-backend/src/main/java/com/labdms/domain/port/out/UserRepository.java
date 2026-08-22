package com.labdms.domain.port.out;

import com.labdms.domain.model.user.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for user persistence operations.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<String> findUserPermissions(UUID userId);

    /**
     * Checks if a user has access to a specific document group.
     */
    boolean hasGroupAccess(UUID userId, UUID documentGroupId);
}
