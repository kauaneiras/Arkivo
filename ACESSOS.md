# Quem pode o quê

Auditoria completa da autorização, endpoint a endpoint. Escrita depois de três
defeitos do mesmo tipo — a mesma recusa a dizer coisas diferentes, e controlos
que existiam sem proteger nada.

---

## Os papéis e o que carregam

| Papel | Permissões |
|---|---|
| **ROLE_ADMIN** | todas |
| **ROLE_EDITOR** | `DOCUMENT_CREATE`, `DOCUMENT_EDIT` |
| **ROLE_REVIEWER** | `DOCUMENT_VIEW_ALL`, `DOCUMENT_EDIT`, `REVIEW_APPROVE` |
| **ROLE_VIEWER** | `DOCUMENT_VIEW_ALL` |

O Revisor **não** tem `DOCUMENT_CREATE`, de propósito: quem aprova não redige.
É a separação que a ISO/IEC 17025 §8.3 pede e a razão de o papel existir.

Uma pessoa acumula papéis (`user_roles` é tabela de ligação). Escrever **e**
revisar é permitido — o que nunca é permitido é revisar o **próprio** documento,
e essa regra é comparada por autor, não por papel.

---

## As três perguntas

Toda a autorização se resume a três, e cada uma tem **um** dono no código.

### 1. Pode ler este documento? — `DocumentVisibilityService.canRead`

| Visibilidade | Quem lê |
|---|---|
| `PUBLIC` | qualquer pessoa, **sem sessão**. Só a versão em vigor, só como PDF |
| `PRIVATE` | qualquer pessoa autenticada |
| `RESTRICTED` | só quem está em `document_access` (por setor ou por pessoa) |

O autor e quem tem `DOCUMENT_VIEW_ALL` leem sempre — senão um documento
restrito podia ficar inacessível ao próprio dono, sem volta.

### 2. Pode escrever neste documento? — `AccessControlService.assertCanEdit`

Precisa de `DOCUMENT_EDIT` (ou `DOCUMENT_EDIT_ALL`) **e** de uma destas:

- ter `DOCUMENT_EDIT_ALL` (administrador);
- **ser o autor** do documento;
- pertencer ao setor do documento.

### 3. Pode aprovar? — `AccessControlService.assertCanApprove`

Precisa de `REVIEW_APPROVE`, **não ser o autor**, e uma destas:

- ter `DOCUMENT_EDIT_ALL` (administrador);
- **ser o revisor designado** do documento;
- pertencer ao setor do documento.

Havendo revisor designado, só ele ou um administrador decidem — para o trabalho
não ficar preso se a pessoa sair.

---

## O que estava errado

### A mesma frase para causas diferentes

`Access refused (approve)` saía de dois sítios: falta de `REVIEW_APPROVE` **e**
não pertencer ao setor. Quem tinha a permissão e estava fora do setor ouvia
"você não tem permissão para aprovar revisões" — falso, e a mandar pedir um
papel que já tinha. O mesmo em `assertCanEdit`.

Agora são motivos distintos (`approve_not_in_sector`, `edit_not_in_sector`), com
mensagens que dizem o que fazer, e cada recusa escreve no log qual verificação
falhou, com utilizador, documento e setor.

### O autor trancado fora do próprio rascunho

O setor passou a ser obrigatório em documentos restritos, e um setor criado
depois da instalação **nasce sem membros** — a migração V11 só pôs toda a gente
nos grupos que existiam naquele momento. Juntando as duas coisas, quem criava um
documento não conseguia gravar uma versão dele.

O autor passa sempre. Não abre nada: ele já criou o documento e já vê o conteúdo.

### O revisor designado trancado fora

Mesma armadilha, do outro lado: criar setor → pôr documento nele → designar
revisor produzia um documento que **ninguém** conseguia aprovar. Ser nomeado
para o trabalho passou a valer mais do que pertencer ao setor.

### O impasse ao editar um documento aprovado

Uma revisão aprovada não pode ser reescrita — é registo da qualidade, e alterar
os seus ficheiros sem nova versão, sem revisão e sem motivo é exatamente o que a
ISO/IEC 17025 §8.3.2 existe para impedir. A trava estava certa; o que estava
errado era **recusar**:

```
409  PUT /documents/{id}/files/content
     Esta é a versão em vigor e não pode ser alterada diretamente.
     Salve uma nova versão e edite os arquivos dela.
```

