-- ============================================================
-- ISO/IEC 17025:2017 — 8.3.2 b) periodic review
--                      8.3.2 c) nature of change
--                      8.4     record retention
-- ============================================================

-- 8.3.2 b) "documents are periodically reviewed and updated as necessary".
-- A document approved once must not stay valid forever: each one declares how
-- often it has to be revisited, and the system derives the due date from the
-- last review. 24 months is the usual default in laboratory quality systems;
-- a null interval means "no periodic review required" (rare, e.g. a one-off
-- record form) and is deliberately allowed rather than forced.
ALTER TABLE public.documents
    ADD COLUMN review_interval_months integer DEFAULT 24;

-- When the document was last formally reviewed and by whom. This is NOT the
-- same as updated_at: reviewing a document and concluding "still valid, no
-- change needed" is itself a review event that must be recorded as evidence.
ALTER TABLE public.documents ADD COLUMN last_reviewed_at timestamp(6) without time zone;
ALTER TABLE public.documents ADD COLUMN last_reviewed_by uuid REFERENCES public.users(id);

-- Derived, but stored so the "overdue" query stays a plain indexed comparison
-- instead of a computation over every row.
ALTER TABLE public.documents ADD COLUMN next_review_date date;

-- 8.4 — the standard sets no fixed retention period; it requires the laboratory
-- to DEFINE one. Declared per document because a calibration procedure and a
-- generic policy rarely share the same legal/contractual retention.
ALTER TABLE public.documents ADD COLUMN retention_years integer DEFAULT 5;

-- Existing ACTIVE documents: treat the last update as the last review so they
-- get a sensible due date instead of showing up as overdue on day one.
UPDATE public.documents
   SET last_reviewed_at = updated_at,
       next_review_date = (updated_at + INTERVAL '24 months')::date
 WHERE status = 'ACTIVE' AND next_review_date IS NULL;

-- Overdue/upcoming reviews are a dashboard query on every page load.
CREATE INDEX IF NOT EXISTS idx_documents_next_review ON public.documents(next_review_date)
    WHERE next_review_date IS NOT NULL;

-- 8.3.2 c) "changes ... are identified". A free-form commit message is how the
-- author describes the edit; this is the formal justification the auditor asks
-- for ("what changed and why"). Kept per revision, never overwritten.
ALTER TABLE public.commits ADD COLUMN change_reason text;
