package com.labdms.application.service;

import com.labdms.domain.exception.DocumentNotFoundException;
import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentCode;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.versioning.Branch;
import com.labdms.domain.model.versioning.Commit;
import com.labdms.domain.model.versioning.ContentSnapshot;
import com.labdms.application.dto.command.CreateDocumentCommand;
import com.labdms.application.dto.command.SearchDocumentsQuery;
import com.labdms.domain.port.in.CreateDocumentUseCase;
import com.labdms.domain.port.in.SearchDocumentsUseCase;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.domain.port.out.BranchRepository;
import com.labdms.domain.port.out.CommitRepository;
import com.labdms.domain.port.out.ContentSnapshotRepository;
import com.labdms.domain.port.out.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Application service implementing document creation and search use cases.
 */
@Service
@Transactional
public class DocumentService implements CreateDocumentUseCase, SearchDocumentsUseCase {

    private final DocumentRepository documentRepository;
    private final BranchRepository branchRepository;
    private final CommitRepository commitRepository;
    private final ContentSnapshotRepository contentSnapshotRepository;
    private final AccessControlService accessControlService;
    private final AuditLogPort auditLogPort;
    private final NotificationService notificationService;
    private final DocumentTypeService documentTypeService;

    public DocumentService(DocumentRepository documentRepository,
                           BranchRepository branchRepository,
                           CommitRepository commitRepository,
                           ContentSnapshotRepository contentSnapshotRepository,
                           AccessControlService accessControlService,
                           AuditLogPort auditLogPort,
                           NotificationService notificationService,
                           DocumentTypeService documentTypeService) {
        this.documentRepository = documentRepository;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.contentSnapshotRepository = contentSnapshotRepository;
        this.accessControlService = accessControlService;
        this.auditLogPort = auditLogPort;
        this.notificationService = notificationService;
        this.documentTypeService = documentTypeService;
    }

