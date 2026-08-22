-- Real access control: who belongs to a group, and who may see a document.
--
-- Until now UserRepositoryAdapter.hasGroupAccess() returned `true`
-- unconditionally, so every authenticated user could see every document
-- regardless of sector. The group column existed and was checked, but the check
-- always passed — the restriction was decorative. These two tables are what
-- makes it mean something.

-- ---------------------------------------------------------------- membership
CREATE TABLE IF NOT EXISTS public.document_group_members (
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    added_at timestamp(6) without time zone NOT NULL DEFAULT NOW(),
    added_by uuid,
    CONSTRAINT document_group_members_pkey PRIMARY KEY (group_id, user_id),
    CONSTRAINT fk_dgm_group FOREIGN KEY (group_id)
        REFERENCES public.document_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_dgm_user FOREIGN KEY (user_id)
        REFERENCES public.users(id) ON DELETE CASCADE
);

-- Answers "which groups is this user in?", the question asked on every read.
CREATE INDEX IF NOT EXISTS idx_dgm_user ON public.document_group_members(user_id);

-- ---------------------------------------------------------------- visibility
--   PUBLIC     — readable without signing in. Only the revision IN FORCE, only
--                as a rendered PDF, never drafts and never the source tree.
--   PRIVATE    — any authenticated user of this installation.
--   RESTRICTED — only the groups and people named in document_access.
ALTER TABLE public.documents
    ADD COLUMN IF NOT EXISTS visibility character varying(20) NOT NULL DEFAULT 'PRIVATE';

DO $$ BEGIN
    ALTER TABLE public.documents ADD CONSTRAINT documents_visibility_check
        CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'RESTRICTED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Existing documents become PRIVATE, which is EXACTLY what they are today:
-- reachable by any signed-in user, because the group check never denied
-- anything. Defaulting them to RESTRICTED would lock people out of documents
-- they can currently open; defaulting them to PUBLIC would put internal
-- procedures on the internet. PRIVATE is the only value that changes nothing.
UPDATE public.documents SET visibility = 'PRIVATE' WHERE visibility IS NULL;

CREATE INDEX IF NOT EXISTS idx_documents_visibility ON public.documents(visibility);

-- ------------------------------------------------------- per-document access
-- One row per grantee. Exactly one of group_id / user_id is set, which keeps
-- "granted to a group" and "granted to a person" in one table without a
-- nullable-key mess to join through.
CREATE TABLE IF NOT EXISTS public.document_access (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL,
    group_id uuid,
    user_id uuid,
    granted_at timestamp(6) without time zone NOT NULL DEFAULT NOW(),
    granted_by uuid,
    CONSTRAINT document_access_pkey PRIMARY KEY (id),
    CONSTRAINT fk_da_document FOREIGN KEY (document_id)
        REFERENCES public.documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_da_group FOREIGN KEY (group_id)
        REFERENCES public.document_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_da_user FOREIGN KEY (user_id)
        REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT document_access_one_grantee CHECK (
        (group_id IS NOT NULL AND user_id IS NULL) OR
        (group_id IS NULL AND user_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_access_group
    ON public.document_access(document_id, group_id) WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_access_user
    ON public.document_access(document_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_document_access_document
    ON public.document_access(document_id);

-- Seeding membership: everyone who exists today joins every group that exists
-- today.
--
-- This looks over-generous, and it is deliberate. Until this migration,
-- hasGroupAccess() returned true for everybody, so the effective state of the
-- system is "every user is in every group". Writing that state down changes
-- nothing for anyone, which is the point: turning a decorative check into a
-- real one must not be the moment people discover they can no longer open
-- documents they were working on yesterday.
--
-- The administrator then prunes the lists from the Users screen, and each
-- removal is a deliberate, visible act rather than a silent side effect of an
-- upgrade.
INSERT INTO public.document_group_members (group_id, user_id)
SELECT g.id, u.id
  FROM public.document_groups g
 CROSS JOIN public.users u
ON CONFLICT DO NOTHING;

COMMENT ON COLUMN public.documents.visibility IS
    'PUBLIC readable without authentication (in-force revision only); PRIVATE any signed-in user; RESTRICTED per document_access.';
