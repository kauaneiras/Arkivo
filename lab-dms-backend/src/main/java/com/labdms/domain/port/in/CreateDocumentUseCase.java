package com.labdms.domain.port.in;

import com.labdms.domain.model.document.Document;

import com.labdms.application.dto.command.CreateDocumentCommand;

/**
 * Use case for creating a new laboratory document.
 */
public interface CreateDocumentUseCase {

    /**
     * Creates a new document with an initial commit on the 'main' branch.
     */
    Document execute(CreateDocumentCommand command);
}
