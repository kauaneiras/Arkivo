package com.labdms.application.service;

import com.labdms.application.dto.command.CommitDocumentCommand;
import com.labdms.domain.exception.DomainException;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentCode;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;
import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.domain.port.out.BranchRepository;
import com.labdms.domain.port.out.CommitRepository;
import com.labdms.domain.port.out.ContentSnapshotRepository;
import com.labdms.domain.port.out.DocumentRepository;
import com.labdms.domain.port.out.ReviewRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The working-draft model: a closed version is never written to; the first
 * write opens a draft, "save" closes it, "discard" drops it.
 */
@ExtendWith(MockitoExtension.class)
class VersioningServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock BranchRepository branchRepository;
    @Mock CommitRepository commitRepository;
    @Mock ContentSnapshotRepository contentSnapshotRepository;
    @Mock ReviewRequestRepository reviewRequestRepository;
    @Mock AccessControlService accessControlService;
    @Mock AuditLogPort auditLogPort;
    @Mock ProjectFileService projectFiles;

    VersioningService service;

    UUID userId = UUID.randomUUID();
    Document document;
    Branch main;
    Commit head;

    @BeforeEach
    void setUp() {
        service = new VersioningService(documentRepository, branchRepository, commitRepository,
                contentSnapshotRepository, reviewRequestRepository, accessControlService,
                auditLogPort, projectFiles);

        document = Document.create(new DocumentCode("PQ-001"), "Procedimento", DocumentType.PQ,
                null, userId, null);
        main = Branch.createDefault(document.getId(), userId);
        head = Commit.create(document.getId(), main.getId(), null, UUID.randomUUID(), "abc",
                "v1", userId);
        main.advanceHead(head.getId());
        document.updateCurrentCommit(head.getId());
        document.makeOfficial(head.getId());
        document.transitionTo(DocumentStatus.ACTIVE);

    }

    private void documentExists() {
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
    }

    private void branchAndHead() {
        when(branchRepository.findDefaultByDocumentId(document.getId())).thenReturn(Optional.of(main));
        when(commitRepository.findById(head.getId())).thenReturn(Optional.of(head));
    }

    @Test
    void firstWriteOnAClosedVersionOpensADraftWithTheSameTree() {
        documentExists();
        branchAndHead();
        when(projectFiles.hasFiles(head.getId())).thenReturn(true);
        when(commitRepository.save(any(Commit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID draftId = service.openWorkingRevision(document.getId(), userId);

        assertThat(draftId).isNotEqualTo(head.getId());
        verify(projectFiles).copyTree(head.getId(), draftId);
        assertThat(main.getHeadCommitId()).isEqualTo(draftId);
        assertThat(document.getCurrentCommitId()).isEqualTo(draftId);
        // The version in force is untouched: that is the whole point.
        assertThat(document.getOfficialCommitId()).isEqualTo(head.getId());

        ArgumentCaptor<Commit> saved = ArgumentCaptor.forClass(Commit.class);
        verify(commitRepository).save(saved.capture());
        assertThat(saved.getValue().isDraft()).isTrue();
        assertThat(saved.getValue().getParentCommitId()).isEqualTo(head.getId());
    }

    @Test
    void aSecondWriteReusesTheOpenDraft() {
        documentExists();
        head.setDraft(true);
        branchAndHead();

        UUID draftId = service.openWorkingRevision(document.getId(), userId);

        assertThat(draftId).isEqualTo(head.getId());
        verify(commitRepository, never()).save(any());
        verify(projectFiles, never()).copyTree(any(), any());
    }

    @Test
    void writesAreRefusedWhileTheDocumentIsInReview() {
        documentExists();
        document.transitionTo(DocumentStatus.IN_REVIEW);

        assertThatThrownBy(() -> service.openWorkingRevision(document.getId(), userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void savingClosesTheDraftWithMessageReasonAndRootText() {
        documentExists();
        head.setDraft(true);
        when(branchRepository.findById(main.getId())).thenReturn(Optional.of(main));
        when(commitRepository.findById(head.getId())).thenReturn(Optional.of(head));
        when(projectFiles.hasFiles(head.getId())).thenReturn(true);
        when(projectFiles.readRootText(head.getId())).thenReturn("\\documentclass{article}\\begin{document}x\\end{document}");
        when(contentSnapshotRepository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(contentSnapshotRepository.save(any(ContentSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(commitRepository.save(any(Commit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Commit saved = service.execute(new CommitDocumentCommand(userId, document.getId(), main.getId(),
                "ignored buffer", "Corrige a seção 3", "Resultado do ensaio EQ-14"));

        assertThat(saved.getId()).isEqualTo(head.getId());
        assertThat(saved.isDraft()).isFalse();
        assertThat(saved.getMessage()).isEqualTo("Corrige a seção 3");
        assertThat(saved.getChangeReason()).isEqualTo("Resultado do ensaio EQ-14");
        assertThat(saved.getAuthorId()).isEqualTo(userId);
        // The editor buffer never becomes the version's content: the root file does.
        verify(projectFiles).readRootText(head.getId());
    }

    @Test
    void savingWithNoDraftAndATreeIsRefusedAsNothingToSave() {
        documentExists();
        when(branchRepository.findById(main.getId())).thenReturn(Optional.of(main));
        when(commitRepository.findById(head.getId())).thenReturn(Optional.of(head));
        when(projectFiles.hasFiles(head.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.execute(new CommitDocumentCommand(userId, document.getId(),
                main.getId(), "x", "msg", "reason")))
                .isInstanceOf(DomainException.class)
                .hasMessage("commit.nothing.to.save");
        verify(commitRepository, never()).save(any());
    }

    @Test
    void discardingADraftReturnsToItsParentAndDeletesIt() {
        documentExists();
        Commit draft = Commit.openDraft(document.getId(), main.getId(), head.getId(),
                head.getContentSnapshotId(), head.getSha256Hash(), userId);
        main.advanceHead(draft.getId());
        document.updateCurrentCommit(draft.getId());
        when(branchRepository.findDefaultByDocumentId(document.getId())).thenReturn(Optional.of(main));
        when(commitRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.discardDraft(document.getId(), userId);

        assertThat(main.getHeadCommitId()).isEqualTo(head.getId());
        assertThat(document.getCurrentCommitId()).isEqualTo(head.getId());
        verify(projectFiles).dropCommitArtifacts(draft.getId());
        verify(commitRepository).delete(eq(draft.getId()));
    }

    @Test
    void discardingWithoutADraftIsRefused() {
        documentExists();
        branchAndHead();

        assertThatThrownBy(() -> service.discardDraft(document.getId(), userId))
                .isInstanceOf(DomainException.class)
                .hasMessage("draft.none");
    }

    @Test
    void aDraftCannotBeClosedTwice() {
        Commit draft = Commit.openDraft(document.getId(), main.getId(), head.getId(),
                head.getContentSnapshotId(), head.getSha256Hash(), userId);
        draft.close("m", "r", userId, UUID.randomUUID(), "hash");
        assertThatThrownBy(() -> draft.close("m2", "r2", userId, UUID.randomUUID(), "hash2"))
                .isInstanceOf(IllegalStateException.class);
    }
}
