package com.labdms.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Configuration
@Profile("!test") // Run in non-test profiles
public class DummyDataSeeder implements CommandLineRunner {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(DummyDataSeeder.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String attachmentsDir;
    private final boolean seedDemoDocuments;

    public DummyDataSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                           @Value("${attachments.storage-dir:/app/attachments}") String attachmentsDir,
                           @Value("${seed.demo-documents:false}") boolean seedDemoDocuments) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.attachmentsDir = attachmentsDir;
        this.seedDemoDocuments = seedDemoDocuments;
    }

    /**
     * People and configuration are seeded always; example documents are not.
     *
     * <p>The demo documents used to be created on every start, which quietly
     * undid any attempt to begin from a clean slate: the migration that empties
     * the document tables ran first, and this put eight example documents
     * straight back. Anyone wiping the data to start their own work found the
     * examples waiting for them again and reasonably concluded the wipe had not
     * worked.
     *
     * <p>Set {@code SEED_DEMO_DOCUMENTS=true} to get them back for a demo.
     */
    @Override
    public void run(String... args) throws Exception {
        seedPermissionsAndRoles();
        seedUsers();
        seedDocumentGroups();

        if (seedDemoDocuments) {
            logger.info("Seeding example documents (seed.demo-documents=true)");
            seedDummyDocuments();
            seedAttachments();
            seedNotifications();
        } else {
            logger.info("Example documents not seeded — the laboratory starts with its own. "
                    + "Set SEED_DEMO_DOCUMENTS=true to load the examples.");
        }
    }

    private void seedPermissionsAndRoles() {
        // Core permissions
        String[] permissions = {
            "DOCUMENT_VIEW_ALL", "DOCUMENT_EDIT_ALL", "DOCUMENT_CREATE", "DOCUMENT_EDIT",
            "DOCUMENT_DELETE", "REVIEW_APPROVE"
        };
        for (String perm : permissions) {
            jdbcTemplate.update("INSERT INTO permissions (id, code, description) VALUES (?, ?, ?) ON CONFLICT (code) DO NOTHING",
                    UUID.randomUUID(), perm, "Permission " + perm);
        }

        // Ensure roles exist (ISO 9000 well-defined roles)
        UUID roleAdminId = getOrInsertRole("ROLE_ADMIN", "Administrator - full system access");
        UUID roleEditorId = getOrInsertRole("ROLE_EDITOR", "Editor - creates and edits documents");
        UUID roleReviewerId = getOrInsertRole("ROLE_REVIEWER", "Reviewer - approves/rejects document changes");
        UUID roleViewerId = getOrInsertRole("ROLE_VIEWER", "Viewer - read-only access to documents");

        // Grant all permissions to ROLE_ADMIN
        for (String perm : permissions) {
            grantPermission(roleAdminId, perm);
        }

        // ROLE_EDITOR: create/edit documents in own groups
        grantPermission(roleEditorId, "DOCUMENT_CREATE");
        grantPermission(roleEditorId, "DOCUMENT_EDIT");

        // ROLE_REVIEWER: view everything, edit, and approve reviews.
        //
        // Deliberately WITHOUT DOCUMENT_CREATE: whoever approves does not write.
        // That separation is the point of having a reviewer at all, and ISO 17025
        // 8.3 expects the review of a document to be somebody else's act.
        grantPermission(roleReviewerId, "DOCUMENT_VIEW_ALL");
        grantPermission(roleReviewerId, "DOCUMENT_EDIT");
        grantPermission(roleReviewerId, "REVIEW_APPROVE");

        // ROLE_VIEWER: read-only
        grantPermission(roleViewerId, "DOCUMENT_VIEW_ALL");
    }

    private void grantPermission(UUID roleId, String permissionCode) {
        jdbcTemplate.update("""
            INSERT INTO role_permissions (role_id, permission_id)
            SELECT ?, id FROM permissions WHERE code = ?
            ON CONFLICT DO NOTHING
            """, roleId, permissionCode);
    }

    private void seedUsers() {
        // One user per role, all with password 123456 (dev/demo credentials)
        String encodedPassword = passwordEncoder.encode("123456");

        seedUser("admin@email.com", "Administrador", "ROLE_ADMIN", encodedPassword);
        seedUser("writer@email.com", "Escritor de Documentos", "ROLE_EDITOR", encodedPassword);
        seedUser("reviewer@email.com", "Revisor da Qualidade", "ROLE_REVIEWER", encodedPassword);
        seedUser("member@email.com", "Membro do Laboratório", "ROLE_VIEWER", encodedPassword);
        seedUser("reader@email.com", "Leitor", "ROLE_VIEWER", encodedPassword);

        // The demo password is reset on every start, on purpose: these accounts
        // exist to evaluate the system, and a half-finished experiment that left
        // one of them with an unknown password used to end with somebody editing
        // password hashes by hand. Only these four seeded addresses are touched —
        // a real user created through the interface is never reset.
        resetSeedPasswords(encodedPassword);
    }

    private void resetSeedPasswords(String encodedPassword) {
        jdbcTemplate.update("""
                UPDATE users SET password_hash = ?, status = 'ACTIVE'
                 WHERE email IN ('admin@email.com', 'writer@email.com',
                                 'reviewer@email.com', 'member@email.com', 'reader@email.com')
                """, encodedPassword);
    }

    private void seedUser(String email, String fullName, String roleName, String encodedPassword) {
        UUID roleId = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE name = ?", UUID.class, roleName);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);

        if (count != null && count == 0) {
            UUID userId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO users (id, username, email, full_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """, userId, email, email, fullName, encodedPassword);
            jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    userId, roleId);
        } else {
            // Ensure existing seed user is active and holds the expected role
            UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
            jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
            jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    userId, roleId);
        }
    }

    private void seedDocumentGroups() {
        String[] groups = {"Soldagem", "Qualidade", "Administração"};
        for (String group : groups) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_groups WHERE name = ?", Integer.class, group);
            if (count != null && count == 0) {
                jdbcTemplate.update("INSERT INTO document_groups (id, name, description, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                        UUID.randomUUID(), group, "Grupo de " + group);
            }
        }
    }

    private void seedDummyDocuments() {
        // Idempotent per document code: missing examples are added even to an
        // existing database, so no volume reset is required.

        // Get Group IDs
        UUID soldagemGroupId = getGroupIdByName("Soldagem");
        UUID qualidadeGroupId = getGroupIdByName("Qualidade");

        // Get Admin User ID (for created_by)
        UUID adminId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'admin@email.com' LIMIT 1", UUID.class);
        if (adminId == null) {
            adminId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'writer@email.com' LIMIT 1", UUID.class);
        }

        if (adminId == null || soldagemGroupId == null || qualidadeGroupId == null) return;

        // ---- IT-SOLDAGEM-001: three versions, edited by two different people ----
        createVersionedDocument("IT-SOLDAGEM-001", "Instrução de Trabalho - Soldagem MIG/MAG",
                "IT", soldagemGroupId, "ACTIVE", List.of(
                new Version("Criação inicial da instrução de soldagem", "admin@email.com",
                        labDoc("IT-SOLDAGEM-001", "Instrução de Trabalho \\\\ Soldagem MIG/MAG", "0", BODY_IT_V1)),
                new Version("Inclui tabela de parâmetros e lista de EPI", "writer@email.com",
                        labDoc("IT-SOLDAGEM-001", "Instrução de Trabalho \\\\ Soldagem MIG/MAG", "1", BODY_IT_V2)),
                new Version("Ajusta corrente da posição vertical após ensaio de qualificação", "writer@email.com",
                        labDoc("IT-SOLDAGEM-001", "Instrução de Trabalho \\\\ Soldagem MIG/MAG", "2", BODY_IT_V3))));

        // ---- PQ-001: two versions ----
        createVersionedDocument("PQ-001", "Procedimento de Qualidade - Calibração de Instrumentos",
                "PQ", qualidadeGroupId, "ACTIVE", List.of(
                new Version("Criação do procedimento de calibração", "admin@email.com",
                        labDoc("PQ-001", "Procedimento da Qualidade \\\\ Calibração de Instrumentos", "0", BODY_PQ_V1)),
                new Version("Adiciona critérios de aceitação e responsabilidades", "reviewer@email.com",
                        labDoc("PQ-001", "Procedimento da Qualidade \\\\ Calibração de Instrumentos", "1", BODY_PQ_V2))));

        // ---- IT-CALIBRACAO-002: submitted for approval (opens a pending review) ----
        createVersionedDocument("IT-CALIBRACAO-002", "Instrução de Trabalho - Verificação de Paquímetros",
                "IT", qualidadeGroupId, "IN_REVIEW", List.of(
                new Version("Primeira redação da verificação de paquímetros", "writer@email.com",
                        labDoc("IT-CALIBRACAO-002", "Instrução de Trabalho \\\\ Verificação de Paquímetros", "0", BODY_IT_CAL_V1)),
                new Version("Inclui tabela de pontos de verificação", "writer@email.com",
                        labDoc("IT-CALIBRACAO-002", "Instrução de Trabalho \\\\ Verificação de Paquímetros", "1", BODY_IT_CAL_V2))));

        // ---- PQ-002: draft in progress ----
        createVersionedDocument("PQ-002", "Procedimento de Qualidade - Controle de Documentos e Registros",
                "PQ", qualidadeGroupId, "DRAFT", List.of(
                new Version("Esboço inicial do controle de documentos", "writer@email.com",
                        labDoc("PQ-002", "Procedimento da Qualidade \\\\ Controle de Documentos e Registros", "0", BODY_PQ2_V1))));

        // ---- MQ-001: quality manual ----
        createVersionedDocument("MQ-001", "Manual da Qualidade do Laboratório",
                "MQ", qualidadeGroupId, "ACTIVE", List.of(
                new Version("Emissão do manual da qualidade", "admin@email.com",
                        labDoc("MQ-001", "Manual da Qualidade do Laboratório", "0", BODY_MQ_V1))));

        // ---- PO-001: no group (group/sector is optional) ----
        createVersionedDocument("PO-001", "Política e Objetivos da Qualidade 2026",
                "PO", null, "ACTIVE", List.of(
                new Version("Definição da política e objetivos de 2026", "admin@email.com",
                        labDoc("PO-001", "Política e Objetivos da Qualidade \\\\ 2026", "0", BODY_PO_V1))));

        // ---- IT-ENSAIO-003: rascunho pronto para o usuário editar e praticar ----
        createVersionedDocument("IT-ENSAIO-003", "Instrução de Trabalho - Ensaio de Tração",
                "IT", soldagemGroupId, "DRAFT", List.of(
                new Version("Redação inicial do ensaio de tração", "writer@email.com",
                        labDoc("IT-ENSAIO-003", "Instrução de Trabalho \\\\ Ensaio de Tração", "0", BODY_IT_ENSAIO_V1)),
                new Version("Inclui fórmulas de tensão e alongamento", "admin@email.com",
                        labDoc("IT-ENSAIO-003", "Instrução de Trabalho \\\\ Ensaio de Tração", "1", BODY_IT_ENSAIO_V2))));

        // ---- PQ-003: rascunho curto, bom para testar edição rápida ----
        createVersionedDocument("PQ-003", "Procedimento de Qualidade - Auditoria Interna",
                "PQ", qualidadeGroupId, "DRAFT", List.of(
                new Version("Esboço do procedimento de auditoria interna", "reviewer@email.com",
                        labDoc("PQ-003", "Procedimento da Qualidade \\\\ Auditoria Interna", "0", BODY_PQ3_V1))));
    }

    /**
     * Standard laboratory document layout: ISO-style header with document code
     * and revision, "page X of Y" footer, clickable table of contents.
     */
    private static String labDoc(String code, String title, String revision, String body) {
        return """
            \\documentclass[12pt,a4paper]{article}
            \\usepackage[utf8]{inputenc}
            \\usepackage[brazil]{babel}
            \\usepackage[a4paper,margin=2.5cm,headheight=15pt]{geometry}
            \\usepackage{booktabs}
            \\usepackage{longtable}
            \\usepackage{enumitem}
            \\usepackage{xcolor}
            \\usepackage{fancyhdr}
            \\usepackage{lastpage}
            \\usepackage[colorlinks=true,linkcolor=blue!60!black,urlcolor=blue!60!black]{hyperref}

            \\pagestyle{fancy}
            \\fancyhf{}
            \\fancyhead[L]{\\small\\textbf{__CODE__}}
            \\fancyhead[R]{\\small Revisão __REV__}
            \\fancyfoot[C]{\\small Página \\thepage\\ de \\pageref{LastPage}}
            \\renewcommand{\\headrulewidth}{0.4pt}

            \\title{__TITLE__}
            \\author{Laboratório de Engenharia}
            \\date{\\today}

            \\begin{document}
            \\maketitle
            \\thispagestyle{fancy}

            __BODY__

            \\end{document}
            """
            .replace("__CODE__", code)
            .replace("__TITLE__", title)
            .replace("__REV__", revision)
            .replace("__BODY__", body);
    }






    /* ------------------------------------------------------------------ */
    /* Example document bodies. Later versions build on earlier ones so    */
    /* the diff view shows meaningful changes.                             */
    /* ------------------------------------------------------------------ */

    private static final String BODY_IT_V1 = """
            \\section{Objetivo}
            Estabelecer o procedimento para execução de soldas pelo processo MIG/MAG,
            garantindo a qualidade e a rastreabilidade exigidas pela ISO 9000.

            \\section{Escopo}
            Aplica-se a todas as juntas soldadas executadas no laboratório em aços
            carbono com espessura entre 3 mm e 12 mm.

            \\section{Procedimento}
            \\begin{enumerate}[leftmargin=*]
              \\item Inspecionar e limpar a junta a ser soldada;
              \\item Executar a solda mantendo o ângulo de trabalho adequado;
              \\item Registrar os parâmetros utilizados no relatório de soldagem.
            \\end{enumerate}
            """;

    private static final String BODY_IT_V2 = """
            \\tableofcontents
            \\newpage

            \\section{Objetivo}
            Estabelecer o procedimento para execução de soldas pelo processo MIG/MAG,
            garantindo a qualidade e a rastreabilidade exigidas pela ISO 9000.

            \\section{Escopo}
            Aplica-se a todas as juntas soldadas executadas no laboratório em aços
            carbono com espessura entre 3 mm e 12 mm.

            \\section{Equipamentos e EPI}
            \\begin{itemize}[leftmargin=*]
              \\item Fonte de soldagem MIG/MAG calibrada conforme \\textbf{PQ-001};
              \\item Arame ER70S-6, diâmetro 1,2 mm;
              \\item Gás de proteção Ar + 25\\% CO$_2$;
              \\item EPI: máscara de solda, luvas de raspa, avental e perneiras.
            \\end{itemize}

            \\section{Parâmetros de Soldagem}
            \\begin{center}
            \\begin{tabular}{@{}lccc@{}}
            \\toprule
            \\textbf{Posição} & \\textbf{Corrente (A)} & \\textbf{Tensão (V)} & \\textbf{Velocidade (cm/min)} \\\\
            \\midrule
            Plana      & 180--220 & 24--28 & 30--40 \\\\
            Horizontal & 160--200 & 22--26 & 25--35 \\\\
            Vertical   & 120--150 & 18--22 & 12--20 \\\\
            \\bottomrule
            \\end{tabular}
            \\end{center}

            \\section{Procedimento}
            \\begin{enumerate}[leftmargin=*]
              \\item Inspecionar e limpar a junta a ser soldada;
              \\item Ajustar os parâmetros conforme a tabela da Seção 4;
              \\item Executar a solda mantendo o ângulo de trabalho de 10--15$^\\circ$;
              \\item Registrar os parâmetros utilizados no relatório de soldagem.
            \\end{enumerate}

            \\section{Registros}
            Os registros desta instrução devem ser mantidos por 5 anos, conforme o
            procedimento \\textbf{PQ-002}.
            """;

    private static final String BODY_IT_V3 = """
            \\tableofcontents
            \\newpage

            \\section{Objetivo}
            Estabelecer o procedimento para execução de soldas pelo processo MIG/MAG,
            garantindo a qualidade e a rastreabilidade exigidas pela ISO 9000.

            \\section{Escopo}
            Aplica-se a todas as juntas soldadas executadas no laboratório em aços
            carbono com espessura entre 3 mm e 12 mm.

            \\section{Equipamentos e EPI}
            \\begin{itemize}[leftmargin=*]
              \\item Fonte de soldagem MIG/MAG calibrada conforme \\textbf{PQ-001};
              \\item Arame ER70S-6, diâmetro 1,2 mm;
              \\item Gás de proteção Ar + 25\\% CO$_2$;
              \\item EPI: máscara de solda, luvas de raspa, avental e perneiras.
            \\end{itemize}

            \\section{Parâmetros de Soldagem}
            \\begin{center}
            \\begin{tabular}{@{}lccc@{}}
            \\toprule
            \\textbf{Posição} & \\textbf{Corrente (A)} & \\textbf{Tensão (V)} & \\textbf{Velocidade (cm/min)} \\\\
            \\midrule
            Plana      & 180--220 & 24--28 & 30--40 \\\\
            Horizontal & 160--200 & 22--26 & 25--35 \\\\
            Vertical   & 140--170 & 20--24 & 15--25 \\\\
            \\bottomrule
            \\end{tabular}
            \\end{center}

            \\textcolor{blue!60!black}{\\textbf{Nota da revisão 2:}} a faixa de corrente da
            posição vertical foi ampliada após o ensaio de qualificação EQ-2026-014,
            que demonstrou penetração insuficiente com os valores anteriores.

            \\section{Procedimento}
            \\begin{enumerate}[leftmargin=*]
              \\item Inspecionar e limpar a junta a ser soldada;
              \\item Ajustar os parâmetros conforme a tabela da Seção 4;
              \\item Executar a solda mantendo o ângulo de trabalho de 10--15$^\\circ$;
              \\item Inspecionar visualmente o cordão antes de liberar a peça;
              \\item Registrar os parâmetros utilizados no relatório de soldagem.
            \\end{enumerate}

            \\section{Registros}
            Os registros desta instrução devem ser mantidos por 5 anos, conforme o
            procedimento \\textbf{PQ-002}.
            """;

    private static final String BODY_PQ_V1 = """
            \\section{Objetivo}
            Definir a sistemática de calibração dos instrumentos de medição do
            laboratório, assegurando rastreabilidade metrológica ao padrão nacional.

            \\section{Periodicidade}
            \\begin{center}
            \\begin{tabular}{@{}lc@{}}
            \\toprule
            \\textbf{Instrumento} & \\textbf{Periodicidade} \\\\
            \\midrule
            Paquímetro & 12 meses \\\\
            Micrômetro & 12 meses \\\\
            Termômetro & 6 meses \\\\
            \\bottomrule
            \\end{tabular}
            \\end{center}
            """;

    private static final String BODY_PQ_V2 = """
            \\tableofcontents
            \\newpage

            \\section{Objetivo}
            Definir a sistemática de calibração dos instrumentos de medição do
            laboratório, assegurando rastreabilidade metrológica ao padrão nacional.

            \\section{Periodicidade e Critérios de Aceitação}
            \\begin{center}
            \\begin{longtable}{@{}lcc@{}}
            \\toprule
            \\textbf{Instrumento} & \\textbf{Periodicidade} & \\textbf{Critério de Aceitação} \\\\
            \\midrule
            \\endhead
            Paquímetro 150 mm & 12 meses & $\\pm$ 0,02 mm \\\\
            Micrômetro 25 mm  & 12 meses & $\\pm$ 0,005 mm \\\\
            Termômetro digital & 6 meses  & $\\pm$ 0,5 $^\\circ$C \\\\
            Balança analítica  & 6 meses  & $\\pm$ 0,1 g \\\\
            Torquímetro        & 12 meses & $\\pm$ 4\\% da leitura \\\\
            \\bottomrule
            \\end{longtable}
            \\end{center}

            \\section{Responsabilidades}
            \\begin{itemize}[leftmargin=*]
              \\item \\textbf{Técnico do laboratório}: encaminhar os instrumentos no prazo;
              \\item \\textbf{Revisor da qualidade}: analisar e aprovar os certificados;
              \\item \\textbf{Administrador}: manter os registros no Lab DMS.
            \\end{itemize}

            \\section{Instrumento Fora de Conformidade}
            Instrumento reprovado deve ser identificado com etiqueta vermelha, retirado
            de uso imediatamente e registrado no formulário de não conformidade.

            \\section{Registros}
            Certificados de calibração são anexados ao documento correspondente e
            mantidos sob controle de versão neste sistema.
            """;

    private static final String BODY_IT_CAL_V1 = """
            \\section{Objetivo}
            Descrever a verificação intermediária de paquímetros entre calibrações,
            conforme exigido pelo procedimento \\textbf{PQ-001}.

            \\section{Recursos Necessários}
            \\begin{itemize}[leftmargin=*]
              \\item Blocos-padrão classe 1 calibrados;
              \\item Pano macio e álcool isopropílico;
              \\item Sala climatizada a $20 \\pm 2$ $^\\circ$C.
            \\end{itemize}
            """;

    private static final String BODY_IT_CAL_V2 = """
            \\section{Objetivo}
            Descrever a verificação intermediária de paquímetros entre calibrações,
            conforme exigido pelo procedimento \\textbf{PQ-001}.

            \\section{Recursos Necessários}
            \\begin{itemize}[leftmargin=*]
              \\item Blocos-padrão classe 1 calibrados;
              \\item Pano macio e álcool isopropílico;
              \\item Sala climatizada a $20 \\pm 2$ $^\\circ$C.
            \\end{itemize}

            \\section{Pontos de Verificação}
            \\begin{center}
            \\begin{tabular}{@{}ccc@{}}
            \\toprule
            \\textbf{Bloco-padrão (mm)} & \\textbf{Leitura admissível (mm)} & \\textbf{Erro máximo} \\\\
            \\midrule
            10,000  & 9,98 -- 10,02   & $\\pm$ 0,02 mm \\\\
            50,000  & 49,98 -- 50,02  & $\\pm$ 0,02 mm \\\\
            100,000 & 99,97 -- 100,03 & $\\pm$ 0,03 mm \\\\
            \\bottomrule
            \\end{tabular}
            \\end{center}

            \\section{Critério de Reprovação}
            Qualquer ponto fora da faixa admissível reprova o instrumento, que deve
            ser recolhido e enviado para calibração conforme \\textbf{PQ-001}.
            """;

    private static final String BODY_PQ2_V1 = """
            \\section{Objetivo}
            Estabelecer as regras de identificação, aprovação, distribuição e guarda
            dos documentos e registros do sistema de gestão da qualidade.

            \\section{Codificação}
            \\begin{center}
            \\begin{tabular}{@{}ll@{}}
            \\toprule
            \\textbf{Prefixo} & \\textbf{Tipo de documento} \\\\
            \\midrule
            MQ & Manual da Qualidade \\\\
            PQ & Procedimento da Qualidade \\\\
            IT & Instrução de Trabalho \\\\
            PO & Política e Objetivos \\\\
            \\bottomrule
            \\end{tabular}
            \\end{center}

            \\section{Fluxo de Aprovação}
            \\begin{enumerate}[leftmargin=*]
              \\item O autor edita o documento e salva uma nova versão;
              \\item O documento é submetido para aprovação (status \\textit{Em Revisão});
              \\item Um revisor aprova ou rejeita com justificativa registrada;
              \\item Aprovado, o documento torna-se \\textit{Ativo} e passa a valer.
            \\end{enumerate}

            \\section{Tempo de Guarda}
            Registros da qualidade são mantidos por, no mínimo, 5 anos. Versões
            obsoletas permanecem no histórico para fins de rastreabilidade.
            """;

    private static final String BODY_MQ_V1 = """
            \\tableofcontents
            \\newpage

            \\section{Apresentação}
            Este manual descreve o sistema de gestão da qualidade do laboratório,
            em conformidade com a família de normas ISO 9000.

            \\section{Política da Qualidade}
            O laboratório compromete-se a fornecer resultados confiáveis, rastreáveis
            e imparciais, promovendo a melhoria contínua de seus processos e a
            competência de seu pessoal.

            \\section{Estrutura Documental}
            \\begin{enumerate}[leftmargin=*]
              \\item \\textbf{MQ} --- Manual da Qualidade (este documento);
              \\item \\textbf{PQ} --- Procedimentos da Qualidade;
              \\item \\textbf{IT} --- Instruções de Trabalho;
              \\item \\textbf{PO} --- Políticas e Objetivos.
            \\end{enumerate}

            \\section{Controle de Documentos}
            Todos os documentos são geridos no Lab DMS com controle de versão, fluxo
            formal de aprovação e trilha de auditoria imutável. Cada alteração
            registra autor, data e justificativa. Documentos obsoletos são
            preservados para fins de rastreabilidade.
            """;

    private static final String BODY_PO_V1 = """
            \\section{Política}
            Garantir a excelência dos serviços laboratoriais por meio de práticas
            padronizadas, pessoal qualificado e melhoria contínua.

            \\section{Objetivos para 2026}
            \\begin{itemize}[leftmargin=*]
              \\item Manter 100\\% dos documentos da qualidade sob controle de versão;
              \\item Reduzir o tempo médio de aprovação de documentos para 5 dias;
              \\item Realizar 2 auditorias internas no ano;
              \\item Capacitar 100\\% da equipe nos procedimentos vigentes.
            \\end{itemize}

            \\section{Acompanhamento}
            Os indicadores são revisados trimestralmente na reunião de análise
            crítica pela direção.
            """;


    private static final String BODY_IT_ENSAIO_V1 = """
            \\section{Objetivo}
            Padronizar a execução do ensaio de tração em corpos de prova metálicos,
            conforme a norma aplicável e o sistema de gestão da qualidade.

            \\section{Corpo de Prova}
            \\begin{itemize}[leftmargin=*]
              \\item Usinado conforme desenho DES-2026-007;
              \\item Superfície livre de riscos transversais;
              \\item Identificado com o número do lote.
            \\end{itemize}

            \\section{Execução}
            \\begin{enumerate}[leftmargin=*]
              \\item Medir e registrar a seção inicial do corpo de prova;
              \\item Fixar o corpo de prova nas garras da máquina;
              \\item Aplicar carga até a ruptura;
              \\item Registrar a carga máxima e o alongamento final.
            \\end{enumerate}
            """;

    private static final String BODY_IT_ENSAIO_V2 = """
            \\tableofcontents
            \\newpage

            \\section{Objetivo}
            Padronizar a execução do ensaio de tração em corpos de prova metálicos,
            conforme a norma aplicável e o sistema de gestão da qualidade.

            \\section{Corpo de Prova}
            \\begin{itemize}[leftmargin=*]
              \\item Usinado conforme desenho DES-2026-007;
              \\item Superfície livre de riscos transversais;
              \\item Identificado com o número do lote.
            \\end{itemize}

            \\section{Execução}
            \\begin{enumerate}[leftmargin=*]
              \\item Medir e registrar a seção inicial $A_0$ do corpo de prova;
              \\item Fixar o corpo de prova nas garras da máquina;
              \\item Aplicar carga com velocidade constante até a ruptura;
              \\item Registrar a carga máxima $F_{max}$ e o comprimento final $L_f$.
            \\end{enumerate}

            \\section{Cálculos}
            A tensão de ruptura e o alongamento percentual são obtidos por:
            \\[ \\sigma_r = \\frac{F_{max}}{A_0} \\qquad
               A\\% = \\frac{L_f - L_0}{L_0} \\times 100 \\]

            \\section{Critério de Aceitação}
            \\begin{center}
            \\begin{tabular}{@{}lcc@{}}
            \\toprule
            \\textbf{Material} & \\textbf{$\\sigma_r$ mínima (MPa)} & \\textbf{Alongamento mínimo} \\\\
            \\midrule
            ASTM A36  & 400 & 20\\% \\\\
            ASTM A572 & 485 & 18\\% \\\\
            \\bottomrule
            \\end{tabular}
            \\end{center}

            \\section{Registros}
            Os resultados são lançados no relatório de ensaio e mantidos conforme
            o procedimento \\textbf{PQ-002}.
            """;

    private static final String BODY_PQ3_V1 = """
            \\section{Objetivo}
            Estabelecer a sistemática das auditorias internas do sistema de gestão
            da qualidade do laboratório.

            \\section{Programação}
            As auditorias internas ocorrem semestralmente, cobrindo todos os
            processos ao longo do ciclo anual.

            \\section{Etapas}
            \\begin{enumerate}[leftmargin=*]
              \\item Elaboração do plano de auditoria;
              \\item Reunião de abertura;
              \\item Coleta de evidências e entrevistas;
              \\item Registro de não conformidades;
              \\item Reunião de encerramento e relatório.
            \\end{enumerate}

            \\section{Qualificação dos Auditores}
            O auditor não pode auditar a própria atividade, garantindo a
            imparcialidade exigida pela norma.
            """;

    private UUID getOrInsertRole(String name, String description) {
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM roles WHERE name = ?", UUID.class, name);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO roles (id, name, description, created_at) VALUES (?, ?, ?, NOW())", id, name, description);
            return id;
        }
    }

    private UUID getGroupIdByName(String name) {
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM document_groups WHERE name = ?", UUID.class, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** One version in a seeded document history. */
    private record Version(String message, String authorEmail, String latexSource) {}

    private void createDummyDocument(String code, String title, String docType, UUID groupId,
                                     UUID authorId, String latexContent) {
        createVersionedDocument(code, title, docType, groupId, "DRAFT",
                List.of(new Version("Initial document creation", "admin@email.com", latexContent)));
    }

    /**
     * Creates a document with a REAL version history: chained commits
     * (parent_commit_id), each with its own author, message and snapshot —
     * exactly what the editor's history sidebar and diff view read.
     */
    private void createVersionedDocument(String code, String title, String docType, UUID groupId,
                                         String status, List<Version> versions) {
        if (versions.isEmpty()) return;

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE code = ?", Integer.class, code);
        if (exists != null && exists > 0) return;   // already seeded

        UUID docId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID firstAuthor = userIdByEmail(versions.get(0).authorEmail());

        // Document + main branch first; the head is updated as commits are added.
        jdbcTemplate.update("""
            INSERT INTO documents (id, code, title, doc_type, status, document_group_id, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW() - INTERVAL '30 days', NOW())
            """, docId, code, title, docType, status, groupId, firstAuthor);

        // 'OPEN' — must match the BranchStatus enum (OPEN/MERGED/CLOSED).
        // Seeding 'ACTIVE' here used to make BranchStatus.valueOf() throw whenever
        // a seeded document's branch was loaded through JPA, breaking "save version"
        // on every example document.
        jdbcTemplate.update("""
            INSERT INTO branches (id, name, document_id, is_default, status, created_by, created_at, updated_at)
            VALUES (?, 'main', ?, true, 'OPEN', ?, NOW() - INTERVAL '30 days', NOW())
            """, branchId, docId, firstAuthor);

        UUID parentCommitId = null;
        UUID lastCommitId = null;
        int daysAgo = 30;

        for (Version version : versions) {
            UUID snapshotId = UUID.randomUUID();
            UUID commitId = UUID.randomUUID();
            UUID authorId = userIdByEmail(version.authorEmail());
            String source = version.latexSource();
            String contentHash = sha256Hex(source);

            jdbcTemplate.update("""
                INSERT INTO content_snapshots (id, content_hash, size_bytes, latex_source, created_at)
                VALUES (?, ?, ?, ?, NOW()) ON CONFLICT DO NOTHING
                """, snapshotId, contentHash, source.length(), source);

            // Reuse the snapshot when the hash already exists (content dedup)
            UUID effectiveSnapshotId = jdbcTemplate.queryForObject(
                    "SELECT id FROM content_snapshots WHERE content_hash = ? LIMIT 1", UUID.class, contentHash);

            // commits.sha256_hash is UNIQUE and identifies the COMMIT, not the
            // content (two versions may legitimately share the same content).
            String commitHash = sha256Hex(commitId + "|" + contentHash + "|" + parentCommitId
                    + "|" + version.message() + "|" + authorId);

            jdbcTemplate.update("""
                INSERT INTO commits (id, document_id, branch_id, content_snapshot_id, sha256_hash,
                                     message, author_id, parent_commit_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW() - MAKE_INTERVAL(days => ?))
                """, commitId, docId, branchId, effectiveSnapshotId, commitHash,
                     version.message(), authorId, parentCommitId, daysAgo);

            parentCommitId = commitId;
            lastCommitId = commitId;
            daysAgo = Math.max(0, daysAgo - 9);
        }

        jdbcTemplate.update("UPDATE documents SET current_commit_id = ? WHERE id = ?", lastCommitId, docId);
        jdbcTemplate.update("UPDATE branches SET head_commit_id = ? WHERE id = ?", lastCommitId, branchId);

        // Audit entries so the trail is populated on a fresh install
        jdbcTemplate.update("""
            INSERT INTO audit_log (user_id, action, entity_type, entity_id, details, created_at)
            VALUES (?, 'DOCUMENT_CREATED', 'Document', ?, ?::jsonb, NOW() - INTERVAL '30 days')
            """, firstAuthor, docId, "{\"code\":\"" + code + "\"}");

        // A document waiting for approval needs its review request
        if ("IN_REVIEW".equals(status)) {
            UUID reviewerRequester = userIdByEmail("writer@email.com");
            jdbcTemplate.update("""
                INSERT INTO review_requests (id, document_id, source_branch_id, target_branch_id, title,
                                             description, status, requested_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, NOW() - INTERVAL '2 days')
                """, UUID.randomUUID(), docId, branchId, branchId,
                     "Revisão anual de " + code,
                     "Atualização periódica exigida pelo procedimento PQ-002.",
                     reviewerRequester);
        }
    }

    private UUID userIdByEmail(String email) {
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ? LIMIT 1", UUID.class, email);
        } catch (Exception e) {
            return jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", UUID.class);
        }
    }

    private static String sha256Hex(String value) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Attachments: external files kept alongside the LaTeX source, e.g.   */
    /* scanned calibration certificates. Idempotent per document+filename. */
    /* ------------------------------------------------------------------ */

    private void seedAttachments() {
        seedAttachment("IT-SOLDAGEM-001", "certificado-calibracao-fonte-solda.txt", "text/plain",
                "admin@email.com",
                """
                CERTIFICADO DE CALIBRAÇÃO
                Equipamento: Fonte de Solda MIG/MAG - Patrimônio LAB-0231
                Data da calibração: 15/01/2026
                Válido até: 15/01/2027
                Instituição: Rede Brasileira de Calibração (RBC)
                Resultado: APROVADO - dentro dos limites de tolerância (±2%)

                Este documento comprova a rastreabilidade metrológica exigida
                pela ISO/IEC 17025 para o equipamento referenciado na IT-SOLDAGEM-001.
                """,
                "Certificado de calibração da fonte de solda referenciada na instrução");

        seedAttachment("PQ-001", "registro-calibracao-2026.csv", "text/csv",
                "reviewer@email.com",
                """
                instrumento,patrimonio,data_calibracao,proxima_calibracao,resultado
                Paquimetro Digital,LAB-0110,10/01/2026,10/01/2027,APROVADO
                Micrometro Externo,LAB-0111,12/01/2026,12/01/2027,APROVADO
                Multimetro,LAB-0142,05/02/2026,05/02/2027,APROVADO
                Termometro Infravermelho,LAB-0156,20/02/2026,20/02/2027,REPROVADO - substituido
                """,
                "Registro consolidado das calibrações realizadas no período");
    }

    private void seedAttachment(String documentCode, String fileName, String contentType,
                                 String uploaderEmail, String textContent, String description) {
        UUID documentId;
        try {
            documentId = jdbcTemplate.queryForObject(
                    "SELECT id FROM documents WHERE code = ?", UUID.class, documentCode);
        } catch (Exception e) {
            return; // document not seeded (e.g. groups missing) — skip quietly
        }

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_attachments WHERE document_id = ? AND file_name = ?",
                Integer.class, documentId, fileName);
        if (exists != null && exists > 0) return;

        try {
            Path documentDir = Path.of(attachmentsDir, documentId.toString());
            Files.createDirectories(documentDir);
            String storedName = UUID.randomUUID() + "_" + fileName;
            byte[] bytes = textContent.getBytes(StandardCharsets.UTF_8);
            Files.write(documentDir.resolve(storedName), bytes);

            UUID uploaderId = userIdByEmail(uploaderEmail);
            jdbcTemplate.update(
                    "INSERT INTO document_attachments (id, document_id, file_name, content_type, size_bytes, " +
                    "storage_path, description, uploaded_by, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), documentId, fileName, contentType, bytes.length,
                    documentId + "/" + storedName, description, uploaderId,
                    LocalDateTime.now().minusDays(5));
        } catch (Exception e) {
            // Seeding is best-effort: a filesystem issue here must not block startup.
        }
    }

    /* ------------------------------------------------------------------ */
    /* Notifications: example in-app notifications so the bell icon has    */
    /* something to show on a fresh install. Idempotent via a type+link    */
    /* check per user.                                                      */
    /* ------------------------------------------------------------------ */

    private void seedNotifications() {
        seedNotification("reviewer@email.com", "REVIEW_SUBMITTED", "Novo documento para revisão",
                "\"Instrução de Trabalho - Verificação de Paquímetros\" (IT-CALIBRACAO-002) foi submetido para aprovação: Revisão anual de IT-CALIBRACAO-002",
                "/dashboard/reviews");
        seedNotification("writer@email.com", "REVIEW_APPROVED", "Documento aprovado",
                "\"Procedimento de Qualidade - Calibração de Instrumentos\" (PQ-001) foi aprovado.",
                "/dashboard/documents");
        seedNotification("writer@email.com", "USER_APPROVED", "Cadastro aprovado",
                "Seu cadastro foi aprovado com o papel ROLE_EDITOR. Você já pode entrar no sistema.",
                "/dashboard");
    }

    private void seedNotification(String userEmail, String type, String title, String message, String link) {
        UUID userId = userIdByEmail(userEmail);
        if (userId == null) return;

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ? AND title = ?",
                Integer.class, userId, type, title);
        if (exists != null && exists > 0) return;

        jdbcTemplate.update(
                "INSERT INTO notifications (id, user_id, type, title, message, link, is_read, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, false, ?)",
                UUID.randomUUID(), userId, type, title, message, link, LocalDateTime.now().minusHours(3));
    }
}
