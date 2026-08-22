package com.labdms.application.service;

import com.labdms.domain.exception.DomainException;
import com.labdms.domain.exception.LatexCompilationException;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.versioning.CompiledPdf;
import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.application.dto.command.CompileLatexCommand;
import com.labdms.domain.port.in.CompileLatexUseCase;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.domain.port.out.CompiledPdfRepository;
import com.labdms.domain.port.out.CommitRepository;
import com.labdms.domain.port.out.ContentSnapshotRepository;
import com.labdms.domain.port.out.LatexCompilerPort;
import com.labdms.shared.util.InputSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Application service for LaTeX compilation.
 * Sanitizes input before delegating to the LaTeX compiler port.
 */
@Service
@Transactional
public class LatexCompilationService implements CompileLatexUseCase {

    private final CommitRepository commitRepository;
    private final ContentSnapshotRepository contentSnapshotRepository;
    private final CompiledPdfRepository compiledPdfRepository;
    private final LatexCompilerPort latexCompilerPort;
    private final AuditLogPort auditLogPort;
    private final ProjectFileService projectFiles;

    public LatexCompilationService(CommitRepository commitRepository,
                                   ContentSnapshotRepository contentSnapshotRepository,
                                   CompiledPdfRepository compiledPdfRepository,
                                   LatexCompilerPort latexCompilerPort,
                                   AuditLogPort auditLogPort,
                                   ProjectFileService projectFiles) {
        this.commitRepository = commitRepository;
        this.contentSnapshotRepository = contentSnapshotRepository;
        this.compiledPdfRepository = compiledPdfRepository;
        this.latexCompilerPort = latexCompilerPort;
        this.auditLogPort = auditLogPort;
        this.projectFiles = projectFiles;
    }

