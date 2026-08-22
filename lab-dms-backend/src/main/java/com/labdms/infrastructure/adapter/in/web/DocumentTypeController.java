package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.service.DocumentTypeService;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administration of the laboratory's document types.
 *
 * <p>Reading is open to any authenticated user, because the document creation
 * form needs the list. Every change requires ADMIN: the taxonomy decides how
 * documents are identified for the life of the system, so it is not something a
 * document author should be able to alter while filling in a form.
 */
@RestController
@RequestMapping("/api/v1/document-types")
@Tag(name = "Document types", description = "The laboratory's document taxonomy (admin-maintained)")
public class DocumentTypeController {

    private final DocumentTypeService service;
    private final AuditLogPort auditLog;

    public DocumentTypeController(DocumentTypeService service, AuditLogPort auditLog) {
        this.service = service;
        this.auditLog = auditLog;
    }

    @GetMapping
    @Operation(summary = "List document types",
               description = "Only active types by default — a retired type must not be offered "
                           + "for new documents, while existing ones keep resolving.")
    public ResponseEntity<List<DocumentTypeService.TypeDto>> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(service.list(includeInactive));
    }

    public record CreateRequest(String prefix, String name, String description,
                                 Integer reviewIntervalMonths, Integer retentionYears,
                                 Integer sortOrder) {}

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a document type (Admin only)",
               description = "The prefix becomes part of every code issued under this type and "
                           + "cannot be changed afterwards.")
    public ResponseEntity<DocumentTypeService.TypeDto> create(
            @RequestBody CreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        var created = service.create(new DocumentTypeService.CreateTypeCommand(
                request.prefix(), request.name(), request.description(),
                request.reviewIntervalMonths(), request.retentionYears(), request.sortOrder()),
                principal.getId());

        audit(principal.getId(), "DOCUMENT_TYPE_CREATED", created.id(),
                Map.of("prefix", created.prefix(), "name", created.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** No prefix field on purpose — see DocumentTypeService#update. */
    public record UpdateRequest(String name, String description,
                                 Integer reviewIntervalMonths, Integer retentionYears,
                                 Integer sortOrder, Boolean active) {}

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename or reconfigure a document type (Admin only)",
               description = "The prefix is immutable: it is already part of the codes issued.")
    public ResponseEntity<DocumentTypeService.TypeDto> update(
            @PathVariable UUID id,
            @RequestBody UpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        var updated = service.update(id, new DocumentTypeService.UpdateTypeCommand(
                request.name(), request.description(), request.reviewIntervalMonths(),
                request.retentionYears(), request.sortOrder(), request.active()),
                principal.getId());

        audit(principal.getId(), "DOCUMENT_TYPE_UPDATED", id,
                Map.of("prefix", updated.prefix(), "name", updated.name(),
                        "active", String.valueOf(updated.active())));
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a document type (Admin only)",
               description = "Deleted when no document ever used it, deactivated otherwise — "
                           + "removing a type in use would orphan existing documents.")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        boolean deleted = service.deleteOrDeactivate(id, principal.getId());
        audit(principal.getId(), deleted ? "DOCUMENT_TYPE_DELETED" : "DOCUMENT_TYPE_DEACTIVATED",
                id, Map.of());
        return ResponseEntity.ok(Map.of("deleted", deleted, "deactivated", !deleted));
    }

    private void audit(UUID userId, String action, UUID typeId, Map<String, Object> details) {
        auditLog.log(AuditEntry.create(userId, action, "DocumentType", typeId, details, null));
    }
}
