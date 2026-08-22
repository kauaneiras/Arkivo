package com.labdms.application.service;

import com.labdms.domain.exception.DocumentNotFoundException;
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
 * Application service for the review workflow.
 * Implements ISO 17025 formal review process for document changes.
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

    public ReviewService(ReviewRequestRepository reviewRequestRepository,
                         ReviewRepository reviewRepository,
                         DocumentRepository documentRepository,
                         AccessControlService accessControlService,
                         AuditLogPort auditLogPort,
                         NotificationService notificationService) {
        this.reviewRequestRepository = reviewRequestRepository;
        this.reviewRepository = reviewRepository;
        this.documentRepository = documentRepository;
        this.accessControlService = accessControlService;
        this.auditLogPort = auditLogPort;
        this.notificationService = notificationService;
    }

    @Override
    public ReviewRequest execute(SubmitReviewCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document.getDocumentGroupId());

        // Transition document to IN_REVIEW
        document.transitionTo(DocumentStatus.IN_REVIEW);
        documentRepository.save(document);

        ReviewRequest reviewRequest = ReviewRequest.create(
                command.documentId(),
                command.sourceBranchId(),
                command.title(),
                command.description(),
                command.userId()
        );

        ReviewRequest saved = reviewRequestRepository.save(reviewRequest);

        // Audit
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "REVIEW_SUBMITTED",
                "ReviewRequest",
                saved.getId(),
                Map.of("documentId", command.documentId().toString(),
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

    @Override
    public Review execute(ApproveReviewCommand command) {
        ReviewRequest reviewRequest = reviewRequestRepository.findById(command.reviewRequestId())
                .orElseThrow(() -> new ReviewNotFoundException(command.reviewRequestId()));

        accessControlService.assertCanApprove(command.userId(), reviewRequest.getDocumentId());

        // Create the review decision
        Review review = Review.create(
                command.reviewRequestId(),
                command.userId(),
                command.decision(),
                command.comments()
        );

        Review savedReview = reviewRepository.save(review);

        // Update review request status based on decision
        // ISO document lifecycle: APPROVED activates the document; REJECTED returns it to DRAFT.
        Document document = documentRepository.findById(reviewRequest.getDocumentId())
                .orElseThrow(() -> new DocumentNotFoundException(reviewRequest.getDocumentId()));

        if (command.decision() == ReviewDecision.APPROVED) {
            reviewRequest.approve();
            document.transitionTo(DocumentStatus.ACTIVE);
            // ISO/IEC 17025 8.3.2 b): approval starts the periodic-review clock.
            // Without this a document approved today would never come up for
            // review again, which is the classic "review dates long past" finding.
            document.recordPeriodicReview(command.userId(), java.time.Instant.now());
        } else if (command.decision() == ReviewDecision.REJECTED) {
            reviewRequest.reject();
            document.transitionTo(DocumentStatus.DRAFT);
        }

        documentRepository.save(document);
        reviewRequestRepository.save(reviewRequest);

        // Audit
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "REVIEW_DECIDED",
                "Review",
                savedReview.getId(),
                Map.of("reviewRequestId", command.reviewRequestId().toString(),
                        "decision", command.decision().name()),
                null
        ));

        boolean approved = command.decision() == ReviewDecision.APPROVED;
        boolean hasComments = command.comments() != null && !command.comments().isBlank();
        String decisionMessage = "\"" + document.getTitle() + "\" (" + document.getCode().value() + ") foi "
                + (approved ? "aprovado" : "rejeitado")
                + (hasComments ? ": " + command.comments() : "");
        notificationService.notify(
                reviewRequest.getRequestedBy(),
                approved ? NotificationService.TYPE_REVIEW_APPROVED : NotificationService.TYPE_REVIEW_REJECTED,
                approved ? "Documento aprovado" : "Documento rejeitado",
                decisionMessage,
                "/dashboard/documents/" + document.getId()
        );

        return savedReview;
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