    @Override
    public CompiledPdf execute(CompileLatexCommand command) {
        // Return cached result only for SUCCESSFUL compilations.
        // Failed attempts must be retried (a cached failure would make the
        // compiler look permanently broken even after the cause is fixed).
        var existingPdf = compiledPdfRepository.findByCommitId(command.commitId());
        if (existingPdf.isPresent()) {
            if (existingPdf.get().getCompilationStatus() == com.labdms.domain.model.versioning.CompilationStatus.SUCCESS) {
                return existingPdf.get();
            }
            compiledPdfRepository.deleteByCommitId(command.commitId());
        }

        // Get the commit and its content
        Commit commit = commitRepository.findById(command.commitId())
                .orElseThrow(() -> new DomainException("commit.not.found", command.commitId()));

        // Compile
        CompiledPdf compiledPdf;
        try {
            LatexCompilerPort.LatexCompilationResult result = compileCommit(command.commitId(), commit);

            if (result.success()) {
                compiledPdf = CompiledPdf.success(command.commitId(), result.pdfBytes(), result.log());
            } else {
                compiledPdf = CompiledPdf.failure(command.commitId(), result.log());
            }
        } catch (Exception e) {
            compiledPdf = CompiledPdf.failure(command.commitId(),
                    "Compilation failed: " + e.getMessage());
        }

        CompiledPdf savedPdf = compiledPdfRepository.save(compiledPdf);

        // Audit
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "LATEX_COMPILED",
                "CompiledPdf",
                savedPdf.getId(),
                Map.of("commitId", command.commitId().toString(),
                        "status", savedPdf.getCompilationStatus().name()),
                null
        ));

        return savedPdf;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getPdf(java.util.UUID commitId) {
        return compiledPdfRepository.findByCommitId(commitId)
                .map(CompiledPdf::getPdfData)
                .orElseThrow(() -> new DomainException("pdf.not.found", commitId));
    }

    /**
     * Same as {@link #getPdf} but returns {@code null} instead of throwing when
     * the version has no compiled PDF. A version that was just saved simply does
     * not have one yet — that is an expected state the editor handles by
     * generating a preview, not an error worth an exception.
     */
    @Override
    public byte[] findPdf(java.util.UUID commitId) {
        return compiledPdfRepository.findByCommitId(commitId)
                .map(CompiledPdf::getPdfData)
                .orElse(null);
    }

    @Override
    public LatexCompilerPort.LatexCompilationResult preview(String latexSource) {
        // Sanitize and compile without persisting — editor live preview.
        String sanitizedSource = InputSanitizer.sanitizeLatex(latexSource);
        return latexCompilerPort.compile(sanitizedSource);
    }

    /**
     * Compiles a saved version, using its project tree when it has one.
     *
     * <p>Commits created before multi-file support (and any commit saved as a
     * single string) still carry a content snapshot, so both are handled here
     * rather than forcing a migration of historical versions: an old version
     * must keep compiling exactly as it did when it was approved, which is the
     * whole point of keeping it.
     */
    private LatexCompilerPort.LatexCompilationResult compileCommit(java.util.UUID commitId, Commit commit) {
        var files = projectFiles.listFiles(commitId);

        if (files.isEmpty()) {
            if (commit.getContentSnapshotId() == null) {
                throw new LatexCompilationException("This version has no files to compile");
            }
            ContentSnapshot snapshot = contentSnapshotRepository.findById(commit.getContentSnapshotId())
                    .orElseThrow(() -> new DomainException("content.snapshot.not.found"));
            return latexCompilerPort.compile(InputSanitizer.sanitizeLatex(snapshot.getLatexSource()));
        }

        String resolved = resolveRoot(files);

        if (resolved == null) {
            // The tree has assets but no .tex — this is what a document created
            // in the app looks like the moment an image is uploaded into it. The
            // document's own text is still the source, so it is written next to
            // the uploaded files instead of refusing to compile.
            String source = commit.getContentSnapshotId() == null ? "" :
                    contentSnapshotRepository.findById(commit.getContentSnapshotId())
                            .map(ContentSnapshot::getLatexSource).orElse("");
            final String body = InputSanitizer.sanitizeLatex(source);
            return latexCompilerPort.compileProject("commit:" + commitId, SYNTHETIC_ROOT,
                    workDir -> {
                        projectFiles.materialise(commitId, workDir);
                        writeRoot(workDir, body);
                    });
        }

        // Keyed on the CONTENTS, not the commit id: the tree of the version being
        // edited changes in place, so an id-only key served the previous PDF
        // after every upload or delete.
        return latexCompilerPort.compileProject(
                "commit:" + commitId + ":" + projectFiles.treeFingerprint(commitId), resolved,
                workDir -> projectFiles.materialise(commitId, workDir));
    }

    /** Name given to the root when the project has assets but no .tex of its own. */
    private static final String SYNTHETIC_ROOT = "main.tex";

    /** The file to compile, or {@code null} when the tree has no .tex at all. */
    private String resolveRoot(java.util.List<ProjectFileService.ProjectFile> files) {
        return files.stream().filter(ProjectFileService.ProjectFile::isRoot)
                .map(ProjectFileService.ProjectFile::path).findFirst()
                .orElseGet(() -> files.stream().map(ProjectFileService.ProjectFile::path)
                        .filter(p -> p.toLowerCase().endsWith(".tex")).findFirst()
                        .orElse(null));
    }

    private static String sha256(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeRoot(java.nio.file.Path workDir, String body) throws java.io.IOException {
        java.nio.file.Files.writeString(workDir.resolve(SYNTHETIC_ROOT), body,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Live preview of a project with unsaved edits.
     *
     * <p>The tree comes from the commit, then the buffer the user is typing into
     * is written over its own path. Without that overlay the preview would show
     * the last saved text and the edit would appear to do nothing — the single
     * most confusing thing an editor can do.
     *
     * @param editedPath    path of the open file, or null to preview as saved
     * @param editedContent current editor buffer for that path
     */
    @Override
    public LatexCompilerPort.LatexCompilationResult previewProject(
            java.util.UUID commitId, String editedPath, String editedContent) {

        var files = projectFiles.listFiles(commitId);
        if (files.isEmpty()) {
            return latexCompilerPort.compile(InputSanitizer.sanitizeLatex(
                    editedContent == null ? "" : editedContent));
        }

        String resolved = resolveRoot(files);

        // Assets uploaded into a document that has no .tex in its tree yet: the
        // editor buffer becomes the root, written alongside them.
        if (resolved == null) {
            final String body = InputSanitizer.sanitizeLatex(
                    editedContent == null ? "" : editedContent);
            return latexCompilerPort.compileProject(
                    "preview:" + commitId + ":" + projectFiles.treeFingerprint(commitId)
                            + ":synthetic:" + sha256(body),
                    SYNTHETIC_ROOT,
                    workDir -> {
                        projectFiles.materialise(commitId, workDir);
                        writeRoot(workDir, body);
                    });
        }
        final String rootFile = resolved;

        final String overlayPath = (editedPath != null && editedContent != null)
                ? ProjectFileService.normalisePath(editedPath) : null;
        final String overlay = overlayPath == null ? null : InputSanitizer.sanitizeLatex(editedContent);

        // The cache identity is the TREE plus the unsaved buffer. Both matter:
        // the tree because uploading a figure must invalidate the preview, and
        // the buffer because typing must. String.hashCode is not usable here —
        // it is 32 bits and trivially collidable (swapping "Aa" for "BB" at the
        // same offset leaves it unchanged), so an ordinary edit could be served
        // the previous PDF.
        String cacheKey = "preview:" + commitId + ":" + projectFiles.treeFingerprint(commitId)
                + ":" + overlayPath + ":" + (overlay == null ? "" : sha256(overlay));

        return latexCompilerPort.compileProject(cacheKey, rootFile, workDir -> {
            projectFiles.materialise(commitId, workDir);
            if (overlayPath != null) {
                var target = workDir.resolve(overlayPath).normalize();
                // Same containment check as materialise: a crafted path must not
                // let the editor write outside the sandbox.
                if (!target.startsWith(workDir)) {
                    throw new IllegalArgumentException("Invalid file path: " + overlayPath);
                }
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.writeString(target, overlay,
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        });
    }
}
