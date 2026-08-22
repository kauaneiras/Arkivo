package com.labdms.application.service;

import com.labdms.application.dto.command.CreateDocumentCommand;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;
import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.domain.port.out.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock BranchRepository branchRepository;
    @Mock CommitRepository commitRepository;
    @Mock ContentSnapshotRepository contentSnapshotRepository;
    @Mock AccessControlService accessControlService;
    @Mock AuditLogPort auditLogPort;
    @Mock NotificationService notificationService;
    @Mock DocumentTypeService documentTypeService;

    DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, branchRepository, commitRepository,
                contentSnapshotRepository, accessControlService, auditLogPort, notificationService,
                documentTypeService);
    }

    @Test
    void nextCodeReturnsTheFirstFreeSequentialNumberForTheType() {
        when(documentRepository.existsByCode("IT-001")).thenReturn(true);
        when(documentRepository.existsByCode("IT-002")).thenReturn(true);
        when(documentRepository.existsByCode("IT-003")).thenReturn(false);

        assertThat(documentService.nextCode(DocumentType.IT)).isEqualTo("IT-003");
    }

    @Test
    void nextCodeStartsAtOneWhenNoDocumentsExistForTheType() {
        when(documentRepository.existsByCode("PQ-001")).thenReturn(false);

        assertThat(documentService.nextCode(DocumentType.PQ)).isEqualTo("PQ-001");
    }

    @Test
    void createDocumentGeneratesCodeWhenOmitted() {
        UUID userId = UUID.randomUUID();
        CreateDocumentCommand command = new CreateDocumentCommand(
                userId, null, "Procedimento de teste", DocumentType.PQ, null,
                "\\documentclass{article}\\begin{document}x\\end{document}", List.of(), null, null, null, null);

        when(documentRepository.existsByCode("PQ-001")).thenReturn(false);
        when(contentSnapshotRepository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(contentSnapshotRepository.save(any(ContentSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(commitRepository.save(any(Commit.class))).thenAnswer(inv -> inv.getArgument(0));

        Document created = documentService.execute(command);

        assertThat(created.getCode().value()).isEqualTo("PQ-001");
        assertThat(created.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        verify(accessControlService).assertCanEdit(userId, null);
        verify(auditLogPort).log(any());
    }

    @Test
    void createDocumentUsesExplicitCodeWhenProvided() {
        UUID userId = UUID.randomUUID();
        CreateDocumentCommand command = new CreateDocumentCommand(
                userId, "it-soldagem-005", "Instrução de solda", DocumentType.IT, null,
                "\\documentclass{article}\\begin{document}x\\end{document}", List.of(), null, null, null, null);

        when(documentRepository.existsByCode("IT-SOLDAGEM-005")).thenReturn(false);
        when(contentSnapshotRepository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(contentSnapshotRepository.save(any(ContentSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(commitRepository.save(any(Commit.class))).thenAnswer(inv -> inv.getArgument(0));

        Document created = documentService.execute(command);

        // Explicit codes are normalized to uppercase.
        assertThat(created.getCode().value()).isEqualTo("IT-SOLDAGEM-005");
    }

    @Test
    void createDocumentRejectsDuplicateCode() {
        UUID userId = UUID.randomUUID();
        CreateDocumentCommand command = new CreateDocumentCommand(
                userId, "PQ-001", "Duplicado", DocumentType.PQ, null,
                "\\documentclass{article}\\begin{document}x\\end{document}", List.of(), null, null, null, null);

        when(documentRepository.existsByCode("PQ-001")).thenReturn(true);

        assertThatThrownBy(() -> documentService.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void createDocumentDeduplicatesIdenticalContentSnapshots() {
        UUID userId = UUID.randomUUID();
        String latex = "\\documentclass{article}\\begin{document}mesmo conteudo\\end{document}";
        CreateDocumentCommand command = new CreateDocumentCommand(
                userId, null, "Doc A", DocumentType.PQ, null, latex, List.of(), null, null, null, null);

        ContentSnapshot existingSnapshot = ContentSnapshot.create(latex);

        when(documentRepository.existsByCode("PQ-001")).thenReturn(false);
        when(contentSnapshotRepository.findByContentHash(existingSnapshot.getContentHash()))
                .thenReturn(Optional.of(existingSnapshot));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(branchRepository.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(commitRepository.save(any(Commit.class))).thenAnswer(inv -> inv.getArgument(0));

        documentService.execute(command);

        // The existing snapshot is reused: no new snapshot row is written.
        verify(contentSnapshotRepository, never()).save(any());
    }
}
