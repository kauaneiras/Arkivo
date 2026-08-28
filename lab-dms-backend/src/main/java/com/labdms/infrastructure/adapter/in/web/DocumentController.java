package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.dto.command.CreateDocumentCommand;
import com.labdms.application.dto.command.SearchDocumentsQuery;
import com.labdms.application.dto.response.DocumentResponse;
import com.labdms.application.dto.response.PageResponse;
import com.labdms.domain.port.in.CreateDocumentUseCase;
import com.labdms.domain.port.in.SearchDocumentsUseCase;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Document Management endpoints")
public class DocumentController {

    private final CreateDocumentUseCase createDocumentUseCase;
    private final SearchDocumentsUseCase searchDocumentsUseCase;
    private final com.labdms.application.service.DocumentService documentService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.labdms.infrastructure.config.ReviewReminderJob reviewReminderJob;
    private final com.labdms.application.service.DocumentVisibilityService visibilityService;
    private final com.labdms.application.service.AccessControlService accessControlService;
    private final com.labdms.domain.port.out.AuditLogPort auditLogPort;

    public DocumentController(CreateDocumentUseCase createDocumentUseCase,
                              SearchDocumentsUseCase searchDocumentsUseCase,
                              com.labdms.application.service.DocumentService documentService,
                              org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                              com.labdms.infrastructure.config.ReviewReminderJob reviewReminderJob,
                              com.labdms.application.service.DocumentVisibilityService visibilityService,
                              com.labdms.application.service.AccessControlService accessControlService,
                              com.labdms.domain.port.out.AuditLogPort auditLogPort) {
        this.createDocumentUseCase = createDocumentUseCase;
        this.searchDocumentsUseCase = searchDocumentsUseCase;
        this.documentService = documentService;
        this.jdbcTemplate = jdbcTemplate;
        this.reviewReminderJob = reviewReminderJob;
        this.visibilityService = visibilityService;
        this.accessControlService = accessControlService;
        this.auditLogPort = auditLogPort;
    }

    public record NextCodeDto(String code) {}

    @GetMapping("/next-code")
    @Operation(summary = "Next available document code for a category",
               description = "Sequential per type: IT-001, IT-002, PQ-001... Used by the UI to prefill the code field")
    public ResponseEntity<NextCodeDto> nextCode(@RequestParam String docType) {
        var type = com.labdms.domain.model.document.DocumentType.valueOf(docType.toUpperCase());
        return ResponseEntity.ok(new NextCodeDto(documentService.nextCode(type)));
    }

