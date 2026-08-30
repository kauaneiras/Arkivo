# O que mudou — os seis pedidos

## 1. Pré-visualização do LaTeX

**A causa era outra, não o motor.** No primeiro carregamento, a função de
compilar fechava sobre a lista de arquivos ainda vazia, então **todo projeto
multi-arquivo era enviado como texto solto** — sem `\input`, sem
`\includegraphics`. Falhava ao abrir o documento, sempre, e o erro parecia ser do
LaTeX. Agora a árvore é passada explicitamente para quem compila.

Junto disso, o comportamento que você pediu:

- **O PDF nunca mais desaparece quando há erro.** Antes, um erro apagava a
  pré-visualização e você perdia justamente a referência para consertar. Agora a
  última compilação boa continua na tela, marcada como desatualizada.
- **Um indicador no canto** mostra quantos problemas existem. Ele abre só quando
  clicado, para um log grande não empurrar o documento para fora da tela.
- **Cada erro com `arquivo:linha` é clicável** e abre aquele arquivo na árvore.
  Num projeto de 42 arquivos o erro costuma estar num arquivo que nem está
  aberto.
- O documento compila **e** mostra os erros ao mesmo tempo, como no Overleaf —
  um `.tex` com problemas recuperáveis renderiza.

## 2 e 3. Identidade visual e configurações

**Configurações** agora têm seções: Aparência e idioma, Senha e acesso,
Identidade visual, Tipos de documento, Sobre. A seção fica na URL, então dá para
linkar direto e recarregar sem se perder. Adicionar uma seção nova é uma linha na
lista.

**Identidade visual** (admin): nome da organização, logo e cor principal.

- O nome substitui "Lab DMS" no topo e no título da aba.
- A logo aceita PNG, JPEG, GIF ou WebP. **SVG é recusado**: é XML que pode
  carregar script, e a logo aparece antes do login.
- A cor deriva os tons automaticamente, **e deriva separado para claro e
  escuro** — escrever direto sobrescreveria o tema escuro e ele perderia o tom
  claro dele.
- O texto sobre a cor é escolhido por luminância: preto ou branco conforme o
  fundo. Sem isso, uma marca amarela geraria botões ilegíveis.
- **O resto do tema não muda**: fontes, espaçamentos e as paletas clara e escura
  continuam iguais para todos. Só o destaque se move, então ninguém consegue
  deixar a interface ilegível a partir de uma tela de configuração.

## 4. Aba "Sobre"

Explica o que o sistema é, lista as **oito cláusulas** atendidas (ISO 9000/9001
§7.5 e ISO/IEC 17025 §8.3.1, §8.3.2 a/b/c/d/f, §8.4) com o que cada uma exige e
como é atendida aqui, e traz as notas da versão 1.0.

Inclui um limite honesto: **software não certifica laboratório.** As funções
sustentam a evidência que um avaliador pede; a conformidade depende também dos
procedimentos e da prática do dia a dia.

**As citações soltas saíram da interface**, como você pediu:

| Onde | Antes | Agora |
|---|---|---|
| Topo | `ISO 9000 · Quality Docs` | (removido — dá lugar ao nome da empresa) |
| Criar documento | `ISO 17025 8.3.2 b — documentos devem ser revisados periodicamente.` | `De quanto em quanto tempo este documento deve ser reavaliado.` |
| Criar documento | `ISO 17025 8.4 — a norma exige que o laboratório defina o prazo.` | `Por quanto tempo guardar as versões antigas depois que saírem de uso.` |
| Motivo da alteração | `ISO 17025 8.3.2 c — exigido em auditoria…` | `Por que esta alteração foi feita. Fica no registro…` |
| Documentos externos | `…(ISO 17025 8.3.1).` | (sem a citação) |

## 5. Privacidade do documento

Três níveis, escolhidos **na criação e depois**, pelo ícone na lista de
documentos:

| Nível | Quem vê |
|---|---|
| **Público** | Qualquer pessoa com o link, **sem entrar no sistema** |
| **Interno** | Qualquer pessoa que tenha acesso ao sistema |
| **Restrito** | Só os grupos e as pessoas escolhidas |

O painel de restrito deixa somar **grupos e pessoas juntos**, com busca. O autor
e os administradores mantêm acesso sempre, senão um documento poderia ficar
trancado sem ninguém para reabrir.

**Sobre o acesso sem login**, que é a parte delicada: é deliberadamente estreito.
Só documentos marcados como públicos, **só a versão em vigor** (nunca rascunho,
nunca versão antiga), só o PDF — sem código-fonte, sem árvore de arquivos, sem
histórico. Não existe endpoint que liste documentos públicos, então ninguém
consegue varrer o sistema; quem lê chegou por um link que recebeu. E responde
404 em vez de 403 quando o documento não é público, porque dizer "existe, mas
você não pode ver" já é uma revelação.

## 6. Grupos com pessoas

Na tela de Usuários, cada setor tem agora um botão de **gerenciar pessoas**: duas
listas lado a lado, quem está dentro e quem pode entrar, com busca. E esses
grupos são exatamente os que aparecem no painel de restrito de um documento.

**Uma coisa importante que encontrei aqui:** `hasGroupAccess` retornava `true`
incondicionalmente. A coluna de grupo existia, as verificações estavam
espalhadas pelo código, e **nenhuma delas restringia nada** — o controle por
setor era decorativo. Agora é uma consulta real de membros.

