package com.labdms.application.dto.command;

import com.labdms.domain.model.document.DocumentStatus;
import com.labdms.domain.model.document.DocumentType;

import java.util.List;
import java.util.UUID;

/**
 * Query record for searching documents with filters and pagination.
 */
public record SearchDocumentsQuery(
        String title,
        String code,
        DocumentType docType,
        DocumentStatus status,
        List<String> tags,
        UUID documentGroupId,
        int page,
        int size
) {
    public SearchDocumentsQuery {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
    }
}
