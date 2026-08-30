package com.labdms.application.service;

import com.labdms.domain.exception.BranchConflictException;
import com.labdms.domain.exception.BranchNotFoundException;
import com.labdms.domain.exception.DocumentNotFoundException;
import com.labdms.domain.exception.DomainException;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.document.Document;
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
 * Application service implementing git-like versioning: commits, branches, and merges.
 * Merge is only allowed after an approved review request.
 */
@Service
@Transactional
public class VersioningService implements CommitDocumentUseCase, CreateBranchUseCase, MergeBranchUseCase {

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

    @Override
    public Commit execute(CommitDocumentCommand command) {
        return commit(command, null);
    }

    /**
     * Creates a new version carrying the content of an OLDER one.
     *
     * <p>Restoring must not rewrite history, so it appends a version rather than
     * moving a pointer. The tree has to come from the version being restored:
     * copying the current head instead produced a commit labelled "Restore v3"
     * whose files were identical to the version it was meant to replace — a
     * traceability record stating something that did not happen, which is worse
     * than an error.
     */
    @Override
    public Commit restore(CommitDocumentCommand command, UUID sourceCommitId) {
        return commit(command, sourceCommitId);
    }

    /**
     * @param treeSource version whose file tree the new commit inherits, or
     *                   {@code null} to inherit from the branch head
     */
    private Commit commit(CommitDocumentCommand command, UUID treeSource) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document);

        Branch branch = branchRepository.findById(command.branchId())
                .orElseThrow(() -> new BranchNotFoundException(command.branchId()));

        UUID parentCommitId = treeSource != null ? treeSource : branch.getHeadCommitId();
        boolean hasTree = parentCommitId != null && projectFiles.hasFiles(parentCommitId);

        // For a multi-file project the version's text is the ROOT file, read from
        // the tree — never the editor buffer, which may hold a sub-file the user
        // happened to have open. Writing that buffer in as the version's content
        // would silently replace main.tex with a chapter.
        String source = command.latexSource();
        if (hasTree) {
            String rootText = projectFiles.readRootText(parentCommitId);
            if (rootText != null) source = rootText;
        }
        if (source == null || source.isBlank()) {
            // Reaches here when the project has files but no readable root .tex.
            // Say so, instead of letting ContentSnapshot report "LaTeX source
            // must not be blank", which tells the user nothing about the cause.
            throw new DomainException("commit.no.root.content");
        }

        // Create content snapshot with deduplication
        ContentSnapshot snapshot = ContentSnapshot.create(source);
        ContentSnapshot savedSnapshot = contentSnapshotRepository.findByContentHash(snapshot.getContentHash())
                .orElseGet(() -> contentSnapshotRepository.save(snapshot));

        // Create the commit
        Commit commit = Commit.create(
                command.documentId(),
                command.branchId(),
                branch.getHeadCommitId(),
                savedSnapshot.getId(),
                savedSnapshot.getContentHash(),
                command.message(),
                command.userId()
        );

        Commit savedCommit = commitRepository.save(commit);

        // The new version inherits the whole file tree. Rows are duplicated but
        // the blobs are shared, so a revision that edits one chapter costs the
        // size of that chapter — measured on the reference project: 6 versions
        // stored 1.47 MB instead of 11.73 MB.
        if (hasTree) {
            projectFiles.copyTree(parentCommitId, savedCommit.getId());
        }

        // Advance branch head
        branch.advanceHead(savedCommit.getId());
        branchRepository.save(branch);

        // If committing to the default branch, update document's current commit
        if (branch.isDefault()) {
            document.updateCurrentCommit(savedCommit.getId());
            documentRepository.save(document);
        }

        // Audit
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "COMMIT_CREATED",
                "Commit",
                savedCommit.getId(),
                Map.of("documentId", command.documentId().toString(),
                        "branchId", command.branchId().toString(),
                        "message", command.message()),
                null
        ));

        return savedCommit;
    }

    @Override
    public Branch execute(CreateBranchCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document);

        // Check for duplicate branch name
        if (branchRepository.existsByDocumentIdAndName(command.documentId(), command.branchName())) {
            throw new BranchConflictException(command.branchName());
        }

        // Get the default branch to fork from
        Branch defaultBranch = branchRepository.findDefaultByDocumentId(command.documentId())
                .orElseThrow(() -> new DomainException("branch.default.not.found"));

        // Create new branch from default branch's head
        Branch newBranch = Branch.create(
                command.documentId(),
                command.branchName(),
                defaultBranch.getHeadCommitId(),
                command.userId()
        );

        Branch savedBranch = branchRepository.save(newBranch);

        // Audit
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "BRANCH_CREATED",
                "Branch",
                savedBranch.getId(),
                Map.of("documentId", command.documentId().toString(),
                        "branchName", command.branchName()),
                null
        ));

        return savedBranch;
    }

    @Override
    public Branch execute(MergeBranchCommand command) {
        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        accessControlService.assertCanEdit(command.userId(), document);

        Branch sourceBranch = branchRepository.findById(command.branchId())
                .orElseThrow(() -> new BranchNotFoundException(command.branchId()));

        // Merge is only allowed after an approved review
        var approvedReview = reviewRequestRepository
                .findBySourceBranchIdAndStatus(command.branchId(), ReviewRequestStatus.APPROVED);
        if (approvedReview.isEmpty()) {
            throw new DomainException("merge.requires.approved.review");
        }

        // Get the default branch
        Branch defaultBranch = branchRepository.findDefaultByDocumentId(command.documentId())
                .orElseThrow(() -> new DomainException("branch.default.not.found"));

        // Create a merge commit on the default branch
        Commit sourceHead = commitRepository.findById(sourceBranch.getHeadCommitId())
                .orElseThrow(() -> new DomainException("commit.not.found"));

        Commit mergeCommit = Commit.create(
                command.documentId(),
                defaultBranch.getId(),
                defaultBranch.getHeadCommitId(),
                sourceHead.getContentSnapshotId(),
                sourceHead.getSha256Hash(),
                "Merge branch '" + sourceBranch.getName() + "' into main",
                command.userId()
        );

        Commit savedMergeCommit = commitRepository.save(mergeCommit);

        // The merge commit takes the SOURCE branch's tree, matching the snapshot
        // it already inherits from sourceHead. Without this the promoted version
        // had zero files: the project vanished from the current version right
        // after the one flow that is supposed to publish it.
        if (projectFiles.hasFiles(sourceHead.getId())) {
            projectFiles.copyTree(sourceHead.getId(), savedMergeCommit.getId());
        }

        // Advance default branch head
        defaultBranch.advanceHead(savedMergeCommit.getId());
        branchRepository.save(defaultBranch);

        // Mark source branch as merged
        sourceBranch.markMerged();
        branchRepository.save(sourceBranch);

        // Update document's current commit
        document.updateCurrentCommit(savedMergeCommit.getId());
        documentRepository.save(document);

        // Mark the review request as merged
        approvedReview.get().markMerged();
        reviewRequestRepository.save(approvedReview.get());

        // Audit
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "BRANCH_MERGED",
                "Branch",
                sourceBranch.getId(),
                Map.of("documentId", command.documentId().toString(),
                        "branchName", sourceBranch.getName()),
                null
        ));

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
}
