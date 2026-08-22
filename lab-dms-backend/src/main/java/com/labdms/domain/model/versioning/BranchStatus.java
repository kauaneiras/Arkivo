package com.labdms.domain.model.versioning;

/**
 * Status of a document branch.
 */
public enum BranchStatus {

    OPEN("Open"),
    MERGED("Merged"),
    CLOSED("Closed");

    private final String displayName;

    BranchStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
