package com.labdms.domain.model.user;

import java.util.UUID;

/**
 * Represents a granular permission that can be assigned to roles.
 */
public class Permission {

    private UUID id;
    private String code;
    private String description;

    public Permission() {
    }

    public Permission(UUID id, String code, String description) {
        this.id = id;
        this.code = code;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
