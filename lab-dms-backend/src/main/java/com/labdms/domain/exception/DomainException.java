package com.labdms.domain.exception;

/**
 * Base exception for all domain-specific errors.
 * Carries a message key for i18n resolution.
 */
public class DomainException extends RuntimeException {

    private final String messageKey;
    private final Object[] args;

    public DomainException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }

    public DomainException(String messageKey, Throwable cause, Object... args) {
        super(messageKey, cause);
        this.messageKey = messageKey;
        this.args = args;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getArgs() {
        return args;
    }
}
