package com.labdms.domain.port.in;

import com.labdms.domain.model.versioning.Commit;

import com.labdms.application.dto.command.CommitDocumentCommand;
import java.util.UUID;

/**
 * Use case for committing new content to a document branch.
 */
public interface CommitDocumentUseCase {

    Commit execute(CommitDocumentCommand command);

    /**
     * Creates a new version with the content and file tree of an older one.
     *
     * @param sourceCommitId the version being restored
     */
    Commit restore(CommitDocumentCommand command, java.util.UUID sourceCommitId);
}
