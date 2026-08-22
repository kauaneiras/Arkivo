package com.labdms.application.service;

import com.labdms.infrastructure.adapter.out.storage.BlobStore;
import com.labdms.infrastructure.adapter.out.storage.EpsConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an Overleaf ZIP export into a project file tree.
 *
 * <p>Sizing comes from a real export measured for this feature: 42 files,
 * 1.08 MB zipped, 15.9 MB unpacked — a 15:1 ratio that is entirely normal for
 * LaTeX (EPS figures are plain PostScript and compress ~99%). That ratio is
 * exactly why the unpacked size has to be enforced while reading, not from the
 * ZIP's own headers, which an attacker controls.
 */
@Service
public class ProjectImportService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectImportService.class);

    public static final long MAX_PROJECT_BYTES = 200L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILES = 2000;

    /** Editor artefacts and build output — importing them adds noise, not content. */
    private static final Pattern IGNORED = Pattern.compile(
            "(^|/)(__MACOSX/|\\.DS_Store$|Thumbs\\.db$|\\.git/|\\.gitignore$)"
            + "|\\.(aux|log|out|toc|lof|lot|synctex\\.gz|fls|fdb_latexmk|bbl|blg|nav|snm|vrb)$",
            Pattern.CASE_INSENSITIVE);

    private final BlobStore blobStore;
    private final EpsConverter epsConverter;

    public ProjectImportService(BlobStore blobStore, EpsConverter epsConverter) {
        this.blobStore = blobStore;
        this.epsConverter = epsConverter;
    }

    public record ImportedFile(String path, String sha256, long sizeBytes, boolean isText,
                                String contentType, String derivedFrom) {}

    public record ImportedProject(List<ImportedFile> files, String rootPath,
                                   List<String> rootCandidates, long totalBytes, int skipped) {}

    /**
     * Reads the archive and stores every file as a blob.
     *
     * @param preferredRoot path the caller already knows should be the root, or
     *                      null to detect it
     */
    public ImportedProject importZip(byte[] zipBytes, String preferredRoot) {
        List<ImportedFile> files = new ArrayList<>();
        List<String> rootCandidates = new ArrayList<>();
        // old .eps path -> new .pdf path, so the \includegraphics that point at
        // the converted files can be repointed afterwards
        java.util.Map<String, String> converted = new java.util.LinkedHashMap<>();
        long total = 0;
        int skipped = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String path = normalisePath(entry.getName());
                if (path == null || IGNORED.matcher(path).find()) {
                    skipped++;
                    continue;
                }
                if (files.size() >= MAX_FILES) {
                    throw new IllegalArgumentException(
                            "The archive has more than " + MAX_FILES + " files");
                }

                // Read with a hard cap instead of trusting entry.getSize(), which
                // comes from the archive and can lie (zip bomb).
                byte[] content = readCapped(zip, MAX_FILE_BYTES, path);
                total += content.length;
                if (total > MAX_PROJECT_BYTES) {
                    throw new IllegalArgumentException("The project exceeds the limit of "
                            + (MAX_PROJECT_BYTES / 1024 / 1024) + " MB when unpacked");
                }

                String contentType = guessContentType(path);
                String derivedFrom = null;

                // EPS cannot be read by pdflatex, and the package that converts it
                // needs shell-escape, which stays off. Converting here — once, on
                // the server — keeps the compiler locked down. Measured on the
                // reference project: 11.84 MB EPS became a 1.45 MB PDF.
                if (path.toLowerCase().endsWith(".eps")) {
                    byte[] pdf = epsConverter.toPdf(content, path);
                    if (pdf != null) {
                        logger.info("Converted {} from EPS to PDF ({} -> {} bytes)",
                                path, content.length, pdf.length);
                        derivedFrom = path;
                        total = total - content.length + pdf.length;
                        content = pdf;
                        String pdfPath = path.substring(0, path.length() - 4) + ".pdf";
                        converted.put(path, pdfPath);
                        path = pdfPath;
                        contentType = "application/pdf";
                    }
                }

                var blob = blobStore.store(content, path, contentType);
                files.add(new ImportedFile(path, blob.sha256(), blob.sizeBytes(),
                        blob.isText(), contentType, derivedFrom));

                if (blob.isText() && path.toLowerCase().endsWith(".tex")
                        && looksLikeRoot(blobStore.readText(blob.sha256()))) {
                    rootCandidates.add(path);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the ZIP: " + e.getMessage());
        }

        if (files.isEmpty()) {
            throw new IllegalArgumentException("The archive has no usable files");
        }

        files = repointConvertedGraphics(files, converted);

        // Strip the wrapper folder BEFORE choosing the root, not after. Both
        // Overleaf and GitHub wrap an export in a single folder, so the caller
        // used to compare a root of "MyThesis/main.tex" against stored paths of
        // "main.tex": nothing matched, no file was marked as the root, and
        // compilation silently fell back to the first .tex in alphabetical
        // order — "bkpmain.tex", the backup, for the project this was built
        // against. Choosing from the same paths that get stored is the fix.
        String prefix = commonPrefix(files);
        if (!prefix.isEmpty()) {
            files = stripPrefix(files, prefix);
            rootCandidates = rootCandidates.stream()
                    .map(c -> c.startsWith(prefix) ? c.substring(prefix.length()) : c)
                    .toList();
        }

        return new ImportedProject(files, chooseRoot(rootCandidates, preferredRoot, files),
                rootCandidates, total, skipped);
    }

    /** The single top-level folder shared by every entry, or "" when there is none. */
    private static String commonPrefix(List<ImportedFile> files) {
        if (files.size() < 2) return "";
        String first = files.get(0).path();
        if (!first.contains("/")) return "";
        String prefix = first.substring(0, first.indexOf('/') + 1);
        return files.stream().allMatch(f -> f.path().startsWith(prefix)) ? prefix : "";
    }

    private static List<ImportedFile> stripPrefix(List<ImportedFile> files, String prefix) {
        return files.stream()
                .map(f -> new ImportedFile(f.path().substring(prefix.length()), f.sha256(),
                        f.sizeBytes(), f.isText(), f.contentType(), f.derivedFrom()))
                .toList();
    }

    /**
     * Points {@code \includegraphics} at the converted files.
     *
     * <p>Converting {@code figuras/capa.eps} to {@code figuras/capa.pdf} renames
     * the file, but the source still asks for the {@code .eps}. The engine then
     * reports "File `figuras/capa.eps' not found" and, because a missing graphic
     * is not a fatal error, the document still compiles — just without the
     * figure. Measured on the reference project: the two EPS figures vanished
     * from a 52-page PDF that otherwise looked correct, which is exactly the
     * kind of silent loss a quality system must not have.
     *
     * <p>Rewriting happens here, once at import, rather than at compile time, so
     * what the user sees in the editor is what the engine reads.
     */
    private List<ImportedFile> repointConvertedGraphics(
            List<ImportedFile> files, java.util.Map<String, String> converted) {

        if (converted.isEmpty()) return files;

        List<ImportedFile> result = new ArrayList<>(files.size());
        for (ImportedFile file : files) {
            if (!file.isText() || !file.path().matches("(?i).*\\.(tex|sty|cls)$")) {
                result.add(file);
                continue;
            }

            String source = blobStore.readText(file.sha256());
            String rewritten = source;
            for (var entry : converted.entrySet()) {
                String oldPath = entry.getKey();
                String newPath = entry.getValue();
                rewritten = rewritten.replace(oldPath, newPath);
                // The source may reference the figure relative to its own folder
                // ("capa.eps" from inside figuras/), so the bare name matters too.
                String oldName = oldPath.substring(oldPath.lastIndexOf('/') + 1);
                String newName = newPath.substring(newPath.lastIndexOf('/') + 1);
                if (!oldName.equals(oldPath)) {
                    rewritten = rewritten.replace(oldName, newName);
                }
            }

            if (rewritten.equals(source)) {
                result.add(file);
                continue;
            }

            byte[] bytes = rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var blob = blobStore.store(bytes, file.path(), file.contentType());
            logger.info("Repointed graphics references in {}", file.path());
            result.add(new ImportedFile(file.path(), blob.sha256(), blob.sizeBytes(),
                    blob.isText(), file.contentType(), file.derivedFrom()));
        }
        return result;
    }

    /**
     * Picks the file latexmk should compile.
     *
     * <p>The reference project contains TWO files with {@code \documentclass}
     * (main.tex and bkpmain.tex), so "the one that has it" is not a decision
     * procedure. Preference order: what the user asked for, then the
     * conventional names, then the shallowest candidate — and the caller still
     * gets the full candidate list to offer a choice.
     */
    private String chooseRoot(List<String> candidates, String preferred, List<ImportedFile> files) {
        if (preferred != null && files.stream().anyMatch(f -> f.path().equals(preferred))) {
            return preferred;
        }
        if (candidates.isEmpty()) {
            return files.stream().filter(f -> f.path().toLowerCase().endsWith(".tex"))
                    .map(ImportedFile::path).findFirst().orElse(files.get(0).path());
        }
        for (String conventional : List.of("main.tex", "principal.tex", "master.tex", "root.tex", "tese.tex")) {
            for (String c : candidates) {
                if (c.equalsIgnoreCase(conventional)) return c;
            }
        }
        return candidates.stream()
                .min((a, b) -> Integer.compare(depth(a), depth(b)))
                .orElse(candidates.get(0));
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static boolean looksLikeRoot(String content) {
        return Pattern.compile("^\\s*\\\\documentclass", Pattern.MULTILINE).matcher(content).find();
    }

    /**
     * Rejects path traversal ("zip slip") and strips the single wrapper folder
     * that Overleaf and GitHub put around an export.
     */
    static String normalisePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String path = raw.replace('\\', '/').replaceAll("^/+", "");
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return null;
        }
        while (path.contains("//")) path = path.replace("//", "/");
        if (path.isBlank()) return null;
        // Delegate to the single hardened normaliser so the ZIP path and the
        // per-file path cannot drift apart in what they consider safe. It
        // rejects ".." segments, leading dashes (argument injection into the TeX
        // engine), names the compiler executes (.latexmkrc, texmf.cnf) and
        // over-long paths — the last one mattering because a path longer than
        // the column width used to abort an import halfway through.
        try {
            return ProjectFileService.normalisePath(path);
        } catch (IllegalArgumentException e) {
            logger.warn("Skipping unsafe entry from archive: {} ({})", raw, e.getMessage());
            return null;
        }
    }

    /**
     * Removes a common top-level folder so "MyThesis/main.tex" imports as
     * "main.tex". Applied inside {@link #importZip} before the root is chosen;
     * callers receive paths that are already stripped.
     */
    public static List<ImportedFile> stripCommonPrefix(List<ImportedFile> files) {
        String prefix = commonPrefix(files);
        return prefix.isEmpty() ? files : stripPrefix(files, prefix);
    }

    private static byte[] readCapped(InputStream in, long max, String path) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            read += n;
            if (read > max) {
                throw new IllegalArgumentException("The file " + path + " exceeds "
                        + (max / 1024 / 1024) + " MB");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Exposed so single-file uploads get the same EPS treatment as ZIP imports. */
    public byte[] convertEps(byte[] content, String fileName) {
        return epsConverter.toPdf(content, fileName);
    }

    public static String guessContentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".eps")) return "application/postscript";
        if (p.endsWith(".bib")) return "text/x-bibtex";
        if (p.endsWith(".tex") || p.endsWith(".cls") || p.endsWith(".sty")) return "text/x-tex";
        return "application/octet-stream";
    }
}