    @Override
    public Document execute(CreateDocumentCommand command) {
        // Access control
        accessControlService.assertCanEditInSector(command.userId(), command.documentGroupId());

        // Fails fast with a clear message if the chosen reviewer cannot review
        // (no permission, inactive, or is the author themselves).
        accessControlService.assertCanBeAssignedAsReviewer(command.assignedReviewerId(), command.userId());

        // The type must be one the laboratory actually defined and still uses.
        // DocumentCode only checks the SHAPE of a code, so this is what stops a
        // typo from quietly inventing a new document type.
        documentTypeService.assertUsableForNewDocument(command.docType());

        // Code is optional: when omitted, it is generated from the document
        // category and the next sequential number (IT-001, IT-002, PQ-001...).
        String code = (command.code() == null || command.code().isBlank())
                ? nextCode(command.docType())
                : command.code().trim().toUpperCase();

        DocumentCode docCode = new DocumentCode(code);

        // Check for duplicate code
        if (documentRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Document code already exists: " + code);
        }

        // Create the document
        Document document = Document.create(
                docCode,
                command.title(),
                command.docType(),
                command.documentGroupId(),
                command.userId(),
                command.tags()
        );

        // Create the default 'main' branch
        Branch mainBranch = Branch.createDefault(document.getId(), command.userId());

        // Create the initial content snapshot (with SHA-256 dedup)
        ContentSnapshot snapshot = ContentSnapshot.create(command.initialLatexSource());
        ContentSnapshot savedSnapshot = contentSnapshotRepository.findByContentHash(snapshot.getContentHash())
                .orElseGet(() -> contentSnapshotRepository.save(snapshot));

        // Create the initial commit
        Commit initialCommit = Commit.create(
                document.getId(),
                mainBranch.getId(),
                null,  // No parent for initial commit
                savedSnapshot.getId(),
                savedSnapshot.getContentHash(),
                "Initial document creation",
                command.userId()
        );

        // Wire everything together
        mainBranch.advanceHead(initialCommit.getId());
        document.updateCurrentCommit(initialCommit.getId());
        document.setAssignedReviewerId(command.assignedReviewerId());
        // ISO/IEC 17025 defaults when the caller does not specify: 24 months for
        // periodic review (8.3.2 b). A document must never end up with NO review
        // interval by accident — that is precisely the "review dates long past"
        // finding.
        document.setReviewIntervalMonths(
                command.reviewIntervalMonths() != null ? command.reviewIntervalMonths() : 24);

        // Retention (8.4) defaults to keeping the document forever, expressed as
        // 0 — the same "no limit" convention the review interval already uses.
        //
        // The default used to be 5 years. The two mistakes are not symmetrical:
        // keeping a record longer than needed costs disk, while discarding one
        // too early is unrecoverable and can be exactly the record an assessment
        // asks for. Shortening the period is a decision for whoever knows the
        // legal requirement of the test, so it has to be made on purpose.
        document.setRetentionYears(
                command.retentionYears() != null ? command.retentionYears() : 0);
        // Start the clock now. An interval of 0 means "this document does not
        // expire" and leaves the date unset, which is what keeps it out of the
        // reminders instead of nagging about a review nobody should perform.
        document.scheduleFirstReview(java.time.Instant.now());

        // Persist in order. The order matters and is not arbitrary:
        // documents.official_commit_id has a foreign key to commits(id), so the
        // commit must EXIST before any row can point at it. Marking the new
        // revision as the one in force before saving it violated that key and
        // failed the whole creation with a 500.
        //
        // (documents.current_commit_id has no such key, which is why only the
        // "in force" pointer failed — the constraint is right, the order was not.)
        Document savedDocument = documentRepository.save(document);
        branchRepository.save(mainBranch);
        commitRepository.save(initialCommit);

        // Now that the commit exists, it can be declared the version in force.
        // A brand new document's only revision is also its current one.
        savedDocument.makeOfficial(initialCommit.getId());
        savedDocument = documentRepository.save(savedDocument);

        // Audit
        // Uses the resolved `code` (explicit or auto-generated), never command.code():
        // that field is null whenever the code was auto-generated, and Map.of()
        // throws NPE on a null value.
        auditLogPort.log(AuditEntry.create(
                command.userId(),
                "DOCUMENT_CREATED",
                "Document",
                savedDocument.getId(),
                Map.of("code", code, "title", command.title()),
                null
        ));

        return savedDocument;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResult execute(SearchDocumentsQuery query) {
        List<Document> documents = documentRepository.search(
                query.title(), query.code(), query.docType(), query.status(),
                query.tags(), query.documentGroupId(), query.page(), query.size()
        );

        long totalElements = documentRepository.count(
                query.title(), query.code(), query.docType(), query.status(),
                query.tags(), query.documentGroupId()
        );

        int totalPages = (int) Math.ceil((double) totalElements / query.size());

        return new SearchResult(documents, totalElements, totalPages, query.page());
    }

    @Transactional(readOnly = true)
    public Document findById(java.util.UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * Next available code for a category: IT-001, IT-002, PQ-001...
     * Sequential per document type, skipping numbers already taken.
     */
    @Transactional(readOnly = true)
    public String nextCode(com.labdms.domain.model.document.DocumentType docType) {
        String prefix = docType.name();
        for (int number = 1; number <= 9999; number++) {
            String candidate = String.format("%s-%03d", prefix, number);
            if (!documentRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available code for type " + prefix);
    }

    /**
     * Records a periodic review (ISO/IEC 17025 8.3.2 b).
     *
     * <p>Deliberately does NOT create a new version. Reviewing a document and
     * concluding it is still valid is a review event in its own right — the
     * evidence an assessor asks for is that the review happened, who did it and
     * when the next one is due. Requires approval rights, same as any other
     * document-control act.
     */
    public Document recordPeriodicReview(java.util.UUID userId, java.util.UUID documentId, String notes) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        accessControlService.assertCanApprove(userId, documentId);

        java.time.LocalDate previousDue = document.getNextReviewDate();
        document.recordPeriodicReview(userId, java.time.Instant.now());
        Document saved = documentRepository.save(document);

        auditLogPort.log(AuditEntry.create(
                userId,
                "DOCUMENT_PERIODIC_REVIEW",
                "Document",
                documentId,
                Map.of("code", document.getCode().value(),
                        "previousDue", String.valueOf(previousDue),
                        "nextDue", String.valueOf(document.getNextReviewDate()),
                        "notes", notes == null ? "" : notes),
                null
        ));

        return saved;
    }

    /**
     * Documents whose scheduled review date has passed, or falls within the next
     * {@code withinDays} days. Drives the compliance panel and the reminder job.
     */
    @Transactional(readOnly = true)
    public List<Document> findDueForReview(int withinDays) {
        java.time.LocalDate limit = java.time.LocalDate.now().plusDays(Math.max(0, withinDays));
        return documentRepository.findDueForReview(limit);
    }

    /**
     * Elects a revision as the official (in-force) one.
     *
     * <p>This is a document-control act, so it requires approval rights and is
     * written to the audit trail with the previous and new revision. Nothing is
     * deleted: every other revision stays in the history and can be elected
     * again later — which is exactly what ISO traceability requires.
     */
    public Document makeVersionOfficial(java.util.UUID userId, java.util.UUID documentId,
                                        java.util.UUID commitId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        accessControlService.assertCanApprove(userId, documentId);

        var commit = commitRepository.findById(commitId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + commitId));
        if (!documentId.equals(commit.getDocumentId())) {
            throw new IllegalArgumentException("That version belongs to another document");
        }

        java.util.UUID previous = document.getOfficialCommitId();
        document.makeOfficial(commitId);
        Document saved = documentRepository.save(document);

        auditLogPort.log(AuditEntry.create(
                userId,
                "OFFICIAL_VERSION_CHANGED",
                "Document",
                documentId,
                Map.of("code", document.getCode().value(),
                        "from", String.valueOf(previous),
                        "to", commitId.toString()),
                null
        ));

        if (document.getCreatedBy() != null && !document.getCreatedBy().equals(userId)) {
            notificationService.notify(
                    document.getCreatedBy(),
                    NotificationService.TYPE_REVIEW_APPROVED,
                    "Versão vigente alterada",
                    "A versão oficial de \"" + document.getTitle() + "\" ("
                            + document.getCode().value() + ") foi alterada.",
                    "/dashboard/documents/" + documentId + "/edit");
        }

        return saved;
    }

    /**
     * Changes the lifecycle status of a document (ISO 9000 document control).
     * Activating or obsoleting a document requires approval rights;
     * other transitions require edit rights on the document group.
     */
    public Document changeStatus(java.util.UUID userId, java.util.UUID documentId, DocumentStatus newStatus) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (newStatus == DocumentStatus.ACTIVE || newStatus == DocumentStatus.OBSOLETE) {
            accessControlService.assertCanApprove(userId, documentId);
        } else {
            accessControlService.assertCanEdit(userId, document);
        }

        DocumentStatus previousStatus = document.getStatus();
        document.transitionTo(newStatus);
        Document saved = documentRepository.save(document);

        auditLogPort.log(AuditEntry.create(
                userId,
                "DOCUMENT_STATUS_CHANGED",
                "Document",
                documentId,
                Map.of("code", document.getCode().value(),
                        "from", previousStatus.name(),
                        "to", newStatus.name()),
                null
        ));

        if (newStatus == DocumentStatus.OBSOLETE && previousStatus != DocumentStatus.OBSOLETE
                && document.getCreatedBy() != null && !document.getCreatedBy().equals(userId)) {
            notificationService.notify(
                    document.getCreatedBy(),
                    NotificationService.TYPE_DOCUMENT_OBSOLETED,
                    "Documento obsoletado",
                    "\"" + document.getTitle() + "\" (" + document.getCode().value() + ") foi marcado como obsoleto.",
                    "/dashboard/documents/" + document.getId()
            );
        }

        return saved;
    }
}
