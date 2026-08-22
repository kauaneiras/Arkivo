package com.labdms.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "compiled_pdfs")
public class CompiledPdfJpaEntity {

    @Id
    private UUID id;

    @Column(name = "commit_id", nullable = false)
    private UUID commitId;

    @Column(name = "pdf_data")
    private byte[] pdfData;

    @Column(name = "compilation_status", nullable = false, length = 20)
    private String compilationStatus;

    @Column(name = "compilation_log", columnDefinition = "TEXT")
    private String compilationLog;

    @Column(name = "compiled_at", nullable = false, updatable = false)
    private LocalDateTime compiledAt;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCommitId() { return commitId; }
    public void setCommitId(UUID commitId) { this.commitId = commitId; }
    public byte[] getPdfData() { return pdfData; }
    public void setPdfData(byte[] pdfData) { this.pdfData = pdfData; }
    public String getCompilationStatus() { return compilationStatus; }
    public void setCompilationStatus(String compilationStatus) { this.compilationStatus = compilationStatus; }
    public String getCompilationLog() { return compilationLog; }
    public void setCompilationLog(String compilationLog) { this.compilationLog = compilationLog; }
    public LocalDateTime getCompiledAt() { return compiledAt; }
    public void setCompiledAt(LocalDateTime compiledAt) { this.compiledAt = compiledAt; }
}
