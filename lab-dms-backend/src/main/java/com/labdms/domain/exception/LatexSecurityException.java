package com.labdms.domain.exception;

public class LatexSecurityException extends DomainException {

    private final String detail;

    public LatexSecurityException(String forbiddenCommand) {
        super("latex.security.violation", forbiddenCommand);
        this.detail = forbiddenCommand;
    }

    /** Human-readable description of what was rejected (command + reason + line). */
    public String getDetail() {
        return detail;
    }
}
