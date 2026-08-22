package com.labdms.application.service;

import com.labdms.domain.exception.DocumentNotFoundException;
import com.labdms.domain.exception.UnauthorizedAccessException;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.user.User;
import com.labdms.domain.port.out.DocumentRepository;
import com.labdms.domain.port.out.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service for access control checks combining RBAC (role-based) and ABAC (attribute-based).
 * Checks user roles/permissions AND document group access.
 */
@Service
public class AccessControlService {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    public AccessControlService(UserRepository userRepository, DocumentRepository documentRepository) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * Asserts that the user can view documents in a given group.
     */
    public void assertCanView(UUID userId, UUID documentGroupId) {
        assertUserActive(userId);
        if (!userRepository.hasGroupAccess(userId, documentGroupId)) {
            List<String> permissions = userRepository.findUserPermissions(userId);
            if (!permissions.contains("DOCUMENT_VIEW_ALL")) {
                throw new UnauthorizedAccessException("view");
            }
        }
    }

    /**
     * Asserts that the user can edit documents in a given group.
     */
    public void assertCanEdit(UUID userId, UUID documentGroupId) {
        assertUserActive(userId);
        List<String> permissions = userRepository.findUserPermissions(userId);
        if (!permissions.contains("DOCUMENT_EDIT") && !permissions.contains("DOCUMENT_EDIT_ALL")) {
            throw new UnauthorizedAccessException("edit");
        }
        if (!permissions.contains("DOCUMENT_EDIT_ALL")
                && !userRepository.hasGroupAccess(userId, documentGroupId)) {
            throw new UnauthorizedAccessException("edit");
        }
    }

    /**
     * Asserts that the user can delete documents.
     */
    public void assertCanDelete(UUID userId, UUID documentGroupId) {
        assertUserActive(userId);
        List<String> permissions = userRepository.findUserPermissions(userId);
        if (!permissions.contains("DOCUMENT_DELETE")) {
            throw new UnauthorizedAccessException("delete");
        }
        if (!userRepository.hasGroupAccess(userId, documentGroupId)) {
            throw new UnauthorizedAccessException("delete");
        }
    }

    /**
     * Asserts that the user can approve reviews for a document.
     *
     * <p>Beyond the permission and group checks, two ISO 9000 rules apply:
     * <ul>
     *   <li><b>No self-review.</b> The author of a document may never approve it,
     *       even when they legitimately hold the reviewer role. This is what makes
     *       it safe to grant one person both EDITOR and REVIEWER.</li>
     *   <li><b>Named responsibility.</b> When a reviewer was designated for the
     *       document, only that person may decide — an administrator can still
     *       act, so work never gets stuck if the reviewer leaves.</li>
     * </ul>
     */
    public void assertCanApprove(UUID userId, UUID documentId) {
        assertUserActive(userId);
        List<String> permissions = userRepository.findUserPermissions(userId);
        if (!permissions.contains("REVIEW_APPROVE")) {
            throw new UnauthorizedAccessException("approve");
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!userRepository.hasGroupAccess(userId, document.getDocumentGroupId())) {
            throw new UnauthorizedAccessException("approve");
        }

        if (userId.equals(document.getCreatedBy())) {
            throw new UnauthorizedAccessException("approve_own_document");
        }

        UUID assigned = document.getAssignedReviewerId();
        boolean isAdmin = permissions.contains("DOCUMENT_EDIT_ALL");
        if (assigned != null && !assigned.equals(userId) && !isAdmin) {
            throw new UnauthorizedAccessException("approve_not_assigned_reviewer");
        }
    }

    /**
     * A user may be designated as reviewer only if they can actually approve and
     * are not the document's author. Validated when the document is created so
     * the problem surfaces immediately, not at approval time.
     */
    public void assertCanBeAssignedAsReviewer(UUID reviewerId, UUID authorId) {
        if (reviewerId == null) return;
        if (reviewerId.equals(authorId)) {
            throw new IllegalArgumentException(
                    "The author cannot be the reviewer of their own document");
        }
        User reviewer = userRepository.findById(reviewerId).orElse(null);
        if (reviewer == null || !reviewer.isActive()) {
            throw new IllegalArgumentException("Reviewer not found or inactive");
        }
        if (!userRepository.findUserPermissions(reviewerId).contains("REVIEW_APPROVE")) {
            throw new IllegalArgumentException(
                    "The selected user does not have reviewer permission");
        }
    }

    private void assertUserActive(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isActive()) {
            throw new UnauthorizedAccessException("inactive_user");
        }
    }
}
