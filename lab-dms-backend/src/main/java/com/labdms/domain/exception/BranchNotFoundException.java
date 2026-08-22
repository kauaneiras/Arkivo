package com.labdms.domain.exception;

import java.util.UUID;

public class BranchNotFoundException extends DomainException {

    public BranchNotFoundException(UUID branchId) {
        super("branch.not.found", branchId);
    }
}
