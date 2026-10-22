package com.labdms.application.service;

import com.labdms.domain.exception.DocumentNotFoundException;
import com.labdms.domain.exception.DomainException;
import com.labdms.domain.exception.ReviewNotFoundException;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.review.Review;
import com.labdms.domain.model.review.ReviewDecision;
import com.labdms.domain.model.review.ReviewRequest;
import com.labdms.domain.model.review.ReviewRequestStatus;
import com.labdms.application.dto.command.ApproveReviewCommand;
import com.labdms.application.dto.command.SubmitReviewCommand;
import com.labdms.domain.port.in.ApproveReviewUseCase;
import com.labdms.domain.port.in.SubmitReviewUseCase;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.domain.port.out.DocumentRepository;
import com.labdms.domain.port.out.ReviewRepository;
import com.labdms.domain.port.out.ReviewRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The review workflow: submit a saved version, approve it into force or send
 * it back. ISO/IEC 17025 8.3.2 a) — approval before use.
 */
@Service
@Transactional
public class ReviewService implements SubmitReviewUseCase, ApproveReviewUseCase {

    private final ReviewRequestRepository reviewRequestRepository;
    private final ReviewRepository reviewRepository;
    private final DocumentRepository documentRepository;
    private final AccessControlService accessControlService;
    private final AuditLogPort auditLogPort;
    private final NotificationService notificationService;
    private final VersioningService versioning;

    public ReviewService(ReviewRequestRepository reviewRequestRepository,
                         ReviewRepository reviewRepository,
                         DocumentRepository documentRepository,
                         AccessControlService accessControlService,
                         AuditLogPort auditLogPort,
                         NotificationService notificationService,
                         VersioningService versioning) {
        this.reviewRequestRepository = reviewRequestRepository;
        this.reviewRepository = reviewRepository;
        this.documentRepository = documentRepository;
        this.accessControlService = accessControlService;
        this.auditLogPort = auditLogPort;
        this.notificationService = notificationService;
        this.versioning = versioning;
    }

    /**
     * Submits the document's current SAVED version for approval.
     *
     * <p>Three refusals, each a real defect that used to slip through:
     * <ul>
     *   <li>unsaved changes — the reviewer would decide on a version that is
     *       not what the author is looking at;</li>
     *   <li>a request already open — two decisions for one document;</li>
     *   <li>nothing to approve — the current version is already in force.</li>
     * </ul>
     */
    @Override
    public ReviewRequest execute(SubmitReviewCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document);

        if (document.getStatus() == DocumentStatus.OBSOLETE) {
            throw new DomainException("review.document.obsolete");
        }
        if (reviewRequestRepository.findOpenByDocumentId(command.documentId()).isPresent()
                || document.getStatus() == DocumentStatus.IN_REVIEW) {
            throw new DomainException("review.already.open");
        }
        if (versioning.findDraft(command.documentId()) != null) {
            throw new DomainException("review.unsaved.changes");
        }
        UUID proposed = document.getCurrentCommitId();
        if (proposed == null) {
            throw new DomainException("review.no.version");
        }
        if (document.getStatus() == DocumentStatus.ACTIVE && proposed.equals(document.getOfficialCommitId())) {
            throw new DomainException("review.nothing.changed");
        }

        document.transitionTo(DocumentStatus.IN_REVIEW);
        documentRepository.save(document);

