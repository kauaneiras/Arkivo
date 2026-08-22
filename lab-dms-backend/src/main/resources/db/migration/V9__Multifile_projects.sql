-- ============================================================
-- Multi-file LaTeX projects (Overleaf-style)
--
-- A real LaTeX document is not one string: the reference project measured for
-- this design has 42 files — 34 .tex totalling 131 KB, plus 14.3 MB of figures.
-- Text is 0.9% of the bytes; binaries are 99.1%. The storage model follows that
-- measurement rather than treating all content the same.
--
-- Layout:
--   file_blobs    content addressed by SHA-256. Text lives in the database
--                 (small, diffable, searchable, transactional). Binary lives on
--                 the attachments volume. Either way a given byte sequence is
--                 stored ONCE, so 50 revisions of a project share one copy of
--                 its images — the single biggest saving over time.
--   project_files one row per path per COMMIT. A commit is a full tree, so a
--                 version is a complete, restorable state (ISO 17025 needs the
--                 whole document, not a delta).
-- ============================================================

-- Clean slate: the previous model stored one LaTeX string per document and
-- cannot be expressed as a tree without inventing data. Approved by the user;
-- the existing rows were test fixtures.
TRUNCATE TABLE public.document_acknowledgements,
               public.document_attachments,
               public.compiled_pdfs,
               public.reviews,
               public.review_requests,
               public.commits,
               public.branches,
               public.content_snapshots,
               public.documents
    RESTART IDENTITY CASCADE;

-- ------------------------------------------------------------
-- Content-addressed blob store
-- ------------------------------------------------------------
CREATE TABLE public.file_blobs (
    -- SHA-256 of the ORIGINAL bytes (before any compression), so identity is
    -- independent of how we happen to store it today.
    sha256 character varying(64) NOT NULL PRIMARY KEY,
    size_bytes bigint NOT NULL,
    -- TEXT blobs keep their content here; Postgres TOAST compresses it
    -- transparently, so no application-level compression is needed.
    content_text text,
    -- BINARY blobs live on the volume; this is the relative path.
    storage_path character varying(500),
    -- Compression is applied only when it actually pays. Measured on the
    -- reference project: EPS compresses 99%, .tex 71%, PNG 17%, JPEG 1% —
    -- so compressing an already-compressed image just burns CPU.
    compression character varying(10) NOT NULL DEFAULT 'NONE',
    stored_bytes bigint,
    content_type character varying(150),
    is_text boolean NOT NULL,
    -- How many project_files point at this blob. Lets unreferenced blobs be
    -- collected without scanning the whole tree table.
    ref_count integer NOT NULL DEFAULT 0,
    created_at timestamp(6) without time zone NOT NULL DEFAULT NOW(),
    CONSTRAINT file_blobs_compression_check CHECK (compression IN ('NONE', 'ZSTD', 'GZIP')),
    -- A blob is either inline text or a file on disk, never both, never neither.
    CONSTRAINT file_blobs_storage_check CHECK (
        (is_text AND content_text IS NOT NULL AND storage_path IS NULL) OR
        (NOT is_text AND storage_path IS NOT NULL AND content_text IS NULL))
);

CREATE INDEX idx_file_blobs_unreferenced ON public.file_blobs(ref_count) WHERE ref_count = 0;

-- ------------------------------------------------------------
-- The file tree of one commit
-- ------------------------------------------------------------
CREATE TABLE public.project_files (
    id uuid NOT NULL PRIMARY KEY,
    commit_id uuid NOT NULL REFERENCES public.commits(id) ON DELETE CASCADE,
    -- Full relative path with forward slashes: "editaveis/introducao.tex".
    -- Storing the whole path (instead of parent_id) keeps reads to one query
    -- and makes a commit's tree trivially comparable to another's.
    path character varying(1000) NOT NULL,
    blob_sha256 character varying(64) NOT NULL REFERENCES public.file_blobs(sha256),
    -- Denormalised for listing the tree without joining blobs.
    size_bytes bigint NOT NULL,
    is_text boolean NOT NULL,
    content_type character varying(150),
    -- Set on the file with \documentclass that latexmk is pointed at.
    -- The reference project has TWO candidates (main.tex and bkpmain.tex),
    -- so this cannot be inferred at compile time — it is chosen and stored.
    is_root boolean NOT NULL DEFAULT false,
    -- For a converted derivative (EPS uploaded, PDF compiled): keeps the
    -- provenance so the UI can explain why the extension changed.
    derived_from character varying(255),
    CONSTRAINT uk_project_files_commit_path UNIQUE (commit_id, path)
);

CREATE INDEX idx_project_files_commit ON public.project_files(commit_id);
CREATE INDEX idx_project_files_blob ON public.project_files(blob_sha256);
-- At most one root per commit.
CREATE UNIQUE INDEX uk_project_files_root ON public.project_files(commit_id) WHERE is_root;

-- ------------------------------------------------------------
-- Documents become projects
-- ------------------------------------------------------------
-- Path of the file latexmk compiles, carried on the document so a new commit
-- inherits the choice instead of re-guessing it.
ALTER TABLE public.documents ADD COLUMN root_file_path character varying(1000) DEFAULT 'main.tex';

-- Aggregate size of the current tree, shown in the UI and used to enforce the
-- 200 MB per-project limit without summing rows on every upload.
ALTER TABLE public.documents ADD COLUMN project_size_bytes bigint NOT NULL DEFAULT 0;

-- content_snapshots is superseded by file_blobs + project_files. Kept (empty)
-- rather than dropped: commits.content_snapshot_id still references it, and
-- removing that column is a separate, riskier change.
ALTER TABLE public.commits ALTER COLUMN content_snapshot_id DROP NOT NULL;

COMMENT ON TABLE public.file_blobs IS
    'Content-addressed storage. Text inline (TOAST-compressed), binary on volume. Deduplicated across every commit of every project.';
COMMENT ON TABLE public.project_files IS
    'File tree of a single commit. One row per path; a commit is a complete restorable snapshot of the project.';
