# Prova de que o visualizador de PDF renderiza

Este teste existe porque o painel de PDF foi dado como pronto três vezes e três
vezes não aparecia nada na tela. Todas as verificações anteriores paravam antes
do último elo: os testes passavam, a API devolvia um PDF válido, o pacote tinha
o código — e o navegador continuava mostrando um painel vazio.

A única prova que vale é abrir a página num navegador de verdade e olhar se os
pixels foram pintados. É isso que este teste faz.

## O que ele verifica

Abre o editor num Chromium sem interface, com um backend simulado, e mede:

1. **A página é desenhada.** Não "o componente montou" — conta quantos pixels
   não-brancos existem no `<canvas>` de cada página. Um canvas em branco reprova.
2. **A largura acompanha o painel.** A página é renderizada na largura medida do
   painel; uma largura fixa maior que ele corta a margem direita de tudo.
3. **A falha se explica.** Com o MIME do worker quebrado de propósito, o painel
   tem de dizer a causa e cair sozinho para o leitor do navegador — em vez de
   ficar em branco sem motivo, que foi o defeito original.

## Como rodar

```bash
cd e2e-visualizador
npm install            # traz um Chromium embutido no próprio pacote npm

# construir o frontend do jeito que o Docker constrói
cd ../lab-dms-frontend && VITE_API_URL=/api/v1 npx vite build && cd ../e2e-visualizador

# um PDF real, do mesmo motor que o backend usa
cd tex && pdflatex -interaction=nonstopmode document.tex && cd ..

export DIST=$PWD/../lab-dms-frontend/dist
export PDF=$PWD/tex/document.pdf
export TEX=$PWD/tex/document.tex

node server.mjs &        # backend simulado em :8100
SHOT=render.png node e2e.mjs
```

Esperado no caminho normal:

```
modo: paginado
pagina 1: 467x660 — 1835 pixels pintados
pagina 2: 467x660 — 772 pixels pintados
erros de consola: nenhum
```

Para reproduzir o defeito original — o worker do pdf.js servido com o tipo
errado — suba o servidor com `BREAK_MJS=1`. O Chrome recusa o worker
(*"Strict MIME type checking is enforced for module scripts"*), o painel explica
a causa e passa sozinho para o leitor do navegador.

`exemplo-renderizado.png` é a captura de uma execução aprovada.

## Por que um backend simulado

O teste é do **visualizador**, não do compilador. Um backend simulado devolve um
PDF conhecido, então qualquer falha só pode estar entre a resposta HTTP e o
pixel — que é exatamente o trecho onde o defeito morava. A compilação de LaTeX
tem os seus próprios testes do lado do servidor.
