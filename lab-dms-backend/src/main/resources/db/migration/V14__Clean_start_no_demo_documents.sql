-- Recomeço limpo, desta vez definitivo.
--
-- A V13 esvaziou as tabelas de documentos, mas o semeador corre DEPOIS das
-- migrações e recriava os oito documentos de exemplo — a limpeza era desfeita
-- no mesmo arranque em que acontecia. O semeador já não os cria (a menos que
-- SEED_DEMO_DOCUMENTS=true), por isso esta limpeza permanece.
--
-- Mesmo aviso da V13: isto DESTRÓI dados. Está aqui porque a instalação está em
-- avaliação e o pedido foi consciente. Numa base com documentos reais, o lugar
-- desta operação é um script executado por decisão deliberada.

TRUNCATE TABLE
    public.document_access,
    public.document_acknowledgements,
    public.document_attachments,
    public.project_files,
    public.compiled_pdfs,
    public.reviews,
    public.review_requests,
    public.commits,
    public.content_snapshots,
    public.branches,
    public.documents,
    public.notifications,
    public.audit_log,
    public.document_versions
RESTART IDENTITY CASCADE;

DELETE FROM public.file_blobs;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = 'external_documents') THEN
        EXECUTE 'TRUNCATE TABLE public.external_documents RESTART IDENTITY CASCADE';
    END IF;
END $$;

-- Preservado: users, roles, permissions, user_roles, role_permissions,
-- document_groups, document_group_members, document_types, branding.
