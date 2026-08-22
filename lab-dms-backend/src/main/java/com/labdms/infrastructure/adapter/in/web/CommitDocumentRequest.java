package com.labdms.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record CommitDocumentRequest(
    @NotBlank String message,
    @NotBlank String latexSource,
    /**
     * ISO/IEC 17025 8.3.2 c) — the formal justification for the revision
     * ("what changed and why"). Distinct from {@code message}, which is the
     * author's free-form description of the edit: an assessor asks for the
     * reason, and a commit message like "ajustes" is not evidence.
     * Optional at the API level so existing integrations keep working; the UI
     * requires it.
     */
    String changeReason
) {}
