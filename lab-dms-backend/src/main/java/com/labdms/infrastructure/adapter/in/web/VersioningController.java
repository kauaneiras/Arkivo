package com.labdms.infrastructure.adapter.in.web;

import com.labdms.application.dto.command.CommitDocumentCommand;
import com.labdms.application.dto.command.CreateBranchCommand;
import com.labdms.application.dto.command.MergeBranchCommand;
import com.labdms.application.dto.response.BranchResponse;
import com.labdms.application.dto.response.CommitResponse;
import com.labdms.domain.port.in.CommitDocumentUseCase;
import com.labdms.domain.port.in.CreateBranchUseCase;
import com.labdms.domain.port.in.MergeBranchUseCase;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/versioning")
@Tag(name = "Versioning", description = "Git-like version control for documents")
public class VersioningController {

    private final CommitDocumentUseCase commitUseCase;
    private final CreateBranchUseCase createBranchUseCase;
    private final MergeBranchUseCase mergeBranchUseCase;

    public VersioningController(CommitDocumentUseCase commitUseCase, 
                                CreateBranchUseCase createBranchUseCase, 
                                MergeBranchUseCase mergeBranchUseCase) {
        this.commitUseCase = commitUseCase;
        this.createBranchUseCase = createBranchUseCase;
        this.mergeBranchUseCase = mergeBranchUseCase;
    }

