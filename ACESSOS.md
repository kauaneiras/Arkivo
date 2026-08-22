# 🔑 Usuários de teste — Lab DMS

Todos os usuários abaixo são criados automaticamente na primeira execução.

> **Senha de todos:** `123456`

| E-mail | Senha | Papel | O que consegue fazer |
|--------|-------|-------|----------------------|
| `admin@email.com` | `123456` | **Administrador** | Tudo: gerenciar usuários e papéis, criar/editar/aprovar documentos, obsoletar, ver auditoria |
| `writer@email.com` | `123456` | **Editor** | Criar e editar documentos, salvar versões, compilar, submeter para aprovação |
| `reviewer@email.com` | `123456` | **Revisor** | Ver tudo, editar, **aprovar ou rejeitar** revisões, ver trilha de auditoria |
| `reader@email.com` | `123456` | **Leitor** | Somente leitura — não cria nem edita documentos |

Acesso: **http://localhost:3000**

---

## Como testar o fluxo completo

### 1. Criar e compilar um documento (`writer@email.com`)

1. Entre com `writer@email.com` / `123456`
2. **Documentos → Novo Documento**
   - Código: **preenchido automaticamente** conforme a categoria e a ordem
     (escolheu IT → `IT-004`; escolheu PQ → `PQ-004`). Você pode editar se quiser.
   - Título: qualquer um
   - Grupo/Setor: **opcional** — pode deixar em "— Sem grupo —"
3. No editor, clique **Compilar** (ou `Ctrl+Enter`) — o PDF aparece **sem precisar salvar**
4. **Salvar Versão** (ou `Ctrl+S`) e escreva a mensagem da versão
5. Edite algo, salve de novo → agora existem `v1` e `v2` no **Histórico**

### 2. Versionamento (estilo GitHub)

No editor, botão **Histórico**:

- Cada versão aparece como `v1`, `v2`, `v3` com autor, data e tamanho
- **Diff** — compara com a versão anterior (verde = adicionado, vermelho = removido)
- **Restaurar** — cria uma **nova versão** com o conteúdo antigo; nada é apagado (rastreabilidade ISO)

O documento de exemplo `IT-SOLDAGEM-001` já vem com **3 versões de dois autores diferentes** — bom para ver o diff funcionando de imediato.

### 3. Aprovação (`writer` → `reviewer`)

1. Como `writer@email.com`, no editor: **Submeter para Aprovação** → documento fica **Em Revisão** (edição bloqueada)
2. Saia e entre como `reviewer@email.com`
3. **Revisões** → aba *Pendentes* → **Aprovar** ou **Rejeitar** (rejeição exige justificativa)
   - Aprovado → documento fica **Ativo**
   - Rejeitado → volta para **Rascunho**

Já existe uma revisão pendente semeada (`IT-CALIBRACAO-002`) esperando na fila.

### 4. Administração (`admin@email.com`)

- **Usuários** → aprovar solicitações de cadastro, trocar papéis, **Adicionar Usuário** direto
- **Usuários → Grupos / Setores** → criar, editar ou remover grupos (remover só é permitido se nenhum documento usar o grupo)
- **Auditoria** → registro imutável de todas as ações (quem, o quê, quando)
- **Documentos** → ícone de arquivar para marcar um documento como **obsoleto**

### 4.1 Anexos (qualquer papel com acesso ao documento)

1. No editor de um documento, clique **Anexos** (ao lado de Histórico)
2. **Enviar arquivo** → PDF, imagem, planilha, Word, CSV ou texto, até 20MB
3. Baixe ou remova anexos existentes na mesma lista

Os documentos `IT-SOLDAGEM-001` e `PQ-001` já vêm com um anexo de exemplo (certificado
de calibração) para você ver o fluxo funcionando de imediato.

### 4.2 Notificações

O sino no topo da tela mostra notificações não lidas:

- Revisor é avisado quando um documento é submetido para aprovação
- Autor é avisado quando seu documento é aprovado ou rejeitado
- Criador do documento é avisado quando ele é marcado como obsoleto
- Novo usuário é avisado quando seu cadastro é aprovado

Clique numa notificação para ir direto ao documento/revisão relacionado. **Marcar
todas como lidas** limpa o contador. Já existem notificações de exemplo para
`writer@email.com` e `reviewer@email.com`.

### 5. Cadastro de novo usuário

1. Na tela de login: **Criar conta** → preencha e envie
2. A conta fica **pendente**
3. Como `admin@email.com`: **Usuários → Solicitações Pendentes** → escolha o papel → **Aprovar**
4. Agora o novo usuário consegue entrar

### 6. Trocar a própria senha

Qualquer usuário: **Configurações → Alterar minha senha** (exige a senha atual).

---

## Documentos de exemplo já cadastrados

| Código | Status | Versões | Bom para testar |
|--------|--------|---------|-----------------|
| `IT-SOLDAGEM-001` | Ativo | 3 (dois autores) | **Diff** entre versões, histórico, **anexo** de certificado |
| `IT-ENSAIO-003` | Rascunho | 2 | **Editar** — fórmulas e tabela de aceitação |
| `PQ-001` | Ativo | 2 | Tabela longa, critérios, **anexo** CSV de calibração |
| `PQ-003` | Rascunho | 1 | Edição rápida (documento curto) |
| `IT-CALIBRACAO-002` | Em Revisão | 2 | **Revisão pendente** na fila do revisor |
| `PQ-002` | Rascunho | 1 | Rascunho não submetido |
| `MQ-001` | Ativo | 1 | Manual com sumário |
| `PO-001` | Ativo | 1 | Documento **sem grupo** |

---

## Observações

- Os documentos de exemplo que ainda **não existirem** são criados a cada
  inicialização — basta reconstruir, sem apagar o banco:

  ```bash
  docker compose up -d --build
  ```

  Se quiser começar do zero (apaga todos os dados):

  ```bash
  docker compose down -v
  docker compose up -d --build
  ```

- A senha `123456` é apenas para desenvolvimento/demonstração. Em produção, troque
  as senhas e os segredos do arquivo `.env` (`DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`).
- A sessão dura 8 horas (`JWT_EXPIRATION_MS` no `.env`). Expirada, o sistema leva
  você de volta para a tela de login automaticamente.
- Login e cadastro têm limite de tentativas (10/min e 5/min por IP) contra força
  bruta. Se aparecer "Too many attempts", espere um minuto.
- Backup: `powershell -ExecutionPolicy Bypass -File scripts\backup.ps1` — salva o
  banco (`pg_dump`) e os anexos em `backups\`.
