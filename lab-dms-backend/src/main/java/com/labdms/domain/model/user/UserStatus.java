package com.labdms.domain.model.user;

/**
 * Status of a user account.
 */
public enum UserStatus {

    PENDING("Pending"),
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    LOCKED("Locked");

    private final String displayName;

    UserStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
