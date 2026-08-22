package com.labdms.application.dto.response;

import com.labdms.domain.model.versioning.CompiledPdf;
import com.labdms.domain.model.versioning.CompilationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for LaTeX compilation results.
 */
public record CompilationResultResponse(
        UUID id,
        UUID commitId,
        CompilationStatus status,
        String compilationLog,
        boolean hasPdf,
        Instant compiledAt
) {
    public static CompilationResultResponse from(CompiledPdf pdf) {
        return new CompilationResultResponse(
                pdf.getId(),
                pdf.getCommitId(),
                pdf.getCompilationStatus(),
                pdf.getCompilationLog(),
                pdf.getPdfData() != null && pdf.getPdfData().length > 0,
                pdf.getCompiledAt()
        );
    }
}
