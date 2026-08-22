-- ============================================================
-- Designated reviewer + official (effective) version
-- ============================================================

-- Who must review this document. ISO 9000 asks for nominal responsibility:
-- the person accountable for the approval is recorded, not just "a reviewer".
-- Nullable: a document may be created without naming one, and then any
-- reviewer may take it.
ALTER TABLE public.documents
    ADD COLUMN assigned_reviewer_id uuid REFERENCES public.users(id);

-- The version that is currently IN FORCE. Deliberately separate from
-- current_commit_id (the latest edit): a lab must be able to keep editing a
-- draft while an older, approved revision remains the official one. Electing an
-- older version as official never deletes anything — the full history stays.
ALTER TABLE public.documents
    ADD COLUMN official_commit_id uuid REFERENCES public.commits(id);

-- Existing documents: the version in force is the one they already point at.
UPDATE public.documents SET official_commit_id = current_commit_id
 WHERE official_commit_id IS NULL;

-- Filtering by author and by reviewer are first-class list operations now,
-- so they get indexes rather than sequential scans.
CREATE INDEX IF NOT EXISTS idx_documents_created_by ON public.documents(created_by);
CREATE INDEX IF NOT EXISTS idx_documents_assigned_reviewer ON public.documents(assigned_reviewer_id);
CREATE INDEX IF NOT EXISTS idx_documents_updated_at ON public.documents(updated_at DESC);

-- Reviews are read per document constantly (history panel); index the FK.
CREATE INDEX IF NOT EXISTS idx_review_requests_document ON public.review_requests(document_id);
CREATE INDEX IF NOT EXISTS idx_reviews_request ON public.reviews(review_request_id);

-- Commits are always read by document, newest first.
CREATE INDEX IF NOT EXISTS idx_commits_document_created ON public.commits(document_id, created_at DESC);
