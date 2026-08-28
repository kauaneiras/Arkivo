package com.labdms.application.service;

import com.labdms.domain.exception.DomainException;
import com.labdms.domain.exception.UnauthorizedAccessException;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentCode;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;
import com.labdms.domain.model.user.User;
import com.labdms.domain.model.user.UserStatus;
import com.labdms.domain.port.out.DocumentRepository;
import com.labdms.domain.port.out.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControlServiceTest {

    @Mock UserRepository userRepository;
    @Mock DocumentRepository documentRepository;

    AccessControlService accessControlService;

    UUID userId;
    UUID groupId;

    @BeforeEach
    void setUp() {
        accessControlService = new AccessControlService(userRepository, documentRepository);
        userId = UUID.randomUUID();
        groupId = UUID.randomUUID();
    }

    private User activeUser() {
        return new User(userId, "user", "user@email.com", "hash", "User",
                UserStatus.ACTIVE, Instant.now(), Instant.now(), null);
    }

    @Test
    void deniesEditWhenUserIsInactive() {
        User inactive = new User(userId, "u", "u@email.com", "h", "U",
                UserStatus.PENDING, Instant.now(), Instant.now(), null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> accessControlService.assertCanEdit(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void deniesEditWithoutDocumentEditPermission() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("DOCUMENT_VIEW_ALL"));

        assertThatThrownBy(() -> accessControlService.assertCanEdit(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void deniesEditWhenUserLacksGroupAccessAndHasNoEditAllPermission() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("DOCUMENT_EDIT"));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(false);

        assertThatThrownBy(() -> accessControlService.assertCanEdit(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void allowsEditWithGroupAccessAndEditPermission() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("DOCUMENT_EDIT"));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(true);

        assertThatCode(() -> accessControlService.assertCanEdit(userId, groupId)).doesNotThrowAnyException();
    }

    @Test
    void editAllPermissionBypassesGroupCheck() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("DOCUMENT_EDIT_ALL"));

        assertThatCode(() -> accessControlService.assertCanEdit(userId, groupId)).doesNotThrowAnyException();
    }

    @Test
    void deniesApproveWhenUserLacksReviewApprovePermission() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("DOCUMENT_EDIT"));

        UUID documentId = UUID.randomUUID();
        assertThatThrownBy(() -> accessControlService.assertCanApprove(userId, documentId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    /** A document written by someone else, for the reviewer tests below. */
    private Document documentBy(UUID authorId) {
        return Document.create(new DocumentCode("PQ-001"), "Doc", DocumentType.PQ,
                groupId, authorId, List.of());
    }

    @Test
    void allowsApproveWithPermissionAndGroupAccess() {
        UUID documentId = UUID.randomUUID();
        // The author must be someone else: a reviewer approving their OWN
        // document is the case the no-self-review rule exists to stop.
        Document document = documentBy(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("REVIEW_APPROVE"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(true);

        assertThatCode(() -> accessControlService.assertCanApprove(userId, documentId)).doesNotThrowAnyException();
    }

    /**
     * ISO 9000: the author never approves their own document. This is what makes
     * it safe to give one person both the editor and the reviewer role, which is
     * a requirement of this system.
     */
    @Test
    void deniesApproveOfOwnDocumentEvenWithReviewerPermission() {
        UUID documentId = UUID.randomUUID();
        Document ownDocument = documentBy(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("REVIEW_APPROVE"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(ownDocument));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(true);

        assertThatThrownBy(() -> accessControlService.assertCanApprove(userId, documentId))
                .isInstanceOf(UnauthorizedAccessException.class)
                // The message is always the i18n key "access.denied"; the reason
                // that distinguishes one denial from another is in the args.
                .satisfies(e -> assertThat(((DomainException) e).getArgs())
                        .containsExactly("approve_own_document"));
    }

    @Test
    void deniesApproveWhenAnotherReviewerWasAssigned() {
        UUID documentId = UUID.randomUUID();
        Document document = documentBy(UUID.randomUUID());
        document.setAssignedReviewerId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("REVIEW_APPROVE"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(true);

        assertThatThrownBy(() -> accessControlService.assertCanApprove(userId, documentId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .satisfies(e -> assertThat(((DomainException) e).getArgs())
                        .containsExactly("approve_not_assigned_reviewer"));
    }

    /** An administrator still acts, so work is not stuck if the reviewer leaves. */
    @Test
    void allowsAdminToApproveWhenAnotherReviewerWasAssigned() {
        UUID documentId = UUID.randomUUID();
        Document document = documentBy(UUID.randomUUID());
        document.setAssignedReviewerId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId))
                .thenReturn(List.of("REVIEW_APPROVE", "DOCUMENT_EDIT_ALL"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(true);

        assertThatCode(() -> accessControlService.assertCanApprove(userId, documentId))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsTheAssignedReviewerToApprove() {
        UUID documentId = UUID.randomUUID();
        Document document = documentBy(UUID.randomUUID());
        document.setAssignedReviewerId(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("REVIEW_APPROVE"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(true);

        assertThatCode(() -> accessControlService.assertCanApprove(userId, documentId))
                .doesNotThrowAnyException();
    }

    /**
     * As duas causas de recusa que partilhavam a mesma mensagem.
     *
     * <p>Quem tinha REVIEW_APPROVE mas estava fora do setor recebia "você não
     * tem permissão para aprovar revisões" — uma frase falsa, que manda a pessoa
     * pedir um papel que já tem. Estes testes fixam a distinção: o motivo viaja
     * na exceção, e é por ele que a mensagem é escolhida.
     */
    @Test
    void tellsSectorRefusalApartFromMissingPermission() {
        UUID documentId = UUID.randomUUID();
        Document document = documentBy(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("REVIEW_APPROVE"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(userRepository.hasGroupAccess(userId, groupId)).thenReturn(false);

        assertThatThrownBy(() -> accessControlService.assertCanApprove(userId, documentId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .satisfies(e -> assertThat(((UnauthorizedAccessException) e).getArgs())
                        .containsExactly("approve_not_in_sector"));
    }

    @Test
    void missingPermissionStillReportsMissingPermission() {
        UUID documentId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("DOCUMENT_EDIT"));

        assertThatThrownBy(() -> accessControlService.assertCanApprove(userId, documentId))
                .isInstanceOf(UnauthorizedAccessException.class)
                .satisfies(e -> assertThat(((UnauthorizedAccessException) e).getArgs())
                        .containsExactly("approve"));
    }

    /**
     * Um administrador não fica preso ao setor.
     *
     * <p>Ele já vê e edita tudo por DOCUMENT_EDIT_ALL, por isso travá-lo aqui
     * não protegia nada — só deixava trabalho parado quando o setor do documento
     * não tinha nenhum revisor dentro.
     */
    @Test
    void administratorApprovesOutsideTheSector() {
        UUID documentId = UUID.randomUUID();
        Document document = documentBy(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId))
                .thenReturn(List.of("REVIEW_APPROVE", "DOCUMENT_EDIT_ALL"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        assertThatCode(() -> accessControlService.assertCanApprove(userId, documentId))
                .doesNotThrowAnyException();
    }

    /**
     * O revisor NOMEADO para o documento aprova mesmo estando fora do setor.
     *
     * <p>Sem isto havia uma armadilha silenciosa: criar um setor novo, pôr um
     * documento nele e designar um revisor produzia um documento que ninguém
     * conseguia aprovar — nem o próprio revisor nomeado — porque o setor
     * recém-criado ainda não tinha membros. Ser nomeado para o trabalho diz mais
     * do que pertencer ao setor.
     */
    @Test
    void theDesignatedReviewerApprovesOutsideTheSector() {
        UUID documentId = UUID.randomUUID();
        Document document = documentBy(UUID.randomUUID());
        document.setAssignedReviewerId(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(userRepository.findUserPermissions(userId)).thenReturn(List.of("REVIEW_APPROVE"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        assertThatCode(() -> accessControlService.assertCanApprove(userId, documentId))
                .doesNotThrowAnyException();
    }

    @Test
    void deniesAllOperationsForAnUnknownUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessControlService.assertCanView(userId, groupId))
                .isInstanceOf(UnauthorizedAccessException.class);
    }
}
