-- ============================================================
-- ISO/IEC 17025:2017 — 8.3.1  control of EXTERNAL documents
--                      8.3.2 d) controlled distribution
-- ============================================================

-- 8.3.1: the clause covers "all documents that relate to fulfilling the
-- standard", internal AND external. For an electronics laboratory the external
-- set is large and is the part assessors most often find uncontrolled:
-- instrument manuals (oscilloscope, power supply, signal generator), reference
-- standards (IPC-A-610, IEC 61010, JEDEC), calibration tables and conversion
-- charts. They are NOT versioned like internal documents — the laboratory does
-- not author them — so what is controlled is the EDITION in force, who owns it,
-- and evidence that it was checked as current.
CREATE TABLE public.external_documents (
    id uuid NOT NULL PRIMARY KEY,
    -- Same unique-identifier rule as internal documents (8.3.2 e)
    code character varying(50) NOT NULL UNIQUE,
    title character varying(255) NOT NULL,
    -- Issuer of the document: IEC, IPC, JEDEC, Tektronix, Keysight...
    issuer character varying(150),
    -- The edition/revision the laboratory declares as current, e.g. "IPC-A-610H"
    -- or "Rev. C, 2023-05". Free text on purpose: every issuer numbers
    -- differently and forcing a scheme here would falsify the record.
    edition character varying(100),
    doc_kind character varying(30) NOT NULL,
    publication_date date,
    -- Evidence that somebody confirmed this is still the edition in force
    -- (8.3.1). Superseded standards at the bench are a classic finding.
    last_verified_at timestamp(6) without time zone,
    last_verified_by uuid REFERENCES public.users(id),
    verification_interval_months integer DEFAULT 12,
    next_verification_date date,
    -- Who is accountable for keeping this external document current
    owner_id uuid REFERENCES public.users(id),
    -- Where the physical/electronic copy lives, or the publisher's URL
    location character varying(500),
    notes text,
    status character varying(20) NOT NULL DEFAULT 'ACTIVE',
    -- Optional local copy (PDF of the manual), reusing the attachment storage
    storage_path character varying(500),
    file_name character varying(255),
    content_type character varying(150),
    size_bytes bigint,
    created_at timestamp(6) without time zone NOT NULL,
    created_by uuid REFERENCES public.users(id),
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT external_documents_kind_check CHECK (doc_kind IN
        ('STANDARD', 'MANUAL', 'CALIBRATION_TABLE', 'REGULATION', 'DATASHEET', 'OTHER')),
    CONSTRAINT external_documents_status_check CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'WITHDRAWN'))
);

CREATE INDEX idx_external_documents_status ON public.external_documents(status);
CREATE INDEX idx_external_documents_next_verification
    ON public.external_documents(next_verification_date)
 WHERE next_verification_date IS NOT NULL;

-- 8.3.2 d): "the current version ... is available at the point of use" and
-- distribution is controlled. In an electronic system the way you evidence that
-- is a read receipt: WHO acknowledged WHICH revision, and when. Tied to the
-- commit (the exact revision), never just to the document — acknowledging v1
-- says nothing about v3.
CREATE TABLE public.document_acknowledgements (
    id uuid NOT NULL PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES public.documents(id) ON DELETE CASCADE,
    commit_id uuid NOT NULL REFERENCES public.commits(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    acknowledged_at timestamp(6) without time zone NOT NULL,
    -- Kept for traceability of the act itself (7.11 data integrity)
    ip_address character varying(45),
    -- One acknowledgement per person per revision; re-reading is not a new fact
    CONSTRAINT uk_ack_user_commit UNIQUE (user_id, commit_id)
);

CREATE INDEX idx_ack_document ON public.document_acknowledgements(document_id);
CREATE INDEX idx_ack_commit ON public.document_acknowledgements(commit_id);

-- 8.3.2 d) also drives a per-document decision: does reading this revision have
-- to be acknowledged at all? A quality manual usually does; an internal form
-- usually does not. Defaults to false so nothing silently becomes mandatory.
ALTER TABLE public.documents
    ADD COLUMN requires_acknowledgement boolean NOT NULL DEFAULT false;