        ReviewRequest reviewRequest = ReviewRequest.create(
                command.documentId(),
                command.sourceBranchId(),
                proposed,
                command.title(),
                command.description(),
                command.userId()
        );
        ReviewRequest saved = reviewRequestRepository.save(reviewRequest);

        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "REVIEW_SUBMITTED",
                "ReviewRequest",
                saved.getId(),
                Map.of("documentId", command.documentId().toString(),
                        "commitId", proposed.toString(),
                        "title", command.title()),
                null
        ));

        notificationService.notifyReviewers(
                "Novo documento para revisão",
                "\"" + document.getTitle() + "\" (" + document.getCode().value() + ") foi submetido para aprovação: " + command.title(),
                "/dashboard/reviews"
        );

        return saved;
    }

    /**
     * Decides. Approval puts the reviewed version in force; rejection sends the
     * document back to draft with the reviewer's comments.
     */
    @Override
    public Review execute(ApproveReviewCommand command) {
        ReviewRequest reviewRequest = reviewRequestRepository.findById(command.reviewRequestId())
                .orElseThrow(() -> new ReviewNotFoundException(command.reviewRequestId()));

        if (reviewRequest.getStatus() != ReviewRequestStatus.OPEN) {
            throw new DomainException("review.already.decided");
        }

        accessControlService.assertCanApprove(command.userId(), reviewRequest.getDocumentId());

        if (command.decision() == ReviewDecision.REJECTED
                && (command.comments() == null || command.comments().isBlank())) {
            // The author has to know what to fix; "rejected" alone is not a review.
            throw new DomainException("review.rejection.needs.comment");
        }

        Review review = Review.create(
                command.reviewRequestId(),
                command.userId(),
                command.decision(),
                command.comments()
        );
        Review savedReview = reviewRepository.save(review);

        Document document = documentRepository.findById(reviewRequest.getDocumentId())
                .orElseThrow(() -> new DocumentNotFoundException(reviewRequest.getDocumentId()));

        UUID previousOfficial = document.getOfficialCommitId();
        if (command.decision() == ReviewDecision.APPROVED) {
            reviewRequest.approve();
            document.transitionTo(DocumentStatus.ACTIVE);
            // The approved version becomes the one in force. Before this the
            // pointer was left where it was and the list showed "version in
            // force is outdated" right after an approval — someone then had to
            // elect the version by hand, which is exactly the kind of manual step
            // that gets forgotten.
            UUID approved = reviewRequest.getCommitId() != null
                    ? reviewRequest.getCommitId() : document.getCurrentCommitId();
            if (approved != null) {
                document.makeOfficial(approved);
            }
            // ISO/IEC 17025 8.3.2 b): approval starts the periodic-review clock.
            document.recordPeriodicReview(command.userId(), java.time.Instant.now());
        } else if (command.decision() == ReviewDecision.REJECTED) {
            reviewRequest.reject();
            document.transitionTo(DocumentStatus.DRAFT);
        }

        documentRepository.save(document);
        reviewRequestRepository.save(reviewRequest);

        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "REVIEW_DECIDED",
                "Review",
                savedReview.getId(),
                Map.of("reviewRequestId", command.reviewRequestId().toString(),
                        "documentId", document.getId().toString(),
                        "commitId", String.valueOf(reviewRequest.getCommitId()),
                        "decision", command.decision().name(),
                        "previousOfficial", String.valueOf(previousOfficial)),
                null
        ));

        boolean approved = command.decision() == ReviewDecision.APPROVED;
        boolean hasComments = command.comments() != null && !command.comments().isBlank();
        String decisionMessage = "\"" + document.getTitle() + "\" (" + document.getCode().value() + ") foi "
                + (approved ? "aprovado e passou a vigorar" : "devolvido para correção")
                + (hasComments ? ": " + command.comments() : "");
        notificationService.notify(
                reviewRequest.getRequestedBy(),
                approved ? NotificationService.TYPE_REVIEW_APPROVED : NotificationService.TYPE_REVIEW_REJECTED,
                approved ? "Documento aprovado" : "Documento devolvido",
                decisionMessage,
                "/dashboard/documents/" + document.getId() + "/edit"
        );

        return savedReview;
    }

    /**
     * Withdraws an open request — the author changed their mind or spotted a
     * mistake before the reviewer looked. The document goes back to draft.
     */
    public void withdraw(UUID userId, UUID reviewRequestId) {
        ReviewRequest reviewRequest = reviewRequestRepository.findById(reviewRequestId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewRequestId));
        if (reviewRequest.getStatus() != ReviewRequestStatus.OPEN) {
            throw new DomainException("review.already.decided");
        }
        Document document = documentRepository.findById(reviewRequest.getDocumentId())
                .orElseThrow(() -> new DocumentNotFoundException(reviewRequest.getDocumentId()));
        accessControlService.assertCanEdit(userId, document);
        // Withdrawing is the submitter's (or the author's) act. A colleague with
        // edit rights on the sector must not be able to pull somebody else's
        // request out from under the reviewer; an administrator still can.
        boolean owner = userId.equals(reviewRequest.getRequestedBy()) || userId.equals(document.getCreatedBy());
        if (!owner && !accessControlService.isAdmin(userId)) {
            throw new DomainException("review.withdraw.not.owner");
        }

        reviewRequest.reject();
        reviewRequestRepository.save(reviewRequest);
        document.transitionTo(document.getOfficialCommitId() != null
                && document.getOfficialCommitId().equals(document.getCurrentCommitId())
                ? DocumentStatus.ACTIVE : DocumentStatus.DRAFT);
        documentRepository.save(document);

        auditLogPort.log(AuditEntry.create(userId, "REVIEW_WITHDRAWN", "ReviewRequest", reviewRequestId,
                Map.of("documentId", document.getId().toString()), null));
    }

    @Transactional(readOnly = true)
    public List<ReviewRequest> findPendingReviews() {
        return reviewRequestRepository.findByStatus(ReviewRequestStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public List<ReviewRequest> findByDocumentId(UUID documentId) {
        return reviewRequestRepository.findByDocumentId(documentId);
    }
}
