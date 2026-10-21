package com.labdms.application.service;

import com.labdms.infrastructure.adapter.out.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The file tree of a project, and how it becomes something latexmk can compile.
 *
 * <p>A commit owns a complete tree: every path present at that revision, each
 * pointing at a content-addressed blob. Storing the whole tree per commit (not a
 * delta) is what makes any past revision restorable on its own — which ISO 17025
 * requires, and which deduplication makes cheap: a commit that edits one .tex
 * writes one small blob and re-points the other rows at blobs that already exist.
 */
@Service
public class ProjectFileService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectFileService.class);

    private final JdbcTemplate jdbcTemplate;
    private final BlobStore blobStore;

    public ProjectFileService(JdbcTemplate jdbcTemplate, BlobStore blobStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.blobStore = blobStore;
    }

    public record ProjectFile(UUID id, String path, String sha256, long sizeBytes,
                               boolean isText, String contentType, boolean isRoot, String derivedFrom) {}

    /** Name given to the root file when a document's text is first written as a tree. */
    public static final String DEFAULT_ROOT = "main.tex";

    /* --------------------------- reading the tree --------------------------- */

    @Transactional(readOnly = true)
    public List<ProjectFile> listFiles(UUID commitId) {
        return jdbcTemplate.query("""
                SELECT id, path, blob_sha256, size_bytes, is_text, content_type, is_root, derived_from
                  FROM project_files WHERE commit_id = ? ORDER BY path
                """, (rs, n) -> new ProjectFile(
                        rs.getObject("id", UUID.class), rs.getString("path"),
                        rs.getString("blob_sha256"), rs.getLong("size_bytes"),
                        rs.getBoolean("is_text"), rs.getString("content_type"),
                        rs.getBoolean("is_root"), rs.getString("derived_from")),
                commitId);
    }

    @Transactional(readOnly = true)
    public boolean hasFiles(UUID commitId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_files WHERE commit_id = ?", Integer.class, commitId);
        return n != null && n > 0;
    }

    /** Path of the file that gets compiled, or null when the commit has no tree. */
    @Transactional(readOnly = true)
    public String rootPath(UUID commitId) {
        List<String> root = jdbcTemplate.query(
                "SELECT path FROM project_files WHERE commit_id = ? AND is_root",
                (rs, n) -> rs.getString(1), commitId);
        if (!root.isEmpty()) return root.get(0);
        List<String> anyTex = jdbcTemplate.query(
                "SELECT path FROM project_files WHERE commit_id = ? AND path LIKE '%.tex' ORDER BY path LIMIT 1",
                (rs, n) -> rs.getString(1), commitId);
        return anyTex.isEmpty() ? null : anyTex.get(0);
    }

    /**
     * Text of the root file, used as the version's content snapshot so diffs and
     * the plain-text history keep working for projects that are a whole tree.
     */
    @Transactional(readOnly = true)
    public String readRootText(UUID commitId) {
        String path = rootPath(commitId);
        return path == null ? null : readTextFile(commitId, path);
    }

    @Transactional(readOnly = true)
    public String readTextFile(UUID commitId, String path) {
        List<String> sha = jdbcTemplate.query(
                "SELECT blob_sha256 FROM project_files WHERE commit_id = ? AND path = ? AND is_text",
                (rs, n) -> rs.getString(1), commitId, path);
        if (sha.isEmpty()) throw new IllegalArgumentException("Text file not found: " + path);
        return blobStore.readText(sha.get(0));
    }

    @Transactional(readOnly = true)
    public byte[] readBinaryFile(UUID commitId, String path) {
        List<String> sha = jdbcTemplate.query(
                "SELECT blob_sha256 FROM project_files WHERE commit_id = ? AND path = ?",
                (rs, n) -> rs.getString(1), commitId, path);
        if (sha.isEmpty()) throw new IllegalArgumentException("File not found: " + path);
        return blobStore.read(sha.get(0));
    }

    /* --------------------------- writing the tree --------------------------- */

    /**
     * Copies the whole tree of {@code fromCommit} onto {@code toCommit}.
     *
     * <p>Rows are duplicated, blobs are not: the new rows point at the same
     * content. This is what makes "save a new version" cost only the bytes that
     * actually changed.
     */
    @Transactional
    public void copyTree(UUID fromCommit, UUID toCommit) {
        jdbcTemplate.update("""
                INSERT INTO project_files
                    (id, commit_id, path, blob_sha256, size_bytes, is_text, content_type, is_root, derived_from)
                SELECT gen_random_uuid(), ?, path, blob_sha256, size_bytes, is_text, content_type, is_root, derived_from
                  FROM project_files WHERE commit_id = ?
                """, toCommit, fromCommit);
    }

    /**
     * Makes {@code toCommit}'s tree an exact copy of {@code fromCommit}'s.
     *
     * <p>Used by restore: the draft's current rows are dropped and the restored
     * version's rows copied in, in one transaction, so the draft is never seen
     * half-way between the two.
     */
    @Transactional
    public void replaceTreeFromCommit(UUID toCommit, UUID fromCommit) {
        jdbcTemplate.update("DELETE FROM project_files WHERE commit_id = ?", toCommit);
        copyTree(fromCommit, toCommit);
        invalidateCompiledPdf(toCommit);
    }

    /**
     * Removes everything that hangs off a commit about to be deleted: its tree
     * and its compiled PDF. Blobs stay — they are content-addressed and may be
     * shared; the garbage collector reclaims the unreferenced ones later.
     */
    @Transactional
    public void dropCommitArtifacts(UUID commitId) {
        jdbcTemplate.update("DELETE FROM project_files WHERE commit_id = ?", commitId);
        invalidateCompiledPdf(commitId);
    }

    /** Adds or replaces one path in a commit's tree. */
    @Transactional
    public void putFile(UUID commitId, String path, byte[] content, String contentType, boolean isRoot) {
        String normalised = normalisePath(path);
        var blob = blobStore.store(content, normalised, contentType);

        // Saving a file must not silently demote it. This replaces the row, so
        // without carrying the flag over, pressing Save on the root file cleared
        // is_root — and compilation then fell back to "first .tex alphabetically",
        // quietly switching the document of record to another file.
        boolean wasRoot = Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT is_root FROM project_files WHERE commit_id = ? AND path = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, commitId, normalised));
        boolean keepRoot = isRoot || wasRoot;

        jdbcTemplate.update("DELETE FROM project_files WHERE commit_id = ? AND path = ?",
                commitId, normalised);

        if (keepRoot) {
            // The partial unique index allows only one root per commit; clear the
            // old one first so the swap does not violate it.
            jdbcTemplate.update("UPDATE project_files SET is_root = false WHERE commit_id = ?", commitId);
        }

        jdbcTemplate.update("""
                INSERT INTO project_files
                    (id, commit_id, path, blob_sha256, size_bytes, is_text, content_type, is_root)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), commitId, normalised, blob.sha256(), blob.sizeBytes(),
                blob.isText(), contentType, keepRoot);
        invalidateCompiledPdf(commitId);
    }

    @Transactional
    public void deleteFile(UUID commitId, String path) {
        String normalised = normalisePath(path);
        boolean wasRoot = Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT is_root FROM project_files WHERE commit_id = ? AND path = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, commitId, normalised));
        if (wasRoot) {
            throw new IllegalStateException(
                    "This is the file the project compiles from. Choose another root file before deleting it.");
        }
        int n = jdbcTemplate.update("DELETE FROM project_files WHERE commit_id = ? AND path = ?",
                commitId, normalised);
        if (n == 0) throw new IllegalArgumentException("File not found: " + path);
        invalidateCompiledPdf(commitId);
    }

    /**
     * Escapes the LIKE metacharacters in a literal prefix.
     *
     * <p>{@code _} matches any single character in SQL LIKE, and folder names
     * with underscores are ordinary in a LaTeX project ({@code cap_1/}). Without
     * escaping, renaming {@code cap_1/} also rewrote {@code cap01/} and
     * {@code capX1/}, breaking every {@code \input} that pointed at them —
     * silent corruption with no error. Used with ESCAPE '\' in the query.
     */
    private static String likeLiteral(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Drops the stored PDF of a version whose tree just changed.
     *
     * <p>{@code compiled_pdfs} is keyed by commit and is returned as-is when the
     * status is SUCCESS, so without this the editor keeps serving the PDF that
     * was compiled before the change. Called from every mutator rather than
     * from the controller: the mutators are the only way a tree changes, so the
     * invalidation cannot be forgotten at a new call site.
     */
    private void invalidateCompiledPdf(UUID commitId) {
        jdbcTemplate.update("DELETE FROM compiled_pdfs WHERE commit_id = ?", commitId);
    }

    /** One entry of a tree being written wholesale. */
    public record NewFile(String path, String sha256, long sizeBytes, boolean isText,
                           String contentType, String derivedFrom) {}

    /**
     * Replaces a version's whole tree in ONE transaction.
     *
     * <p>Importing an export means "this is the project now", so the old tree
     * goes and the new one lands. The transaction is the point: the controller
     * used to DELETE the tree and then INSERT the new rows one autocommit at a
     * time, so any failure part-way — a duplicate path, a database hiccup —
     * left the user with their previous project already destroyed and the new
     * one half-written, with no way back. Either the whole tree lands or none
     * of it does.
     *
     * @return the number of files stored
     */
    @Transactional
    public int replaceTree(UUID commitId, List<NewFile> files, String rootPath) {
        jdbcTemplate.update("DELETE FROM project_files WHERE commit_id = ?", commitId);

        // Last one wins on a repeated path. A ZIP can legitimately produce one:
        // converting "figuras/capa.eps" to PDF collides with a "figuras/capa.pdf"
        // that was already in the archive. Letting that reach the unique index
        // would abort the import.
        var byPath = new java.util.LinkedHashMap<String, NewFile>();
        for (NewFile f : files) byPath.put(normalisePath(f.path()), f);

        boolean rootExists = rootPath != null && byPath.containsKey(rootPath);
        for (var entry : byPath.entrySet()) {
            String path = entry.getKey();
            NewFile f = entry.getValue();
            jdbcTemplate.update("""
                    INSERT INTO project_files
                        (id, commit_id, path, blob_sha256, size_bytes, is_text, content_type, is_root, derived_from)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), commitId, path, f.sha256(), f.sizeBytes(),
                    f.isText(), f.contentType(), rootExists && path.equals(rootPath), f.derivedFrom());
        }

        // Never leave a project with no root: without one, compilation falls back
        // to "first .tex in alphabetical order", which is how a backup file ends
        // up being the document of record.
        if (!rootExists) {
            byPath.keySet().stream().filter(p -> p.toLowerCase().endsWith(".tex")).findFirst()
                    .ifPresent(p -> jdbcTemplate.update(
                            "UPDATE project_files SET is_root = true WHERE commit_id = ? AND path = ?",
                            commitId, p));
        }
        invalidateCompiledPdf(commitId);
        return byPath.size();
    }

    /** Renames a file, or a whole folder when the path ends in '/'. */
    @Transactional
    public int renamePath(UUID commitId, String from, String to) {
        String f = normalisePath(from), t = normalisePath(to);
        if (f.endsWith("/") || isFolder(commitId, f)) {
            String prefix = f.endsWith("/") ? f : f + "/";
            String newPrefix = t.endsWith("/") ? t : t + "/";
            int moved = jdbcTemplate.update("""
                    UPDATE project_files SET path = ? || SUBSTRING(path FROM ?)
                     WHERE commit_id = ? AND path LIKE ? ESCAPE '\\'
                    """, newPrefix, prefix.length() + 1, commitId, likeLiteral(prefix) + "%");
            invalidateCompiledPdf(commitId);
            return moved;
        }
        int n = jdbcTemplate.update(
                "UPDATE project_files SET path = ? WHERE commit_id = ? AND path = ?", t, commitId, f);
        if (n == 0) throw new IllegalArgumentException("File not found: " + from);
        invalidateCompiledPdf(commitId);
        return n;
    }

    private boolean isFolder(UUID commitId, String path) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_files WHERE commit_id = ? AND path LIKE ? ESCAPE '\\'",
                Integer.class, commitId, likeLiteral(path + "/") + "%");
        return n != null && n > 0;
    }

    @Transactional
    public void setRoot(UUID commitId, String path) {
        jdbcTemplate.update("UPDATE project_files SET is_root = false WHERE commit_id = ?", commitId);
        int n = jdbcTemplate.update(
                "UPDATE project_files SET is_root = true WHERE commit_id = ? AND path = ?",
                commitId, normalisePath(path));
        if (n == 0) throw new IllegalArgumentException("File not found: " + path);
        invalidateCompiledPdf(commitId);
    }

    /**
     * A fingerprint of the tree's current contents.
     *
     * <p>The compilation cache used to be keyed on the commit id alone, on the
     * assumption that a commit is immutable. That is not true of the version
     * being edited: uploading a figure, deleting a file or changing the root all
     * mutate the tree inside the same commit. The result was the editor serving
     * the PREVIOUS PDF after a change — in a quality system, a record that does
     * not match its source. Hashing (path, blob, root) makes the key change
     * exactly when the compiled output could change.
     */
    @Transactional(readOnly = true)
    public String treeFingerprint(UUID commitId) {
        StringBuilder sb = new StringBuilder();
        jdbcTemplate.query("""
                SELECT path, blob_sha256, is_root FROM project_files
                 WHERE commit_id = ? ORDER BY path
                """, rs -> {
            sb.append(rs.getString(1)).append(':').append(rs.getString(2))
              .append(rs.getBoolean(3) ? ":R\n" : "\n");
        }, commitId);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    public long totalSize(UUID commitId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(size_bytes), 0) FROM project_files WHERE commit_id = ?",
                Long.class, commitId);
        return total == null ? 0 : total;
    }

    /* ------------------------- preparing to compile ------------------------- */

    /**
     * Writes the entire tree to a working directory so latexmk can resolve
     * {@code \input}, {@code \includegraphics} and {@code \bibliography}
     * relative to the root file — which is the whole point of supporting
     * multiple files.
     *
     * @return the root file's name, relative to {@code workDir}
     */
    public String materialise(UUID commitId, Path workDir) throws IOException {
        List<ProjectFile> files = listFiles(commitId);
        if (files.isEmpty()) throw new IllegalStateException("This version has no files");

        String rootPath = files.stream().filter(ProjectFile::isRoot)
                .map(ProjectFile::path).findFirst()
                .orElseGet(() -> files.stream().filter(f -> f.path().endsWith(".tex"))
                        .map(ProjectFile::path).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No .tex file to compile")));

        // One query for every text blob instead of one per file: on the
        // reference project that is 1 round-trip instead of 35 per compilation.
        Map<String, byte[]> texts = blobStore.readTextBatch(
                files.stream().filter(ProjectFile::isText).map(ProjectFile::sha256).toList());

        for (ProjectFile f : files) {
            Path target = workDir.resolve(f.path()).normalize();
            // Defence in depth: the path was sanitised on the way in, but this
            // directory is about to be handed to an external process.
            if (!target.startsWith(workDir)) {
                throw new IOException("Invalid path in the tree: " + f.path());
            }
            Files.createDirectories(target.getParent());
            byte[] bytes = f.isText() ? texts.get(f.sha256()) : null;
            Files.write(target, bytes != null ? bytes : blobStore.read(f.sha256()));
        }

        logger.debug("Materialised {} files for commit {} (root: {})", files.size(), commitId, rootPath);
        return rootPath;
    }

    /* ------------------------------- helpers -------------------------------- */

    /**
     * File names the compiler treats as instructions rather than content.
     *
     * <p>latexmk executes {@code .latexmkrc} as Perl before compiling, and
     * kpathsea reads {@code texmf.cnf} to decide what the engine is allowed to
     * do — including whether shell escape is on. Both are read from the working
     * directory, which is filled with whatever the user uploaded, so neither may
     * ever enter a project tree. The engine is also locked down at run time
     * ({@code -norc}, {@code openin_any=p}); this is the second lock, so that
     * one mistake in either place is not enough.
     */
    private static final java.util.regex.Pattern FORBIDDEN_NAMES =
            java.util.regex.Pattern.compile(
                    "(^|/)(\\.?latexmkrc|texmf\\.cnf|\\.tex(mf)?rc|ls-R)$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** True when this file may never be stored, whatever the user calls it. */
    public static boolean isForbiddenName(String path) {
        return path != null && FORBIDDEN_NAMES.matcher(path).find();
    }

    public static String normalisePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        String path = raw.replace('\\', '/').replaceAll("^/+", "").trim();
        while (path.contains("//")) path = path.replace("//", "/");
        if (path.isBlank() || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Invalid path: " + raw);
        }
        // Reject the ".." SEGMENT, not the two-character sequence: names like
        // "resultados..v2.png" are legitimate and were being refused, while the
        // traversal that actually matters is a whole segment.
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException("Invalid path: " + raw);
            }
            // A leading dash turns a file name into a command-line option for
            // the TeX engine (e.g. "-shell-escape").
            if (segment.startsWith("-")) {
                throw new IllegalArgumentException(
                        "A file or folder name may not start with '-': " + raw);
            }
        }
        if (isForbiddenName(path)) {
            throw new IllegalArgumentException(
                    "This file name is not allowed because the compiler would execute it: " + raw);
        }
        if (path.length() > 1000) throw new IllegalArgumentException("Path is too long");
        return path;
    }
}
