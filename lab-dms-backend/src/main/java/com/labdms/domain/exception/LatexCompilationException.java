package com.labdms.domain.exception;

public class LatexCompilationException extends DomainException {

    public LatexCompilationException(String detail) {
        super("latex.compilation.error", detail);
    }

    public LatexCompilationException(String detail, Throwable cause) {
        super("latex.compilation.error", cause, detail);
    }
}
