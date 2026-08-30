# Projetos LaTeX multi-arquivo (regra: Overleaf)

Um documento deixou de ser uma string e passou a ser uma **árvore de arquivos**, como no Overleaf.

## O que o editor faz

| Overleaf | Lab DMS |
|---|---|
| Painel de arquivos com pastas | Botão **Arquivos** na barra superior (aberto por padrão) |
| Upload de imagem | Botão de upload, ou arrastar o arquivo para cima da pasta |
| Importar projeto (.zip) | Botão **Importar ZIP do Overleaf**, ou arrastar o .zip |
| Baixar projeto (.zip) | Ícone de download no topo do painel |
| Novo arquivo / nova pasta | Ícones no topo do painel |
| Renomear / apagar | Ícones que aparecem ao passar o mouse sobre o arquivo |
| Definir arquivo principal | Ícone de estrela (só em `.tex`); a etiqueta **raiz** mostra qual é |
| Pré-visualização automática | Compila o projeto inteiro ~2,5 s depois que você para de digitar |
| Histórico de versões | Botão **Histórico** no editor **e** na lista de fichas |

Imagens abrem como imagem, não como texto embaralhado. O caminho do arquivo aberto
fica no cabeçalho do editor — em um projeto de 42 arquivos, saber onde você está importa.

## Como os arquivos são guardados

Armazenamento **híbrido endereçado por conteúdo** (SHA-256):

- **Texto** (`.tex`, `.bib`, `.cls`…) fica no Postgres, em `file_blobs.content_text`.
  O TOAST comprime sozinho e o diff continua funcionando.
- **Binário** (imagens, PDF) fica no volume, em `blobs/aa/bb/<sha256>`, com gzip
  **apenas quando compensa** (>10% de ganho). JPG/PNG já vêm comprimidos; gravar
  um .gz maior que o original seria pior que não comprimir.
- **Deduplicação por hash**: dois arquivos iguais, em qualquer versão, ocupam
  espaço uma vez só.
- **Uma nova versão copia as linhas, não os bytes.** Editar um capítulo custa o
  tamanho daquele capítulo.

Limites: **200 MB por projeto**, **50 MB por arquivo**, 2000 arquivos.

### Custo medido com o TCC real (42 arquivos)

| | |
|---|---|
| ZIP original | 15,9 MB descompactado |
| Depois de converter EPS→PDF | 1,96 MB |
| Efetivamente armazenado (v1) | 1,46 MB |
| 6 versões, com dedup | **1,47 MB** |
| 6 versões, copiando tudo | 11,73 MB |
| Economia | **87%** |

## EPS: convertido no servidor

`epstopdf` dentro do LaTeX exige `--shell-escape`, que executa comandos arbitrários.
Isso continua **desligado**. A conversão acontece no servidor, com Ghostscript, no
momento do upload — `capa.eps` de 11,84 MB virou um PDF de 1,45 MB (−88%).

**As referências `\includegraphics` são reapontadas junto.** Sem isso o arquivo
mudava de nome e o `.tex` continuava pedindo o `.eps`: como figura faltando não é
erro fatal no LaTeX, o documento compilava normalmente **sem as figuras**. No TCC
real isso custava 2 imagens em um PDF de 52 páginas que parecia certo — o tipo de
perda silenciosa que um sistema da qualidade não pode ter.

## Endpoints

Base: `/api/v1/documents/{documentId}/files`

| Método | Caminho | O que faz |
|---|---|---|
| GET | `/` | Árvore da versão (`?commitId` para inspecionar outra) |
| GET | `/content` | Texto de um arquivo |
| GET | `/raw` | Bytes de um arquivo (imagem, PDF) |
| PUT | `/content` | Grava um arquivo de texto |
| POST | `/upload` | Envia um arquivo (`file`, `folder`) |
| POST | `/rename` | Renomeia arquivo ou pasta |
| DELETE | `/` | Remove (`?path=`) |
| POST | `/root` | Define o arquivo principal (`?path=`) |
| POST | `/import-zip` | Importa um ZIP do Overleaf |
| GET | `/export-zip` | Baixa o projeto |

Compilação: `POST /api/v1/latex/preview/project/{commitId}` com `{path, content}`
— materializa a árvore em disco e compila a raiz, com o texto não salvo por cima.
É o único caminho que resolve `\input` e `\includegraphics`.