    public record ChangeStatusRequest(@jakarta.validation.constraints.NotBlank String status) {}

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change document lifecycle status",
               description = "Transitions a document between DRAFT / IN_REVIEW / ACTIVE / OBSOLETE (ISO 9000 document control)")
    public ResponseEntity<DocumentResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        var newStatus = com.labdms.domain.model.document.DocumentStatus.valueOf(request.status());
        var updated = documentService.changeStatus(principal.getId(), id, newStatus);
        return ResponseEntity.ok(DocumentResponse.from(updated));
    }

    @PutMapping("/{id}/official-version/{commitId}")
    @Operation(summary = "Elect a revision as the official (in-force) one",
               description = "Requires approval rights. No revision is ever deleted — any past "
                           + "version can be made official again, and the change is audited.")
    public ResponseEntity<DocumentResponse> makeVersionOfficial(
            @PathVariable UUID id,
            @PathVariable UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {
        var updated = documentService.makeVersionOfficial(principal.getId(), id, commitId);
        return ResponseEntity.ok(DocumentResponse.from(updated));
    }

    @GetMapping(value = "/master-list", produces = "text/csv; charset=UTF-8")
    @Operation(summary = "Master list of internal documents (ISO/IEC 17025 mandatory record)",
               description = "Controlled index with the current revision status of every document, "
                           + "in CSV so it can be handed to an assessor or archived as evidence.")
    public ResponseEntity<org.springframework.core.io.Resource> masterList(
            @AuthenticationPrincipal UserPrincipal principal) {

        List<String> rows = jdbcTemplate.query("""
                SELECT d.code, d.title, d.doc_type, d.status,
                       COALESCE(g.name, '') AS group_name,
                       COALESCE(ua.full_name, '') AS author_name,
                       COALESCE(ur.full_name, '') AS reviewer_name,
                       COALESCE((SELECT COUNT(*) FROM commits c WHERE c.document_id = d.id), 0) AS revisions,
                       COALESCE(TO_CHAR(d.last_reviewed_at, 'YYYY-MM-DD'), '') AS last_reviewed,
                       COALESCE(TO_CHAR(d.next_review_date, 'YYYY-MM-DD'), '') AS next_review,
                       COALESCE(d.review_interval_months::text, '') AS review_interval,
                       COALESCE(d.retention_years::text, '') AS retention,
                       COALESCE(TO_CHAR(d.updated_at, 'YYYY-MM-DD'), '') AS updated
                  FROM documents d
                  LEFT JOIN document_groups g ON g.id = d.document_group_id
                  LEFT JOIN users ua ON ua.id = d.created_by
                  LEFT JOIN users ur ON ur.id = d.assigned_reviewer_id
                 ORDER BY d.code
                """, (rs, n) -> String.join(";",
                        csv(rs.getString("code")), csv(rs.getString("title")),
                        csv(rs.getString("doc_type")), csv(rs.getString("status")),
                        csv(rs.getString("group_name")), csv(rs.getString("author_name")),
                        csv(rs.getString("reviewer_name")), csv(rs.getString("revisions")),
                        csv(rs.getString("last_reviewed")), csv(rs.getString("next_review")),
                        csv(rs.getString("review_interval")), csv(rs.getString("retention")),
                        csv(rs.getString("updated"))));

        StringBuilder csv = new StringBuilder();
        // BOM so Excel opens the accented Portuguese titles correctly.
        csv.append('﻿');
        csv.append("Codigo;Titulo;Tipo;Status;Grupo;Autor;Revisor;Revisoes;")
           .append("Ultima revisao;Proxima revisao;Periodicidade (meses);Retencao (anos);Atualizado\n");
        rows.forEach(r -> csv.append(r).append('\n'));

        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String fileName = "lista-mestra-" + java.time.LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentLength(bytes.length)
                .body(new org.springframework.core.io.ByteArrayResource(bytes));
    }

    /** Escapes a CSV field for the ';' separator used by Excel in pt-BR locales. */
    private static String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(";") || escaped.contains("\n") || escaped.contains("\"")
                ? "\"" + escaped + "\"" : escaped;
    }

    public record ReminderRunDto(int remindersSent) {}

    @PostMapping("/run-review-reminders")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Run the periodic-review reminder now (ISO 8.3.2 b)",
               description = "Same routine the daily job runs. Exists so the reminder can be "
                           + "verified on demand instead of waiting for the scheduled hour, and "
                           + "so a lab can force a sweep after a bulk import.")
    public ResponseEntity<ReminderRunDto> runReviewReminders(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(new ReminderRunDto(reviewReminderJob.notifyDueReviews()));
    }

    public record PeriodicReviewRequest(String notes) {}

    @PostMapping("/{id}/periodic-review")
    @Operation(summary = "Record a periodic review (ISO/IEC 17025 8.3.2 b)",
               description = "Confirms the document was revisited and is still valid. Does NOT "
                           + "create a new version — the evidence required is that the review "
                           + "happened, not that the content changed. Reschedules the next review.")
    public ResponseEntity<DocumentResponse> recordPeriodicReview(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody(required = false) PeriodicReviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        var updated = documentService.recordPeriodicReview(
                principal.getId(), id, request == null ? null : request.notes());
        return ResponseEntity.ok(DocumentResponse.from(updated));
    }

    public record DueForReviewDto(UUID id, String code, String title, String status,
                                   java.time.LocalDate nextReviewDate, long daysOverdue,
                                   String reviewerName) {}

    @GetMapping("/due-for-review")
    @Operation(summary = "Documents whose periodic review is overdue or coming up",
               description = "Feeds the compliance panel. withinDays=0 returns only overdue ones.")
    public ResponseEntity<List<DueForReviewDto>> dueForReview(
            @RequestParam(defaultValue = "30") int withinDays,
            @AuthenticationPrincipal UserPrincipal principal) {

        java.time.LocalDate today = java.time.LocalDate.now();
        List<DueForReviewDto> due = documentService.findDueForReview(withinDays).stream()
                .map(d -> new DueForReviewDto(
                        d.getId(),
                        d.getCode().value(),
                        d.getTitle(),
                        d.getStatus().name(),
                        d.getNextReviewDate(),
                        // Positive = overdue by N days, negative = still N days to go
                        java.time.temporal.ChronoUnit.DAYS.between(d.getNextReviewDate(), today),
                        null))
                .toList();
        return ResponseEntity.ok(due);
    }

    public record DocumentGroupDto(UUID id, String name, String description) {}

    @GetMapping("/groups")
    @Operation(summary = "Get all document groups")
    public ResponseEntity<java.util.List<DocumentGroupDto>> getGroups() {
        var groups = jdbcTemplate.query(
            "SELECT id, name, description FROM document_groups ORDER BY name",
            (rs, rowNum) -> new DocumentGroupDto(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description")
            )
        );
        return ResponseEntity.ok(groups);
    }

    public record VisibilityRequest(String visibility, List<GranteeDto> grantees) {}
    public record GranteeDto(java.util.UUID groupId, java.util.UUID userId) {}
    public record VisibilityDto(String visibility,
                                 List<com.labdms.application.service.DocumentVisibilityService.AccessEntryDto> grantees) {}

    @GetMapping("/{id}/visibility")
    @Operation(summary = "Who can read this document")
    public ResponseEntity<VisibilityDto> getVisibility(@PathVariable java.util.UUID id) {
        List<String> v = jdbcTemplate.query("SELECT visibility FROM documents WHERE id = ?",
                (rs, n) -> rs.getString(1), id);
        if (v.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new VisibilityDto(v.get(0), visibilityService.accessList(id)));
    }

    @PutMapping("/{id}/visibility")
    @Operation(summary = "Change who can read this document",
               description = "PUBLIC publishes it for reading without signing in; PRIVATE is any "
                           + "signed-in user; RESTRICTED is the listed groups and people.")
    public ResponseEntity<VisibilityDto> setVisibility(
            @PathVariable java.util.UUID id,
            @org.springframework.web.bind.annotation.RequestBody VisibilityRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        var doc = documentService.findById(id);
        // Publishing is a document-control decision, not an editing one: it is
        // gated on approval rights, the same as obsoleting a document.
        accessControlService.assertCanApprove(principal.getId(), id);

        String visibility = request.visibility() == null ? "" : request.visibility().toUpperCase();
        if (!List.of("PUBLIC", "PRIVATE", "RESTRICTED").contains(visibility)) {
            throw new IllegalArgumentException("Visibility must be PUBLIC, PRIVATE or RESTRICTED");
        }

        jdbcTemplate.update("UPDATE documents SET visibility = ?, updated_at = NOW() WHERE id = ?",
                visibility, id);

        // The access list only means anything for RESTRICTED; clearing it
        // otherwise stops a stale list from silently taking effect later if the
        // document is switched back.
        visibilityService.replaceAccessList(id,
                "RESTRICTED".equals(visibility) && request.grantees() != null
                        ? request.grantees().stream()
                            .map(g -> new com.labdms.application.service.DocumentVisibilityService.Grantee(
                                    g.groupId(), g.userId()))
                            .toList()
                        : List.of(),
                principal.getId());

        auditLogPort.log(com.labdms.domain.model.audit.AuditEntry.create(
                principal.getId(), "DOCUMENT_VISIBILITY_CHANGED", "Document", id,
                java.util.Map.of("visibility", visibility, "code", doc.getCode().value()), null));

        return ResponseEntity.ok(new VisibilityDto(visibility, visibilityService.accessList(id)));
    }

    public record CreateGroupRequest(String name, String description) {}
    public record UpdateGroupRequest(String name, String description) {}

    @PostMapping("/groups")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a document group/sector (Admin only)")
    public ResponseEntity<DocumentGroupDto> createGroup(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody CreateGroupRequest request,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        java.util.UUID id = java.util.UUID.randomUUID();
        try {
            jdbcTemplate.update(
                    "INSERT INTO document_groups (id, name, description, created_at, updated_at, created_by) " +
                    "VALUES (?, ?, ?, NOW(), NOW(), ?)",
                    id, request.name().trim(), request.description(), principal.getId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("A group with this name already exists: " + request.name());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DocumentGroupDto(id, request.name().trim(), request.description()));
    }

    @PutMapping("/groups/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename/update a document group (Admin only)")
    public ResponseEntity<DocumentGroupDto> updateGroup(@PathVariable java.util.UUID id,
                                                          @org.springframework.web.bind.annotation.RequestBody UpdateGroupRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        int updated;
        try {
            updated = jdbcTemplate.update(
                    "UPDATE document_groups SET name = ?, description = ?, updated_at = NOW() WHERE id = ?",
                    request.name().trim(), request.description(), id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("A group with this name already exists: " + request.name());
        }
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DocumentGroupDto(id, request.name().trim(), request.description()));
    }

    @DeleteMapping("/groups/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a document group (Admin only)",
               description = "Rejected with 409 if any document still references this group")
    public ResponseEntity<Void> deleteGroup(@PathVariable java.util.UUID id) {
        Integer inUse = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE document_group_id = ?", Integer.class, id);
        if (inUse != null && inUse > 0) {
            throw new IllegalStateException("Cannot delete group: " + inUse + " document(s) still use it");
        }
        int deleted = jdbcTemplate.update("DELETE FROM document_groups WHERE id = ?", id);
        if (deleted == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // This endpoint had no authorization at all: any authenticated account,
    // including a read-only Viewer, could create documents by calling it
    // directly. The button was the only thing standing in the way, and a button
    // is not access control — anyone who knew the URL was past it.
    //
    // Checked here rather than in the screen so the rule holds for every caller.
    @PreAuthorize("hasAuthority('DOCUMENT_CREATE')")
    @Operation(summary = "Create a new document", description = "Creates a document and initializes the main branch with the first commit")
    public ResponseEntity<DocumentResponse> createDocument(
            @Valid @RequestBody CreateDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        CreateDocumentCommand command = new CreateDocumentCommand(
                principal.getId(),
                request.code(),
                request.title(),
                com.labdms.domain.model.document.DocumentType.valueOf(request.docType()),
                request.documentGroupId(),
                request.initialLatexSource(),
                request.tags(),
                request.assignedReviewerId(),
                request.reviewIntervalMonths(),
                request.retentionYears(),
                request.requiresAcknowledgement()
        );

        var created = createDocumentUseCase.execute(command);

        // 8.3.2 d) flag. Set here rather than carried through the domain because
        // it is compliance configuration, not something the creation logic acts
        // on — the column already defaults to false, so this only ever turns it on.
        if (Boolean.TRUE.equals(request.requiresAcknowledgement())) {
            jdbcTemplate.update("UPDATE documents SET requires_acknowledgement = true WHERE id = ?",
                    created.getId());
        }

        // Same reasoning as above: reading configuration, not creation logic.
        // An unrecognised value falls back to PRIVATE rather than erroring —
        // failing the whole creation over a visibility typo would lose the
        // document, and PRIVATE reveals nothing that was not already visible.
        String visibility = request.visibility() == null ? "PRIVATE" : request.visibility().toUpperCase();
        if (!List.of("PUBLIC", "PRIVATE", "RESTRICTED").contains(visibility)) {
            visibility = "PRIVATE";
        }
        if (!"PRIVATE".equals(visibility)) {
            jdbcTemplate.update("UPDATE documents SET visibility = ? WHERE id = ?",
                    visibility, created.getId());
            auditLogPort.log(com.labdms.domain.model.audit.AuditEntry.create(
                    principal.getId(), "DOCUMENT_VISIBILITY_CHANGED", "Document", created.getId(),
                    java.util.Map.of("visibility", visibility, "atCreation", "true"), null));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(created));
    }

    /**
     * List row: the document plus the people involved. Author and reviewer names
     * live in another table, so the list is served by a dedicated read query
     * rather than by mapping the domain entity — the same approach already used
     * for reviews, commits and the audit trail in this codebase. It also keeps
     * the list to a single SQL round-trip instead of an N+1 lookup per row.
     */
    public record DocumentListItemDto(
            UUID id, String code, String title, String docType, String status,
            UUID documentGroupId, String groupName,
            UUID createdBy, String authorName,
            UUID assignedReviewerId, String reviewerName,
            UUID currentCommitId, UUID officialCommitId,
            boolean officialIsLatest,
            // ISO 8.3.2 b) — surfaced in the list so an overdue review is visible
            // without opening each document.
            java.time.LocalDate nextReviewDate, boolean reviewOverdue,
            // Shown as an icon per row so who-can-see-this is visible without
            // opening each document.
            String visibility,
            java.time.Instant createdAt, java.time.Instant updatedAt) {}

    @GetMapping
    @Operation(summary = "Search documents",
               description = "Filters: title, code, docType, status, group, author (createdBy) "
                           + "and reviewer (assignedReviewerId). Returns author and reviewer names.")
    public ResponseEntity<PageResponse<DocumentListItemDto>> searchDocuments(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID documentGroupId,
            @RequestParam(required = false) UUID createdBy,
            @RequestParam(required = false) UUID assignedReviewerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        int safeSize = (size <= 0 || size > 200) ? 20 : size;
        int safePage = Math.max(0, page);

        // Every parameter is CAST explicitly: PostgreSQL cannot infer the type of
        // a bare "$1 IS NULL", and an untyped null there aborts the whole query.
        String where = """
                 WHERE (CAST(? AS text) IS NULL OR LOWER(d.title) LIKE LOWER('%' || CAST(? AS text) || '%'))
                   AND (CAST(? AS text) IS NULL OR LOWER(d.code)  LIKE LOWER('%' || CAST(? AS text) || '%'))
                   AND (CAST(? AS text) IS NULL OR d.doc_type = CAST(? AS text))
                   AND (CAST(? AS text) IS NULL OR d.status   = CAST(? AS text))
                   AND (CAST(? AS uuid) IS NULL OR d.document_group_id     = CAST(? AS uuid))
                   AND (CAST(? AS uuid) IS NULL OR d.created_by            = CAST(? AS uuid))
                   AND (CAST(? AS uuid) IS NULL OR d.assigned_reviewer_id  = CAST(? AS uuid))
                """;

        String t = blankToNull(title), c = blankToNull(code);
        String ty = blankToNull(docType), st = blankToNull(status);
        Object[] filters = {
                t, t, c, c, ty, ty, st, st,
                documentGroupId, documentGroupId,
                createdBy, createdBy,
                assignedReviewerId, assignedReviewerId
        };

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents d" + where, Long.class, filters);
        long totalElements = total != null ? total : 0;

        Object[] pageArgs = new Object[filters.length + 2];
        System.arraycopy(filters, 0, pageArgs, 0, filters.length);
        pageArgs[filters.length] = safeSize;
        pageArgs[filters.length + 1] = safePage * safeSize;

        List<DocumentListItemDto> rows = jdbcTemplate.query("""
                SELECT d.id, d.code, d.title, d.doc_type, d.status,
                       d.document_group_id, g.name AS group_name,
                       d.created_by, ua.full_name AS author_name,
                       d.assigned_reviewer_id, ur.full_name AS reviewer_name,
                       d.current_commit_id, d.official_commit_id, d.next_review_date,
                       d.visibility,
                       d.created_at, d.updated_at
                  FROM documents d
                  LEFT JOIN document_groups g ON g.id = d.document_group_id
                  LEFT JOIN users ua ON ua.id = d.created_by
                  LEFT JOIN users ur ON ur.id = d.assigned_reviewer_id
                """ + where + " ORDER BY d.updated_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    UUID current = rs.getObject("current_commit_id", UUID.class);
                    UUID official = rs.getObject("official_commit_id", UUID.class);
                    java.time.LocalDate nextReview = rs.getObject("next_review_date", java.time.LocalDate.class);
                    return new DocumentListItemDto(
                            rs.getObject("id", UUID.class),
                            rs.getString("code"),
                            rs.getString("title"),
                            rs.getString("doc_type"),
                            rs.getString("status"),
                            rs.getObject("document_group_id", UUID.class),
                            rs.getString("group_name"),
                            rs.getObject("created_by", UUID.class),
                            rs.getString("author_name"),
                            rs.getObject("assigned_reviewer_id", UUID.class),
                            rs.getString("reviewer_name"),
                            current,
                            official,
                            official == null || official.equals(current),
                            nextReview,
                            nextReview != null && nextReview.isBefore(java.time.LocalDate.now()),
                            rs.getString("visibility"),
                            toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("updated_at")));
                },
                pageArgs);

        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        return ResponseEntity.ok(PageResponse.of(rows, safePage, safeSize, totalElements, totalPages));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static java.time.Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    @GetMapping("/public")
    @Operation(summary = "Search public documents", description = "Fetch only ACTIVE documents for public access")
    public ResponseEntity<PageResponse<DocumentResponse>> searchPublicDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        SearchDocumentsQuery query = new SearchDocumentsQuery(
                null, null, null,
                com.labdms.domain.model.document.DocumentStatus.ACTIVE, 
                null, null, page, size
        );

        SearchDocumentsUseCase.SearchResult result = searchDocumentsUseCase.execute(query);
        PageResponse<DocumentResponse> response = PageResponse.of(
            result.documents().stream().map(DocumentResponse::from).toList(),
            result.currentPage(),
            size,
            result.totalElements(),
            result.totalPages()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document details")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        DocumentResponse response = DocumentResponse.from(searchDocumentsUseCase.findById(id));
        return ResponseEntity.ok(response);
    }
}
