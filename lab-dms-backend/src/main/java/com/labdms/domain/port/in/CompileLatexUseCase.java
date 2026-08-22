package com.labdms.domain.port.in;

import com.labdms.application.dto.command.CompileLatexCommand;
import com.labdms.domain.model.versioning.CompiledPdf;
import com.labdms.domain.port.out.LatexCompilerPort;

/**
 * Use case for compiling LaTeX source into PDF.
 */
public interface CompileLatexUseCase {

    CompiledPdf execute(CompileLatexCommand command);

    byte[] getPdf(java.util.UUID commitId);

    /** Stored PDF for a version, or {@code null} when it has not been compiled yet. */
    byte[] findPdf(java.util.UUID commitId);

    /**
     * Compiles arbitrary LaTeX source WITHOUT persisting anything.
     * Used by the editor to preview unsaved changes (Overleaf-style).
     */
    LatexCompilerPort.LatexCompilationResult preview(String latexSource);

    /**
     * Previews a whole project without persisting anything, with the unsaved
     * buffer of the file being edited written over the saved tree.
     *
     * @param commitId      version whose tree provides the other files
     * @param editedPath    path of the file open in the editor, may be null
     * @param editedContent unsaved text of that file, may be null
     */
    LatexCompilerPort.LatexCompilationResult previewProject(
            java.util.UUID commitId, String editedPath, String editedContent);
}
