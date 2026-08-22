package com.labdms.domain.port.out;

import com.labdms.domain.model.versioning.CompiledPdf;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port for compiled PDF persistence.
 */
public interface CompiledPdfRepository {

    CompiledPdf save(CompiledPdf compiledPdf);

    Optional<CompiledPdf> findByCommitId(UUID commitId);

    void deleteByCommitId(UUID commitId);
}
