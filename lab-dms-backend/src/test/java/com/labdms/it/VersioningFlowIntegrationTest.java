package com.labdms.it;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole document lifecycle over HTTP against a real PostgreSQL.
 *
 * <p>Everything the unit tests cannot see lives in SQL: the 16 migrations,
 * the window functions of the history query, the diff of two trees, the
 * storage figures. This test boots the application on an embedded Postgres
 * (downloaded once by Maven), seeds the demo accounts and walks the path a
 * laboratory walks: create → edit (draft) → save → diff → ZIP → submit →
 * approve → restore → storage.
 *
 * <p>Skipped, not failed, when the embedded database cannot start (no
 * binaries for the platform, no permission to execute them): that is an
 * environment limitation, not a defect in the code under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VersioningFlowIntegrationTest {

    private static EmbeddedPostgres postgres;
    private static Throwable startupFailure;

    @BeforeAll
    static void startDatabase() {
        try {
            postgres = EmbeddedPostgres.builder().start();
            // V1 is a pg_dump and says "OWNER TO labdms": the role must exist
            // before Flyway runs, exactly as docker-compose creates it.
            try (var c = postgres.getPostgresDatabase().getConnection(); var st = c.createStatement()) {
                st.execute("CREATE ROLE labdms LOGIN SUPERUSER PASSWORD 'labdms'");
                st.execute("CREATE DATABASE labdms OWNER labdms");
            }
        } catch (Throwable t) {
            startupFailure = t;
        }
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        if (postgres == null) {
            // Spring still needs a URL to build the context; the test is skipped
            // before any request when the database did not start.
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:1/none");
            return;
        }
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("labdms", "labdms"));
        registry.add("spring.datasource.username", () -> "labdms");
        registry.add("spring.datasource.password", () -> "labdms");
        String storage = Files.createTempDirectory("labdms-it").toString();
        registry.add("attachments.storage-dir", () -> storage);
        registry.add("latex.mode", () -> "local");
        registry.add("seed.demo-documents", () -> "false");
        registry.add("spring.jpa.show-sql", () -> "false");
        // No Redis here: the rate limiter fails open, and it should do so fast.
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;

    private static String adminToken, writerToken, reviewerToken, readerToken;
    private static String documentId, v1, draft, v2, v3, v4, reviewId;

    /* ------------------------------------------------------------------ */

    private String url(String path) { return "http://localhost:" + port + "/api/v1" + path; }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> call(HttpMethod method, String path, Object body, String token) {
        return http.exchange(url(path), method, new HttpEntity<>(body, auth(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<List> callList(String path, String token) {
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(null, auth(token)), List.class);
    }

    private String login(String email) {
        ResponseEntity<Map> r = call(HttpMethod.POST, "/auth/login", Map.of("username", email, "password", "123456"), null);
        assertThat(r.getStatusCode().value()).as("login " + email).isEqualTo(200);
        return (String) r.getBody().get("accessToken");
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private ResponseEntity<Map> upload(String path, byte[] bytes, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "proj.zip"; }
        });
        return http.exchange(url(path), HttpMethod.POST, new HttpEntity<>(form, h), Map.class);
    }

    /* ------------------------------------------------------------------ */

    @Test @Order(1)
    void migrationsApplyAndSeededAccountsSignIn() {
        assumeTrue(postgres != null, "embedded PostgreSQL could not start: " + startupFailure);
        adminToken = login("admin@email.com");
        writerToken = login("writer@email.com");
        reviewerToken = login("reviewer@email.com");
        readerToken = login("reader@email.com");

        ResponseEntity<Map> me = call(HttpMethod.GET, "/auth/me", null, writerToken);
        assertThat(me.getBody().get("groups")).isInstanceOf(List.class);
        assertThat(me.getBody().get("authProvider")).isEqualTo("LOCAL");

        ResponseEntity<Map> cfg = call(HttpMethod.GET, "/auth/config", null, null);
        assertThat(cfg.getStatusCode().value()).isEqualTo(200);
        assertThat(cfg.getBody().get("ssoEnabled")).isEqualTo(false);
    }

    @Test @Order(2)
    void pendingAccountCannotSignIn() {
        assumeTrue(postgres != null);
        String email = "pendente-it@email.com";
        call(HttpMethod.POST, "/auth/register", Map.of("email", email, "fullName", "Pendente", "password", "123456"), null);
        ResponseEntity<Map> r = call(HttpMethod.POST, "/auth/login", Map.of("username", email, "password", "123456"), null);
        assertThat(r.getStatusCode().value()).isIn(401, 403);
    }

    @Test @Order(3)
    void createDocumentGivesV1WithATree() {
        assumeTrue(postgres != null);
        String src = "\\documentclass{article}\n\\begin{document}\nOla v1\n\\end{document}\n";
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", "IT Versionamento");
        body.put("docType", "IT");
        body.put("initialLatexSource", src);
        body.put("reviewIntervalMonths", 24);
        body.put("retentionYears", 0);
        body.put("visibility", "PRIVATE");
        body.put("tags", List.of());
        ResponseEntity<Map> r = call(HttpMethod.POST, "/documents", body, writerToken);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        documentId = (String) r.getBody().get("id");
        assertThat(r.getBody().get("officialCommitId")).isEqualTo(r.getBody().get("currentCommitId"));

        List tree = callList("/documents/" + documentId + "/files", writerToken).getBody();
        assertThat(tree).anySatisfy(f -> {
            Map m = (Map) f;
            assertThat(m.get("path")).isEqualTo("main.tex");
            assertThat(m.get("isRoot")).isEqualTo(true);
        });

        List commits = callList("/documents/" + documentId + "/versioning/commits", writerToken).getBody();
        assertThat(commits).hasSize(1);
        Map c = (Map) commits.get(0);
        assertThat(c.get("revision")).isEqualTo(1);
        assertThat(c.get("isDraft")).isEqualTo(false);
        v1 = (String) c.get("id");
    }

    @Test @Order(4)
    void firstEditOpensADraftAndKeepsV1Intact() {
        assumeTrue(postgres != null);
        ResponseEntity<Map> w = call(HttpMethod.PUT, "/documents/" + documentId + "/files/content",
                Map.of("path", "main.tex", "content", "\\documentclass{article}\n\\begin{document}\nOla v2 editado\n\\end{document}\n"), writerToken);
        assertThat(w.getStatusCode().value()).isEqualTo(200);
        draft = w.getHeaders().getFirst("X-Working-Commit");
        assertThat(draft).isNotNull().isNotEqualTo(v1);

        ResponseEntity<Map> w2 = call(HttpMethod.PUT, "/documents/" + documentId + "/files/content",
                Map.of("path", "capitulo.tex", "content", "Novo capitulo\n"), writerToken);
        assertThat(w2.getHeaders().getFirst("X-Working-Commit")).isEqualTo(draft);

        List commits = callList("/documents/" + documentId + "/versioning/commits", writerToken).getBody();
        assertThat(commits).hasSize(2);
        assertThat(((Map) commits.get(0)).get("isDraft")).isEqualTo(true);
        assertThat(((Map) commits.get(0)).get("revision")).isEqualTo(0);

        Map draftContent = call(HttpMethod.GET, "/documents/" + documentId + "/versioning/commits/" + draft + "/content", null, writerToken).getBody();
        assertThat((String) draftContent.get("content")).contains("Ola v2 editado");
        Map v1Content = call(HttpMethod.GET, "/documents/" + documentId + "/versioning/commits/" + v1 + "/content", null, writerToken).getBody();
        assertThat((String) v1Content.get("content")).contains("Ola v1").doesNotContain("editado");

        // A reader can look but not write.
        ResponseEntity<Map> denied = call(HttpMethod.PUT, "/documents/" + documentId + "/files/content",
                Map.of("path", "x.tex", "content", "x"), readerToken);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
    }

    @Test @Order(5)
    void diffOfDraftAgainstV1ListsFilesAndLines() {
        assumeTrue(postgres != null);
        Map diff = call(HttpMethod.GET, "/documents/" + documentId + "/files/diff?to=" + draft, null, readerToken).getBody();
        assertThat(diff.get("modified")).isEqualTo(1);
        assertThat(diff.get("added")).isEqualTo(1);
        List changes = (List) diff.get("changes");
        Map main = (Map) changes.stream().filter(c -> "main.tex".equals(((Map) c).get("path"))).findFirst().orElseThrow();
        assertThat(main.get("status")).isEqualTo("MODIFIED");
        assertThat((Integer) main.get("additions")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) main.get("deletions")).isGreaterThanOrEqualTo(1);

        Map fd = call(HttpMethod.GET, "/documents/" + documentId + "/files/diff/file?path=main.tex&to=" + draft, null, writerToken).getBody();
        List hunks = (List) fd.get("hunks");
        assertThat(hunks).isNotEmpty();
        List lines = (List) ((Map) hunks.get(0)).get("lines");
        assertThat(lines).anySatisfy(l -> {
            Map m = (Map) l;
            assertThat(m.get("kind")).isEqualTo("REMOVED");
            assertThat(m.get("oldLine")).isEqualTo(3);
        });
    }

    @Test @Order(6)
    void saveVersionClosesTheDraftAndRefusesEmptySaves() {
        assumeTrue(postgres != null);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("message", "Segunda versão");
        body.put("changeReason", "Teste de integração");
        body.put("latexSource", "unused");
        ResponseEntity<Map> r = call(HttpMethod.POST, "/documents/" + documentId + "/versioning/commits", body, writerToken);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        assertThat(r.getBody().get("id")).isEqualTo(draft);
        v2 = draft;

        List commits = callList("/documents/" + documentId + "/versioning/commits", writerToken).getBody();
        assertThat(commits).hasSize(2);
        Map head = (Map) commits.get(0);
        assertThat(head.get("isDraft")).isEqualTo(false);
        assertThat(head.get("revision")).isEqualTo(2);
        assertThat(head.get("changeReason")).isEqualTo("Teste de integração");

        ResponseEntity<Map> empty = call(HttpMethod.POST, "/documents/" + documentId + "/versioning/commits", body, writerToken);
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
    }

    @Test @Order(7)
    void editingAfterSaveOpensANewDraftThatCanBeDiscarded() {
        assumeTrue(postgres != null);
        ResponseEntity<Map> w = call(HttpMethod.PUT, "/documents/" + documentId + "/files/content",
                Map.of("path", "main.tex", "content", "rascunho"), writerToken);
        String draft2 = w.getHeaders().getFirst("X-Working-Commit");
        assertThat(draft2).isNotIn(v1, v2);

        Map v2Content = call(HttpMethod.GET, "/documents/" + documentId + "/versioning/commits/" + v2 + "/content", null, writerToken).getBody();
        assertThat((String) v2Content.get("content")).contains("Ola v2 editado");

        ResponseEntity<Map> d = call(HttpMethod.DELETE, "/documents/" + documentId + "/versioning/working", null, writerToken);
        assertThat(d.getStatusCode().value()).isEqualTo(204);
        List commits = callList("/documents/" + documentId + "/versioning/commits", writerToken).getBody();
        assertThat(commits).hasSize(2);
        assertThat(((Map) commits.get(0)).get("id")).isEqualTo(v2);
        Map doc = call(HttpMethod.GET, "/documents/" + documentId, null, writerToken).getBody();
        assertThat(doc.get("currentCommitId")).isEqualTo(v2);
    }

    @Test @Order(8)
    void zipDryRunPreviewsAndApplyLandsInTheDraft() throws IOException {
        assumeTrue(postgres != null);
        byte[] zip = zip(Map.of(
                "proj/main.tex", "\\documentclass{article}\n\\input{cap1}\n\\begin{document}\nOla v3 zip\n\\end{document}\n".getBytes(),
                "proj/cap1.tex", "Capitulo um\n".getBytes(),
                "proj/fig.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5, 6, 7, 8, 9}));

        ResponseEntity<Map> dry = upload("/documents/" + documentId + "/files/import-zip?dryRun=true", zip, writerToken);
        assertThat(dry.getStatusCode().value()).as(String.valueOf(dry.getBody())).isEqualTo(200);
        Map preview = (Map) dry.getBody().get("preview");
        List changes = (List) preview.get("changes");
        Map<String, String> status = new java.util.HashMap<>();
        for (Object c : changes) status.put((String) ((Map) c).get("path"), (String) ((Map) c).get("status"));
        assertThat(status).containsEntry("capitulo.tex", "REMOVED").containsEntry("cap1.tex", "ADDED")
                .containsEntry("fig.png", "ADDED").containsEntry("main.tex", "MODIFIED");
        assertThat((Map) dry.getBody().get("previewDiffs")).containsKey("main.tex");
        List commits = callList("/documents/" + documentId + "/versioning/commits", writerToken).getBody();
        assertThat(((Map) commits.get(0)).get("isDraft")).as("dry run writes nothing").isEqualTo(false);

        ResponseEntity<Map> apply = upload("/documents/" + documentId + "/files/import-zip", zip, writerToken);
        assertThat(apply.getStatusCode().value()).isEqualTo(200);
        assertThat(apply.getBody().get("workingCommit")).isNotNull();
        assertThat(apply.getBody().get("filesImported")).isEqualTo(3);

        ResponseEntity<Map> saved = call(HttpMethod.POST, "/documents/" + documentId + "/versioning/commits",
                Map.of("message", "Importado do Overleaf", "changeReason", "Atualização", "latexSource", ""), writerToken);
        assertThat(saved.getStatusCode().value()).isEqualTo(201);
        v3 = (String) saved.getBody().get("id");
    }

    @Test @Order(9)
    void reviewFixesTheVersionAndApprovalPutsItInForce() {
        assumeTrue(postgres != null);
        ResponseEntity<Map> submit = call(HttpMethod.POST, "/documents/" + documentId + "/reviews",
                Map.of("title", "Aprovar v3", "description", "IT"), writerToken);
        assertThat(submit.getStatusCode().value()).as(String.valueOf(submit.getBody())).isEqualTo(201);
        reviewId = (String) submit.getBody().get("id");

        assertThat(call(HttpMethod.POST, "/documents/" + documentId + "/reviews",
                Map.of("title", "dup"), writerToken).getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.PUT, "/documents/" + documentId + "/files/content",
                Map.of("path", "main.tex", "content", "x"), writerToken).getStatusCode().value()).isEqualTo(409);

        List open = callList("/reviews?status=OPEN", reviewerToken).getBody();
        Map item = (Map) open.stream().filter(r -> reviewId.equals(((Map) r).get("id"))).findFirst().orElseThrow();
        assertThat(item.get("reviewedCommitId")).isEqualTo(v3);
        assertThat(item.get("documentOfficialCommitId")).isEqualTo(v1);

        assertThat(call(HttpMethod.POST, "/reviews/" + reviewId + "/decision",
                Map.of("decision", "REJECTED", "comments", ""), reviewerToken).getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/reviews/" + reviewId + "/decision",
                Map.of("decision", "APPROVED", "comments", "ok"), writerToken).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/reviews/" + reviewId + "/decision",
                Map.of("decision", "APPROVED", "comments", "ok"), reviewerToken).getStatusCode().value()).isEqualTo(200);

        Map doc = call(HttpMethod.GET, "/documents/" + documentId, null, writerToken).getBody();
        assertThat(doc.get("officialCommitId")).isEqualTo(v3);
        assertThat(doc.get("status")).isEqualTo("ACTIVE");
        assertThat(doc.get("nextReviewDate")).isNotNull();

        assertThat(call(HttpMethod.POST, "/documents/" + documentId + "/reviews",
                Map.of("title", "nada"), writerToken).getStatusCode().value()).as("nothing changed").isEqualTo(409);
    }

    @Test @Order(10)
    void restoreAppendsAVersionWithTheOldTree() {
        assumeTrue(postgres != null);
        ResponseEntity<Map> r = call(HttpMethod.POST, "/documents/" + documentId + "/versioning/commits/" + v1 + "/restore", Map.of(), writerToken);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        v4 = (String) r.getBody().get("id");

        List commits = callList("/documents/" + documentId + "/versioning/commits", writerToken).getBody();
        Map head = (Map) commits.get(0);
        assertThat(head.get("revision")).isEqualTo(4);
        assertThat(head.get("fileCount")).isEqualTo(1);
        assertThat(commits.stream().anyMatch(c -> v3.equals(((Map) c).get("id")) && Boolean.TRUE.equals(((Map) c).get("isOfficial")))).isTrue();
        Map content = call(HttpMethod.GET, "/documents/" + documentId + "/versioning/commits/" + v4 + "/content", null, writerToken).getBody();
        assertThat((String) content.get("content")).contains("Ola v1");

        ResponseEntity<Map> elect = call(HttpMethod.PUT, "/documents/" + documentId + "/official-version/" + v4, null, reviewerToken);
        assertThat(elect.getStatusCode().value()).isEqualTo(200);
        assertThat(elect.getBody().get("officialCommitId")).isEqualTo(v4);
    }

    @Test @Order(11)
    void listAndStorageReportTheDocument() {
        assumeTrue(postgres != null);
        Map page = call(HttpMethod.GET, "/documents?size=50", null, adminToken).getBody();
        List content = (List) page.get("content");
        Map row = (Map) content.stream().filter(d -> documentId.equals(((Map) d).get("id"))).findFirst().orElseThrow();
        assertThat(row.get("versionCount")).isEqualTo(4);
        assertThat(row.get("hasDraft")).isEqualTo(false);

        ResponseEntity<Map> ov = call(HttpMethod.GET, "/storage/overview", null, adminToken);
        assertThat(ov.getStatusCode().value()).as(String.valueOf(ov.getBody())).isEqualTo(200);
        assertThat(((Number) ov.getBody().get("databaseBytes")).longValue()).isPositive();
        assertThat((List) ov.getBody().get("tables")).hasSizeGreaterThan(5);
        assertThat(((Number) ov.getBody().get("blobs")).longValue()).isPositive();

        assertThat(call(HttpMethod.GET, "/storage/overview", null, writerToken).getStatusCode().value()).isEqualTo(403);

        Map per = call(HttpMethod.GET, "/storage/documents/" + documentId, null, writerToken).getBody();
        assertThat(per.get("versions")).isEqualTo(4);
        assertThat(((Number) per.get("logicalBytes")).longValue()).isGreaterThanOrEqualTo(((Number) per.get("storedBytes")).longValue());

        Map gc = call(HttpMethod.POST, "/storage/gc", null, adminToken).getBody();
        assertThat(gc.get("removed")).as("grace period keeps fresh orphans").isEqualTo(0);

        ResponseEntity<String> csv = http.exchange(url("/documents/master-list"), HttpMethod.GET, new HttpEntity<>(null, auth(adminToken)), String.class);
        assertThat(csv.getBody()).contains("Codigo;");
    }
}
