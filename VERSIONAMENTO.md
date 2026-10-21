# Versionamento: como o Lab DMS trata um documento como código

O modelo é o do Git, visto pela lente do GitHub Desktop: há uma **área de
trabalho** onde se edita, e um **histórico** de versões fechadas que nunca
mudam. Este documento explica o que cada peça é, o que cada ação faz ao banco e
por que o modelo anterior foi trocado.

---

## O problema que existia

Até a V15 a "última versão gravada" continuava editável. Cada tecla no editor
escrevia na árvore de arquivos do commit que já constava no histórico com
mensagem, autor, data e motivo. Uma versão "gravada" cujo conteúdo muda depois
não é uma versão — e numa auditoria essa é exatamente a pergunta que não se
quer ouvir: *"o conteúdo da v3 é o que foi aprovado em março?"* Não dava para
responder com certeza.

Dois outros defeitos vinham do mesmo lugar:

- **Aprovar não tornava a versão vigente.** O pedido de revisão não guardava
  qual versão foi submetida; aprovar lia "a versão atual" e nem atualizava o
  ponteiro `official_commit_id`. A lista mostrava "a vigente não é a mais
  recente" logo depois de uma aprovação, e alguém tinha de eleger a versão à
  mão.
- **O texto mostrado podia ser velho.** O editor abria o `content_snapshot` do
  commit, tirado no momento da gravação, enquanto a árvore já tinha sido
  editada depois. Gravar por cima disso sobrescrevia o arquivo mais novo com o
  mais velho.

---

## O modelo

```
 documents.current_commit_id ──▶ [rascunho]  is_draft = true   ← a única linha gravável
                                     │ parent
 documents.official_commit_id ──▶ [v3] ◀──── [v4]              ← fechadas, imutáveis
                                     ▲
                                    [v2]
                                     ▲
                                    [v1]
```

| Peça | O que é | No GitHub Desktop |
|---|---|---|
| **Versão fechada** (`is_draft = false`) | Um commit com mensagem, motivo, autor, data e árvore completa. Nunca é alterado. | um commit no *History* |
| **Rascunho** (`is_draft = true`) | A árvore de trabalho. Nasce copiando a árvore da cabeça e recebe todas as escritas. Existe no máximo um por documento (índice único). | o separador *Changes* |
| **Vigente** (`official_commit_id`) | A versão aprovada e em uso. Escolha explícita, nunca "a mais recente". | uma *tag* de release |
| **Atual** (`current_commit_id`) | A cabeça do ramo: o rascunho, se houver; senão a última versão fechada. | `HEAD` |

### O que cada ação faz

| Ação | Banco |
|---|---|
| **Primeira edição depois de uma gravação** (salvar arquivo, enviar figura, importar ZIP, renomear) | `openWorkingRevision`: insere um commit com `is_draft = true`, copia as linhas de `project_files` (os blobs são partilhados), avança a cabeça e `current_commit_id`. Auditoria: `WORKING_REVISION_OPENED`. |
| **Edições seguintes** | Escrevem no rascunho existente. Nenhuma linha nova em `commits`. |
| **Salvar versão** | `closeDraft`: o rascunho recebe mensagem, motivo (ISO/IEC 17025 §8.3.2 c), autor e data, e `is_draft` passa a `false`. O texto da raiz vira o `content_snapshot`. Auditoria: `COMMIT_CREATED`. |
| **Descartar alterações** | A cabeça e `current_commit_id` voltam ao pai; a linha do rascunho e a sua árvore são apagadas. Nada no histórico muda. Auditoria: `WORKING_REVISION_DISCARDED`. |
| **Restaurar v*N*** | Abre (ou reutiliza) o rascunho, substitui a sua árvore pela de v*N* e fecha-o como uma versão nova "Restaurar versão v*N*". O histórico não é reescrito. Auditoria: `VERSION_RESTORED`. |
| **Submeter para aprovação** | Recusado se houver rascunho (o ecrã grava primeiro). O pedido guarda `review_requests.commit_id`: a versão exata que o revisor decide. |
| **Aprovar** | `official_commit_id = review_requests.commit_id`, status `ACTIVE`, relógio da revisão periódica reiniciado. |
| **Devolver** | status `DRAFT`, com o comentário do revisor obrigatório. |
| **Retirar o pedido** | O autor fecha o pedido sem decisão; o documento volta a ser editável. |

### Regras que o servidor garante

- Nenhuma escrita escolhe o commit: o servidor resolve o rascunho. O cliente só
  é **informado** (`X-Working-Commit`) para passar a apontar para ele.
- Uma versão fechada nunca recebe escrita. Não há endpoint para isso.
- Um rascunho nunca é vigente nem entra em revisão.
- Documento `IN_REVIEW` ou `OBSOLETE` é só leitura para toda a gente.
- Ver uma versão antiga no editor é só leitura; o autosave está desligado.

---

## O que o ecrã mostra

**Arquivos** — a árvore do projeto, como no Overleaf.

**Alterações em andamento** — a lista dos arquivos que o rascunho muda em
relação à última versão (A/M/D e `+n −m`), e a caixa de gravação: mensagem,
motivo, *Salvar versão*, *Descartar*. É o separador *Changes* do GitHub
Desktop.

**Histórico** — as versões fechadas, v1…vN, com autor, data, motivo, tamanho da
árvore e as etiquetas *vigente* / *atual*. Cada uma tem *Comparar com anterior*,
*Comparar com a vigente*, *Tornar vigente* e *Restaurar*.

**Comparação** — lista de arquivos à esquerda, diff à direita, com números de
linha antigos e novos, cabeçalhos de *hunk*, destaque do trecho que mudou
dentro da linha, e modo unificado ou lado a lado. O mesmo componente aparece:

| Onde | Compara |
|---|---|
| Alterações em andamento | rascunho × última versão |
| Histórico, "Comparar com anterior" | v*N* × v*N−1* |
| Histórico, "Comparar com a vigente" | v*N* × vigente |
| Ao submeter | atual × vigente |
| Ao aprovar (e no histórico de revisões) | versão submetida × vigente na submissão |
| Antes de aplicar um ZIP | o que o ZIP faria × árvore atual |

**Autosave** — 1,5 s depois de parar de digitar, o arquivo aberto é escrito no
rascunho. O indicador no topo diz *Salvando rascunho…*, *Rascunho salvo às
HH:MM* ou *Alterações não salvas*. O rascunho não é uma versão: gravar a versão
continua sendo um ato deliberado com mensagem e motivo.

---

## O diff é calculado no servidor

`GET /documents/{id}/files/diff?from=&to=` compara duas árvores por SHA-256 e,
para os arquivos de texto, conta linhas acrescentadas e removidas.
`GET /documents/{id}/files/diff/file?path=&from=&to=` devolve os *hunks* de um
arquivo, com número de linha de cada lado. O algoritmo é o de Myers (O(ND)),
em `shared/util/TextDiff`, com testes. Calcular no servidor garante que todos
os ecrãs — editor, submissão, aprovação, ZIP — mostram a mesma resposta, e que
um documento de 42 arquivos não é baixado inteiro para o navegador só para
descobrir que mudaram três linhas.

---

## Migração

A V16 acrescenta `commits.is_draft` (todas as linhas existentes ficam `false`:
tudo o que existia passa a ser imutável) e `review_requests.commit_id`
(preenchido com a versão atual do documento para pedidos antigos). A primeira
edição num documento existente abre um rascunho, exatamente como num documento
novo.