**As escritas não aceitam `commitId`.** O servidor escolhe a versão editável e
recusa se o documento estiver em revisão ou obsoleto, para que o cliente não
consiga alterar uma versão já aprovada.

## Verificado executando

Com `TCC1___Kauan_de_Torres_Eiras.zip`:

```
1. IMPORT:     42 arquivos | 2 EPS->PDF | raiz=main.tex
               candidatos: ['bkpmain.tex', 'main.tex']  -> escolheu main.tex
1b. REPOINT:   2 arquivos .tex reescritos
2. copyTree:   v2 com 42 arquivos, 42 compartilhando blob (0 bytes novos)
3. EDIT:       editaveis/introducao.tex
4. materialise: 42 arquivos em disco
5. COMPILE:    52 páginas, 2.181.376 bytes
6. texto editado aparece no PDF: SIM
7. armazenado 1,48 MB (2 versões sem dedup seriam 3,91 MB)
```

Figuras carregadas pelo motor: `capa.pdf` (convertida) e as duas PNG.

### Bugs de runtime encontrados depois do build passar

O `docker compose build` verde não prova que a aplicação funciona. Estes dois só
apareceram relendo o código com a pergunta "o que acontece na primeira vez?":

1. **Todo upload de arquivo novo dava 500.** `assertFitsInProject` usava
   `jdbcTemplate.queryForObject` para descobrir o tamanho do arquivo que está
   sendo substituído. `queryForObject` lança `EmptyResultDataAccessException`
   quando a consulta devolve **zero linhas** — e o `COALESCE` só cobre valor
   nulo, não ausência de linha. Um arquivo novo, por definição, não tem linha.
   Afetava tanto `POST /upload` quanto `PUT /content`. Trocado por
   `queryForList`, que devolve lista vazia.

2. **Subir uma imagem num documento criado no app quebrava a compilação.** A
   árvore passava a ter arquivos mas nenhum `.tex`, e o compilador recusava com
   "no .tex file to compile". Agora, quando a árvore não tem `.tex`, o texto do
   editor é escrito como `main.tex` ao lado dos arquivos enviados — que é
   justamente o que faz `\includegraphics` funcionar nesse caso.

## Portão crítico: o que a auditoria adversarial encontrou

Cinco revisões independentes (QA, dados, segurança estática, qualidade de código,
UX) auditaram esta entrega. **Todas devolveram FALHA.** O `docker compose build`
estava verde e a aplicação tinha execução remota de código.

### Bloqueadores de segurança (provados por exploit executado)

| | |
|---|---|
| **RCE via `.latexmkrc`** | O latexmk lê `./.latexmkrc` do diretório onde corre e **executa-o como Perl**, antes de compilar e independentemente de `-no-shell-escape`. Esse diretório é preenchido com os ficheiros que o utilizador enviou. Um `.latexmkrc` dentro de um ZIP importado executava comandos no servidor. Provado: um `system("id")` correu. Corrigido com `-norc`, mais recusa do nome na ingestão. |
| **Leitura de ficheiros do servidor** | O TeX vem com `openin_any=a`, ou seja `\input{/etc/passwd}` lê qualquer ficheiro e imprime-o no PDF — sem shell escape. Provado: `root:x:0:0:...` apareceu no PDF compilado. Corrigido com `openin_any=p` e `openout_any=p`. |
| **Sanitizador contornável** | `\input\detokenize{/etc/passwd}` e `\InputIfFileExists` passavam pela expressão regular. Por isso a defesa passou a ser o próprio motor, não o filtro de texto: o motor a impor os seus limites não se contorna com sinónimos. |
| **Leitura cruzada de documentos** | `/latex/pdf/{commitId}`, `/latex/compile` e `/latex/preview/project` não tinham **nenhuma** verificação de acesso — só autenticação. Qualquer utilizador lia o PDF de qualquer documento de qualquer grupo. E nos endpoints de ficheiros o `commitId` vinha por query e nunca era ligado ao documento do caminho. Corrigido com uma verificação central. |
| **XSS armazenado** | Um `.svg` enviado era servido inline com `image/svg+xml` na origem da aplicação, e SVG executa `<script>`. Agora só PNG/JPEG/GIF/PDF renderizam inline; o resto vai como transferência, com `nosniff` e CSP. |

A mitigação do motor foi validada contra regressão: um projeto legítimo com
`\input`, `\includegraphics`, `.sty` em subpasta e bibtex produz um PDF **byte a
byte idêntico** com e sem as defesas.

