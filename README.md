# Lab DMS

**Controle de documentos da qualidade para laboratórios — com histórico de versões de verdade.**

Um laboratório acreditado precisa provar, a qualquer momento, qual versão de um
procedimento está em vigor, quem a aprovou, o que mudou desde a anterior e por
quê. Na prática isso costuma virar uma pasta de rede com `PQ-001_v3_final_REV2.docx`
— e é exatamente aí que uma auditoria encontra problema.

O Lab DMS trata documento da qualidade como código: cada versão é um *commit*
completo, nada é apagado, e a versão em vigor é uma escolha explícita — não "o
arquivo mais recente".

[![CI](https://github.com/kauaneiras/lab-dms/actions/workflows/ci.yml/badge.svg)](../../actions)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)
![React 19](https://img.shields.io/badge/React-19-61DAFB)
![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791)

---

## Sumário

- [Começar em 3 minutos](#começar-em-3-minutos)
- [O que ele faz](#o-que-ele-faz)
- [Normas atendidas](#normas-atendidas)
- [Arquitetura](#arquitetura)
- [Editor LaTeX](#editor-latex)
- [Privacidade e grupos](#privacidade-e-grupos)
- [Identidade visual](#identidade-visual)
- [Desenvolvimento](#desenvolvimento)
- [Configuração](#configuração)
- [Backup e restauração](#backup-e-restauração)
- [Segurança](#segurança)

Guias por assunto: [VERSIONAMENTO.md](VERSIONAMENTO.md) (rascunho, versões,
comparação), [ATUALIZAR-DO-OVERLEAF.md](ATUALIZAR-DO-OVERLEAF.md),
[KEYCLOAK.md](KEYCLOAK.md), [ACESSOS.md](ACESSOS.md),
[BANCO-DE-DADOS.md](BANCO-DE-DADOS.md), [MELHORIAS-FUTURAS.md](MELHORIAS-FUTURAS.md).

---

## Começar em 3 minutos

**Pré-requisitos:** Docker e Docker Compose. Mais nada — Java, Node, PostgreSQL
e a distribuição LaTeX vivem todos dentro das imagens.

```bash
git clone https://github.com/kauaneiras/lab-dms.git
cd lab-dms

cp .env.example .env      # no Windows: copy .env.example .env
# edite o .env e troque as três senhas

docker compose up -d --build
```

A primeira construção demora — a imagem do backend baixa uma distribuição TeX
Live completa (cerca de 2 GB), porque compilar LaTeX é função do sistema, não
uma dependência que o usuário deva instalar. As construções seguintes usam cache.

Depois: **http://localhost:3000**

### Entrar

O sistema cria quatro usuários na primeira execução, todos com a senha `123456`:

| E-mail | Papel | O que consegue fazer |
|---|---|---|
| `admin@email.com` | Administrador | Tudo: usuários, grupos, tipos de documento, identidade visual, aprovação |
| `writer@email.com` | Editor | Criar e editar documentos, salvar versões, submeter para aprovação |
| `reviewer@email.com` | Revisor | Aprovar ou rejeitar revisões, ver a trilha de auditoria |
| `reader@email.com` | Leitor | Somente leitura |

> **Estes usuários são para avaliar o sistema.** Antes de usar com documentos
> reais, troque as senhas e remova os que não forem necessários.

Um roteiro de teste do fluxo completo está em [ACESSOS.md](ACESSOS.md).

---

## O que ele faz

### Versões que não se perdem

O modelo é o do Git, visto pela lente do GitHub Desktop. Editar escreve numa
**área de trabalho** (o rascunho, salvo automaticamente); **salvar versão**
fecha-a com autor, data, mensagem e **motivo da alteração**, e a partir daí
ela é imutável. A tela *Alterações em andamento* lista o que o rascunho muda
(arquivo a arquivo, `+n −m`) e tem a caixa de gravação; o *Histórico* mostra
v1…vN e compara qualquer versão com a anterior ou com a vigente — lista de
arquivos à esquerda, diff com números de linha à direita, unificado ou lado a
lado. O modelo está explicado em [VERSIONAMENTO.md](VERSIONAMENTO.md).

Restaurar uma versão antiga **não apaga nada**: cria uma nova versão com o
conteúdo da escolhida. O histórico continua contando a verdade sobre o que
aconteceu — que é o que uma auditoria pede.

A **versão em vigor** é uma escolha separada da "mais recente". Você pode
publicar a v3 enquanto a v4 ainda está em revisão, e voltar para a v2 sem perder
nem a v3 nem a v4.

### Aprovação com responsável nomeado

Um documento vai para revisão **numa versão exata** (o pedido guarda qual), um
revisor designado vê o diff contra a vigente dentro do diálogo de decisão, e
aprovar torna essa versão a vigente — sem passo manual depois. **Ninguém aprova o próprio documento** — a regra é do
sistema, não do combinado da equipe, e vale mesmo para quem tem os dois papéis.

### Marca d'água em quem não está em vigor

Uma versão obsoleta ou fora de vigor sai com marca d'água no PDF, **inclusive ao
imprimir**. Um crachá que só existe na tela não resolve nada quando a folha está
na bancada — e é isso que o avaliador vai olhar quando andar pelo laboratório.

### Revisão periódica e retenção

Cada documento tem um intervalo de revisão e um prazo de retenção. O sistema
avisa quando a data se aproxima e registra a revisão mesmo quando a conclusão é
"continua válido" — porque a evidência pedida é que a revisão *aconteceu*.

### Outras funções

- **Ciência de leitura** — documentos podem exigir confirmação de que foram
  lidos, com registro de quem e quando
- **Documentos externos** — normas, manuais de instrumento e tabelas de
  calibração de terceiros, com verificação periódica e responsável
- **Trilha de auditoria** imutável de toda ação relevante
- **Lista mestra** exportável em CSV para entregar a um avaliador
- **Anexos** por documento (planilhas, evidências, fotos)
- **Notificações** internas de revisões pendentes e prazos
- **Login único (SSO)** opcional com Keycloak, com criação automática de
  contas e papéis sincronizados — [KEYCLOAK.md](KEYCLOAK.md)
- **Consumo de armazenamento** em Configurações: tamanho do banco por tabela,
  bytes por documento, economia da desduplicação e coletor de blobs órfãos
- **Seis idiomas**, tema claro, escuro ou seguindo o sistema

---

## Normas atendidas

O sistema é construído em torno dos requisitos de **controle de documentos** da
ISO 9000/9001 e da ISO/IEC 17025. A aba **Sobre**, dentro do sistema, explica
cada cláusula e como ela é atendida.

| Cláusula | Requisito | Como é atendido |
|---|---|---|
| ISO 9001 §7.5 | Informação documentada | Identificação por código e título, aprovação antes do uso, versão correta sempre disponível |
| ISO/IEC 17025 §8.3.1 | Documentos de origem externa | Registro próprio, com verificação periódica e responsável |
| §8.3.2 a) | Aprovação antes do uso | Revisão por quem tem permissão de revisor; autorrevisão bloqueada |
| §8.3.2 b) | Revisão periódica | Intervalo por documento, aviso de vencimento, registro da revisão |
| §8.3.2 c) | Identificação das alterações | Motivo obrigatório por versão, separado da mensagem, mais comparação com a anterior |
| §8.3.2 d) | Disponibilidade no ponto de uso | Ciência de leitura registrada por pessoa e data |
| §8.3.2 f) | Prevenção do uso de obsoletos | Marca d'água no PDF, inclusive impresso |
| §8.4 | Controle de registros | Retenção definida por documento; histórico completo preservado |

> **Software não certifica laboratório.** Estas funções sustentam a evidência que
> um avaliador pede, mas a conformidade depende também dos seus procedimentos,
> dos seus registros e da prática do dia a dia.

---

## Arquitetura

```
┌──────────────────┐        ┌────────────────────────┐        ┌──────────────┐
│  React 19 + Vite │───────▶│    Spring Boot 3.3     │───────▶│ PostgreSQL 16│
│   TypeScript     │  REST  │   Arquitetura Hexagonal│  JDBC  │   (Flyway)   │
│   :3000 (nginx)  │        │        :8080           │  /JPA  └──────────────┘
└──────────────────┘        │                        │        ┌──────────────┐
                            │  ┌──────────────────┐  │───────▶│   Redis 7    │
                            │  │  TeX Live +      │  │        │ (rate limit) │
                            │  │  Ghostscript     │  │        └──────────────┘
                            │  └──────────────────┘  │
                            └────────────────────────┘
```

O nginx do container do frontend faz proxy de `/api/v1` para o backend, então
tudo é servido da mesma origem — sem CORS em produção.

### Camadas do backend

```
com.labdms
├── domain/           Regras e modelos. Não conhece Spring, JDBC nem HTTP.
│   ├── model/        Documento, Versão, Revisão, Usuário
│   ├── port/in/      Casos de uso (o que o sistema faz)
│   └── port/out/     Contratos de persistência e serviços externos
├── application/      Orquestra os casos de uso
│   └── service/      Documentos, versões, revisões, visibilidade, tipos, LaTeX
├── infrastructure/   O mundo de fora
│   ├── adapter/in/   Controladores REST
│   ├── adapter/out/  Persistência, armazenamento de blobs, motor LaTeX
│   ├── config/       Segurança, agendamento, semeadura
│   └── security/     JWT e autorização
└── shared/           Sanitização, validação, marca d'água
```

A direção da dependência aponta sempre para dentro: o domínio não sabe que
existe um banco de dados. Trocar PostgreSQL por outra coisa é escrever um
adaptador novo, não reescrever as regras.

**Números:** 24 controladores REST, 12 serviços de aplicação, 16 migrações
Flyway, 11 telas.

---

## Editor LaTeX

O editor segue o Overleaf como referência, porque é a ferramenta que quem
escreve documento técnico já conhece.

**Projetos com vários arquivos.** Um documento real tem capítulos, figuras,
bibliografia e classe própria. O editor tem árvore de arquivos com pastas,
envio por arrastar, renomear, apagar e escolha do arquivo principal.

**Importa ZIP do Overleaf** — o formato que o Overleaf exporta — e exporta de
volta. Também aceita um `.tex` solto ou um **PDF pronto**: um manual de
fornecedor ou certificado digitalizado vira documento controlado, com versões,
revisão e marca d'água, sem precisar de um caminho separado no sistema.

**Erros como no Overleaf.** O PDF continua na tela mesmo quando a compilação
falha — perder a referência bem na hora de consertar é o pior momento possível.
Um indicador no canto mostra quantos problemas existem, e cada erro com
`arquivo:linha` abre o arquivo certo na árvore.

### Como os arquivos são guardados

Armazenamento endereçado por conteúdo (SHA-256), híbrido:

- **Texto** (`.tex`, `.bib`, `.cls`) fica no PostgreSQL, onde o TOAST comprime
  sozinho e a comparação entre versões continua funcionando
- **Binário** (imagens, PDF) fica em volume, com gzip **só quando compensa** —
  um JPEG já comprimido só ficaria maior
- **Deduplicação por hash**: arquivos iguais, em qualquer versão, ocupam espaço
  uma vez
- **Uma nova versão copia as referências, não os bytes**
- **Blobs sem referência** (de arquivos apagados, ZIPs substituídos, rascunhos
  descartados) são recolhidos à noite, com carência de 24 h; a tela de
  armazenamento mostra quantos há e permite limpar na hora

Medido com uma tese real de 42 arquivos: **6 versões ocupam 1,47 MB** em vez dos
11,73 MB que uma cópia integral custaria — 87% de economia.

Figuras EPS são convertidas para PDF no servidor com Ghostscript, e as
referências `\includegraphics` são reapontadas junto. Sem isso o arquivo mudaria
de nome e o documento compilaria **sem as figuras**, silenciosamente, porque
figura faltando não é erro fatal no LaTeX.

**Limites:** 200 MB por projeto, 50 MB por arquivo, 2000 arquivos.

---

## Privacidade e grupos

Cada documento escolhe quem pode lê-lo:

| Nível | Quem vê |
|---|---|
| **Público** | Qualquer pessoa com o link, **sem entrar no sistema** |
| **Interno** | Qualquer pessoa com acesso ao sistema |
| **Restrito** | Só os grupos e as pessoas escolhidas |

O acesso sem login é deliberadamente estreito: **só a versão em vigor**, só o
PDF, sem código-fonte, sem histórico e sem listagem que possa ser varrida. Quem
lê chegou por um link que recebeu.

O administrador cria grupos (setores) na tela de Usuários e define quem está em
cada um. Esses mesmos grupos aparecem ao restringir um documento, e um documento
restrito pode combinar grupos **e** pessoas específicas.

O autor e os administradores mantêm acesso sempre — senão um documento poderia
ficar trancado sem ninguém para reabrir.

---

## Identidade visual

Em **Configurações → Identidade visual**, o administrador define o nome da
organização, a logo e a cor principal. O nome substitui "Lab DMS" no topo e no
título da aba.

A cor deriva automaticamente os tons, **separadamente para o tema claro e o
escuro**, e o texto sobre a cor é escolhido por luminância — uma marca amarela
recebe texto escuro, uma azul recebe texto claro. Sem isso, metade das empresas
teria botões ilegíveis.

O resto do tema — fontes, espaçamentos e as paletas clara e escura — é igual
para todos. Só o destaque se move, então não é possível deixar a interface
ilegível a partir de uma tela de configuração.

> A logo aceita PNG, JPEG, GIF ou WebP. **SVG é recusado**: é XML que pode
> carregar script, e a logo aparece antes do login.

---

## Desenvolvimento

Rodar fora do Docker, com recarga automática:

```bash
# Infraestrutura apenas
docker compose up -d postgres redis

# Backend — exige JDK 21 e Maven instalados
cd lab-dms-backend
mvn spring-boot:run             # http://localhost:8080

# Frontend
cd lab-dms-frontend
npm install
npm run dev                     # http://localhost:5173
```

Para o modo de desenvolvimento, aponte o frontend direto para a API:

```
VITE_API_URL=http://localhost:8080/api/v1
FRONTEND_URL=http://localhost:5173
```

> Rodando fora do Docker, o LaTeX usa o `pdflatex` da sua máquina. Sem TeX Live
> instalado, tudo funciona menos a compilação.

**Testes e documentação da API:**

```bash
cd lab-dms-backend && mvn test
```

Além dos testes unitários, `VersioningFlowIntegrationTest` sobe a aplicação
num **PostgreSQL embutido** (baixado uma vez pelo Maven), aplica as 16
migrações e percorre por HTTP o ciclo inteiro: criar → editar (rascunho) →
salvar versão → comparar → importar ZIP → submeter → aprovar → restaurar →
armazenamento. Não precisa de Docker, Redis nem LaTeX. Uma consulta SQL que só
quebra no Postgres quebra o build.

A construção da imagem Docker também roda os testes (`RUN mvn package` no
Dockerfile), então uma regressão quebra o build em vez de subir em silêncio.

Swagger UI em `http://localhost:8080/swagger-ui.html` com o sistema no ar.

### Migrações do banco

Ficam em `lab-dms-backend/src/main/resources/db/migration/` e são aplicadas pelo
Flyway na subida. Para mudar o esquema, **crie uma migração nova** — nunca edite
uma já aplicada, porque o Flyway valida o checksum e vai recusar subir.

---

## Configuração

Tudo vem do `.env` (veja o `.env.example`).

| Variável | Padrão | Para que serve |
|---|---|---|
| `DB_PASSWORD` | — | Senha do PostgreSQL. **Obrigatória.** |
| `REDIS_PASSWORD` | — | Senha do Redis. **Obrigatória.** |
| `JWT_SECRET` | — | Chave de assinatura dos tokens. **Obrigatória.** Gere com `openssl rand -base64 64` |
| `JWT_EXPIRATION_MS` | 28800000 | Duração da sessão (8 h) |
| `FRONTEND_PORT` | 3000 | Porta da interface |
| `BACKEND_PORT` | 8080 | Porta da API |
| `VITE_API_URL` | `/api/v1` | URL da API, embutida na **construção** do frontend |
| `LATEX_TIMEOUT_SECONDS` | 60 | Tempo máximo por compilação |
| `LATEX_MAX_MEMORY` | 512m | Memória por compilação |
| `MAX_FILE_SIZE` | 50MB | Limite de upload |
| `SSO_ENABLED`, `SSO_ISSUER_URI`, `SSO_CLIENT_ID` | — | Login único com Keycloak ([KEYCLOAK.md](KEYCLOAK.md)) |
| `STORAGE_GC_GRACE_HOURS` | 24 | Idade mínima de um blob órfão para ser recolhido |

> `VITE_API_URL` entra na imagem no momento da construção, não na execução —
> mudar exige `docker compose up -d --build`.

---

## Backup e restauração

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup.ps1
```

Gera `backups/lab-dms-backup-<data>/` com o dump do banco e os anexos.
Restaurar:

```powershell
Get-Content database.sql | docker exec -i lab-dms-postgres psql -U labdms -d labdms
docker run --rm -v lab-dms_attachments:/data -v ${PWD}:/backup alpine `
    sh -c "cd /data && tar xzf /backup/attachments.tar.gz"
```

> A pasta `backups/` está no `.gitignore`: contém documentos, anexos e dados de
> usuários reais.

---

## Segurança

- **Autenticação** JWT local, com limitação de tentativas de login via Redis;
  ou OpenID Connect (Keycloak), validando o token contra as chaves do provedor
- **Contas pendentes, desativadas ou bloqueadas não entram** — nem com senha
  certa, nem com um token emitido antes da desativação
- **Autorização** por papel e por documento, decidida num único ponto do código
- **Múltiplos papéis** por usuário, com autorrevisão bloqueada mesmo assim
- **Senhas** com BCrypt
- **Uploads** com lista de tipos permitidos, limite de tamanho e proteção contra
  *zip slip* e *zip bomb*
- **SVG recusado** como logo e servido como download, nunca embutido: é XML que
  pode executar script na origem da aplicação
- **Motor LaTeX** isolado: `-no-shell-escape`, `openin_any=p` (impede
  `\input{/etc/passwd}` ler arquivos do servidor) e `-norc` (impede que um
  `.latexmkrc` enviado pelo usuário execute Perl na máquina)
- **Trilha de auditoria** imutável

**Antes de produção:** troque todas as senhas do `.env`, gere um `JWT_SECRET`
novo, remova os usuários de exemplo e ponha o sistema atrás de HTTPS.

---

## Licença e contribuição

O que ainda não existe e o que valeria construir a seguir está em
**[MELHORIAS-FUTURAS.md](MELHORIAS-FUTURAS.md)** — é um bom ponto de partida para
quem quiser contribuir.

Issues e pull requests são bem-vindos. Ao propor mudança que toque controle de
documentos, explique **qual requisito da norma** ela atende — é o que separa uma
funcionalidade útil de uma que só parece útil.
