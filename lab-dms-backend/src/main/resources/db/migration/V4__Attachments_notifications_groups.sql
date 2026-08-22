-- Document attachments: external files (scanned certificates, photos, spreadsheets)
-- that live alongside the LaTeX source of a document.
CREATE TABLE public.document_attachments (
    id uuid NOT NULL PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES public.documents(id) ON DELETE CASCADE,
    file_name character varying(255) NOT NULL,
    content_type character varying(150) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_path character varying(500) NOT NULL,
    description character varying(500),
    uploaded_by uuid NOT NULL REFERENCES public.users(id),
    uploaded_at timestamp(6) without time zone NOT NULL
);

CREATE INDEX idx_document_attachments_document_id ON public.document_attachments(document_id);

-- In-app notifications: submitted / approved / rejected / obsoleted / account events.
CREATE TABLE public.notifications (
    id uuid NOT NULL PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    type character varying(50) NOT NULL,
    title character varying(255) NOT NULL,
    message character varying(1000) NOT NULL,
    link character varying(500),
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamp(6) without time zone NOT NULL
);

CREATE INDEX idx_notifications_user_unread ON public.notifications(user_id, is_read);
CREATE INDEX idx_notifications_user_created ON public.notifications(user_id, created_at DESC);

-- document_groups gains updated_at + created_by so the admin CRUD screen can
-- show who manages each group and support optimistic "last modified" info.
-- DEFAULT NOW() matters: existing INSERTs (seeder, older code paths) don't
-- set this column, and it must not fail them with a NOT NULL violation.
ALTER TABLE public.document_groups ADD COLUMN updated_at timestamp(6) without time zone DEFAULT NOW();
UPDATE public.document_groups SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE public.document_groups ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE public.document_groups ADD COLUMN created_by uuid REFERENCES public.users(id);