### Bloqueadores de integridade do registo

- **Escrita na versão em vigor.** Depois de aprovada, `current_commit_id` e
  `official_commit_id` apontam para o mesmo commit, então gravar "na versão
  atual" alterava o **registo aprovado** — sem nova versão, sem revisão, sem
  motivo da alteração, com o hash a descrever o conteúdo antigo. Agora é
  recusado: cria-se uma nova versão e edita-se essa.
- **PDF errado servido pelo cache.** A chave era só o id do commit, com o
  comentário "um commit é imutável". Deixou de ser: a árvore da versão em edição
  muda no lugar. Agora a chave inclui uma impressão digital de `(caminho, blob,
  raiz)`, e o PDF guardado é invalidado em cada mutação. A chave do rascunho
  usava `String.hashCode`, de 32 bits — provei uma colisão com uma edição banal
  (`Aa` → `BB`), que servia o PDF anterior. Passou a SHA-256.
- **Compilava o ficheiro errado.** A raiz era escolhida **antes** de remover a
  pasta-invólucro do ZIP, então nenhum ficheiro casava, nenhum ficava marcado
  como raiz, e a compilação caía em "primeiro `.tex` por ordem alfabética" — no
  seu TCC, `bkpmain.tex`, o backup. Provado e corrigido.
- **Import destruía o projeto.** Apagava a árvore e inseria linha a linha sem
  transação: qualquer falha a meio deixava o projeto anterior destruído e o novo
  pela metade. Agora é uma transação só.
- **Restaurar versão era um registo falso.** Criava uma versão rotulada
  "Restaurar v3" com o conteúdo da versão **atual**. Agora copia a árvore da
  versão restaurada.
- **Merge perdia a árvore** e **gravar o `main.tex` desmarcava-o como raiz**.
- **Marca d'água de OBSOLETO desligada em projetos multi-arquivo**: recompilava a
  raiz isolada, falhava, e servia o PDF **sem marca**. Agora compila dentro da
  árvore e, se não conseguir marcar, recusa a transferência em vez de entregar
  um documento enganoso.

### Corrupção silenciosa de dados

- **`.tex` em Latin-1** (comum em teses antigas com `inputenc[latin1]`) tinha
  cada acento substituído por U+FFFD, de forma irreversível — e o conteúdo lido
  de volta deixava de corresponder ao seu próprio SHA-256. Agora só UTF-8 válido
  entra como texto; o resto é guardado byte a byte.
- **Renomear `cap_1/` renomeava também `cap01/` e `capX1/`**: `_` é wildcard em
  SQL LIKE. Provado (5 ficheiros afetados em 4 pastas) e corrigido com ESCAPE.
- **Limites contraditórios**: multipart do Spring em 20 MB contra 50/200 MB
  anunciados no código e na interface. Um ZIP de tese era recusado antes de
  chegar ao código que verifica o limite.

### Perda de trabalho no editor

- Clicar noutro ficheiro na árvore **descartava** tudo o que fora escrito desde a
  última gravação. Agora grava antes de trocar.
- Abrir uma figura e carregar em Salvar **escrevia o LaTeX por cima da imagem**,
  destruindo-a. O mesmo acontecia na compilação. Agora nada de texto é escrito
  num caminho binário.
- Fechar o separador não avisava. Agora avisa.
- O painel mostrava a árvore da versão mais recente enquanto se via uma versão
  antiga, e permitia apagar — mas a escrita ia para outra versão. Agora recarrega
  a árvore certa e fica só de leitura.

### Acessibilidade

As linhas da árvore eram `<div onClick>` e as ações apareciam só em `:hover` com
`display:none` — nenhum ficheiro podia ser aberto, renomeado ou apagado por
teclado, nem em tablet. Agora são `treeitem` focáveis, com Enter/Espaço e setas,
e as ações estão sempre visíveis em toque.

### Ainda não verificado

O Spring não sobe neste ambiente (Maven Central bloqueado pelo proxy), então
**a fiação de injeção de dependências e o mapeamento de rotas em tempo de execução
não foram provados** — só a lógica, executada de forma equivalente fora do Spring.
Rode `docker compose up -d --build` para confirmar.

> Se o `vite build` falhar com erro ao apagar `dist/`, é o OneDrive segurando a
> pasta. Feche a sincronização ou apague `dist/` na mão.
