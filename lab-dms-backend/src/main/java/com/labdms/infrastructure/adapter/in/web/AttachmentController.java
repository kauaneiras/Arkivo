package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.service.AccessControlService;
import com.labdms.application.service.DocumentService;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.adapter.out.storage.LocalAttachmentStorage;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Attachments are external files (scanned calibration certificates, test photos,
 * spreadsheets) kept alongside a document's LaTeX source and version history.
 * They are NOT versioned like the LaTeX content — each attachment is its own row,
 * uploaded/removed independently, which matches how labs actually handle scanned
 * paperwork (replace the certificate, don't diff it).
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/attachments")
@Tag(name = "Attachments", description = "External files attached to a document")
public class AttachmentController {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // xlsx
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
            "application/vnd.ms-excel",
            "text/csv", "text/plain"
    );
    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    private final JdbcTemplate jdbcTemplate;
    private final LocalAttachmentStorage storage;
    private final DocumentService documentService;
    private final AccessControlService accessControlService;
    private final AuditLogPort auditLogPort;

    public AttachmentController(JdbcTemplate jdbcTemplate, LocalAttachmentStorage storage,
                                 DocumentService documentService, AccessControlService accessControlService,
                                 AuditLogPort auditLogPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
        this.documentService = documentService;
        this.accessControlService = accessControlService;
        this.auditLogPort = auditLogPort;
    }

    public record AttachmentDto(UUID id, String fileName, String contentType, long sizeBytes,
                                 String description, String uploadedByName, Instant uploadedAt) {}

    @GetMapping
    @Operation(summary = "List a document's attachments")
    public ResponseEntity<List<AttachmentDto>> list(@PathVariable UUID documentId,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        Document document = documentService.findById(documentId);
        accessControlService.assertCanView(principal.getId(), document.getDocumentGroupId());

        List<AttachmentDto> attachments = jdbcTemplate.query(
                "SELECT a.id, a.file_name, a.content_type, a.size_bytes, a.description, " +
                "u.full_name AS uploaded_by_name, a.uploaded_at " +
                "FROM document_attachments a JOIN users u ON u.id = a.uploaded_by " +
                "WHERE a.document_id = ? ORDER BY a.uploaded_at DESC",
                (rs, rowNum) -> new AttachmentDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("description"),
                        rs.getString("uploaded_by_name"),
                        rs.getTimestamp("uploaded_at").toLocalDateTime().toInstant(ZoneOffset.UTC)
                ),
                documentId);
        return ResponseEntity.ok(attachments);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload an attachment",
               description = "Max 20MB. Allowed types: PDF, images, Office spreadsheets/docs, CSV, plain text.")
    public ResponseEntity<AttachmentDto> upload(@PathVariable UUID documentId,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) String description,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        Document document = documentService.findById(documentId);
        accessControlService.assertCanEdit(principal.getId(), document.getDocumentGroupId());

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Empty file");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds the 20MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("File type not allowed: " + contentType
                    + ". Allowed: PDF, PNG, JPEG, GIF, XLSX, XLS, DOCX, CSV, TXT");
        }

        String storagePath = storage.save(documentId, file);
        UUID id = UUID.randomUUID();
        String safeFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "arquivo";

        jdbcTemplate.update(
                "INSERT INTO document_attachments (id, document_id, file_name, content_type, size_bytes, " +
                "storage_path, description, uploaded_by, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, documentId, safeFileName, contentType, file.getSize(), storagePath, description,
                principal.getId(), LocalDateTime.now());

        auditLogPort.log(AuditEntry.create(
                principal.getId(), "ATTACHMENT_UPLOADED", "Document", documentId,
                Map.of("fileName", safeFileName, "sizeBytes", String.valueOf(file.getSize())), null));

        AttachmentDto dto = new AttachmentDto(id, safeFileName, contentType, file.getSize(),
                description, principal.getFullName(), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/{attachmentId}/download")
    @Operation(summary = "Download an attachment")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable UUID documentId, @PathVariable UUID attachmentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Document document = documentService.findById(documentId);
        accessControlService.assertCanView(principal.getId(), document.getDocumentGroupId());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT file_name, content_type, storage_path FROM document_attachments " +
                "WHERE id = ? AND document_id = ?", attachmentId, documentId);

        String storagePath = (String) row.get("storage_path");
        String fileName = (String) row.get("file_name");
        String contentType = (String) row.get("content_type");
        var resource = new FileSystemResource(storage.resolve(storagePath));
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String encodedName = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    @DeleteMapping("/{attachmentId}")
    @Operation(summary = "Delete an attachment")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId, @PathVariable UUID attachmentId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        Document document = documentService.findById(documentId);
        accessControlService.assertCanEdit(principal.getId(), document.getDocumentGroupId());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT file_name, storage_path FROM document_attachments WHERE id = ? AND document_id = ?",
                attachmentId, documentId);

        jdbcTemplate.update("DELETE FROM document_attachments WHERE id = ? AND document_id = ?",
                attachmentId, documentId);
        storage.delete((String) row.get("storage_path"));

        auditLogPort.log(AuditEntry.create(
                principal.getId(), "ATTACHMENT_DELETED", "Document", documentId,
                Map.of("fileName", (String) row.get("file_name")), null));

        return ResponseEntity.noContent().build();
    }
}
