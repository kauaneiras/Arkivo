package com.labdms.domain.model.review;

/**
 * Decision made by a reviewer on a review request.
 */
public enum ReviewDecision {

    APPROVED("Approved"),
    CHANGES_REQUESTED("Changes Requested"),
    REJECTED("Rejected");

    private final String displayName;

    ReviewDecision(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
