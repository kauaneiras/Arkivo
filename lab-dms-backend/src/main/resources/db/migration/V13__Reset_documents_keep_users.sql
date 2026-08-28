-- Recomeço limpo: apaga documentos e registos de atividade, mantém as pessoas.
--
-- Pedido explícito do dono da instalação, para avaliar o sistema desde o
-- princípio com os utilizadores já configurados.
--
-- ATENÇÃO A QUEM FOR REUTILIZAR ESTE PROJETO: esta migração DESTRÓI dados. Ela
-- existe porque esta instalação estava em avaliação e o pedido foi consciente.
-- Numa base com documentos reais, isto é perda irreversível. O lugar certo para
-- uma operação destas é um script executado por decisão deliberada
-- (scripts/), não uma migração que corre sozinha em qualquer ambiente.

-- Ordem: das folhas para a raiz, para não depender de CASCADE e para deixar
-- explícito o que está a ser apagado.
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

-- Os blobs ficam órfãos depois do TRUNCATE acima: nenhuma linha de
-- project_files os referencia. Como o coletor de lixo ainda não está agendado
-- (ver MELHORIAS-FUTURAS.md), limpamos aqui de forma explícita.
-- Os ficheiros binários no volume continuam em disco — são inofensivos e serão
-- reaproveitados por dedução de hash se o mesmo conteúdo voltar a ser enviado.
DELETE FROM public.file_blobs;

-- Documentos externos (8.3.1) também são registos de trabalho, não configuração.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = 'external_documents') THEN
        EXECUTE 'TRUNCATE TABLE public.external_documents RESTART IDENTITY CASCADE';
    END IF;
END $$;

-- O que NÃO é apagado, de propósito:
--   users, roles, permissions, user_roles, role_permissions  → as pessoas e o que podem fazer
--   document_groups, document_group_members                  → os setores e quem está neles
--   document_types                                           → a taxonomia do laboratório
--   branding                                                 → a identidade visual configurada
