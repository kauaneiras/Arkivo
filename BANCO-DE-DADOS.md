# O banco de dados

PostgreSQL 16, esquema versionado com Flyway. **25 tabelas em 16 migrações.**

Este documento explica não só o que existe, mas **por que** cada decisão foi
tomada — que é o que falta quando alguém pega um esquema alheio e tenta mudá-lo
sem quebrar nada.

---

## O modelo em uma frase

Um **documento** é uma identidade estável (código, título, tipo). O conteúdo dele
vive numa cadeia de **versões imutáveis**, e cada versão é uma **árvore de
arquivos** completa. Nada é apagado; o que muda é qual versão está em vigor.

```
documents ──┬── branches ──── commits ──┬── content_snapshots   (texto da raiz)
            │                  ▲        ├── project_files ──── file_blobs
            │                  │        └── compiled_pdfs      (PDF gerado)
            ├── document_access         review_requests ── reviews
            └── document_attachments
```

---

## As quatro decisões que explicam o resto

### 1. Versão é um retrato completo, não um delta

`commits` não guarda diferenças. Cada versão aponta para a árvore inteira de
arquivos daquele momento.

**Por quê:** a ISO/IEC 17025 exige que qualquer revisão anterior seja
recuperável. Com deltas, restaurar a v3 significa reconstruir aplicando v1+v2+v3
— e um delta corrompido no meio leva tudo. Com retratos, a v3 é lida
diretamente.

**O custo disso é resolvido pela dedução por hash** (ver `file_blobs`): guardar a
árvore inteira só é caro se os bytes forem duplicados, e eles não são.

### 1b. O rascunho é a única linha gravável de `commits`

`commits.is_draft` (V16) marca a **área de trabalho**: o commit que nasce na
primeira edição depois de uma gravação, copiando a árvore da cabeça, e que
recebe todas as escritas até "Salvar versão" o fechar (mensagem, motivo, autor,
data; `is_draft = false`). Há no máximo um por documento — índice único parcial
`uk_commits_one_draft_per_document`. Tudo o que está `is_draft = false` é
imutável: nenhum endpoint escreve nele. É o que faz "v3" significar sempre o
mesmo conteúdo. Detalhes em [VERSIONAMENTO.md](VERSIONAMENTO.md).

### 2. "Versão atual" e "versão em vigor" são coisas diferentes

`documents` tem **duas** colunas apontando para `commits`:

| Coluna | Significa |
|---|---|
| `current_commit_id` | a última versão salva, aprovada ou não |
| `official_commit_id` | a versão **aprovada e em vigor** |

**Por quê:** o mais recente não é o válido. Enquanto a v4 está em revisão, quem
executa o procedimento na bancada deve continuar a ver a v3. E é possível voltar
a v2 para vigente sem apagar a v3 nem a v4.

> **Armadilha real, já corrigida:** só `official_commit_id` tem chave estrangeira
> para `commits`. Gravar um documento apontando para um commit ainda não inserido
> falha a restrição. A ordem correta é documento → branch → commit → **então**
> marcar a vigente.

### 3. Conteúdo é endereçado por hash

`file_blobs` é indexada pelo **SHA-256 do conteúdo**, não por um id. Armazenamento
híbrido:

| Tipo | Onde fica | Por quê |
|---|---|---|
| Texto (`.tex`, `.bib`, `.cls`) | `content_text` no Postgres | O TOAST comprime sozinho, e comparar versões continua sendo SQL |
| Binário (imagem, PDF) | arquivo no volume, `storage_path` | Não incha o banco nem os backups lógicos |

Compressão gzip **só quando compensa** (>10% de ganho): um JPEG já comprimido
ficaria maior.

**O efeito medido**, com uma tese real de 42 arquivos: 6 versões ocupam **1,47 MB**
em vez dos 11,73 MB de cópias integrais — 87% de economia. Uma nova versão copia
as *linhas* de `project_files`, não os bytes.

### 4. O registro de auditoria não tem chave estrangeira

`audit_log` guarda `entity_id` solto, sem FK.

**Por quê:** uma trilha de auditoria precisa sobreviver ao que ela descreve. Se
tivesse FK com CASCADE, apagar um documento apagaria a prova de que ele existiu.

> **O reverso disso** é que a trilha pode apontar para linhas que já não existem
> — visível depois de um reset. É o preço consciente da imutabilidade.

---

## Tabelas por área

### Pessoas e permissões

