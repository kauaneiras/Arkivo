package com.labdms.infrastructure.adapter.out.latex;

import com.labdms.domain.port.out.LatexCompilerPort.LatexCompilationResult;

public record DockerCompilationResult(
        boolean success,
        byte[] pdfBytes,
        String log
) {
    public LatexCompilationResult toDomain() {
        return new LatexCompilationResult(success, pdfBytes, log);
    }
}
