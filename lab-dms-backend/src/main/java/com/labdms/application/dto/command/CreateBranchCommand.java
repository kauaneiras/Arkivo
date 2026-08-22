package com.labdms.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for creating a new branch.
 */
public record CreateBranchCommand(
        @NotNull UUID userId,
        @NotNull UUID documentId,
        @NotBlank String branchName
) {}
