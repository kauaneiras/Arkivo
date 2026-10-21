# Melhorias futuras

O que o Lab DMS ainda não faz, e o que valeria construir a seguir.

Cada item aqui foi **verificado no código**, não suposto. Onde há um limite, ele
está descrito com o efeito prático, para que a decisão de atacá-lo ou não seja
informada.

Prioridade em três níveis:

- **Alta** — pode causar perda de dados, custo crescente ou bloquear trabalho real
- **Média** — atrito diário sensível, mas contornável
- **Baixa** — conforto e polimento

---

## O que saiu desta lista

Resolvido na revisão que introduziu o rascunho de trabalho (V16):

- **Coletor de blobs** — agendado (03:30, carência de 24 h), apaga a linha
  antes do arquivo, visível e acionável em *Configurações → Armazenamento*.
- **`ref_count`** — removida, junto com o índice que cobria a tabela inteira.
- **Exportação de ZIP em memória** — transmitida entrada a entrada.
- **Leitura de blobs em N+1** — os blobs de texto são lidos num só `= ANY(?)`
  ao materializar e ao comparar árvores.
- **Salvamento automático** — o arquivo aberto é escrito no rascunho 1,5 s
  depois de parar de digitar; o rascunho não é uma versão.
- **Versão gravada editável** — não é mais: uma versão fechada é imutável.
- **Aprovar não tornava a versão vigente** — agora torna, e o pedido guarda a
  versão exata que foi revista.
- **Diff só do arquivo raiz** — a comparação é das árvores, arquivo a arquivo
  e linha a linha, com visualização estilo GitHub Desktop.
- **Contas pendentes/desativadas entravam** — não entram.
- **Recusas de `@PreAuthorize` saíam como 500** — agora 403.
- **SSO**: renovação silenciosa do token e grupos do Keycloak espelhados nos
  setores ([KEYCLOAK.md](KEYCLOAK.md)).

---

## Armazenamento

### Deduplicação não cobre o PDF compilado — **média**

`compiled_pdfs` guarda um PDF por versão, em `bytea` no banco. Como cada
rascunho fechado ganha o seu, um documento com 30 versões carrega 30 PDFs
quase iguais. A tela de armazenamento mostra o custo (coluna *PDFs*).

**O que fazer:** mover os PDFs para o `BlobStore` (endereçado por hash, no
volume) e guardar só o `sha256` em `compiled_pdfs`. A compilação determinística
do mesmo conteúdo produz o mesmo PDF na maior parte dos casos, e o volume não
incha o dump do banco.

### Retenção não é aplicada — **média**

`documents.retention_years` é guardado e mostrado, mas nenhum processo age
sobre ele: um documento obsoleto fica para sempre. O que a norma pede é que o
prazo seja **definido**; aplicá-lo (arquivar e, só depois de decisão humana,
remover) é o passo seguinte.

---

## Migrações

### Os `TRUNCATE` da V9, V13 e V14 viajam com o repositório — **alta**

Aplicadas a uma base nova são inofensivas; aplicadas a uma base com documentos
reais que tenha ficado **antes** delas, destroem tudo. Como o Flyway valida os
checksums, a correção é documental e operacional: quem clona este projeto para
uma instalação própria deve partir de um esquema limpo (todas as migrações de
uma vez), nunca de um dump anterior à V9.

---

## Editor LaTeX

### Menu de contexto na árvore — **média**

Não existe `onContextMenu`. As ações estão em botões que aparecem na linha, o
que funciona mas não é onde a mão vai primeiro.

### Busca e filtro na lista de arquivos — **média**

Num projeto de 42 arquivos numa coluna de 256 px, encontrar `capitulo03.tex` é
rolagem.

### Mover arquivos arrastando dentro da árvore — **média**

Só é possível escrevendo o caminho completo no diálogo de renomear.

### Renomear não reaponta as referências — **média**

Renomear `introducao.tex` não atualiza os `\input{introducao}` que apontam para
ele; o documento compila **sem aquele capítulo**. O import de ZIP já reaponta
figuras EPS convertidas; o rename merece o mesmo.

### Diff de arquivos binários — **baixa**

Uma figura alterada aparece como "alterado, 1,2 MB → 1,3 MB". Mostrar as duas
imagens lado a lado seria o passo seguinte.

### Virtualização da árvore — **baixa**

Acima de umas 200 linhas o painel começa a arrastar.

### SyncTeX, contagem de palavras, corretor ortográfico — **baixa**

---

## Colaboração

### Edição simultânea — **média**

Duas pessoas no mesmo arquivo ao mesmo tempo se sobrescrevem dentro do mesmo
rascunho. O fluxo previsto é uma pessoa por documento de cada vez. O rascunho
ser um commit com `author_id` dá o primeiro passo barato: mostrar **quem** o
abriu e avisar a segunda pessoa.

### Comentários ancorados na revisão — **média**

A decisão tem um comentário geral. Comentários por linha, em cima do diff que
o revisor já vê, é como uma revisão técnica costuma acontecer.

---

## Conformidade e processo

### Assinatura digital com certificado — **média**

### Hash da versão cobre só o arquivo raiz — **média**

`commits.sha256_hash` identifica o commit; a integridade da árvore inteira é
dada pelos `blob_sha256` de cada linha de `project_files`, não por um único
hash de Merkle que se possa citar num registro.

### Fluxos de aprovação em várias etapas — **baixa**

---

## Operação

### Métricas e observabilidade — **média**

Há `/actuator/health` e a tela de armazenamento. Não há métricas de negócio
expostas ao Prometheus (documentos vencendo, tempo médio de aprovação).

### Notificações por e-mail — **média**

### Trilha de auditoria à prova de adulteração — **baixa**

### Instalação multiempresa — **baixa**

---

## Como contribuir com um destes

Ao abrir um PR para qualquer item aqui, explique **qual requisito da norma ou
qual atrito real** ele resolve.
