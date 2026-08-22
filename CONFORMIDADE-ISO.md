# Conformidade ISO — Lab DMS

Análise de lacunas do sistema contra os requisitos de documentação aplicáveis a um
**laboratório de eletrônica** (ensaios/calibração).

Normas de referência:

- **ISO/IEC 17025:2017** — competência de laboratórios de ensaio e calibração.
  É a norma que rege este caso; a seção 8 trata do sistema de gestão.
- **ISO 9001:2015** — gestão da qualidade (a 17025 adota estrutura equivalente).

> A 17025 distingue **documentos** (dizem como o trabalho *deve* ser feito —
> procedimentos, instruções, métodos, formulários) de **registros** (provam que o
> trabalho *foi* feito — planilhas preenchidas, certificados, assinaturas).
> Documentos = cláusula 8.3. Registros = 8.4 e 7.5. Este sistema é, por natureza,
> um controle de **documentos**; as lacunas de registros estão sinalizadas como
> módulos à parte.

---

## 1. Cláusula 8.3 — Controle de documentos

Os sete pontos que um auditor verifica, e onde estamos:

| # | Requisito | Situação | Como é atendido |
|---|-----------|----------|-----------------|
| **8.3.1** | Todos os documentos sob controle, **internos e externos** | ✅ Atende | Registro de documentos externos (`external_documents`): manuais de instrumento, normas (IPC-A-610, IEC 61010, JEDEC), tabelas de calibração. Cada um com **edição vigente**, responsável, local e **verificação periódica** de que ainda é a edição em vigor — com data, quem verificou e fila de vencidos. |
| **8.3.2 a** | Aprovado antes do uso por quem tem autoridade | ✅ Atende | Fluxo de revisão com revisor designado, bloqueio de autoaprovação, decisão registrada. |
| **8.3.2 b** | **Revisado e atualizado em intervalos apropriados** | ✅ Atende | Periodicidade por documento (padrão 24 meses). Aprovar inicia o relógio. Endpoint de **revisão periódica sem alteração** — a norma exige evidência de que a revisão ocorreu, não que o conteúdo mudou. Consulta de vencidos e a vencer. |
| **8.3.2 c** | Alterações e revisão vigente identificadas | ✅ Atende | Histórico de versões + versão vigente + **motivo formal da alteração** por revisão (`change_reason`), separado da mensagem livre do autor e imutável depois de gravado. |
| **8.3.2 d** | Versão vigente disponível no ponto de uso, **distribuição controlada** | ✅ Atende | Registro de **ciência de leitura** por pessoa **e por revisão** (nunca por documento — dar ciência na v1 não diz nada sobre a v3). Lista de quem deu ciência e de **quem ainda falta**. Exigência configurável por documento. |
| **8.3.2 e** | Identificador único | ✅ Atende | Códigos IT/PQ/PO/MQ sequenciais por categoria, gerados pelo sistema, sem reuso. |
| **8.3.2 f** | Obsoletos retirados e **claramente marcados** | ✅ Atende | **Marca d'água diagonal em todas as páginas** do PDF servido: `DOCUMENTO OBSOLETO`, `VERSÃO NÃO VIGENTE` ou `RASCUNHO`. Aplicada na **entrega**, não na compilação — o mesmo arquivo é rascunho hoje, vigente depois e obsoleto ano que vem. |

## 2. Cláusula 8.4 — Controle de registros

| Requisito | Situação | Como é atendido |
|-----------|----------|-----------------|
| Identificação, armazenamento, proteção, backup, recuperação | ✅ Atende | Banco + volume de anexos + `scripts\backup.ps1`. |
| **Tempo de retenção definido** e descarte | ✅ Atende | `retention_years` por documento (padrão 5 anos). A norma não fixa prazo — exige que o laboratório defina o seu, e agora ele fica declarado documento a documento. |
| Trilha de auditoria imutável | ✅ Atende | Toda ação registrada com quem/o quê/quando. |
| Registros legíveis e com acesso controlado | ✅ Atende | RBAC + ABAC por grupo. |

## 3. Lista de documentos internos e externos

A 17025 exige esse registro (o "master list"): índice controlado com todo
documento, sua revisão vigente e seu status. Os dados existem — internos em
`GET /documents` (com autor, revisor, vigente e status) e externos em
`GET /external-documents` (com edição e verificação). **Falta apenas a exportação
formatada** para entregar ao auditor; está listado como pendência abaixo.

