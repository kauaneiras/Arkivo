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

## Armazenamento

### Coletor de blobs não referenciados — **alta**

`BlobStore.collectGarbage()` existe e **nunca é chamado** (0 chamadores fora da
própria classe). Apagar arquivos, substituir uma árvore por um import novo ou um
import que falha a meio deixam blobs órfãos no volume e linhas em `file_blobs`
para sempre. O armazenamento cresce de forma monotônica.

Há um agravante no desenho atual: o método apaga o arquivo do disco **antes** do
`DELETE` na base. Se um `INSERT` concorrente referenciar o blob nesse intervalo,
a chave estrangeira aborta o `DELETE` — mas os bytes já se foram, e a linha
sobrevive apontando para o vazio. A figura de um documento aprovado ficaria
irrecuperável.

**O que fazer:** inverter a ordem (apagar a linha primeiro, o arquivo depois),
envolver em transação, e agendar a execução com uma janela de carência — só
apagar blobs órfãos há mais de X dias, para que um import em curso nunca perca
o que acabou de escrever.

### A coluna `ref_count` mente — **alta**

`file_blobs.ref_count` é inserida como `0` e **nunca é incrementada nem lida**.
O índice parcial `WHERE ref_count = 0` cobre, por isso, 100% da tabela.

Hoje é peso morto. O risco é o dia em que alguém confiar no nome da coluna ou no
predicado do índice e escrever `DELETE FROM file_blobs WHERE ref_count = 0` —
isso apagaria o repositório de conteúdo inteiro, incluindo os blobs de documentos
aprovados.

**O que fazer:** ou manter a contagem de verdade (incrementar em `putFile` e
`copyTree`, decrementar em `deleteFile` e `replaceTree`), ou remover a coluna e
o índice e deixar o `NOT EXISTS` sobre `project_files` ser a única verdade. A
segunda opção é mais simples e menos sujeita a desvio.

### Exportação de ZIP montada em memória — **média**

`ProjectFileController.exportZip` acumula o projeto inteiro num
`ByteArrayOutputStream` antes de responder. No teto anunciado de 200 MB por
projeto, cada exportação simultânea segura algumas centenas de MB de heap.

**O que fazer:** transmitir com `StreamingResponseBody`, escrevendo cada entrada
direto na resposta. O import tem o mesmo padrão (`file.getBytes()`) e merece o
mesmo tratamento.

### Leitura de blobs em N+1 — **média**

`materialise()` e `exportZip()` fazem uma consulta por arquivo. No projeto de
referência com 42 arquivos, são 43 idas à base por compilação e por exportação.

**O que fazer:** um `SELECT ... WHERE sha256 = ANY(?)` em lote para os blobs de
texto, e leitura por streaming para os binários.

---

## Migrações

### O `TRUNCATE` da V9 vive numa migração versionada — **alta**

`V9__Multifile_projects.sql` contém um `TRUNCATE ... CASCADE` de nove tabelas.
Isso foi acordado e correto para **esta** instalação, que estava em
desenvolvimento — mas a migração viaja com o repositório. Aplicá-la a uma base
que já tenha documentos reais destrói tudo, sem aviso e sem undo.

Dois agravantes: `audit_entries` não tem chave estrangeira para `documents`, por
isso a trilha de auditoria **sobrevive** apontando para documentos que já não
existem (numa auditoria, isso é pior que trilha ausente); e os anexos no volume
não são removidos, ficando órfãos.

**O que fazer:** transformar o `TRUNCATE` num script explícito em `scripts/`,
executado por decisão consciente, e deixar a V9 apenas criar o esquema. Como a
V9 já foi aplicada aqui, a correção é uma migração nova que documente o
histórico, não uma edição da V9 — o Flyway valida checksums e recusaria subir.

---

## Editor LaTeX

O editor tem árvore de arquivos, import e export de ZIP do Overleaf, envio de
PDF, compilação com erros clicáveis e histórico de versões. O que falta, em
ordem de impacto no trabalho real:

### Salvamento automático — **alta**

Não existe. Gravar é um ato deliberado que exige mensagem e motivo da alteração
— o que é correto para o registro ISO, mas significa que as pessoas acumulam
trabalho entre gravações. Há proteção contra perder (aviso ao fechar a aba, e o
buffer é gravado ao trocar de arquivo), mas não há rede de segurança contra
fechar o navegador à força ou cair a energia.

**O que fazer:** rascunho automático por debounce num armazenamento separado do
histórico de versões — o rascunho não é uma versão e não deve poluir o registro.
Recuperado ao reabrir, com opção de descartar.