Gravar uma versão começa por escrever o ficheiro aberto na árvore — e era essa
escrita que estava a ser recusada. Para gravar era preciso escrever; para
escrever era preciso ter gravado. **O conselho da mensagem era impossível de
seguir**, e o editor ficava trancado.

Agora a primeira alteração num documento aprovado **abre uma revisão de
trabalho**: copia a árvore da versão em vigor, deixa a aprovada intacta, e a
escrita entra na nova. É o que a mensagem pedia, feito automaticamente — e é o
que "editar um documento aprovado" significa num sistema versionado: não se
altera o que foi aprovado, começa-se o próximo.

Acontece **uma vez por ciclo**. A partir daí a versão atual já não é a vigente e
as alterações seguintes vão todas para o mesmo rascunho. Fica registado em
auditoria como `WORKING_REVISION_OPENED`, com a versão de origem — numa
auditoria, uma versão sem origem explicada é uma pergunta.

A resposta traz `X-Working-Commit` para o editor passar a apontar para a revisão
nova; sem isso ele continuaria a pré-visualizar a árvore antiga. O cliente
continua sem poder **escolher** em que versão escreve: só é informado da que o
servidor escolheu.

### Criar documento não tinha verificação nenhuma

`POST /api/v1/documents` não tinha `@PreAuthorize`. Qualquer conta autenticada,
incluindo um Leitor, criava documento chamando a API — o botão no ecrã era a
única barreira, e um botão não é controlo de acesso. Agora exige
`DOCUMENT_CREATE` no servidor.

### Leitura de documento e de versões sem verificar visibilidade

Dois caminhos passavam ao lado da regra de leitura:

- `GET /api/v1/documents/{id}` recebia o utilizador e não o usava.
- `GET .../versioning/commits`, `/branches` e `/commits/{id}/content` **nem
  sequer recebiam o utilizador** — bastava o identificador para obter o
  histórico e o LaTeX inteiro de qualquer documento restrito.

Esconder na listagem e servir no acesso direto não é uma restrição, é uma
sugestão. Os quatro passaram a usar `canRead`, a mesma regra do resto do
sistema: duas regras paralelas divergem, e a que diverge é a que ninguém vê.

---

## Endpoints com exigência de papel

| | Rota | Exige |
|---|---|---|
| POST | `/api/v1/documents` | `DOCUMENT_CREATE` |
| GET/POST/PUT/DELETE | `/api/v1/users*` (gestão) | `ADMIN` |
| PUT | `/api/v1/users/{id}/status` | `ADMIN` |
| DELETE | `/api/v1/users/{id}` | `ADMIN` (e só contas sem histórico) |
| POST/PUT/DELETE | `/api/v1/documents/groups*` | `ADMIN` |
| POST | `/api/v1/documents/groups/{id}/members` | `ADMIN` |
| PUT | `/api/v1/branding` | `ADMIN` |
| GET | `/api/v1/audit/actions` | `ADMIN` ou `REVIEWER` |
| POST/PUT | `/api/v1/external-documents` | `ADMIN`, `REVIEWER` ou `EDITOR` |
| DELETE | `/api/v1/external-documents/{id}` | `ADMIN` |

Sem sessão, de propósito: `POST /auth/login`, `POST /auth/register`,
`GET /branding` (a logo aparece antes do login), e `/api/v1/public/*`, que serve
**apenas** documentos `PUBLIC`, **apenas** a versão em vigor e **apenas** como
PDF — nunca a árvore de ficheiros, nunca um rascunho.

---

## Remoção de contas

Desativar é o caminho normal: a conta perde o acesso e o nome continua em cada
documento que escreveu e em cada linha de auditoria que gerou — a ISO/IEC 17025
exige saber quem redigiu e quem aprovou, e isso não pode depender de a pessoa
ainda trabalhar cá.

Apagar só é aceite para contas **sem histórico nenhum**; nas outras, a resposta
diz quantos registos existem e que o caminho é desativar. Ninguém se desativa ou
se apaga a si próprio, e o último administrador ativo não pode ser removido —
senão o sistema fica sem quem o administre.

---

## Verificar numa instalação

```
DIAGNOSTICO-REVISAO.bat <id-do-pedido> <email>
```

Percorre as cinco verificações da aprovação na ordem em que o código as faz e
diz qual reprovou, além de listar quem está no setor.
