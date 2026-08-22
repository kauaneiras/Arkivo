package com.labdms.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for committing new content to a branch.
 */
public record CommitDocumentCommand(
        @NotNull UUID userId,
        @NotNull UUID documentId,
        @NotNull UUID branchId,
        @NotBlank String latexSource,
        @NotBlank String message
) {}
