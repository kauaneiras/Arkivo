package com.labdms.domain.exception;

import java.util.UUID;

public class ReviewNotFoundException extends DomainException {

    public ReviewNotFoundException(UUID reviewId) {
        super("review.not.found", reviewId);
    }
}