### Menu de contexto na árvore — **média**

Não existe `onContextMenu`. No Overleaf, o botão direito é a interação primária
para renomear, apagar e criar. Aqui as ações estão em botões que aparecem na
linha, o que funciona mas não é onde a mão vai primeiro.

### Busca e filtro na lista de arquivos — **média**

A árvore não tem campo de busca. Num projeto de 42 arquivos numa coluna de 256
px, encontrar `capitulo03.tex` é rolagem.

### Mover arquivos arrastando dentro da árvore — **média**

Só é possível escrevendo o caminho completo no diálogo de renomear. Funciona,
mas ninguém descobre sozinho.

### Renomear não reaponta as referências — **média**

Renomear `introducao.tex` não atualiza os `\input{introducao}` que apontam para
ele. Como arquivo faltando não é erro fatal no LaTeX, o documento continua
compilando — **sem aquele capítulo**. O import de ZIP já faz esse reaponte para
figuras EPS convertidas; o rename merece a mesma atenção.

### Virtualização da árvore — **baixa**

O limite do backend é 2000 arquivos por projeto, e a árvore renderiza tudo. Acima
de umas 200 linhas o painel começa a arrastar.

### SyncTeX, contagem de palavras, corretor ortográfico — **baixa**

Ausentes. O SyncTeX (duplo clique no PDF leva à linha do código) é o mais sentido
dos três em documentos longos.

### Histórico por arquivo — **baixa**

O histórico é do documento inteiro. Não dá para perguntar "quando é que
`cap3.tex` mudou?".

---

## Colaboração

### Edição simultânea — **média**

Duas pessoas no mesmo arquivo ao mesmo tempo se sobrescrevem. O fluxo previsto é
uma pessoa por documento de cada vez, com revisão formal depois, o que é
coerente com o processo ISO — mas convém ser explícito.

**O que fazer, do mais barato ao mais caro:** (1) indicar quem mais está com o
documento aberto; (2) bloqueio otimista, recusando a gravação se a versão base
mudou; (3) edição colaborativa real com CRDT. A opção 2 resolve a maior parte do
problema por uma fração do custo da 3.

### Comentários e sugestões na revisão — **média**

A revisão é aprovar ou rejeitar, com uma descrição. Não há comentários ancorados
num trecho, que é como uma revisão técnica costuma acontecer na prática.

---

## Conformidade e processo

### Assinatura digital com certificado — **média**

A aprovação é registrada, auditável e atribuída a uma pessoa autenticada, mas
não é uma assinatura com certificado ICP-Brasil. Alguns escopos de acreditação
pedem isso explicitamente.

### Hash da versão cobre só o arquivo raiz — **média**

`commits.sha256_hash` é calculado sobre o texto do arquivo principal. Num projeto
de 42 arquivos, as outras 41 partes — sub-capítulos, figuras, bibliografia — não
estão cobertas por prova de integridade. Alterar uma figura de um commit aprovado
não muda o hash.

**O que fazer:** hash de Merkle sobre `(caminho, blob_sha256)` ordenado, que é
barato de calcular e cobre a árvore inteira.

### Fluxos de aprovação em várias etapas — **baixa**

Hoje é um revisor designado. Alguns laboratórios exigem aprovação técnica **e**
da qualidade, em sequência.

---

## Operação

### Instalação multiempresa — **baixa**

Uma instalação serve um laboratório. A identidade visual é uma linha única na
tabela `branding`, por decisão de desenho. Suportar várias organizações exigiria
isolamento por inquilino em toda a camada de dados — mudança grande, e só vale se
houver a necessidade.

### Métricas e observabilidade — **média**

Existe `/actuator/health`. Não há métricas de negócio (documentos vencendo,
tempo médio de aprovação, filas de compilação) nem rastreamento distribuído.

### Notificações por e-mail — **média**

As notificações são internas. Um documento vencendo não gera e-mail, o que
significa que só quem entra no sistema fica sabendo.

### Trilha de auditoria à prova de adulteração — **baixa**

A trilha é imutável pela aplicação, mas quem tiver acesso ao banco pode editá-la.
Encadear os registros por hash tornaria a adulteração detectável.

---

## Como contribuir com um destes

Ao abrir um PR para qualquer item aqui, explique **qual requisito da norma ou
qual atrito real** ele resolve. É o que separa uma funcionalidade útil de uma que
só parece útil — e num sistema da qualidade, funcionalidade que só parece útil
costuma virar não conformidade.
