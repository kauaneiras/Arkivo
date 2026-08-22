package com.labdms.domain.exception;

public class UnauthorizedAccessException extends DomainException {

    public UnauthorizedAccessException() {
        super("access.denied");
    }

    public UnauthorizedAccessException(String reason) {
        super("access.denied", reason);
    }
}
