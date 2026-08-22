package com.labdms.application.dto.command;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for merging a branch into main.
 */
public record MergeBranchCommand(
        @NotNull UUID userId,
        @NotNull UUID documentId,
        @NotNull UUID branchId
) {}
