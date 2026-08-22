package com.labdms.domain.exception;

public class BranchConflictException extends DomainException {

    public BranchConflictException(String branchName) {
        super("branch.conflict", branchName);
    }
}
