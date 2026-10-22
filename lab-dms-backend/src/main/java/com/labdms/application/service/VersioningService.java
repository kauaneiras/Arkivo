package com.labdms.application.service;

import com.labdms.domain.exception.BranchConflictException;
import com.labdms.domain.exception.BranchNotFoundException;
import com.labdms.domain.exception.DocumentNotFoundException;
import com.labdms.domain.exception.DomainException;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.review.ReviewRequestStatus;
import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.application.dto.command.CommitDocumentCommand;
import com.labdms.application.dto.command.CreateBranchCommand;
import com.labdms.application.dto.command.MergeBranchCommand;
import com.labdms.domain.port.in.CommitDocumentUseCase;
import com.labdms.domain.port.in.CreateBranchUseCase;
import com.labdms.domain.port.in.MergeBranchUseCase;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.domain.port.out.BranchRepository;
import com.labdms.domain.port.out.CommitRepository;
import com.labdms.domain.port.out.ContentSnapshotRepository;
import com.labdms.domain.port.out.DocumentRepository;
import com.labdms.domain.port.out.ReviewRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Git-like versioning: a working revision ("draft"), closed commits, restore,
 * branches and merges.
 *
 * <p>The model, in one paragraph. The history of a document is a chain of
 * immutable commits, each owning a complete file tree. Editing never touches a
 * closed commit: the first change after a save opens a <b>draft</b> commit on
 * top of the head, copies the tree, and every subsequent write lands in that
 * draft — it is the working tree of Git, kept in the same table because the
 * tree storage is keyed by commit. "Save version" closes the draft with a
 * message, a change reason and the author, and the next change opens a new
 * one. A document therefore has at most one draft, and it is always the head.
 */
@Service
@Transactional
public class VersioningService implements CommitDocumentUseCase, CreateBranchUseCase, MergeBranchUseCase {

    /** Message carried by a restore commit; the revision number is appended. */
    public static final String RESTORE_MESSAGE_PREFIX = "Restaurar versão v";

    private final DocumentRepository documentRepository;
    private final BranchRepository branchRepository;
    private final CommitRepository commitRepository;
    private final ContentSnapshotRepository contentSnapshotRepository;
    private final ReviewRequestRepository reviewRequestRepository;
    private final AccessControlService accessControlService;
    private final AuditLogPort auditLogPort;
    private final ProjectFileService projectFiles;

    public VersioningService(DocumentRepository documentRepository,
                             BranchRepository branchRepository,
                             CommitRepository commitRepository,
                             ContentSnapshotRepository contentSnapshotRepository,
                             ReviewRequestRepository reviewRequestRepository,
                             AccessControlService accessControlService,
                             AuditLogPort auditLogPort,
                             ProjectFileService projectFiles) {
        this.documentRepository = documentRepository;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.contentSnapshotRepository = contentSnapshotRepository;
        this.reviewRequestRepository = reviewRequestRepository;
        this.accessControlService = accessControlService;
        this.auditLogPort = auditLogPort;
        this.projectFiles = projectFiles;
    }

    /* ------------------------------------------------------------------ */
    /* Working revision                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * The commit that may be written right now, opening a draft if needed.
     *
     * <p>Every write path — saving a file, uploading, importing a ZIP, renaming —
     * asks here first. The answer is the existing draft when there is one;
     * otherwise a new draft is opened on top of the head, inheriting its tree
     * (rows copied, blobs shared), and becomes the document's current commit.
     * The closed commit it was opened from is never touched again.
     *
     * <p>Refuses while the document is in review or obsolete: a document under
     * approval must be exactly what the reviewer sees.
     */
    public UUID openWorkingRevision(UUID documentId, UUID userId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        accessControlService.assertCanEdit(userId, document);
        assertEditable(document);

        Branch branch = defaultBranch(documentId);
        Commit head = branch.getHeadCommitId() == null ? null
                : commitRepository.findById(branch.getHeadCommitId()).orElse(null);

        if (head != null && head.isDraft()) {
            return head.getId();
        }

        Commit draft = Commit.openDraft(documentId, branch.getId(),
                head == null ? null : head.getId(),
                head == null ? null : head.getContentSnapshotId(),
                head == null ? "" : head.getSha256Hash(),
                userId);
        Commit saved = commitRepository.save(draft);

        if (head != null && projectFiles.hasFiles(head.getId())) {
            projectFiles.copyTree(head.getId(), saved.getId());
        }

        branch.advanceHead(saved.getId());
        branchRepository.save(branch);
        document.updateCurrentCommit(saved.getId());
        documentRepository.save(document);

        // A revision that was born from an edit, not from "save version", is
        // recorded as such: in an audit a version with no explained origin is a
        // question.
        auditLogPort.log(AuditEntry.create(userId, "WORKING_REVISION_OPENED", "Document", documentId,
                Map.of("commitId", saved.getId().toString(),
                       "fromCommit", String.valueOf(head == null ? null : head.getId())),
                null));
        return saved.getId();
    }

