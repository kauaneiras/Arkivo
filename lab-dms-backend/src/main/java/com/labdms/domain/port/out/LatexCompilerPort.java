package com.labdms.domain.port.out;

import java.util.List;

/**
 * Output port for LaTeX compilation.
 * The infrastructure adapter handles the actual compilation.
 */
public interface LatexCompilerPort {

    /**
     * Compiles the given LaTeX source into a PDF.
     *
     * @param latexSource the LaTeX source code to compile
     * @return the compilation result containing PDF bytes, log and parsed errors
     */
    LatexCompilationResult compile(String latexSource);

    /**
     * Writes the files of a project into the directory the engine will run in.
     *
     * <p>The application layer owns the file tree, the infrastructure layer owns
     * the sandbox directory. This callback is the seam between them: the adapter
     * creates and cleans up the directory, the caller fills it.
     */
    @FunctionalInterface
    interface WorkDirPreparer {
        void prepare(java.nio.file.Path workDir) throws Exception;
    }

    /**
     * Compiles a multi-file project.
     *
     * <p>A real LaTeX document is a tree, not a string: {@code \input{fixos/pacotes}}
     * and {@code \includegraphics{figuras/capa}} only resolve when the sibling
     * files exist on disk next to the root. Compiling the root's text alone —
     * which is all {@link #compile(String)} can do — fails on every project that
     * is more than one file.
     *
     * @param cacheKey identity of this tree, so an unchanged project reuses its
     *                 working directory and latexmk skips redundant runs
     * @param rootFile path of the file to compile, relative to the work dir
     */
    LatexCompilationResult compileProject(String cacheKey, String rootFile, WorkDirPreparer preparer);

    /**
     * @param success   whether a PDF was produced
     * @param pdfBytes  the compiled PDF (null on failure)
     * @param log       the full engine log
     * @param errors    parsed error lines ("! ..." plus "l.NN" markers), newest run
     * @param pages     number of pages in the produced PDF (0 when unknown)
     */
    record LatexCompilationResult(
            boolean success,
            byte[] pdfBytes,
            String log,
            List<String> errors,
            int pages
    ) {
        public LatexCompilationResult(boolean success, byte[] pdfBytes, String log) {
            this(success, pdfBytes, log, List.of(), 0);
        }

        /** Short, user-facing summary of what went wrong. */
        public String errorSummary() {
            return errors.isEmpty() ? "" : String.join("\n", errors);
        }
    }
}
