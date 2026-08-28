-- Duas coisas que faltavam para o sistema poder dizer "não".
--
-- 1) Permissão de CRIAR documento.
--
-- Não existia. O controlador de criação não tinha nenhuma verificação, então
-- qualquer conta autenticada — incluindo um Leitor — criava documento pela API.
-- Esconder o botão no ecrã não resolveria: quem sabe o endereço continua a
-- conseguir. A permissão é criada aqui e exigida no servidor; o ecrã passa a ser
-- apenas o reflexo da regra, não a regra.
--
-- Concedida a Administrador e Editor, decisão do laboratório. O Revisor fica de
-- fora de propósito: quem aprova não redige, que é a separação que a ISO 17025
-- pede em 8.3.
--
-- 2) Idioma preferido, por conta.
--
-- Estava só no navegador, então mudar de máquina voltava ao português. Passa a
-- viver com a pessoa.

INSERT INTO public.permissions (id, code, description)
SELECT gen_random_uuid(), 'DOCUMENT_CREATE', 'Criar novos documentos'
 WHERE NOT EXISTS (SELECT 1 FROM public.permissions WHERE code = 'DOCUMENT_CREATE');

-- Os papéis são garantidos aqui antes de se conceder seja o que for.
--
-- Numa base nova esta tabela está VAZIA quando esta migração corre: quem cria
-- os papéis é o semeador da aplicação, que arranca depois do Flyway. Sem esta
-- parte, a concessão abaixo não encontrava nenhuma linha, a permissão ficava
-- sem dono e ninguém — nem o administrador — conseguia criar um documento.
-- A falha era silenciosa: a migração terminava com sucesso.
--
-- Manter isto na migração e não confiar no semeador é deliberado. Uma regra de
-- autorização não deve depender de uma classe chamada "DummyDataSeeder", que
-- num ambiente sério é a primeira coisa que se desliga.
INSERT INTO public.roles (id, created_at, name, description)
SELECT gen_random_uuid(), now(), v.name, v.description
  FROM (VALUES
        ('ROLE_ADMIN',  'Administrator - full system access'),
        ('ROLE_EDITOR', 'Editor - creates and edits documents')
       ) AS v(name, description)
 WHERE NOT EXISTS (SELECT 1 FROM public.roles r WHERE r.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM public.roles r
  CROSS JOIN public.permissions p
 WHERE p.code = 'DOCUMENT_CREATE'
   AND r.name IN ('ROLE_ADMIN', 'ROLE_EDITOR')
   AND NOT EXISTS (
       SELECT 1 FROM public.role_permissions rp
        WHERE rp.role_id = r.id AND rp.permission_id = p.id);

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS preferred_language varchar(5);

-- Sem NOT NULL com valor por omissão: uma conta sem preferência gravada é
-- diferente de uma que escolheu português. A primeira segue o padrão do sistema
-- e acompanha uma mudança futura desse padrão; a segunda ficou fixa por decisão
-- de quem a usa.
ALTER TABLE public.users
    DROP CONSTRAINT IF EXISTS users_preferred_language_check;
ALTER TABLE public.users
    ADD CONSTRAINT users_preferred_language_check
    CHECK (preferred_language IS NULL
           OR preferred_language IN ('pt', 'en', 'fr', 'de', 'zh', 'es'));
