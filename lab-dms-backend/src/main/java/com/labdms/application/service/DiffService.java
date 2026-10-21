package com.labdms.application.service;

import com.labdms.infrastructure.adapter.out.storage.BlobStore;
import com.labdms.shared.util.TextDiff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What changed between two trees, file by file and line by line.
 *
 * <p>This is the "Changes" and "History" view of GitHub Desktop, computed on
 * the server so that every screen — the editor, the submission dialog, the
 * approval dialog, the ZIP preview — shows the same answer. Equality is by
 * SHA-256 of the content, never by size or date; two files with the same hash
 * are the same file, and that is what lets "unchanged" be said with certainty.
 */
@Service
public class DiffService {

    /** Text files bigger than this get a status but no line diff. */
    private static final int MAX_TEXT_DIFF_BYTES = 2 * 1024 * 1024;

    private final ProjectFileService projectFiles;
    private final BlobStore blobStore;

    public DiffService(ProjectFileService projectFiles, BlobStore blobStore) {
        this.projectFiles = projectFiles;
        this.blobStore = blobStore;
    }

    public enum Status { ADDED, REMOVED, MODIFIED, UNCHANGED }

    /**
     * One file of a comparison.
     *
     * @param additions lines added (text files only, else 0)
     * @param deletions lines removed (text files only, else 0)
     */
    public record FileChange(String path, Status status, boolean isText,
                             long sizeBefore, long sizeAfter,
                             String shaBefore, String shaAfter,
                             int additions, int deletions) {}

    public record TreeDiff(UUID from, UUID to, List<FileChange> changes,
                           int added, int removed, int modified, int unchanged,
                           int additions, int deletions) {}

    /** A single file's line diff, in the shape the viewer draws. */
    public record FileDiff(String path, Status status, boolean isText, boolean truncated,
                           int additions, int deletions, List<TextDiff.Hunk> hunks) {}

    /** The side of a comparison: path → (sha, size, isText). */
    public record Entry(String sha256, long sizeBytes, boolean isText) {}

    /* ----------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public TreeDiff compareCommits(UUID from, UUID to) {
        return compare(from, to, treeOf(from), treeOf(to));
    }

    /**
     * Compares a stored tree with a tree that is not stored anywhere (the
     * files of a ZIP read in dry-run mode). The blobs are already in the store,
     * so line counts can be computed the same way.
     */
    @Transactional(readOnly = true)
    public TreeDiff compareWithCandidate(UUID from, Map<String, Entry> candidate) {
        return compare(from, null, treeOf(from), candidate);
    }

    @Transactional(readOnly = true)
    public FileDiff fileDiff(UUID from, UUID to, String path) {
        Map<String, Entry> antes = treeOf(from);
        Map<String, Entry> depois = treeOf(to);
        return fileDiff(antes.get(path), depois.get(path), path);
    }

    /** Line diff between two blobs, for the ZIP preview where "to" is not a commit. */
    @Transactional(readOnly = true)
    public FileDiff fileDiff(Entry before, Entry after, String path) {
        if (before == null && after == null) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        Status status = before == null ? Status.ADDED
                : after == null ? Status.REMOVED
                : Objects.equals(before.sha256(), after.sha256()) ? Status.UNCHANGED : Status.MODIFIED;
        boolean isText = (after != null ? after : before).isText();
        if (!isText || status == Status.UNCHANGED) {
            return new FileDiff(path, status, isText, false, 0, 0, List.of());
        }
        if ((before != null && before.sizeBytes() > MAX_TEXT_DIFF_BYTES)
                || (after != null && after.sizeBytes() > MAX_TEXT_DIFF_BYTES)) {
            return new FileDiff(path, status, true, true, 0, 0, List.of());
        }
        String a = before == null ? "" : blobStore.readText(before.sha256());
        String b = after == null ? "" : blobStore.readText(after.sha256());
        TextDiff.Result r = TextDiff.diff(a, b);
        return new FileDiff(path, status, true, r.truncated(), r.additions(), r.deletions(), r.hunks());
    }

    /* ----------------------------------------------------------------- */

    private Map<String, Entry> treeOf(UUID commitId) {
        if (commitId == null) return Map.of();
        return projectFiles.listFiles(commitId).stream().collect(Collectors.toMap(
                ProjectFileService.ProjectFile::path,
                f -> new Entry(f.sha256(), f.sizeBytes(), f.isText()),
                (x, y) -> x));
    }

    private TreeDiff compare(UUID from, UUID to, Map<String, Entry> antes, Map<String, Entry> depois) {
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(antes.keySet());
        paths.addAll(depois.keySet());

        // Text blobs of every changed text file, fetched in one query each side.
        Function<Map<String, Entry>, Map<String, byte[]>> load = tree -> blobStore.readTextBatch(
                tree.entrySet().stream()
                        .filter(e -> e.getValue().isText() && e.getValue().sizeBytes() <= MAX_TEXT_DIFF_BYTES)
                        .filter(e -> {
                            Entry other = (tree == antes ? depois : antes).get(e.getKey());
                            return other == null || !Objects.equals(other.sha256(), e.getValue().sha256());
                        })
                        .map(e -> e.getValue().sha256()).distinct().toList());
        Map<String, byte[]> textosAntes = load.apply(antes);
        Map<String, byte[]> textosDepois = load.apply(depois);

        List<FileChange> changes = new ArrayList<>();
        int a = 0, r = 0, m = 0, u = 0, adds = 0, dels = 0;
        for (String path : paths) {
            Entry v = antes.get(path);
            Entry n = depois.get(path);
            Status status;
            if (v == null) { status = Status.ADDED; a++; }
            else if (n == null) { status = Status.REMOVED; r++; }
            else if (!Objects.equals(v.sha256(), n.sha256())) { status = Status.MODIFIED; m++; }
            else { status = Status.UNCHANGED; u++; }

            boolean isText = (n != null ? n : v).isText();
            int additions = 0, deletions = 0;
            if (isText && status != Status.UNCHANGED) {
                byte[] before = v == null ? null : textosAntes.get(v.sha256());
                byte[] after = n == null ? null : textosDepois.get(n.sha256());
                boolean readable = (v == null || before != null) && (n == null || after != null);
                if (readable) {
                    int[] s = TextDiff.stats(
                            before == null ? "" : new String(before, java.nio.charset.StandardCharsets.UTF_8),
                            after == null ? "" : new String(after, java.nio.charset.StandardCharsets.UTF_8));
                    additions = s[0];
                    deletions = s[1];
                }
            }
            adds += additions;
            dels += deletions;
            changes.add(new FileChange(path, status, isText,
                    v == null ? 0 : v.sizeBytes(), n == null ? 0 : n.sizeBytes(),
                    v == null ? null : v.sha256(), n == null ? null : n.sha256(),
                    additions, deletions));
        }
        return new TreeDiff(from, to, changes, a, r, m, u, adds, dels);
    }
}
