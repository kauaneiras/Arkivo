package com.labdms.domain.model.audit;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit trail entry for ISO 17025 compliance.
 * Records every significant action performed in the system.
 */
public class AuditEntry {

    private UUID id;
    private UUID userId;
    private String action;
    private String entityType;
    private UUID entityId;
    private Map<String, Object> details;
    private String ipAddress;
    private Instant createdAt;

    public AuditEntry() {
        this.details = new HashMap<>();
    }

    public AuditEntry(UUID id, UUID userId, String action, String entityType, UUID entityId,
                      Map<String, Object> details, String ipAddress, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details != null ? new HashMap<>(details) : new HashMap<>();
        this.ipAddress = ipAddress;
        this.createdAt = createdAt;
    }

    public static AuditEntry create(UUID userId, String action, String entityType,
                                    UUID entityId, Map<String, Object> details, String ipAddress) {
        AuditEntry entry = new AuditEntry();
        entry.id = UUID.randomUUID();
        entry.userId = userId;
        entry.action = action;
        entry.entityType = entityType;
        entry.entityId = entityId;
        entry.details = details != null ? new HashMap<>(details) : new HashMap<>();
        entry.ipAddress = ipAddress;
        entry.createdAt = Instant.now();
        return entry;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details != null ? new HashMap<>(details) : new HashMap<>();
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