    /** The document's draft, or null when every change has been saved. */
    @Transactional(readOnly = true)
    public Commit findDraft(UUID documentId) {
        Branch branch = branchRepository.findDefaultByDocumentId(documentId).orElse(null);
        if (branch == null || branch.getHeadCommitId() == null) return null;
        Commit head = commitRepository.findById(branch.getHeadCommitId()).orElse(null);
        return head != null && head.isDraft() ? head : null;
    }

    /**
     * Throws away the working revision — "discard changes" in GitHub Desktop.
     *
     * <p>The draft's rows go (its tree cascades), the head returns to the
     * closed commit it was opened from, and nothing in the history changes
     * because the draft was never part of it.
     */
    public void discardDraft(UUID documentId, UUID userId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        accessControlService.assertCanEdit(userId, document);
        assertEditable(document);

        Commit draft = findDraft(documentId);
        if (draft == null) {
            throw new DomainException("draft.none");
        }
        if (draft.getParentCommitId() == null) {
            // A draft with no parent would leave the document with no version
            // at all. Cannot happen through the UI (documents are born with a
            // closed commit), but refusing beats deleting the only content.
            throw new DomainException("draft.cannot.discard.initial");
        }

        Branch branch = defaultBranch(documentId);
        branch.advanceHead(draft.getParentCommitId());
        branchRepository.save(branch);
        document.updateCurrentCommit(draft.getParentCommitId());
        documentRepository.save(document);

        projectFiles.dropCommitArtifacts(draft.getId());
        commitRepository.delete(draft.getId());

        auditLogPort.log(AuditEntry.create(userId, "WORKING_REVISION_DISCARDED", "Document", documentId,
                Map.of("commitId", draft.getId().toString(),
                       "backTo", draft.getParentCommitId().toString()),
                null));
    }

    /* ------------------------------------------------------------------ */
    /* Save version                                                        */
    /* ------------------------------------------------------------------ */

