-- ============================================================
-- V16 — Versionamento ao estilo Git, revisão rastreável, SSO e armazenamento
-- ============================================================
--
-- Quatro coisas, todas pequenas no esquema e grandes no comportamento.
--
-- 1) commits.is_draft — a "árvore de trabalho".
--
--    Até aqui a última versão gravada continuava EDITÁVEL: cada escrita no
--    editor alterava a árvore do commit que já constava no histórico com
--    mensagem, autor e motivo. Uma versão "gravada" cujo conteúdo muda depois
--    não é uma versão — e numa auditoria isso é exatamente a pergunta que não
--    se quer ouvir.
--
--    Agora uma versão gravada é imutável. A primeira alteração depois de uma
--    gravação abre um commit de RASCUNHO (is_draft = true), que é a única linha
--    de commits que pode ser escrita no lugar. "Salvar versão" fecha esse
--    rascunho — mensagem, motivo, autor, data — e a alteração seguinte abre
--    outro. É o modelo do Git: working tree + commit; ou do GitHub Desktop:
--    separador "Changes" e separador "History".
--
-- 2) review_requests.commit_id — O QUE foi revisto.
--
--    O pedido guardava só o documento e o ramo. Aprovar lia "a versão atual" no
--    momento da decisão, e não a versão que o autor submeteu — e a aprovação
--    nem sequer marcava a versão aprovada como vigente. Agora o pedido aponta
--    para a versão exata, e aprovar torna ESSA versão a vigente.
--
-- 3) users.auth_provider / external_subject — contas vindas do Keycloak.
--
--    Uma conta autenticada por um provedor externo não tem senha local. O
--    sujeito (sub) do token é a chave estável; o e-mail pode mudar.
--
-- 4) file_blobs.ref_count — removida.
--
--    Era inserida como 0 e nunca mais tocada; o índice parcial sobre ela cobria
--    100% da tabela. Uma coluna cujo nome promete uma contagem que não existe
--    é um convite a um DELETE catastrófico. A verdade sobre referências é
--    NOT EXISTS sobre project_files, e é isso que o coletor usa.

-- ------------------------------------------------------------ 1) rascunhos
ALTER TABLE public.commits
    ADD COLUMN IF NOT EXISTS is_draft boolean NOT NULL DEFAULT false;

-- Cada documento tem no máximo UM rascunho: o da sua cabeça de ramo.
CREATE UNIQUE INDEX IF NOT EXISTS uk_commits_one_draft_per_document
    ON public.commits(document_id) WHERE is_draft;

-- A lista mestra e o histórico contam só versões fechadas.
CREATE INDEX IF NOT EXISTS idx_commits_document_draft
    ON public.commits(document_id, is_draft);

CREATE INDEX IF NOT EXISTS idx_commits_parent ON public.commits(parent_commit_id);

COMMENT ON COLUMN public.commits.is_draft IS
    'Working revision: the only commit whose tree may be rewritten in place. Closed by "save version".';

-- ------------------------------------------------------------ 2) revisão
ALTER TABLE public.review_requests
    ADD COLUMN IF NOT EXISTS commit_id uuid REFERENCES public.commits(id);

-- Pedidos antigos: a versão revista foi a que o documento apontava.
UPDATE public.review_requests rr
   SET commit_id = d.current_commit_id
  FROM public.documents d
 WHERE d.id = rr.document_id AND rr.commit_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_requests_status ON public.review_requests(status);
CREATE INDEX IF NOT EXISTS idx_review_requests_commit ON public.review_requests(commit_id);

-- Um documento com dois pedidos abertos ao mesmo tempo é um erro de fluxo, não
-- um estado: a segunda submissão é recusada no código, e o índice garante que
-- uma corrida entre dois cliques não produza o que o código recusa.
--
-- Bases antigas podem ter esse erro guardado (o código permitia submeter duas
-- vezes). Fica o pedido mais recente; os anteriores são fechados como
-- rejeitados, para o índice poder ser criado sem destruir nada.
UPDATE public.review_requests rr
   SET status = 'REJECTED', resolved_at = COALESCE(rr.resolved_at, NOW())
 WHERE rr.status = 'OPEN'
   AND EXISTS (SELECT 1 FROM public.review_requests o
                WHERE o.document_id = rr.document_id AND o.status = 'OPEN'
                  AND (o.created_at > rr.created_at
                       OR (o.created_at = rr.created_at AND o.id > rr.id)));

CREATE UNIQUE INDEX IF NOT EXISTS uk_review_requests_one_open_per_document
    ON public.review_requests(document_id) WHERE status = 'OPEN';

-- ------------------------------------------------------------ 3) SSO
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS auth_provider character varying(20) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS external_subject character varying(255);

DO $$ BEGIN
    ALTER TABLE public.users ADD CONSTRAINT users_auth_provider_check
        CHECK (auth_provider IN ('LOCAL', 'KEYCLOAK'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- O mesmo sujeito externo nunca cria duas contas.
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_external_subject
    ON public.users(auth_provider, external_subject) WHERE external_subject IS NOT NULL;

-- Uma conta SSO não tem senha local. A coluna passa a aceitar NULL; o login
-- local recusa explicitamente contas sem senha em vez de comparar com nada.
ALTER TABLE public.users ALTER COLUMN password_hash DROP NOT NULL;

COMMENT ON COLUMN public.users.auth_provider IS
    'LOCAL (password in password_hash) or KEYCLOAK (token-based; password_hash is NULL).';

-- ------------------------------------------------------------ 4) blobs
DROP INDEX IF EXISTS public.idx_file_blobs_unreferenced;
ALTER TABLE public.file_blobs DROP COLUMN IF EXISTS ref_count;

-- O coletor só apaga blobs órfãos com mais de X horas; a data é o filtro.
CREATE INDEX IF NOT EXISTS idx_file_blobs_created ON public.file_blobs(created_at);

-- ------------------------------------------------------------ 5) índices em falta
-- Trilha de auditoria: lida sempre por data, e filtrada por entidade.
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON public.audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON public.audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_user ON public.audit_log(user_id);

-- O PDF compilado é procurado por versão em cada abertura do editor. Sem
-- índice era uma varredura sobre uma tabela de bytea — a pior tabela para isso.
CREATE INDEX IF NOT EXISTS idx_compiled_pdfs_commit ON public.compiled_pdfs(commit_id);

-- Listagem de documentos filtra por estado e por tipo.
CREATE INDEX IF NOT EXISTS idx_documents_status ON public.documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_doc_type ON public.documents(doc_type);
