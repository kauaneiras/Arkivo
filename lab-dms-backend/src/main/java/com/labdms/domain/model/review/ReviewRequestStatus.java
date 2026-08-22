package com.labdms.domain.model.review;

/**
 * Status of a review request.
 */
public enum ReviewRequestStatus {

    OPEN("Open"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    MERGED("Merged");

    private final String displayName;

    ReviewRequestStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
