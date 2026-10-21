package com.labdms.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * "Save version": closes the working draft.
 *
 * @param latexSource  optional. The version's text is the root file of the
 *                     draft's tree; this is only used by documents that have no
 *                     tree yet (created before multi-file support).
 * @param changeReason ISO/IEC 17025 8.3.2 c) — the formal justification for the
 *                     revision ("what changed and why"). Distinct from
 *                     {@code message}, the author's free-form description. The
 *                     UI requires it; the API tolerates its absence for older
 *                     integrations.
 */
public record CommitDocumentRequest(
    @NotBlank String message,
    String latexSource,
    String changeReason
) {}
