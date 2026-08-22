package com.labdms.application.dto.command;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command record for compiling LaTeX from a specific commit.
 */
public record CompileLatexCommand(
        @NotNull UUID userId,
        @NotNull UUID commitId
) {}