| Tabela | Papel | Migração |
|---|---|---|
| `users` | Pessoas. `username` = e-mail. `auth_provider` LOCAL/KEYCLOAK e `external_subject` (V16); conta SSO tem `password_hash` nulo | V1 |
| `roles` | Papéis (`ROLE_ADMIN`, `ROLE_EDITOR`, `ROLE_REVIEWER`, `ROLE_VIEWER`) | V1 |
| `permissions` | Permissões atômicas (`DOCUMENT_EDIT`, `REVIEW_APPROVE`…) | V1 |
| `user_roles` | **Vários papéis por pessoa** | V2 |
| `role_permissions` | Papel → permissões | V1 |

`user_roles` é tabela de ligação porque uma pessoa pode escrever **e** revisar —
só nunca o próprio documento. Essa regra é de código
(`AccessControlService.assertCanApprove`), não do banco: ela compara o autor com
quem aprova, e uma restrição SQL não expressa isso bem.

### Documentos e versões

| Tabela | Papel | Migração |
|---|---|---|
| `documents` | Identidade: código, título, tipo, status, visibilidade | V1 |
| `branches` | Linha de trabalho. Hoje só a `main` por documento | V1 |
| `commits` | Uma versão. Imutável, com autor, mensagem e **motivo da alteração** | V1 |
| `content_snapshots` | Texto do arquivo raiz, deduplicado por hash | V1 |
| `project_files` | A árvore daquela versão: caminho → blob | V9 |
| `file_blobs` | O conteúdo, endereçado por SHA-256 | V9 |
| `compiled_pdfs` | PDF gerado, em cache por versão | V1 |
| `document_versions` | **Legada.** Do modelo anterior a V9; nada lê | V1 |

`commits.content_snapshot_id` é **anulável desde a V9**: um projeto multi-arquivo
não tem um "texto único". Consultas que fazem JOIN nessa tabela precisam de
`LEFT JOIN` — um `INNER JOIN` some com essas versões.

### Revisão e conformidade

| Tabela | Papel | Migração |
|---|---|---|
| `review_requests` | Pedido de aprovação de uma versão. `commit_id` (V16) fixa **qual**; aprovar torna-a vigente | V1 |
| `reviews` | A decisão: aprovado ou rejeitado, com comentário | V1 |
| `document_acknowledgements` | Ciência de leitura, por pessoa e versão (8.3.2 d) | V8 |
| `external_documents` | Normas e manuais de terceiros (8.3.1) | V8 |
| `audit_log` | Trilha imutável de toda ação relevante | V1 |

Colunas de conformidade em `documents`, todas da V7: `review_interval_months`,
`next_review_date`, `last_reviewed_at/by` (8.3.2 b) e `retention_years` (8.4).
**Intervalo `0` ou nulo significa "não vence"** — e `next_review_date` fica nula,
o que mantém o documento fora dos lembretes.

### Acesso e organização

| Tabela | Papel | Migração |
|---|---|---|
| `document_groups` | Setores do laboratório | V1 |
| `document_group_members` | Quem pertence a cada setor | V11 |
| `document_access` | Concessões de um documento restrito | V11 |
| `document_types` | Tipos e siglas, editáveis pelo admin | V10 |
| `branding` | Nome, logo e cor. **Linha única**, fixada por CHECK | V12 |

`documents.visibility` (V11) tem três valores:

| Valor | Quem vê |
|---|---|
| `PUBLIC` | Qualquer pessoa, **sem login**. Só a versão em vigor |
| `PRIVATE` | Qualquer pessoa autenticada |
| `RESTRICTED` | Só quem está em `document_access` (por grupo ou por pessoa) |

`document_access` tem **exatamente um** destinatário por linha — grupo *ou*
pessoa, garantido por CHECK. Isso evita linhas ambíguas com os dois preenchidos.

`document_types.prefix` é **imutável na prática**: a sigla já faz parte de todos
os códigos emitidos (`IT-001`), e mudá-la reescreveria o significado de
identificadores citados em auditoria. O serviço não expõe forma de alterá-la.

---

## As migrações

| | O que trouxe |
|---|---|
| **V1** | Esquema inicial: documentos, versões, revisões, auditoria |
| **V2** | Vários papéis por usuário |
| **V3** | Setor passa a ser opcional |
| **V4** | Anexos, notificações, grupos |
| **V5** | Correção: `branches.status` tinha valor fora do enum |
| **V6** | Revisor designado e versão em vigor separada da atual |
| **V7** | Revisão periódica, motivo da alteração, retenção |
| **V8** | Documentos externos e ciência de leitura |
| **V9** | Projetos multi-arquivo (`project_files`, `file_blobs`) |
| **V10** | Tipos de documento saem do código para o banco |
| **V11** | Membros de grupo e visibilidade por documento |
| **V12** | Identidade visual |
| **V13** | Reset de documentos, mantendo pessoas e configuração |
| **V14** | O mesmo reset, agora que o semeador não o desfaz |
| **V15** | Permissão de criar documento e idioma por conta |
| **V16** | Rascunho de trabalho (`commits.is_draft`), versão exata no pedido de revisão, contas SSO, remoção de `ref_count`, índices em falta |

