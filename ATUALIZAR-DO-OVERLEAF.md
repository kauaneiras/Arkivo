# Atualizar um documento com um ZIP do Overleaf

O caso: o documento já existe e está em vigor. Você continuou a trabalhar no
Overleaf, exportou o projeto, e quer que essa nova versão entre no sistema —
passando pela revisão, com quem aprova a ver o que mudou.

---

## O percurso

**1. Escolher o ZIP.** No editor, *Importar ZIP do Overleaf*. Nada é escrito
ainda: o servidor lê o ficheiro em modo de ensaio e devolve o que ele **faria**.

**2. Conferir.** Abre uma janela com a lista de alterações, ordenada pelo que
importa: primeiro o que **desaparece**, depois o que é novo, depois o que mudou.
Se houver remoções, o aviso é explícito e o botão de aplicar fica vermelho.

> Um ZIP substitui a árvore inteira. Exportar a subpasta errada apaga capítulos,
> e o erro costuma aparecer semanas depois, quando alguém procura o ficheiro —
> já com a versão aprovada. Ver a lista antes transforma um acidente silencioso
> numa decisão.

**3. Aplicar.** Os ficheiros entram no **rascunho** (a área de trabalho), não
numa versão. A versão em vigor continua intacta e ninguém a vê mudar. Se o ZIP
já é a versão final, marque *Gravar como nova versão imediatamente* e escreva a
mensagem e o motivo ali mesmo — um passo em vez de dois.

Esta é a primeira escrita depois de uma gravação, e o sistema abre sozinho o
rascunho a partir da última versão — registado na auditoria como
`WORKING_REVISION_OPENED`. Uma versão fechada nunca é reescrita.

**4. Rever e gravar.** Compile, corrija o que quiser. *Alterações em andamento*
mostra o que o rascunho muda em relação à última versão; a caixa no fundo grava
a versão com a justificativa (ISO/IEC 17025 §8.3.2 c). Um ZIP errado
descarta-se com *Descartar alterações*, sem deixar rasto no histórico.

**5. Submeter.** A janela de submissão mostra a comparação entre o que você está
a enviar e a versão em vigor. Assinar sem ler é fácil quando um ZIP muda trinta
ficheiros de uma vez.

**6. Aprovar.** Quem revisa vê a mesma comparação **dentro do diálogo de
aprovação**, antes de decidir. Antes disto era preciso abrir o editor, encontrar
o histórico e comparar à mão — o que na prática significava não comparar.

---

## Como a comparação funciona

Compara as duas **árvores de ficheiros**, não só o texto do ficheiro raiz.

A comparação anterior olhava apenas para o `content_snapshot` da versão, que é o
texto do ficheiro principal. Num projeto do Overleaf isso é quase nada: dá para
acrescentar capítulos, apagar figuras e reescrever a bibliografia sem tocar no
`main.tex` — e a tela dizia *"sem alterações"*.

A igualdade é por **SHA-256 do conteúdo**, não por data nem por tamanho: dois
ficheiros com o mesmo hash são o mesmo ficheiro. É isso que permite dizer
"inalterado" com certeza, em vez de por aproximação.

| Estado | Significa |
|---|---|
| **removido** | existia antes, não vem no ZIP — aparece primeiro, a vermelho |
| **novo** | não existia |
| **alterado** | mesmo caminho, conteúdo diferente |
| **igual** | mesmo hash; escondido por omissão, porque numa tese de 42 ficheiros a lista afogaria as três linhas que interessam |

A lista mostra, por ficheiro, `+n −m` linhas. Clicar num ficheiro de texto abre
o diff à direita, com números de linha dos dois lados, cabeçalhos de *hunk* e
o trecho alterado destacado dentro da linha — unificado ou lado a lado. Para
imagens e PDFs só há o estado e o tamanho: não há diff útil de bytes binários.

O diff é calculado no servidor (Myers, `shared/util/TextDiff`), para que o
ensaio do ZIP, o histórico e a aprovação mostrem exatamente a mesma resposta.

---

## Onde aparece

| Onde | Compara |
|---|---|
| Antes de aplicar o ZIP | o que o ZIP faria à árvore atual |
| Alterações em andamento | o rascunho contra a última versão gravada |
| Ao submeter para aprovação | a versão atual contra a em vigor |
| No diálogo de aprovação | a versão submetida contra a em vigor na submissão |
| No histórico do documento | qualquer versão contra a anterior, ou contra a vigente |

---

## O que o servidor expõe

```
GET  /api/v1/documents/{id}/files/diff?to={commit}[&from={commit}]
     Sem 'from', compara com a versão anterior; sem 'to', com a atual.
     Por ficheiro: estado, tamanhos, SHA-256 e linhas +/−.

GET  /api/v1/documents/{id}/files/diff/file?path=...&to={commit}[&from={commit}]
     Os hunks de um ficheiro, com número de linha de cada lado.

POST /api/v1/documents/{id}/files/import-zip?dryRun=true
     Lê o ZIP e devolve a previsão em `preview`, com os diffs de texto em
     `previewDiffs`. Não escreve na árvore.

POST /api/v1/documents/{id}/files/import-zip
     Aplica ao rascunho. Devolve `workingCommit`, a versão em que entrou.
```

Ambos exigem poder **ler** o documento; aplicar o ZIP exige poder **escrever**,
pela mesma regra de sempre (ver `ACESSOS.md`).
