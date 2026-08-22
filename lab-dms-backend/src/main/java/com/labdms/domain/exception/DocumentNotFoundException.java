package com.labdms.domain.exception;

import java.util.UUID;

public class DocumentNotFoundException extends DomainException {

    public DocumentNotFoundException(UUID documentId) {
        super("document.not.found", documentId);
    }
}
