package com.labdms.domain.port.in;

import com.labdms.domain.model.document.Document;
import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;

import java.util.List;
import java.util.UUID;

/**
 * Use case for searching and filtering documents.
 */
import com.labdms.application.dto.command.SearchDocumentsQuery;

public interface SearchDocumentsUseCase {

    SearchResult execute(SearchDocumentsQuery query);

    Document findById(UUID id);

    record SearchResult(
            List<Document> documents,
            long totalElements,
            int totalPages,
            int currentPage
    ) {}
}
