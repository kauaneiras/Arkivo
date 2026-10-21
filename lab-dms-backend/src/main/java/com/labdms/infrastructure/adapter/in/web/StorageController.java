package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.service.DocumentVisibilityService;
import com.labdms.domain.exception.UnauthorizedAccessException;
import com.labdms.infrastructure.adapter.out.storage.BlobStore;
import com.labdms.infrastructure.config.BlobGarbageCollector;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How much the documents cost in storage, and where.
 *
 * <p>Two levels. The installation view (administrators) answers "how big is
 * the database, which tables, how much is deduplicated, how much is garbage".
 * The per-document view (anyone who can read the document) answers "how much
 * does this document weigh across all its versions, and what would it weigh
 * without deduplication".
 *
 * <p>All numbers come from the database at request time; nothing is cached or
 * estimated, so what the screen shows is what {@code pg_database_size} says.
 */
@RestController
@RequestMapping("/api/v1/storage")
@Tag(name = "Storage", description = "Database and blob storage consumption")
public class StorageController {

    private final JdbcTemplate jdbcTemplate;
    private final BlobStore blobStore;
    private final BlobGarbageCollector collector;
    private final DocumentVisibilityService visibility;

    public StorageController(JdbcTemplate jdbcTemplate, BlobStore blobStore,
                             BlobGarbageCollector collector, DocumentVisibilityService visibility) {
        this.jdbcTemplate = jdbcTemplate;
        this.blobStore = blobStore;
        this.collector = collector;
        this.visibility = visibility;
    }

    public record TableSizeDto(String table, long rows, long totalBytes, long tableBytes, long indexBytes) {}

    public record OverviewDto(
            long databaseBytes,
            List<TableSizeDto> tables,
            long documents, long versions, long drafts, long fileRows,
            long blobs, long textBlobs, long binaryBlobs,
            /** Bytes of every file in every version, as if nothing were shared. */
            long logicalBytes,
            /** Bytes of unique content, before compression. */
            long uniqueBytes,
            /** Bytes actually stored (text inline, binaries on disk, compressed when it pays). */
            long storedBytes,
            long textStoredBytes, long binaryStoredBytes,
            long compiledPdfBytes, long compiledPdfs,
            long attachmentBytes, long attachments,
            long orphanBlobs, long orphanBytes, int gcGraceHours,
            double dedupSavingsPercent) {}

