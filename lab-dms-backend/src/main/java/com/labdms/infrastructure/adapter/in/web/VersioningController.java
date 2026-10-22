package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.dto.command.CommitDocumentCommand;
import com.labdms.application.dto.command.CreateBranchCommand;
import com.labdms.application.dto.command.MergeBranchCommand;
import com.labdms.application.dto.response.BranchResponse;
import com.labdms.application.dto.response.CommitResponse;
import com.labdms.application.service.DocumentVisibilityService;
import com.labdms.application.service.ProjectFileService;
import com.labdms.application.service.VersioningService;
import com.labdms.domain.exception.UnauthorizedAccessException;
import com.labdms.domain.port.in.CreateBranchUseCase;
import com.labdms.domain.port.in.MergeBranchUseCase;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The version history of a document — the "History" side of GitHub Desktop.
 *
 * <p>Writes go through the draft mechanism in {@link VersioningService}: a
 * version is saved by closing the working revision, never by rewriting a
 * version that is already in the history.
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/versioning")
@Tag(name = "Versioning", description = "Git-like version control for documents")
public class VersioningController {

    private final VersioningService versioning;
    private final CreateBranchUseCase createBranchUseCase;
    private final MergeBranchUseCase mergeBranchUseCase;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentVisibilityService visibility;
    private final ProjectFileService projectFiles;

    public VersioningController(VersioningService versioning,
                                CreateBranchUseCase createBranchUseCase,
                                MergeBranchUseCase mergeBranchUseCase,
                                JdbcTemplate jdbcTemplate,
                                DocumentVisibilityService visibility,
                                ProjectFileService projectFiles) {
        this.versioning = versioning;
        this.createBranchUseCase = createBranchUseCase;
        this.mergeBranchUseCase = mergeBranchUseCase;
        this.jdbcTemplate = jdbcTemplate;
        this.visibility = visibility;
        this.projectFiles = projectFiles;
    }

    /* ------------------------------ write ------------------------------ */

