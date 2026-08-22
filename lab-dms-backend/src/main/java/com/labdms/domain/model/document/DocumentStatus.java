package com.labdms.domain.model.document;

/**
 * Lifecycle status of a laboratory document.
 */
public enum DocumentStatus {

    DRAFT("Draft"),
    IN_REVIEW("In Review"),
    ACTIVE("Active"),
    OBSOLETE("Obsolete");

    private final String displayName;

    DocumentStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
