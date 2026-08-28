package com.labdms.application.service;

import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.domain.port.out.CommitRepository;
import com.labdms.domain.port.out.CompiledPdfRepository;
import com.labdms.domain.port.out.ContentSnapshotRepository;
import com.labdms.domain.port.out.LatexCompilerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the editor's "Compilar" button must do for a document that has no project
 * tree — which is every document at the moment it is created.
 *
 * <p>These exist because a freshly created document answered
 * "LaTeX source cannot be empty": the preview path fell back to the unsaved
 * editor buffer and, when the caller sent none, compiled an empty string instead
 * of the text the document was created with.
 *
 * <p>The stubbing lives in helpers rather than in {@code @BeforeEach} because
 * Mockito's strict mode fails a test that leaves a stub unused, and the last case
 * here deliberately exercises a version with no snapshot at all.
 */
@ExtendWith(MockitoExtension.class)
class LatexCompilationServiceTest {

    @Mock CommitRepository commitRepository;
    @Mock ContentSnapshotRepository contentSnapshotRepository;
    @Mock CompiledPdfRepository compiledPdfRepository;
    @Mock LatexCompilerPort latexCompilerPort;
    @Mock AuditLogPort auditLogPort;
    @Mock ProjectFileService projectFiles;

    LatexCompilationService service;

    private static final String SAVED =
            "\\documentclass{article}\\begin{document}texto gravado\\end{document}";

    @BeforeEach
    void setUp() {
        service = new LatexCompilationService(commitRepository, contentSnapshotRepository,
                compiledPdfRepository, latexCompilerPort, auditLogPort, projectFiles);
    }

    /**
     * A single-file document: text in the version's snapshot, no project tree.
     *
     * <p>Only the empty tree is stubbed here. Reading the saved text is a separate
     * step because the buffer case never gets that far — it returns as soon as it
     * sees an edit — and a stub that goes unused fails the run under strict mode.
     */
    private UUID givenSingleFileDocument() {
        ContentSnapshot snapshot = ContentSnapshot.create(SAVED);
        Commit commit = Commit.create(UUID.randomUUID(), UUID.randomUUID(), null,
                snapshot.getId(), snapshot.getContentHash(), "versão inicial", UUID.randomUUID());
        savedCommit = commit;
        savedSnapshot = snapshot;

        when(projectFiles.listFiles(commit.getId())).thenReturn(List.of());
        return commit.getId();
    }

    private Commit savedCommit;
    private ContentSnapshot savedSnapshot;

    /** Makes the version's stored text reachable. Only for the tests that read it. */
    private void andItsSavedTextIsReadable() {
        when(commitRepository.findById(savedCommit.getId())).thenReturn(Optional.of(savedCommit));
        when(contentSnapshotRepository.findById(savedSnapshot.getId()))
                .thenReturn(Optional.of(savedSnapshot));
    }

    private void givenTheCompilerSucceeds() {
        when(latexCompilerPort.compile(anyString())).thenReturn(
                new LatexCompilerPort.LatexCompilationResult(true, new byte[]{1, 2, 3}, "ok"));
    }

    private String whatWasCompiled() {
        ArgumentCaptor<String> compiled = ArgumentCaptor.forClass(String.class);
        verify(latexCompilerPort).compile(compiled.capture());
        return compiled.getValue();
    }

    @Test
    void previewsTheSavedTextWhenTheEditorSendsNoBuffer() {
        UUID commitId = givenSingleFileDocument();
        andItsSavedTextIsReadable();
        givenTheCompilerSucceeds();

        var result = service.previewProject(commitId, null, null);

        assertThat(result.success()).isTrue();
        assertThat(whatWasCompiled()).contains("texto gravado");
    }

    @Test
    void previewsTheSavedTextWhenTheBufferIsBlank() {
        UUID commitId = givenSingleFileDocument();
        andItsSavedTextIsReadable();
        givenTheCompilerSucceeds();

        // A blank buffer is not an instruction to compile nothing — it is the
        // absence of an edit, same as sending none at all.
        service.previewProject(commitId, "document.tex", "   \n  ");

        assertThat(whatWasCompiled()).contains("texto gravado");
    }

    @Test
    void theUnsavedBufferWinsOverTheSavedText() {
        UUID commitId = givenSingleFileDocument();
        givenTheCompilerSucceeds();

        service.previewProject(commitId, "document.tex",
                "\\documentclass{article}\\begin{document}texto por gravar\\end{document}");

        assertThat(whatWasCompiled())
                .contains("texto por gravar")
                .doesNotContain("texto gravado");
    }

    @Test
    void aVersionWithNoTreeAndNoSavedTextIsStillReportedAsEmpty() {
        // The fallback must not paper over a genuinely empty document: with
        // nothing to compile, the caller should still be told so.
        Commit empty = Commit.create(UUID.randomUUID(), UUID.randomUUID(), null,
                null, "hash", "sem conteúdo", UUID.randomUUID());
        when(projectFiles.listFiles(empty.getId())).thenReturn(List.of());
        when(commitRepository.findById(empty.getId())).thenReturn(Optional.of(empty));

        assertThatThrownBy(() -> service.previewProject(empty.getId(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