    @PostMapping("/branches/{branchId}/commits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new commit on a branch")
    public ResponseEntity<CommitResponse> commit(
            @PathVariable UUID documentId,
            @PathVariable UUID branchId,
            @Valid @RequestBody CommitDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        CommitDocumentCommand command = new CommitDocumentCommand(
            principal.getId(), documentId, branchId,
            request.latexSource(), request.message()
        );
        CommitResponse response = CommitResponse.from(commitUseCase.execute(command));
        recordChangeReason(response.id(), request.changeReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Stores the ISO 8.3.2 c) change justification alongside the commit.
     *
     * <p>Written here rather than threaded through the domain command because it
     * is compliance metadata about the revision, not something the versioning
     * logic acts on — the commit is valid with or without it. Kept immutable
     * afterwards: the reason belongs to that revision forever.
     */
    private void recordChangeReason(UUID commitId, String changeReason) {
        if (commitId == null || changeReason == null || changeReason.isBlank()) return;
        jdbcTemplate.update("UPDATE commits SET change_reason = ? WHERE id = ?",
                changeReason.trim(), commitId);
    }

    @PostMapping("/commits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new commit on the default branch (shortcut for UI)")
    public ResponseEntity<CommitResponse> commitToDefault(
            @PathVariable UUID documentId,
            @Valid @RequestBody CommitDocumentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        // Fetch default branch ID
        UUID branchId = jdbcTemplate.queryForObject(
            "SELECT id FROM branches WHERE document_id = ? AND is_default = true",
            UUID.class, documentId
        );

        CommitDocumentCommand command = new CommitDocumentCommand(
            principal.getId(), documentId, branchId,
            request.latexSource(), request.message()
        );
        CommitResponse response = CommitResponse.from(commitUseCase.execute(command));
        recordChangeReason(response.id(), request.changeReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new branch (draft) from current HEAD")
    public ResponseEntity<BranchResponse> createBranch(
            @PathVariable UUID documentId,
            @Valid @RequestBody CreateBranchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        CreateBranchCommand command = new CreateBranchCommand(
            principal.getId(), documentId, request.branchName()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(BranchResponse.from(createBranchUseCase.execute(command)));
    }

    @PostMapping("/branches/{branchId}/merge")
    @Operation(summary = "Merge an approved branch into main (requires approved review)")
    public ResponseEntity<Void> mergeBranch(
            @PathVariable UUID documentId,
            @PathVariable UUID branchId,
            @AuthenticationPrincipal UserPrincipal principal) {

        MergeBranchCommand command = new MergeBranchCommand(
            principal.getId(), documentId, branchId
        );
        mergeBranchUseCase.execute(command);
        return ResponseEntity.ok().build();
    }

    // Direct JDBC queries for simple UI fetch
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private com.labdms.application.service.DocumentVisibilityService visibility;

    @org.springframework.beans.factory.annotation.Autowired
    public void setVisibility(com.labdms.application.service.DocumentVisibilityService visibility) {
        this.visibility = visibility;
    }

    /**
     * Ninguém lê a história de um documento que não pode ler.
     *
     * <p>Os três métodos de leitura deste controlador não recebiam sequer o
     * utilizador: bastava saber o identificador para obter o histórico de
     * versões e o LaTeX inteiro de qualquer documento, incluindo os restritos.
     * A visibilidade era decidida noutro sítio e este caminho passava ao lado
     * dela — que é o modo clássico de um controlo de acesso existir e não
     * proteger nada.
     *
     * <p>Usa a mesma regra de leitura que serve o resto do sistema, em vez de
     * uma verificação própria: duas regras paralelas divergem, e a que diverge
     * é sempre a que ninguém está a olhar.
     */
    private void assertCanRead(UUID documentId, UserPrincipal principal) {
        UUID userId = principal == null ? null : principal.getId();
        if (!visibility.canRead(userId, documentId)) {
            throw new com.labdms.domain.exception.UnauthorizedAccessException("view");
        }
    }

    /**
     * A version in the document history.
     *
     * @param revision  sequential revision number (v1 = first version), ISO-style
     * @param isCurrent whether this is the version the document currently points to
     * @param branchName branch this version belongs to
     */
    public record CommitHistoryDto(UUID id, String message, String authorName,
                                   java.time.Instant createdAt, int revision,
                                   boolean isCurrent, boolean isOfficial, String branchName,
                                   UUID parentCommitId, int sizeBytes,
                                   /** ISO 8.3.2 c) justification for this revision. */
                                   String changeReason) {}

    @GetMapping("/commits")
    @Operation(summary = "Get version history for a document (newest first)")
    public ResponseEntity<List<CommitHistoryDto>> getCommits(@PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        assertCanRead(documentId, principal);

        List<CommitHistoryDto> commits = jdbcTemplate.query("""
            SELECT c.id, c.message, c.change_reason, u.full_name AS author_name, c.created_at,
                   c.parent_commit_id, b.name AS branch_name, s.size_bytes,
                   ROW_NUMBER() OVER (ORDER BY c.created_at ASC) AS revision,
                   (d.current_commit_id = c.id) AS is_current,
                   (d.official_commit_id = c.id) AS is_official
            FROM commits c
            JOIN users u ON c.author_id = u.id
            JOIN documents d ON d.id = c.document_id
            LEFT JOIN branches b ON b.id = c.branch_id
            LEFT JOIN content_snapshots s ON s.id = c.content_snapshot_id
            WHERE c.document_id = ?
            ORDER BY c.created_at DESC
            """,
            (rs, rowNum) -> new CommitHistoryDto(
                rs.getObject("id", UUID.class),
                rs.getString("message"),
                rs.getString("author_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("revision"),
                rs.getBoolean("is_current"),
                rs.getBoolean("is_official"),
                rs.getString("branch_name"),
                rs.getObject("parent_commit_id", UUID.class),
                rs.getInt("size_bytes"),
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

    @PostMapping("/commits/{commitId}/restore")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Restore an older version",
               description = "Git-style revert: creates a NEW version with the content of the "
                           + "chosen one, so the history stays immutable (ISO traceability)")
    public ResponseEntity<CommitResponse> restoreCommit(
            @PathVariable UUID documentId,
            @PathVariable UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {

        // Same nullable-snapshot case as getCommitContent: a multi-file version
        // has no single-string snapshot, and restore must still work for it —
        // the tree is what gets restored, via commitUseCase.restore below.
        var contentRows = jdbcTemplate.queryForList(
            "SELECT s.latex_source FROM content_snapshots s " +
            "JOIN commits c ON s.id = c.content_snapshot_id WHERE c.id = ? AND c.document_id = ?",
            String.class, commitId, documentId);
        String content = contentRows.isEmpty() ? "" : contentRows.get(0);

        Integer revision = jdbcTemplate.queryForObject("""
            SELECT revision FROM (
                SELECT id, ROW_NUMBER() OVER (ORDER BY created_at ASC) AS revision
                FROM commits WHERE document_id = ?
            ) t WHERE id = ?
            """, Integer.class, documentId, commitId);

        UUID branchId = jdbcTemplate.queryForObject(
            "SELECT id FROM branches WHERE document_id = ? AND is_default = true",
            UUID.class, documentId);

        CommitDocumentCommand command = new CommitDocumentCommand(
            principal.getId(), documentId, branchId, content,
            "Restore version v" + (revision != null ? revision : "?"));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommitResponse.from(commitUseCase.restore(command, commitId)));
    }

    public record CommitContentDto(String content) {}

    @GetMapping("/commits/{commitId}/content")
    @Operation(summary = "Get LaTeX content for a specific commit")
    public ResponseEntity<CommitContentDto> getCommitContent(
            @PathVariable UUID documentId,
            @PathVariable UUID commitId,
            @AuthenticationPrincipal UserPrincipal principal) {
        assertCanRead(documentId, principal);

        // queryForList, not queryForObject: V9 made commits.content_snapshot_id
        // nullable (a version can now be a file tree with no single-string
        // snapshot), so this JOIN legitimately returns NO row — and
        // queryForObject turns that into a 500 on a request the editor makes
        // every time a document is opened.
        var rows = jdbcTemplate.queryForList(
            "SELECT s.latex_source FROM content_snapshots s JOIN commits c ON s.id = c.content_snapshot_id WHERE c.id = ?",
            String.class, commitId);
        String content = rows.isEmpty() ? "" : rows.get(0);
        return ResponseEntity.ok(new CommitContentDto(content));
    }
}
