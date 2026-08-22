package com.labdms.domain.model.user;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a role that groups permissions together.
 * Users are assigned roles which grant them permissions.
 */
public class Role {

    private UUID id;
    private String name;
    private String description;
    private Set<Permission> permissions;

    public Role() {
        this.permissions = new HashSet<>();
    }

    public Role(UUID id, String name, String description, Set<Permission> permissions) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.permissions = permissions != null ? new HashSet<>(permissions) : new HashSet<>();
    }

    public void addPermission(Permission permission) {
        this.permissions.add(permission);
    }

    public boolean hasPermission(String code) {
        return permissions.stream().anyMatch(p -> p.getCode().equals(code));
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions != null ? new HashSet<>(permissions) : new HashSet<>();
    }
}
