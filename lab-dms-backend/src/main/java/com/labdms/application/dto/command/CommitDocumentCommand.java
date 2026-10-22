package com.labdms.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for saving a version ("closing the draft") on a branch.
 *
 * @param latexSource  editor buffer; used only for documents that have no file
 *                     tree yet, otherwise the root file is read from the tree
 * @param changeReason ISO/IEC 17025 8.3.2 c) justification; may be null
 */
public record CommitDocumentCommand(
        @NotNull UUID userId,
        @NotNull UUID documentId,
        @NotNull UUID branchId,
        String latexSource,
        @NotBlank String message,
        String changeReason
) {
    /** Compatibility constructor for callers without a change reason. */
    public CommitDocumentCommand(UUID userId, UUID documentId, UUID branchId,
                                 String latexSource, String message) {
        this(userId, documentId, branchId, latexSource, message, null);
    }
}
