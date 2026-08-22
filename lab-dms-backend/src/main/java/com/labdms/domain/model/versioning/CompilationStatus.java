package com.labdms.domain.model.versioning;

/**
 * Status of a LaTeX compilation job.
 */
public enum CompilationStatus {

    PENDING("Pending"),
    SUCCESS("Success"),
    ERROR("Error");

    private final String displayName;

    CompilationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
