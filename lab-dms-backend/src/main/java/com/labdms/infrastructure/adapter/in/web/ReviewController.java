package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.dto.command.ApproveReviewCommand;
import com.labdms.application.dto.command.SubmitReviewCommand;
import com.labdms.application.dto.response.ReviewRequestResponse;
import com.labdms.domain.port.in.ApproveReviewUseCase;
import com.labdms.domain.port.in.SubmitReviewUseCase;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reviews", description = "Review and Approval workflows (Pull Requests)")
public class ReviewController {

    private final SubmitReviewUseCase submitReviewUseCase;
    private final ApproveReviewUseCase approveReviewUseCase;
    private final JdbcTemplate jdbcTemplate;
    private final com.labdms.application.service.ReviewService reviewService;

    public ReviewController(SubmitReviewUseCase submitReviewUseCase,
                            ApproveReviewUseCase approveReviewUseCase,
                            JdbcTemplate jdbcTemplate,
                            com.labdms.application.service.ReviewService reviewService) {
        this.submitReviewUseCase = submitReviewUseCase;
        this.approveReviewUseCase = approveReviewUseCase;
        this.jdbcTemplate = jdbcTemplate;
        this.reviewService = reviewService;
    }

    @PostMapping("/reviews/{reviewRequestId}/withdraw")
    @Operation(summary = "Withdraw an open review request (author or editor of the document)")
    public ResponseEntity<Void> withdraw(
            @PathVariable UUID reviewRequestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        reviewService.withdraw(principal.getId(), reviewRequestId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/documents/{documentId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a document for review",
               description = "Creates a review request. If sourceBranchId is omitted, the default branch is used.")
    public ResponseEntity<ReviewRequestResponse> submitReview(
            @PathVariable UUID documentId,
            @Valid @RequestBody SubmitReviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID sourceBranchId = request.sourceBranchId();
        if (sourceBranchId == null) {
            sourceBranchId = jdbcTemplate.queryForObject(
                "SELECT id FROM branches WHERE document_id = ? AND is_default = true",
                UUID.class, documentId
            );
        }

        SubmitReviewCommand command = new SubmitReviewCommand(
            principal.getId(), documentId, sourceBranchId,
            request.title(), request.description()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReviewRequestResponse.from(submitReviewUseCase.execute(command)));
    }

    @PostMapping("/reviews/{reviewRequestId}/decision")
    @Operation(summary = "Approve or Reject a review request")
    public ResponseEntity<Void> makeDecision(
            @PathVariable UUID reviewRequestId,
            @Valid @RequestBody ReviewDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        ApproveReviewCommand command = new ApproveReviewCommand(
            principal.getId(), reviewRequestId, com.labdms.domain.model.review.ReviewDecision.valueOf(request.decision()), request.comments()
        );

        approveReviewUseCase.execute(command);
        return ResponseEntity.ok().build();
    }

    /**
     * Review request enriched with document and requester info for list views.
     */
    /**
     * Uma revisão pendente, com o que o ecrã precisa para saber se ESTA pessoa
     * pode decidir.
     *
     * <p>Os três últimos campos existem só para isso. A regra de aprovação é
     * "tem REVIEW_APPROVE, não é o autor, e é administrador OU o revisor
     * designado OU membro do setor" — sem o autor, o revisor designado e o
     * setor, o ecrã não consegue prever a resposta e acaba a oferecer botões que
     * o servidor recusa. Nada aqui é sensível: são identificadores de pessoas
     * que a própria listagem já mostra pelo nome.
     */
    public record ReviewListItemDto(
            UUID id,
            UUID documentId,
            String documentCode,
            String documentTitle,
            String title,
            String description,
            String status,
            String requestedByName,
            UUID requestedById,
            Instant createdAt,
            UUID documentCreatedBy,
            UUID assignedReviewerId,
            UUID documentGroupId,
            /** A versão proposta e a que está em vigor — o par que a revisão compara. */
            UUID documentCurrentCommitId,
            UUID documentOfficialCommitId,
            /** A versão EXATA submetida (fixa no pedido), e a vigente quando foi submetida. */
            UUID reviewedCommitId,
            UUID baseCommitId,
            /** Decisão, quando já houve uma. */
            String decision,
            String decisionComments,
            String reviewerName,
            Instant decidedAt
    ) {}

    @GetMapping("/reviews")
    @Operation(summary = "List review requests", description = "Filter by status (OPEN, APPROVED, REJECTED, MERGED). Defaults to all.")
    public ResponseEntity<List<ReviewListItemDto>> listReviews(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal UserPrincipal principal) {

        StringBuilder sql = new StringBuilder(
            "SELECT rr.id, rr.document_id, d.code AS document_code, d.title AS document_title, " +
            "rr.title, rr.description, rr.status, u.full_name AS requested_by_name, rr.requested_by, rr.created_at, " +
            "d.created_by AS document_created_by, d.assigned_reviewer_id, d.document_group_id, " +
            "d.current_commit_id, d.official_commit_id, rr.commit_id AS reviewed_commit_id, " +
            "(SELECT c.parent_commit_id FROM commits c WHERE c.id = rr.commit_id) AS base_commit_id, " +
            "rv.decision, rv.comments AS decision_comments, ru.full_name AS reviewer_name, rv.created_at AS decided_at " +
            "FROM review_requests rr " +
            "JOIN documents d ON d.id = rr.document_id " +
            "JOIN users u ON u.id = rr.requested_by " +
            "LEFT JOIN LATERAL (SELECT r.decision, r.comments, r.reviewer_id, r.created_at FROM reviews r " +
            "                   WHERE r.review_request_id = rr.id ORDER BY r.created_at DESC LIMIT 1) rv ON true " +
            "LEFT JOIN users ru ON ru.id = rv.reviewer_id "
        );

        Object[] args;
        if (status != null && !status.isBlank()) {
            sql.append("WHERE rr.status = ? ");
            args = new Object[]{status};
        } else {
            args = new Object[]{};
        }
        sql.append("ORDER BY rr.created_at DESC");

        List<ReviewListItemDto> reviews = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ReviewListItemDto(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_code"),
                rs.getString("document_title"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("requested_by_name"),
                rs.getObject("requested_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("document_created_by", UUID.class),
                rs.getObject("assigned_reviewer_id", UUID.class),
                rs.getObject("document_group_id", UUID.class),
                rs.getObject("current_commit_id", UUID.class),
                rs.getObject("official_commit_id", UUID.class),
                rs.getObject("reviewed_commit_id", UUID.class),
                rs.getObject("base_commit_id", UUID.class),
                rs.getString("decision"),
                rs.getString("decision_comments"),
                rs.getString("reviewer_name"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()
        ), args);

        return ResponseEntity.ok(reviews);
    }

    @GetMapping("/documents/{documentId}/reviews")
    @Operation(summary = "List review requests for a document")
    public ResponseEntity<List<ReviewListItemDto>> listDocumentReviews(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {

        List<ReviewListItemDto> reviews = jdbcTemplate.query(
            "SELECT rr.id, rr.document_id, d.code AS document_code, d.title AS document_title, " +
            "rr.title, rr.description, rr.status, u.full_name AS requested_by_name, rr.requested_by, rr.created_at, " +
            "d.created_by AS document_created_by, d.assigned_reviewer_id, d.document_group_id, " +
            "d.current_commit_id, d.official_commit_id, rr.commit_id AS reviewed_commit_id, " +
            "(SELECT c.parent_commit_id FROM commits c WHERE c.id = rr.commit_id) AS base_commit_id, " +
            "rv.decision, rv.comments AS decision_comments, ru.full_name AS reviewer_name, rv.created_at AS decided_at " +
            "FROM review_requests rr " +
            "JOIN documents d ON d.id = rr.document_id " +
            "JOIN users u ON u.id = rr.requested_by " +
            "LEFT JOIN LATERAL (SELECT r.decision, r.comments, r.reviewer_id, r.created_at FROM reviews r " +
            "                   WHERE r.review_request_id = rr.id ORDER BY r.created_at DESC LIMIT 1) rv ON true " +
            "LEFT JOIN users ru ON ru.id = rv.reviewer_id " +
            "WHERE rr.document_id = ? ORDER BY rr.created_at DESC",
            (rs, rowNum) -> new ReviewListItemDto(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_code"),
                rs.getString("document_title"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("requested_by_name"),
                rs.getObject("requested_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("document_created_by", UUID.class),
                rs.getObject("assigned_reviewer_id", UUID.class),
                rs.getObject("document_group_id", UUID.class),
                rs.getObject("current_commit_id", UUID.class),
                rs.getObject("official_commit_id", UUID.class),
                rs.getObject("reviewed_commit_id", UUID.class),
                rs.getObject("base_commit_id", UUID.class),
                rs.getString("decision"),
                rs.getString("decision_comments"),
                rs.getString("reviewer_name"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()
            ),
            documentId
        );

        return ResponseEntity.ok(reviews);
    }
}
