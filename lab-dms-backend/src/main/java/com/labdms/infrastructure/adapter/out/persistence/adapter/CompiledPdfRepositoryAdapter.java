package com.labdms.infrastructure.adapter.out.persistence.adapter;

import com.labdms.domain.model.versioning.CompiledPdf;
import com.labdms.domain.model.versioning.CompilationStatus;
import com.labdms.domain.port.out.CompiledPdfRepository;
import com.labdms.infrastructure.adapter.out.persistence.entity.CompiledPdfJpaEntity;
import com.labdms.infrastructure.adapter.out.persistence.repository.CompiledPdfJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CompiledPdfRepositoryAdapter implements CompiledPdfRepository {

    private final CompiledPdfJpaRepository repository;

    public CompiledPdfRepositoryAdapter(CompiledPdfJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public CompiledPdf save(CompiledPdf pdf) {
        CompiledPdfJpaEntity entity = new CompiledPdfJpaEntity();
        entity.setId(pdf.getId());
        entity.setCommitId(pdf.getCommitId());
        entity.setPdfData(pdf.getPdfData());
        entity.setCompilationStatus(pdf.getCompilationStatus().name());
        entity.setCompilationLog(pdf.getCompilationLog());
        entity.setCompiledAt(LocalDateTime.ofInstant(pdf.getCompiledAt(), ZoneOffset.UTC));
        CompiledPdfJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<CompiledPdf> findByCommitId(UUID commitId) {
        // Direct query — never load all PDFs (bytea) into memory
        return repository.findByCommitId(commitId).map(this::toDomain);
    }

    @Override
    public void deleteByCommitId(UUID commitId) {
        repository.deleteByCommitId(commitId);
    }

    private CompiledPdf toDomain(CompiledPdfJpaEntity entity) {
        return new CompiledPdf(
            entity.getId(),
            entity.getCommitId(),
            entity.getPdfData(),
            CompilationStatus.valueOf(entity.getCompilationStatus()),
            entity.getCompilationLog(),
            entity.getCompiledAt().toInstant(ZoneOffset.UTC)
        );
    }
}
