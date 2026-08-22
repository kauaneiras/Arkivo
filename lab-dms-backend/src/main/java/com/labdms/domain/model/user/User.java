package com.labdms.domain.model.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity representing a system user.
 */
public class User {

    private UUID id;
    private String username;
    private String email;
    private String passwordHash;
    private String fullName;
    private UserStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLogin;

    public User() {
    }

    public User(UUID id, String username, String email, String passwordHash,
                String fullName, UserStatus status, Instant createdAt,
                Instant updatedAt, Instant lastLogin) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLogin = lastLogin;
    }

    public static User create(String username, String email, String passwordHash, String fullName) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.username = username;
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.status = UserStatus.PENDING;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        return user;
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void lock() {
        this.status = UserStatus.LOCKED;
        this.updatedAt = Instant.now();
    }

    public void recordLogin() {
        this.lastLogin = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(Instant lastLogin) {
        this.lastLogin = lastLogin;
    }
}