    @GetMapping("/overview")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Installation-wide storage consumption (Admin only)")
    public ResponseEntity<OverviewDto> overview() {
        long dbBytes = scalar("SELECT pg_database_size(current_database())");

        List<TableSizeDto> tables = jdbcTemplate.query("""
                SELECT c.relname AS table_name,
                       COALESCE(s.n_live_tup, 0) AS rows,
                       pg_total_relation_size(c.oid) AS total_bytes,
                       pg_relation_size(c.oid) AS table_bytes,
                       pg_indexes_size(c.oid) AS index_bytes
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                 WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                 ORDER BY pg_total_relation_size(c.oid) DESC
                """, (rs, i) -> new TableSizeDto(rs.getString("table_name"), rs.getLong("rows"),
                        rs.getLong("total_bytes"), rs.getLong("table_bytes"), rs.getLong("index_bytes")));

        Map<String, Object> blobs = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS blobs,
                       COUNT(*) FILTER (WHERE is_text) AS text_blobs,
                       COUNT(*) FILTER (WHERE NOT is_text) AS binary_blobs,
                       COALESCE(SUM(size_bytes), 0) AS unique_bytes,
                       COALESCE(SUM(COALESCE(stored_bytes, size_bytes)), 0) AS stored_bytes,
                       COALESCE(SUM(COALESCE(stored_bytes, size_bytes)) FILTER (WHERE is_text), 0) AS text_stored,
                       COALESCE(SUM(COALESCE(stored_bytes, size_bytes)) FILTER (WHERE NOT is_text), 0) AS binary_stored
                  FROM file_blobs
                """);

        Map<String, Object> files = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS rows, COALESCE(SUM(size_bytes), 0) AS logical FROM project_files");

        Map<String, Object> commits = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE NOT is_draft) AS versions, COUNT(*) FILTER (WHERE is_draft) AS drafts FROM commits");

        Map<String, Object> pdfs = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS n, COALESCE(SUM(octet_length(pdf_data)), 0) AS bytes FROM compiled_pdfs");

        Map<String, Object> attachments = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS n, COALESCE(SUM(size_bytes), 0) AS bytes FROM document_attachments");

        List<BlobStore.Orphan> orphans = blobStore.findOrphans(collector.getGraceHours());
        long orphanBytes = orphans.stream().mapToLong(BlobStore.Orphan::storedBytes).sum();

        long logical = num(files.get("logical"));
        long stored = num(blobs.get("stored_bytes"));
        double savings = logical == 0 ? 0 : Math.max(0, 100.0 * (logical - stored) / logical);

        return ResponseEntity.ok(new OverviewDto(
                dbBytes, tables,
                scalar("SELECT COUNT(*) FROM documents"),
                num(commits.get("versions")), num(commits.get("drafts")), num(files.get("rows")),
                num(blobs.get("blobs")), num(blobs.get("text_blobs")), num(blobs.get("binary_blobs")),
                logical, num(blobs.get("unique_bytes")), stored,
                num(blobs.get("text_stored")), num(blobs.get("binary_stored")),
                num(pdfs.get("bytes")), num(pdfs.get("n")),
                num(attachments.get("bytes")), num(attachments.get("n")),
                orphans.size(), orphanBytes, collector.getGraceHours(),
                Math.round(savings * 10) / 10.0));
    }

    public record DocumentStorageDto(UUID id, String code, String title, String status,
                                     long versions, boolean hasDraft, long filesInCurrent,
                                     long currentBytes, long logicalBytes, long uniqueBytes,
                                     long storedBytes, long compiledPdfBytes, long attachmentBytes) {}

    private static final String PER_DOCUMENT_SQL = """
            SELECT d.id, d.code, d.title, d.status,
                   (SELECT COUNT(*) FROM commits c WHERE c.document_id = d.id AND NOT c.is_draft) AS versions,
                   EXISTS (SELECT 1 FROM commits c WHERE c.document_id = d.id AND c.is_draft) AS has_draft,
                   (SELECT COUNT(*) FROM project_files pf WHERE pf.commit_id = d.current_commit_id) AS files_current,
                   (SELECT COALESCE(SUM(pf.size_bytes), 0) FROM project_files pf WHERE pf.commit_id = d.current_commit_id) AS current_bytes,
                   (SELECT COALESCE(SUM(pf.size_bytes), 0) FROM project_files pf
                      JOIN commits c ON c.id = pf.commit_id WHERE c.document_id = d.id) AS logical_bytes,
                   (SELECT COALESCE(SUM(b.size_bytes), 0) FROM file_blobs b
                     WHERE b.sha256 IN (SELECT DISTINCT pf.blob_sha256 FROM project_files pf
                                          JOIN commits c ON c.id = pf.commit_id WHERE c.document_id = d.id)) AS unique_bytes,
                   (SELECT COALESCE(SUM(COALESCE(b.stored_bytes, b.size_bytes)), 0) FROM file_blobs b
                     WHERE b.sha256 IN (SELECT DISTINCT pf.blob_sha256 FROM project_files pf
                                          JOIN commits c ON c.id = pf.commit_id WHERE c.document_id = d.id)) AS stored_bytes,
                   (SELECT COALESCE(SUM(octet_length(p.pdf_data)), 0) FROM compiled_pdfs p
                      JOIN commits c ON c.id = p.commit_id WHERE c.document_id = d.id) AS pdf_bytes,
                   (SELECT COALESCE(SUM(a.size_bytes), 0) FROM document_attachments a WHERE a.document_id = d.id) AS attachment_bytes
              FROM documents d
            """;

    @GetMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Storage per document, heaviest first (Admin only)")
    public ResponseEntity<List<DocumentStorageDto>> perDocument(@RequestParam(defaultValue = "100") int limit) {
        int safe = Math.max(1, Math.min(limit, 1000));
        return ResponseEntity.ok(jdbcTemplate.query(PER_DOCUMENT_SQL + " ORDER BY stored_bytes DESC, d.code LIMIT ?",
                this::mapDocument, safe));
    }

    @GetMapping("/documents/{id}")
    @Operation(summary = "Storage of one document, across all its versions")
    public ResponseEntity<DocumentStorageDto> oneDocument(@PathVariable UUID id,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        if (!visibility.canRead(principal == null ? null : principal.getId(), id)) {
            throw new UnauthorizedAccessException("view");
        }
        List<DocumentStorageDto> rows = jdbcTemplate.query(PER_DOCUMENT_SQL + " WHERE d.id = ?", this::mapDocument, id);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.get(0));
    }

    public record GcResultDto(int removed) {}

    @PostMapping("/gc")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Run the blob garbage collector now (Admin only)",
               description = "Deletes blobs no version references, older than the grace period.")
    public ResponseEntity<GcResultDto> runGc() {
        return ResponseEntity.ok(new GcResultDto(collector.runNow()));
    }

    /* ------------------------------------------------------------------ */

    private DocumentStorageDto mapDocument(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DocumentStorageDto(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("title"), rs.getString("status"),
                rs.getLong("versions"), rs.getBoolean("has_draft"), rs.getLong("files_current"),
                rs.getLong("current_bytes"), rs.getLong("logical_bytes"), rs.getLong("unique_bytes"),
                rs.getLong("stored_bytes"), rs.getLong("pdf_bytes"), rs.getLong("attachment_bytes"));
    }

    private long scalar(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
