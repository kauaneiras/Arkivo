package com.labdms.domain.model.versioning;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores the compiled PDF output for a given commit.
 */
public class CompiledPdf {

    private UUID id;
    private UUID commitId;
    private byte[] pdfData;
    private CompilationStatus compilationStatus;
    private String compilationLog;
    private Instant compiledAt;

    public CompiledPdf() {
    }

    public CompiledPdf(UUID id, UUID commitId, byte[] pdfData,
                       CompilationStatus compilationStatus, String compilationLog,
                       Instant compiledAt) {
        this.id = id;
        this.commitId = commitId;
        this.pdfData = pdfData;
        this.compilationStatus = compilationStatus;
        this.compilationLog = compilationLog;
        this.compiledAt = compiledAt;
    }

    /**
     * Factory method to create a compiled PDF record after successful compilation.
     */
    public static CompiledPdf success(UUID commitId, byte[] pdfData, String log) {
        CompiledPdf compiled = new CompiledPdf();
        compiled.id = UUID.randomUUID();
        compiled.commitId = commitId;
        compiled.pdfData = pdfData;
        compiled.compilationStatus = CompilationStatus.SUCCESS;
        compiled.compilationLog = log;
        compiled.compiledAt = Instant.now();
        return compiled;
    }

    /**
     * Factory method to create a failed compilation record.
     */
    public static CompiledPdf failure(UUID commitId, String errorLog) {
        CompiledPdf compiled = new CompiledPdf();
        compiled.id = UUID.randomUUID();
        compiled.commitId = commitId;
        compiled.pdfData = null;
        compiled.compilationStatus = CompilationStatus.ERROR;
        compiled.compilationLog = errorLog;
        compiled.compiledAt = Instant.now();
        return compiled;
    }

    /**
     * Factory method to create a pending compilation record.
     */
    public static CompiledPdf pending(UUID commitId) {
        CompiledPdf compiled = new CompiledPdf();
        compiled.id = UUID.randomUUID();
        compiled.commitId = commitId;
        compiled.compilationStatus = CompilationStatus.PENDING;
        compiled.compiledAt = Instant.now();
        return compiled;
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCommitId() {
        return commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public byte[] getPdfData() {
        return pdfData;
    }

    public void setPdfData(byte[] pdfData) {
        this.pdfData = pdfData;
    }

    public CompilationStatus getCompilationStatus() {
        return compilationStatus;
    }

    public void setCompilationStatus(CompilationStatus compilationStatus) {
        this.compilationStatus = compilationStatus;
    }

    public String getCompilationLog() {
        return compilationLog;
    }

    public void setCompilationLog(String compilationLog) {
        this.compilationLog = compilationLog;
    }

    public Instant getCompiledAt() {
        return compiledAt;
    }

    public void setCompiledAt(Instant compiledAt) {
        this.compiledAt = compiledAt;
    }
}