## 4. Fora do escopo de um DMS (módulos separados)

Estes são requisitos reais da 17025 para um laboratório de eletrônica, mas são
sistemas próprios — registro aqui para o mapa ficar honesto, não como dívida
deste sistema:

- **6.4 / 6.5 Equipamentos e rastreabilidade metrológica** — inventário de
  osciloscópios, multímetros, fontes e geradores, com certificado de calibração,
  data de vencimento e cadeia de rastreabilidade até um padrão nacional.
  *É o módulo de maior valor para um laboratório de eletrônica depois do 8.3.*
- **6.2 Competência de pessoal** — matriz de treinamento e autorização por ensaio.
- **7.6 Incerteza de medição** — cálculo e declaração de incerteza.
- **7.7 Validade dos resultados** — controle de qualidade, ensaios de proficiência.
- **7.8 Relatórios de ensaio/certificados** — conteúdo obrigatório do laudo.
- **7.9 / 7.10 / 8.7 Reclamações, trabalho não conforme e ações corretivas (CAPA)**.
- **8.5 / 8.8 / 8.9 Riscos e oportunidades, auditoria interna, análise crítica.**

---

## Situação atual

Os sete pontos da 8.3 e os requisitos de 8.4 estão **atendidos no backend**.

### Endpoints de conformidade

| Requisito | Endpoint |
|-----------|----------|
| 8.3.1 documentos externos | `GET/POST/PUT/DELETE /api/v1/external-documents` |
| 8.3.1 verificação de edição | `POST /api/v1/external-documents/{id}/verify` · `GET .../due-for-verification` |
| 8.3.2 b revisão periódica | `POST /api/v1/documents/{id}/periodic-review` · `GET /api/v1/documents/due-for-review` |
| 8.3.2 c motivo da alteração | campo `changeReason` ao salvar versão; devolvido no histórico |
| 8.3.2 d ciência de leitura | `GET/POST /api/v1/documents/{id}/acknowledgements` · `.../me` · `.../pending` |
| 8.3.2 f marca d'água | automática em `GET /api/v1/latex/pdf/{commitId}` |

### Onde cada controle aparece na interface

| Controle | Onde |
|----------|------|
| Documentos externos (8.3.1) | Menu **Documentos Externos** — cadastro, filtros e botão "confirmar edição vigente" |
| Periodicidade e retenção (8.3.2 b / 8.4) | Campos no diálogo de **Novo Documento** |
| Revisão vencida | Faixa vermelha no editor + ícone na listagem + notificação diária |
| Revisão periódica sem alteração | Botão na faixa do editor |
| Motivo da alteração (8.3.2 c) | Campo obrigatório ao **Salvar Versão**; exibido no histórico |
| Ciência de leitura (8.3.2 d) | Faixa "Li e entendi" no editor, por versão |
| Marca d'água (8.3.2 f) | Automática ao baixar o PDF |
| Lista mestra | Botão **Exportar lista mestra** (CSV) na tela de Documentos |

### Lembrete automático

`ReviewReminderJob` roda todo dia às 07:00 e avisa o revisor designado (ou o
autor, se não houver) sobre revisões vencidas e a vencer em 15 dias. Notificações
são deduplicadas por dia, então reiniciar o sistema não gera spam.

---

## Fontes

- [ISO/IEC 17025:2017 — texto da norma (PDF)](https://dastmardi.ir/wp-content/uploads/2017/09/ISO_IEC_17025_2017E.pdf)
- [Checklist da cláusula 8.3 para laboratórios — Cognidox](https://www.cognidox.com/blog/iso/iec-170252017-clause-8.3-checklist)
- [Lista de documentos obrigatórios da ISO 17025:2017 — Advisera](https://advisera.com/17025academy/blog/2019/08/30/list-of-mandatory-documents-required-by-iso-170252017/)
- [Requisitos de documentos e registros — PJLA (slides)](https://www.pjlabs.com/downloads/webinar_slides/4.20.2020_Doc-Control-Records.pdf)
- [Controle de documentos e registros em laboratórios ISO — QIA](https://www.qi-a.com/learning-center/document-and-record-control-in-iso-compliant-labs-where-lims-fits-in/)
- [Document Control and Management (ORA-LAB.4.3) — FDA](https://www.fda.gov/media/73789/download)
