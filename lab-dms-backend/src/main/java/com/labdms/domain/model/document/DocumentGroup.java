package com.labdms.domain.model.document;

import java.util.UUID;

/**
 * Represents a logical grouping of documents (e.g., by laboratory section or department).
 * Used for access control: users are granted access to specific groups.
 */
public class DocumentGroup {

    private UUID id;
    private String name;
    private String description;

    public DocumentGroup() {
    }

    public DocumentGroup(UUID id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public static DocumentGroup create(String name, String description) {
        DocumentGroup group = new DocumentGroup();
        group.id = UUID.randomUUID();
        group.name = name;
        group.description = description;
        return group;
    }

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
}