### Uma nota sobre a V15 e os papéis

A V15 **cria os papéis `ROLE_ADMIN` e `ROLE_EDITOR` se ainda não existirem**,
antes de conceder a permissão. Parece redundante — o semeador também os cria —
mas não é: numa base nova a tabela `roles` está vazia quando o Flyway roda,
porque o semeador da aplicação só arranca depois. Sem essa parte, a concessão
não encontrava nenhuma linha, `DOCUMENT_CREATE` ficava sem dono e **ninguém, nem
o administrador, conseguia criar um documento** — com a migração terminando em
sucesso. Foi apanhado rodando as 15 migrações contra um PostgreSQL de verdade.

Uma regra de autorização não deve depender de uma classe chamada
`DummyDataSeeder`, que num ambiente sério é a primeira coisa que se desliga.

### Por que o reset precisou de duas migrações

A V13 esvaziou as tabelas e, no mesmo boot, os oito documentos de exemplo
voltaram. O motivo não estava nela: o `DummyDataSeeder` roda **depois** do
Flyway, e recriava os exemplos a cada arranque. Quem limpasse a base para começar
o próprio trabalho encontrava os exemplos de volta e concluía, com razão, que a
limpeza não tinha funcionado.

O semeador agora só cria os exemplos quando `SEED_DEMO_DOCUMENTS=true`. Pessoas,
papéis e setores continuam sendo semeados sempre — são configuração, não dado de
trabalho. A V14 repete a limpeza porque a V13 já constava como aplicada e o
Flyway não roda a mesma versão duas vezes.

Para ter os exemplos de volta numa demonstração:

```bash
SEED_DEMO_DOCUMENTS=true docker compose up -d backend
```

### Regras ao mexer no esquema

**Nunca edite uma migração já aplicada.** O Flyway guarda o checksum de cada uma
em `flyway_schema_history` e recusa subir se algo mudou. Corrigir é sempre uma
migração nova.

**V13 e V14 destroem dados.** Elas existem porque esta instalação estava em
avaliação e o reset foi pedido. Numa base com documentos reais isso é perda
irreversível — o lugar certo para uma operação dessas é um script em `scripts/`,
executado por decisão consciente, não uma migração que roda sozinha em qualquer
ambiente.

---

## Consultas úteis

```sql
-- Lista mestra: cada documento com a revisão em vigor
SELECT d.code, d.title, d.status, d.visibility,
       (SELECT COUNT(*) FROM commits c WHERE c.document_id = d.id) AS revisoes,
       d.next_review_date
  FROM documents d ORDER BY d.code;

-- Quanto o armazenamento está realmente ocupando, e quanto a dedução poupou
SELECT pg_size_pretty(SUM(stored_bytes))  AS armazenado,
       pg_size_pretty(SUM(size_bytes))    AS sem_compressao,
       COUNT(*)                           AS blobs
  FROM file_blobs;

-- Blobs órfãos (o coletor corre às 03:30 com 24 h de carência; a tela
-- Configurações → Armazenamento mostra o mesmo número e permite limpar agora)
SELECT COUNT(*), pg_size_pretty(SUM(size_bytes))
  FROM file_blobs b
 WHERE NOT EXISTS (SELECT 1 FROM project_files p WHERE p.blob_sha256 = b.sha256);

-- Documentos com alterações em andamento (rascunho aberto)
SELECT d.code, c.created_at AS rascunho_desde, u.full_name
  FROM commits c JOIN documents d ON d.id = c.document_id JOIN users u ON u.id = c.author_id
 WHERE c.is_draft;

-- Quem enxerga um documento restrito
SELECT COALESCE(g.name, u.full_name) AS quem,
       CASE WHEN da.group_id IS NOT NULL THEN 'setor' ELSE 'pessoa' END AS tipo
  FROM document_access da
  LEFT JOIN document_groups g ON g.id = da.group_id
  LEFT JOIN users u ON u.id = da.user_id
 WHERE da.document_id = '<uuid>';
```

## Backup

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup.ps1
```

Gera o dump do banco **e** os anexos do volume. Os dois são necessários: o dump
tem as linhas de `file_blobs`, mas os blobs binários vivem em disco. Restaurar só
o banco deixa referências apontando para arquivos que não existem.