A migração coloca **todos os usuários em todos os grupos existentes**, de
propósito. Parece generoso demais, mas esse já é o estado efetivo do sistema
hoje; transformar uma verificação falsa em verdadeira não pode ser o momento em
que as pessoas descobrem que perderam acesso a documentos com que trabalhavam
ontem. O admin poda as listas a partir da tela nova, e cada remoção passa a ser
um ato deliberado e visível.

---

## Migrações novas

| | |
|---|---|
| **V10** | Tipos de documento viram tabela, semeada com IT/PQ/PO/MQ e adotando qualquer sigla já em uso |
| **V11** | Membros de grupo, visibilidade do documento e lista de acesso por documento |
| **V12** | Identidade visual (nome, logo, cor) |

Documentos existentes ficam **Internos**, que é exatamente a visibilidade que já
têm hoje. Marcá-los como restritos trancaria gente para fora; marcá-los como
públicos colocaria procedimentos internos na internet.

## Verificado

- TypeScript e build do frontend limpos; **502 chaves de tradução, todas com par
  pt/en**, nenhuma usada sem tradução.
- Matriz de visibilidade validada por execução: anônimo vê só o público, restrito
  só abre para grupo ou pessoa nomeada, autor e admin nunca se trancam fora.
- Regex das siglas: 17 casos, todos corretos. Semeadura idempotente.
- Equilíbrio estrutural e aridade de todos os arquivos Java tocados; o DTO da
  listagem tem 19 campos e 19 argumentos.

---

# Rodada seguinte — o que o teste no Docker encontrou

O `mvn package` do seu Docker passou a ser o portão real: **75 testes, zero
falhas**, e o `RODAR-TESTE.bat` deixou de só olhar o arranque e passou a *usar* o
sistema — entra, cria documento, compila o LaTeX e busca o PDF.

## A limpeza da base voltava atrás sozinha

A V13 esvaziava as tabelas e, no mesmo boot, os oito documentos de exemplo
reapareciam: o semeador roda **depois** do Flyway e os recriava a cada arranque.
Agora os exemplos só são criados com `SEED_DEMO_DOCUMENTS=true`; pessoas, papéis
e setores continuam sempre. A V14 repete a limpeza porque o Flyway não roda a
V13 outra vez. Confirmado: `documentos=0  versões=0  auditoria=0  usuários=5`.

## Documento recém-criado não pré-visualizava

`preview/project` respondia **"LaTeX source cannot be empty"**. Um documento de
arquivo único guarda o texto no *snapshot* da versão e não tem árvore de
projeto — que é como todo documento nasce. Sem árvore, a pré-visualização caía
no buffer não-salvo do editor e, sem buffer, compilava string vazia em vez do
texto com que o documento foi criado. Agora recorre ao texto salvo, igual ao que
compilar a versão já fazia.

Atinge principalmente projetos com imagens e nenhum `.tex`, com um arquivo
binário aberto — no arquivo único a tela usa outro caminho (`/latex/preview`).
Quatro testes fixam a regra, inclusive o de que um documento realmente vazio
continua sendo reportado como vazio.

## Verificado por execução, não por leitura

| | |
|---|---|
| Login dos 5 usuários com `123456` | 200 em todos |
| Criar documento | 201, `PO-001` |
| `POST /latex/preview/project/{commit}` | **PDF válido, 26.424 bytes**, `X-Pdf-Pages: 1` |
| `POST /latex/compile/{commit}` | `SUCCESS`, `hasPdf: true` |
| `GET /latex/pdf/{commit}` | **PDF servido, 26.424 bytes** |
| `GET /documents` (a chamada que dava 500) | 200 |
| Migrações | V14 no topo, todas com sucesso |

## O nginx servia o worker do pdf.js com o tipo errado

Os testes acima batem na porta 8080. **O navegador não** — ele passa pelo nginx
na 3000. Testando por lá, o PDF chegava intacto (byte a byte igual ao da 8080),
mas o `pdf.worker.min.mjs` saía como **`application/octet-stream`**.

O `mime.types` que vem no nginx não tem entrada para `.mjs`, então o arquivo caía
no `default_type`. E o Chrome **recusa arrancar um worker de módulo cujo script
não venha com MIME de JavaScript**. O visor nunca inicializava: os bytes do PDF
chegavam perfeitos e o painel ficava vazio, com "Failed to load PDF file" — sem
nenhuma pista de que a causa era o tipo de um outro arquivo.

Corrigido com uma `location ~ \.mjs$` e `default_type text/javascript`. De
propósito **não** com um bloco `types`: dentro de `server` ele *substitui* o mapa
herdado, e CSS, fontes e imagens ficariam sem tipo. Agora:

```
worker -> 200 text/javascript
MIME DE JAVASCRIPT -- o worker do pdf.js arranca
```

O `TESTE-NAVEGADOR.bat` passou a falhar alto se isso regredir.

## Ainda por confirmar

**A tela em si.** A extensão do Chrome não estava conectada, então não vi o PDF
renderizando. O que está provado é que o servidor entrega o PDF correto, que o
nginx não o altera, e que o worker agora carrega com o tipo que o Chrome exige.

Abra `http://localhost:3000` e faça **CTRL+SHIFT+R** — a página que está aberta
aí é anterior à limpeza, e o nginx serve o `index.html` do cache num refresh
normal.

## Os scripts

| | |
|---|---|
| `RODAR-TESTE.bat` | Constrói tudo, roda os testes e exercita a API. ~5 min |
| `TESTE-RAPIDO.bat` | Só a API, com os contêineres de pé. ~40 s |
| `TESTE-NAVEGADOR.bat` | O caminho do navegador, pelo nginx |
| `RECONSTRUIR-FRONTEND.bat` | Reconstrói só o frontend e testa o navegador |