    @Override
    public Commit execute(CommitDocumentCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));
        accessControlService.assertCanEdit(command.userId(), document);
        assertEditable(document);

        Branch branch = branchRepository.findById(command.branchId())
                .orElseThrow(() -> new BranchNotFoundException(command.branchId()));
        Commit head = branch.getHeadCommitId() == null ? null
                : commitRepository.findById(branch.getHeadCommitId()).orElse(null);

        Commit saved;
        if (head != null && head.isDraft()) {
            saved = closeDraft(head, command, document);
        } else if (head == null || !projectFiles.hasFiles(head.getId())) {
            // A document without a file tree (created before multi-file support)
            // keeps its text in the editor buffer: the save IS the content.
            saved = appendTreelessCommit(branch, head, command, document);
        } else {
            // Everything is already saved. Creating an empty commit would put a
            // version in the history whose diff is nothing, and the history is
            // supposed to answer "what changed".
            throw new DomainException("commit.nothing.to.save");
        }

        auditLogPort.log(AuditEntry.create(command.userId(), "COMMIT_CREATED", "Commit", saved.getId(),
                Map.of("documentId", command.documentId().toString(),
                       "branchId", command.branchId().toString(),
                       "message", command.message()),
                null));
        return saved;
    }

    private Commit closeDraft(Commit draft, CommitDocumentCommand command, Document document) {
        // The version's text is the ROOT file read from the tree — never the
        // editor buffer, which may hold a sub-file the user happened to have
        // open. Writing that buffer in as the content would replace main.tex
        // with a chapter.
        String source = projectFiles.hasFiles(draft.getId())
                ? projectFiles.readRootText(draft.getId()) : command.latexSource();
        if (source == null || source.isBlank()) {
            throw new DomainException("commit.no.root.content");
        }
        ContentSnapshot snapshot = snapshotOf(source);
        draft.close(command.message(), blankToNull(command.changeReason()), command.userId(),
                snapshot.getId(), snapshot.getContentHash());
        Commit saved = commitRepository.save(draft);
        // The document's timestamp is what the list sorts by.
        document.updateCurrentCommit(saved.getId());
        documentRepository.save(document);
        return saved;
    }

    private Commit appendTreelessCommit(Branch branch, Commit head, CommitDocumentCommand command,
                                        Document document) {
        String source = command.latexSource();
        if (source == null || source.isBlank()) {
            throw new DomainException("commit.no.root.content");
        }
        ContentSnapshot snapshot = snapshotOf(source);
        Commit commit = Commit.create(command.documentId(), command.branchId(),
                head == null ? null : head.getId(), snapshot.getId(), snapshot.getContentHash(),
                command.message(), command.userId());
        commit.setChangeReason(blankToNull(command.changeReason()));
        Commit saved = commitRepository.save(commit);

        // From here on the document has a tree, like every other one: the text
        // becomes main.tex so the next edit goes through the draft mechanism.
        projectFiles.putFile(saved.getId(), ProjectFileService.DEFAULT_ROOT,
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/x-tex", true);

        branch.advanceHead(saved.getId());
        branchRepository.save(branch);
        if (branch.isDefault()) {
            document.updateCurrentCommit(saved.getId());
            documentRepository.save(document);
        }
        return saved;
    }

    /* ------------------------------------------------------------------ */
    /* Restore                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Makes an older version the newest one, without rewriting history.
     *
     * <p>A new closed commit is appended whose tree is the restored version's.
     * When a draft exists its unsaved changes are replaced — the caller
     * (the screen) says so before asking. The restored commit's message names
     * the revision it came from so the history reads "v7 = v3 again".
     */
    @Override
    public Commit restore(CommitDocumentCommand command, UUID sourceCommitId) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));
        accessControlService.assertCanEdit(command.userId(), document);
        assertEditable(document);

        Commit source = commitRepository.findById(sourceCommitId)
                .orElseThrow(() -> new DomainException("commit.not.found", sourceCommitId));
        if (!command.documentId().equals(source.getDocumentId())) {
            throw new IllegalArgumentException("That version belongs to another document");
        }
        if (source.isDraft()) {
            throw new DomainException("restore.draft");
        }

        UUID draftId = openWorkingRevision(command.documentId(), command.userId());
        if (projectFiles.hasFiles(source.getId())) {
            projectFiles.replaceTreeFromCommit(draftId, source.getId());
        } else {
            // A pre-tree version: its text becomes the root file of the tree.
            String text = contentSnapshotRepository.findById(source.getContentSnapshotId())
                    .map(ContentSnapshot::getLatexSource).orElse("");
            projectFiles.replaceTree(draftId, List.of(), null);
            projectFiles.putFile(draftId, ProjectFileService.DEFAULT_ROOT,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/x-tex", true);
        }

        Commit draft = commitRepository.findById(draftId)
                .orElseThrow(() -> new DomainException("commit.not.found", draftId));
        Commit saved = closeDraft(draft, command, document);

        auditLogPort.log(AuditEntry.create(command.userId(), "VERSION_RESTORED", "Commit", saved.getId(),
                Map.of("documentId", command.documentId().toString(),
                       "restoredFrom", sourceCommitId.toString()),
                null));
        return saved;
    }

    /* ------------------------------------------------------------------ */
    /* Branches (kept; the UI works on the default branch)                 */
    /* ------------------------------------------------------------------ */

    @Override
    public Branch execute(CreateBranchCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document);

        if (branchRepository.existsByDocumentIdAndName(command.documentId(), command.branchName())) {
            throw new BranchConflictException(command.branchName());
        }

        Branch defaultBranch = defaultBranch(command.documentId());
        Branch newBranch = Branch.create(command.documentId(), command.branchName(),
                defaultBranch.getHeadCommitId(), command.userId());
        Branch savedBranch = branchRepository.save(newBranch);

        auditLogPort.log(AuditEntry.create(command.userId(), "BRANCH_CREATED", "Branch", savedBranch.getId(),
                Map.of("documentId", command.documentId().toString(),
                       "branchName", command.branchName()),
                null));
        return savedBranch;
    }

    @Override
    public Branch execute(MergeBranchCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document);

        Branch sourceBranch = branchRepository.findById(command.branchId())
                .orElseThrow(() -> new BranchNotFoundException(command.branchId()));

        var approvedReview = reviewRequestRepository
                .findBySourceBranchIdAndStatus(command.branchId(), ReviewRequestStatus.APPROVED);
        if (approvedReview.isEmpty()) {
            throw new DomainException("merge.requires.approved.review");
        }

        Branch defaultBranch = defaultBranch(command.documentId());
        Commit sourceHead = commitRepository.findById(sourceBranch.getHeadCommitId())
                .orElseThrow(() -> new DomainException("commit.not.found"));

        Commit mergeCommit = Commit.create(command.documentId(), defaultBranch.getId(),
                defaultBranch.getHeadCommitId(), sourceHead.getContentSnapshotId(),
                sourceHead.getSha256Hash(),
                "Merge branch '" + sourceBranch.getName() + "' into main", command.userId());
        Commit savedMergeCommit = commitRepository.save(mergeCommit);

        if (projectFiles.hasFiles(sourceHead.getId())) {
            projectFiles.copyTree(sourceHead.getId(), savedMergeCommit.getId());
        }

        defaultBranch.advanceHead(savedMergeCommit.getId());
        branchRepository.save(defaultBranch);
        sourceBranch.markMerged();
        branchRepository.save(sourceBranch);
        document.updateCurrentCommit(savedMergeCommit.getId());
        documentRepository.save(document);
        approvedReview.get().markMerged();
        reviewRequestRepository.save(approvedReview.get());

        auditLogPort.log(AuditEntry.create(command.userId(), "BRANCH_MERGED", "Branch", sourceBranch.getId(),
                Map.of("documentId", command.documentId().toString(),
                       "branchName", sourceBranch.getName()),
                null));
        return defaultBranch;
    }

    @Transactional(readOnly = true)
    public List<Branch> findBranchesByDocumentId(UUID documentId) {
        return branchRepository.findByDocumentId(documentId);
    }

    @Transactional(readOnly = true)
    public List<Commit> findCommitsByBranchId(UUID branchId) {
        return commitRepository.findByBranchId(branchId);
    }

    @Transactional(readOnly = true)
    public List<Commit> findCommitsByDocumentId(UUID documentId) {
        return commitRepository.findByDocumentId(documentId);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /** A document under review or withdrawn is read-only for everyone. */
    public static void assertEditable(Document document) {
        if (document.getStatus() == DocumentStatus.IN_REVIEW
                || document.getStatus() == DocumentStatus.OBSOLETE) {
            throw new IllegalStateException(
                    "This document is locked for editing (" + document.getStatus() + ")");
        }
    }

    private Branch defaultBranch(UUID documentId) {
        return branchRepository.findDefaultByDocumentId(documentId)
                .orElseThrow(() -> new DomainException("branch.default.not.found"));
    }

    private ContentSnapshot snapshotOf(String source) {
        ContentSnapshot snapshot = ContentSnapshot.create(source);
        return contentSnapshotRepository.findByContentHash(snapshot.getContentHash())
                .orElseGet(() -> contentSnapshotRepository.save(snapshot));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
