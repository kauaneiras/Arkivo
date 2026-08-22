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

    public ReviewController(SubmitReviewUseCase submitReviewUseCase,
                            ApproveReviewUseCase approveReviewUseCase,
                            JdbcTemplate jdbcTemplate) {
        this.submitReviewUseCase = submitReviewUseCase;
        this.approveReviewUseCase = approveReviewUseCase;
        this.jdbcTemplate = jdbcTemplate;
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
    public record ReviewListItemDto(
            UUID id,
            UUID documentId,
            String documentCode,
            String documentTitle,
            String title,
            String description,
            String status,
            String requestedByName,
            Instant createdAt
    ) {}

    @GetMapping("/reviews")
    @Operation(summary = "List review requests", description = "Filter by status (OPEN, APPROVED, REJECTED, MERGED). Defaults to all.")
    public ResponseEntity<List<ReviewListItemDto>> listReviews(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal UserPrincipal principal) {

        StringBuilder sql = new StringBuilder(
            "SELECT rr.id, rr.document_id, d.code AS document_code, d.title AS document_title, " +
            "rr.title, rr.description, rr.status, u.full_name AS requested_by_name, rr.created_at " +
            "FROM review_requests rr " +
            "JOIN documents d ON d.id = rr.document_id " +
            "JOIN users u ON u.id = rr.requested_by "
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
                rs.getTimestamp("created_at").toInstant()
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
            "rr.title, rr.description, rr.status, u.full_name AS requested_by_name, rr.created_at " +
            "FROM review_requests rr " +
            "JOIN documents d ON d.id = rr.document_id " +
            "JOIN users u ON u.id = rr.requested_by " +
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
                rs.getTimestamp("created_at").toInstant()
            ),
            documentId
        );

        return ResponseEntity.ok(reviews);
    }
}
