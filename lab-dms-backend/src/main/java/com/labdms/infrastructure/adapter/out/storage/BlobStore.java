package com.labdms.infrastructure.adapter.out.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Content-addressed storage for project files.
 *
 * <p>Every blob is keyed by the SHA-256 of its ORIGINAL bytes, so the same
 * content is stored once no matter how many commits or projects reference it.
 * On the reference project (a real thesis: 42 files, 15.9 MB) that is the
 * dominant saving — editing one .tex creates one new small blob while the
 * ~2 MB of figures stay shared across every revision.
 *
 * <p>Where a blob lives depends on what it is, and the split was measured
 * rather than guessed:
 * <ul>
 *   <li><b>Text</b> (.tex, .bib, .cls) — 0.9% of the bytes. Kept in Postgres:
 *       small, diffable, searchable, and written in the same transaction as the
 *       tree. TOAST compresses it transparently.</li>
 *   <li><b>Binary</b> (images, PDFs) — 99.1% of the bytes. Kept on the volume,
 *       so the database and its dumps stay small.</li>
 * </ul>
 *
 * <p>Compression is applied only when it earns its keep. Measured ratios on the
 * reference project: EPS 99%, .tex 71%, PNG 17%, JPEG 1%. Compressing an
 * already-compressed image costs CPU and saves nothing, so a blob is only kept
 * compressed when it shrinks by more than {@value #MIN_COMPRESSION_GAIN}%.
 */
@Component
public class BlobStore {

    private static final Logger logger = LoggerFactory.getLogger(BlobStore.class);

    /** Below this saving, storing the compressed form is not worth the CPU. */
    private static final int MIN_COMPRESSION_GAIN = 10;

    /** Files this large are streamed to disk regardless of type. */
    private static final int MAX_INLINE_TEXT_BYTES = 2 * 1024 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final Path baseDir;

    public BlobStore(JdbcTemplate jdbcTemplate,
                     @Value("${attachments.storage-dir:/app/attachments}") String storageDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.baseDir = Path.of(storageDir, "blobs");
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the blob directory: " + this.baseDir, e);
        }
    }

    public record StoredBlob(String sha256, long sizeBytes, boolean isText, String contentType) {}

    /**
     * Stores the bytes and returns their identity. If the content already
     * exists, nothing is written — the existing blob is reused.
     */
    public StoredBlob store(byte[] content, String fileName, String contentType) {
        String sha = sha256(content);

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_blobs WHERE sha256 = ?", Integer.class, sha);
        if (exists != null && exists > 0) {
            // Deduplicated: this is the case that makes 50 revisions of a
            // project cost roughly one project's worth of storage.
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT size_bytes, is_text, content_type FROM file_blobs WHERE sha256 = ?", sha);
            return new StoredBlob(sha, ((Number) row.get("size_bytes")).longValue(),
                    Boolean.TRUE.equals(row.get("is_text")), (String) row.get("content_type"));
        }

        boolean isText = looksLikeText(content, fileName) && content.length <= MAX_INLINE_TEXT_BYTES;

        if (isText) {
            jdbcTemplate.update("""
                    INSERT INTO file_blobs (sha256, size_bytes, content_text, compression,
                                            stored_bytes, content_type, is_text)
                    VALUES (?, ?, ?, 'NONE', ?, ?, true)
                    ON CONFLICT (sha256) DO NOTHING
                    """, sha, content.length, new String(content, StandardCharsets.UTF_8),
                    content.length, contentType);
        } else {
            byte[] compressed = gzip(content);
            // long arithmetic: content.length * 90 overflows int above ~23.8 MB,
            // and MAX_FILE_BYTES is 50 MB, so the comparison silently inverted
            // for exactly the large files where compression matters most.
            boolean worthCompressing =
                    compressed.length < (long) content.length * (100 - MIN_COMPRESSION_GAIN) / 100;
            byte[] toWrite = worthCompressing ? compressed : content;
            String compression = worthCompressing ? "GZIP" : "NONE";

            String relative = sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha
                    + (worthCompressing ? ".gz" : "");
            try {
                Path target = resolve(relative);
                Files.createDirectories(target.getParent());
                Files.write(target, toWrite);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write the blob " + sha, e);
            }

            // ON CONFLICT: two imports of the same figure at the same moment both
            // pass the COUNT(*) above; the second INSERT must not abort an import.
            jdbcTemplate.update("""
                    INSERT INTO file_blobs (sha256, size_bytes, storage_path, compression,
                                            stored_bytes, content_type, is_text)
                    VALUES (?, ?, ?, ?, ?, ?, false)
                    ON CONFLICT (sha256) DO NOTHING
                    """, sha, content.length, relative, compression, toWrite.length, contentType);

            if (worthCompressing) {
                logger.debug("Blob {} compressed {}% ({} -> {} bytes)", sha.substring(0, 8),
                        100 - (100 * toWrite.length / Math.max(1, content.length)),
                        content.length, toWrite.length);
            }
        }

        return new StoredBlob(sha, content.length, isText, contentType);
    }

    /** Returns the ORIGINAL bytes, transparently decompressing when needed. */
    public byte[] read(String sha256) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content_text, storage_path, compression, is_text FROM file_blobs WHERE sha256 = ?",
                sha256);

        if (Boolean.TRUE.equals(row.get("is_text"))) {
            return ((String) row.get("content_text")).getBytes(StandardCharsets.UTF_8);
        }
        try {
            byte[] stored = Files.readAllBytes(resolve((String) row.get("storage_path")));
            return "GZIP".equals(row.get("compression")) ? gunzip(stored) : stored;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the blob " + sha256, e);
        }
    }

    public String readText(String sha256) {
        return new String(read(sha256), StandardCharsets.UTF_8);
    }

    /**
     * Reads many TEXT blobs in one query.
     *
     * <p>Materialising a project for compilation, and diffing two trees, used
     * to read blobs one by one: 43 round-trips for the 42-file reference
     * project, on every preview. One {@code = ANY(?)} does it in one. Binary
     * blobs are not included — they live on disk and are read individually.
     *
     * @return sha → bytes, only for the hashes that exist as text
     */
    public Map<String, byte[]> readTextBatch(List<String> shas) {
        Map<String, byte[]> out = new java.util.HashMap<>();
        if (shas == null || shas.isEmpty()) return out;
        // Chunked so a 2 000-file project never builds a parameter array
        // Postgres refuses.
        for (int i = 0; i < shas.size(); i += 500) {
            List<String> chunk = shas.subList(i, Math.min(shas.size(), i + 500));
            jdbcTemplate.query(con -> {
                var ps = con.prepareStatement(
                        "SELECT sha256, content_text FROM file_blobs WHERE is_text AND sha256 = ANY(?)");
                ps.setArray(1, con.createArrayOf("varchar", chunk.toArray()));
                return ps;
            }, rs -> {
                out.put(rs.getString(1), rs.getString(2).getBytes(StandardCharsets.UTF_8));
            });
        }
        return out;
    }

    /** One orphaned blob, as reported before collection. */
    public record Orphan(String sha256, long sizeBytes, long storedBytes, boolean isText) {}

    /**
     * Blobs no commit references any more, older than the grace period.
     *
     * <p>The grace period is the whole point. An import stores its blobs
     * before the rows that reference them exist, so a blob written one second
     * ago is "unreferenced" and still about to be used. Only blobs older than
     * the window are candidates, which makes the collector safe to run at any
     * time without a global lock.
     */
    public List<Orphan> findOrphans(int graceHours) {
        return jdbcTemplate.query("""
                SELECT b.sha256, b.size_bytes, COALESCE(b.stored_bytes, b.size_bytes) AS stored, b.is_text
                  FROM file_blobs b
                 WHERE b.created_at < NOW() - make_interval(hours => ?)
                   AND NOT EXISTS (SELECT 1 FROM project_files f WHERE f.blob_sha256 = b.sha256)
                """, (rs, n) -> new Orphan(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getBoolean(4)),
                graceHours);
    }

    /**
     * Deletes blobs no commit references any more.
     *
     * <p>Order matters: the ROW goes first, the FILE second. If a concurrent
     * import references the blob between the two steps, the foreign key makes
     * the DELETE fail and nothing is lost. The previous order — file first —
     * could leave a row pointing at bytes that were already gone, which for a
     * figure of an approved document is irrecoverable.
     *
     * @return number of blobs removed
     */
    public int collectGarbage(int graceHours) {
        int removed = 0;
        for (Orphan o : findOrphans(graceHours)) {
            int rows;
            try {
                rows = jdbcTemplate.update("""
                        DELETE FROM file_blobs b WHERE b.sha256 = ?
                           AND NOT EXISTS (SELECT 1 FROM project_files f WHERE f.blob_sha256 = b.sha256)
                        """, o.sha256());
            } catch (org.springframework.dao.DataIntegrityViolationException referencedMeanwhile) {
                continue;   // a commit took it in the last instant; keep it
            }
            if (rows == 0) continue;
            if (!o.isText()) {
                String relative = o.sha256().substring(0, 2) + "/" + o.sha256().substring(2, 4) + "/" + o.sha256();
                for (String candidate : List.of(relative + ".gz", relative)) {
                    try {
                        Files.deleteIfExists(resolve(candidate));
                    } catch (IOException e) {
                        logger.warn("Could not delete the blob file {}", candidate);
                    }
                }
            }
            removed++;
        }
        if (removed > 0) logger.info("Blob garbage collection removed {} unreferenced blob(s)", removed);
        return removed;
    }

    /* ----------------------------- internals ---------------------------- */

    private Path resolve(String relative) {
        Path resolved = baseDir.resolve(relative).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid blob path");
        }
        return resolved;
    }

    /**
     * A file is treated as text when its extension says so AND the bytes agree.
     * The extension alone is not enough: a .tex that is actually binary would
     * corrupt on the UTF-8 round trip into the database.
     */
    static boolean looksLikeText(byte[] content, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        boolean textExtension = lower.endsWith(".tex") || lower.endsWith(".bib") || lower.endsWith(".cls")
                || lower.endsWith(".sty") || lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".bst") || lower.endsWith(".cfg") || lower.endsWith(".def")
                || lower.endsWith(".ldf") || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".gitignore")
                || lower.endsWith("makefile");
        if (!textExtension) return false;

        // A NUL byte never appears in valid UTF-8 text.
        int limit = Math.min(content.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) return false;
        }

        // The bytes must be valid UTF-8, not merely free of NULs. Text blobs are
        // stored as a String and read back with getBytes(UTF_8), so a Latin-1
        // .tex — routine in older Brazilian theses using inputenc[latin1] — had
        // every accented character replaced by U+FFFD on the way in. That is
        // silent, irreversible corruption of a controlled document, and it also
        // breaks content addressing: the bytes read back no longer hash to the
        // sha256 they are filed under. Anything that is not clean UTF-8 is kept
        // byte-for-byte as a binary blob instead.
        return isValidUtf8(content);
    }

    private static boolean isValidUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
            gz.finish();
            return out.toByteArray();
        } catch (IOException e) {
            return data;   // never fail a save because compression misbehaved
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