    @PostMapping("/branches/{branchId}/commits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a version on a branch (closes the working draft)")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable UUID documentId,
            @PathVariable UUID branchId,
            @Valid @RequestBody CommitDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        CommitDocumentCommand command = new CommitDocumentCommand(
            principal.getId(), documentId, branchId,
            request.latexSource(), request.message(), request.changeReason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommitResponse.from(versioning.execute(command)));
    }

    @PostMapping("/commits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a version on the default branch (closes the working draft)")
    public ResponseEntity<CommitResponse> commitToDefault(
            @PathVariable UUID documentId,
            @Valid @RequestBody CommitDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        CommitDocumentCommand command = new CommitDocumentCommand(
            principal.getId(), documentId, defaultBranchId(documentId),
            request.latexSource(), request.message(), request.changeReason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommitResponse.from(versioning.execute(command)));
    }

    /**
     * "Discard changes": drops the working revision and returns to the last
     * saved version. Nothing in the history changes.
     */
    @DeleteMapping("/working")
    @Operation(summary = "Discard the working draft (unsaved changes)")
    public ResponseEntity<Void> discardDraft(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        versioning.discardDraft(documentId, principal.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new branch (draft) from current HEAD")
    public ResponseEntity<BranchResponse> createBranch(
            @PathVariable UUID documentId,
            @Valid @RequestBody CreateBranchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        CreateBranchCommand command = new CreateBranchCommand(
            principal.getId(), documentId, request.branchName());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(BranchResponse.from(createBranchUseCase.execute(command)));
    }

    @PostMapping("/branches/{branchId}/merge")
    @Operation(summary = "Merge an approved branch into main (requires approved review)")
    public ResponseEntity<Void> mergeBranch(
            @PathVariable UUID documentId,
            @PathVariable UUID branchId,
            @AuthenticationPrincipal UserPrincipal principal) {

        mergeBranchUseCase.execute(new MergeBranchCommand(principal.getId(), documentId, branchId));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/commits/{commitId}/restore")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Restore an older version",
               description = "Git-style revert: creates a NEW version with the tree of the chosen one, "
                           + "so the history stays immutable (ISO traceability). Unsaved changes in "
                           + "the working draft are replaced.")
    public ResponseEntity<CommitResponse> restoreCommit(
            @PathVariable UUID documentId,
            @PathVariable UUID commitId,
            @RequestBody(required = false) RestoreRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        Integer revision = revisionOf(documentId, commitId);
        String message = VersioningService.RESTORE_MESSAGE_PREFIX + (revision != null ? revision : "?");
        String reason = request != null && request.changeReason() != null && !request.changeReason().isBlank()
                ? request.changeReason().trim()
                : "Conteúdo restaurado da versão v" + (revision != null ? revision : "?") + ".";

        CommitDocumentCommand command = new CommitDocumentCommand(
            principal.getId(), documentId, defaultBranchId(documentId), null, message, reason);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommitResponse.from(versioning.restore(command, commitId)));
    }

    public record RestoreRequest(String changeReason) {}

    /* ------------------------------- read ------------------------------ */

    /**
     * A version in the document history.
     *
     * @param revision  sequential number among CLOSED versions (v1 = first);
     *                  0 for the working draft
     * @param isDraft   the working revision — unsaved changes, not a version
     * @param fileCount files in this version's tree
     * @param treeBytes total size of this version's tree
     */
    public record CommitHistoryDto(UUID id, String message, String authorName,
                                   java.time.Instant createdAt, int revision,
                                   boolean isCurrent, boolean isOfficial, boolean isDraft,
                                   String branchName, UUID parentCommitId,
                                   int fileCount, long treeBytes,
                                   /** ISO 8.3.2 c) justification for this revision. */
                                   String changeReason) {}

    @GetMapping("/commits")
    @Operation(summary = "Get version history for a document (newest first)",
               description = "The working draft, when there is one, comes first with isDraft=true "
                           + "and revision 0; closed versions are numbered v1..vN.")
    public ResponseEntity<List<CommitHistoryDto>> getCommits(@PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        assertCanRead(documentId, principal);

        List<CommitHistoryDto> commits = jdbcTemplate.query("""
            SELECT c.id, c.message, c.change_reason, c.is_draft, u.full_name AS author_name, c.created_at,
                   c.parent_commit_id, b.name AS branch_name,
                   CASE WHEN c.is_draft THEN 0
                        ELSE ROW_NUMBER() OVER (PARTITION BY c.is_draft ORDER BY c.created_at ASC) END AS revision,
                   (d.current_commit_id = c.id) AS is_current,
                   (d.official_commit_id = c.id) AS is_official,
                   COALESCE(t.n, 0) AS file_count, COALESCE(t.bytes, 0) AS tree_bytes
              FROM commits c
              JOIN users u ON c.author_id = u.id
              JOIN documents d ON d.id = c.document_id
              LEFT JOIN branches b ON b.id = c.branch_id
              LEFT JOIN (SELECT commit_id, COUNT(*) AS n, SUM(size_bytes) AS bytes
                           FROM project_files GROUP BY commit_id) t ON t.commit_id = c.id
             WHERE c.document_id = ?
             ORDER BY c.is_draft DESC, c.created_at DESC
            """,
            (rs, rowNum) -> new CommitHistoryDto(
                rs.getObject("id", UUID.class),
                rs.getString("message"),
                rs.getString("author_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("revision"),
                rs.getBoolean("is_current"),
                rs.getBoolean("is_official"),
                rs.getBoolean("is_draft"),
                rs.getString("branch_name"),
                rs.getObject("parent_commit_id", UUID.class),
                rs.getInt("file_count"),
                rs.getLong("tree_bytes"),
                rs.getString("change_reason")
            ),
            documentId
        );
        return ResponseEntity.ok(commits);
    }

    public record BranchDto(UUID id, String name, boolean isDefault, String status,
                            UUID headCommitId, java.time.Instant updatedAt) {}

    @GetMapping("/branches")
    @Operation(summary = "List branches of a document")
    public ResponseEntity<List<BranchDto>> getBranches(@PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        assertCanRead(documentId, principal);
        return ResponseEntity.ok(jdbcTemplate.query(
            "SELECT id, name, is_default, status, head_commit_id, updated_at " +
            "FROM branches WHERE document_id = ? ORDER BY is_default DESC, name",
            (rs, rowNum) -> new BranchDto(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("is_default"),
                rs.getString("status"),
                rs.getObject("head_commit_id", UUID.class),
                rs.getTimestamp("updated_at").toInstant()
            ),
            documentId));
    }

    public record CommitContentDto(String content, String path) {}

    /**
     * The text of a version's root file.
     *
     * <p>Read from the TREE when the version has one. The snapshot stored with
     * the commit is taken when the version is closed; for the working draft it
     * would be stale the moment a file was saved — and an editor opened on the
     * stale text would overwrite the newer file on its next save.
     */
    @GetMapping("/commits/{commitId}/content")
    @Operation(summary = "Get the root LaTeX content for a specific version")
    public ResponseEntity<CommitContentDto> getCommitContent(
            @PathVariable UUID documentId,
            @PathVariable UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {
        assertCanRead(documentId, principal);
        assertBelongs(documentId, commitId);

        String rootPath = projectFiles.rootPath(commitId);
        if (rootPath != null) {
            try {
                return ResponseEntity.ok(new CommitContentDto(projectFiles.readTextFile(commitId, rootPath), rootPath));
            } catch (IllegalArgumentException binaryRoot) {
                // falls through to the snapshot
            }
        }
        var rows = jdbcTemplate.queryForList(
            "SELECT s.latex_source FROM content_snapshots s JOIN commits c ON s.id = c.content_snapshot_id WHERE c.id = ?",
            String.class, commitId);
        return ResponseEntity.ok(new CommitContentDto(rows.isEmpty() ? "" : rows.get(0), rootPath));
    }

    /* ------------------------------ helpers ---------------------------- */

    private void assertCanRead(UUID documentId, UserPrincipal principal) {
        UUID userId = principal == null ? null : principal.getId();
        if (!visibility.canRead(userId, documentId)) {
            throw new UnauthorizedAccessException("view");
        }
    }

    /** A commit id in the URL must belong to the document in the URL. */
    private void assertBelongs(UUID documentId, UUID commitId) {
        List<UUID> owner = jdbcTemplate.queryForList(
                "SELECT document_id FROM commits WHERE id = ?", UUID.class, commitId);
        if (owner.isEmpty() || !documentId.equals(owner.get(0))) {
            throw new IllegalArgumentException("This version does not belong to this document");
        }
    }

    private UUID defaultBranchId(UUID documentId) {
        List<UUID> ids = jdbcTemplate.queryForList(
            "SELECT id FROM branches WHERE document_id = ? AND is_default = true", UUID.class, documentId);
        if (ids.isEmpty()) throw new IllegalStateException("This document has no default branch");
        return ids.get(0);
    }

    private Integer revisionOf(UUID documentId, UUID commitId) {
        List<Integer> rows = jdbcTemplate.queryForList("""
            SELECT revision FROM (
                SELECT id, ROW_NUMBER() OVER (ORDER BY created_at ASC) AS revision
                  FROM commits WHERE document_id = ? AND NOT is_draft
            ) t WHERE id = ?
            """, Integer.class, documentId, commitId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
