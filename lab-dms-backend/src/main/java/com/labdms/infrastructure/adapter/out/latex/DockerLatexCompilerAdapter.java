package com.labdms.infrastructure.adapter.out.latex;

import com.labdms.domain.port.out.LatexCompilerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LaTeX compilation engine.
 *
 * Execution modes:
 * - "local"  : runs the TeX engine inside this container (the backend image
 *              ships TeX Live + latexmk). Security relies on -no-shell-escape,
 *              input sanitization and container isolation.
 * - "docker" : spawns a hardened ephemeral container through the host Docker
 *              daemon (requires docker.sock and a shared /tmp/latex-compile).
 * - "auto"   : local when a TeX engine is on PATH, docker otherwise.
 *
 * Engine selection (local mode): latexmk when available — it reruns the
 * compiler as needed so \tableofcontents, \ref, \pageref and LastPage resolve
 * — otherwise pdflatex is invoked twice.
 *
 * Identical sources are served from a small in-memory cache, which makes
 * repeated editor previews instant.
 */
@Component
public class DockerLatexCompilerAdapter implements LatexCompilerPort {

    private static final Logger logger = LoggerFactory.getLogger(DockerLatexCompilerAdapter.class);

    @Value("${latex.mode:auto}")
    private String mode;

    @Value("${latex.docker-image:lab-dms-latex:custom}")
    private String dockerImage;

    @Value("${latex.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${latex.max-memory:512m}")
    private String maxMemory;

    @Value("${latex.cache-size:32}")
    private int cacheSize;

    @Value("${latex.max-concurrent:2}")
    private int maxConcurrent;

    private static final String TEMP_BASE_DIR = "/tmp/latex-compile";

    /** The pdflatex invocation used both as the primary engine and as the fallback. */
    private static final List<String> PDFLATEX_RUN = List.of(
            "pdflatex", "-no-shell-escape", "-interaction=nonstopmode", "-file-line-error",
            "-output-directory=.", "document.tex");

    /**
     * Caps how many TeX processes run at the same time. Each one is CPU- and
     * memory-hungry; letting every HTTP thread start its own is what turned a
     * few simultaneous previews into cascading timeouts.
     */
    private static java.util.concurrent.Semaphore ENGINE_SLOTS;

    /** PATH probes are expensive (they fork a process), so they run once per JVM. */
    private volatile Boolean pdflatexAvailable;
    private volatile Boolean latexmkAvailable;

    @jakarta.annotation.PostConstruct
    void init() {
        ENGINE_SLOTS = new java.util.concurrent.Semaphore(Math.max(1, maxConcurrent), true);
        // Leftovers from a previous container run would otherwise accumulate in
        // /tmp forever and eventually fill the filesystem.
        purgeStaleWorkDirs();
    }

    /**
     * Engine errors in every shape they appear:
     *   "! Undefined control sequence."         classic
     *   "./document.tex:12: Missing $ inserted"  with -file-line-error
     *   "l.12 \badcommand"                       source line marker
     */
    private static final Pattern ERROR_LINE = Pattern.compile(
            "^(!.*|l\\.\\d+.*|.*\\.(tex|sty|cls|def|ldf|cfg):\\d+:.*)$", Pattern.MULTILINE);
    private static final Pattern PAGES = Pattern.compile("Output written on .*?\\((\\d+) pages?");

    /** Bounded LRU cache of successful compilations, keyed by source hash. */
    private final Map<String, LatexCompilationResult> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LatexCompilationResult> eldest) {
                    return size() > Math.max(1, cacheSize);
                }
            });

    @Override
    public LatexCompilationResult compile(String sanitizedLatexSource) {
        String key = sha256(sanitizedLatexSource);
        LatexCompilationResult cached = cache.get(key);
        if (cached != null) {
            logger.debug("LaTeX cache hit for {}", key.substring(0, 8));
            return cached;
        }
        return compileIn(key, "document.tex", workDir ->
                Files.writeString(workDir.resolve("document.tex"), sanitizedLatexSource, StandardCharsets.UTF_8));
    }

    /**
     * Compiles a whole project directory.
     *
     * <p>A real LaTeX document is a tree — the reference project has 42 files
     * across three folders — so {@code \input{fixos/pacotes}} and
     * {@code \includegraphics{figuras/capa}} only resolve if every file is on
     * disk, laid out exactly as authored, before the engine starts.
     */
    @Override
    public LatexCompilationResult compileProject(String cacheKey, String rootFile,
                                                  WorkDirPreparer preparer) {
        LatexCompilationResult cached = cache.get(cacheKey);
        if (cached != null) {
            logger.debug("LaTeX cache hit for project {}", cacheKey.substring(0, 8));
            return cached;
        }
        return compileIn(cacheKey, rootFile, preparer);
    }

    private static String fileNameOnly(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Refuses a root file name the engine would read as an option.
     *
     * <p>Command arguments are positional, so a file called {@code -shell-escape}
     * is not a file to pdflatex — it is a flag, and that particular one switches
     * arbitrary command execution back on. The name reaches us from an uploaded
     * ZIP or from "set as root", both user-controlled.
     */
    private static String safeRootArgument(String rootFile) {
        String name = rootFile;
        if (name.startsWith("-")) {
            throw new IllegalArgumentException(
                    "A file name may not start with '-': " + rootFile);
        }
        // "./" makes it unambiguous that this is a path, not an option, even if
        // a future change lets a leading dash slip through.
        return name.startsWith("./") ? name : "./" + name;
    }

    private LatexCompilationResult compileIn(String key, String rootFile, WorkDirPreparer preparer) {
        String compileId = UUID.randomUUID().toString();
        Path workDir = Paths.get(TEMP_BASE_DIR, compileId);
        // The engine writes its output into the working directory ROOT, named
        // after the root file WITHOUT its folders: "cap/main.tex" produces
        // "main.pdf", not "cap/main.pdf". Deriving the name from the full path
        // made every project whose root sits in a subfolder look like it had
        // failed, because the code looked for a PDF that was never going to be
        // at that path. Verified by running latexmk -outdir=. on a nested root.
        String baseName = fileNameOnly(rootFile).replaceAll("(?i)\\.tex$", "");

        try {
            Files.createDirectories(workDir);
            preparer.prepare(workDir);

            boolean useLocal = resolveUseLocal();
            boolean latexmk = useLocal && hasLatexmk();
            logger.info("LaTeX compilation started (mode={}, engine={}, id={})",
                    useLocal ? "local" : "docker", latexmk ? "latexmk" : "pdflatex", compileId);

            // Only a bounded number of TeX processes may run at once. Without this,
            // a handful of simultaneous previews spawn enough pdflatex processes to
            // starve the container of CPU/RAM, and compilations start timing out for
            // everyone — the "sometimes it works, sometimes it doesn't" symptom.
            if (!ENGINE_SLOTS.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
                return new LatexCompilationResult(false, null,
                        "The compilation queue is busy. Please try again.",
                        List.of("Compilation queue busy — too many simultaneous compilations"), 0);
            }
            String fullLog;
            try {
                List<List<String>> runs = useLocal
                        ? localRuns(latexmk, rootFile)
                        : List.of(dockerCommand(workDir, rootFile));
                fullLog = runEngine(runs, workDir, compileId, baseName);

                // Fallback: latexmk can fail for reasons that have nothing to do with
                // the user's document (a stale aux file, a latexmk/perl hiccup). If it
                // produced no PDF, retry once with plain pdflatex before giving up, so
                // a valid document always renders.
                if (latexmk && !hasPdf(workDir, baseName)) {
                    logger.warn("latexmk produced no PDF (id={}), retrying with pdflatex", compileId);
                    clearIntermediateFiles(workDir, baseName);
                    fullLog = runEngine(List.of(pdflatexRun(rootFile), pdflatexRun(rootFile)),
                            workDir, compileId, baseName) + "\n" + fullLog;
                }
            } finally {
                ENGINE_SLOTS.release();
            }

            if (fullLog == null) {   // timed out
                logger.error("LaTeX compilation timed out (id={})", compileId);
                return new LatexCompilationResult(false, null,
                        "Compilation timed out after " + timeoutSeconds + " seconds.",
                        List.of("Timeout after " + timeoutSeconds + "s — check for an unclosed environment "
                                + "or a \\newcommand that calls itself"), 0);
            }

            // Success is decided by the produced PDF: the engine may exit non-zero
            // on recoverable errors while still emitting a valid document.
            Path pdfPath = workDir.resolve(baseName + ".pdf");
            if (Files.exists(pdfPath) && Files.size(pdfPath) > 0) {
                byte[] pdfBytes = Files.readAllBytes(pdfPath);
                // Errors are reported even on success so the editor can warn
                // "rendered, but with N problems" (Overleaf behaviour).
                List<String> warnings = hasErrorMarkers(fullLog) ? extractErrors(fullLog) : List.of();
                LatexCompilationResult result =
                        new LatexCompilationResult(true, pdfBytes, capLog(fullLog), warnings, extractPages(fullLog));
                cache.put(key, result);
                logger.info("LaTeX compilation succeeded (id={}, {} bytes, {} pages, {} problems)",
                        compileId, pdfBytes.length, result.pages(), warnings.size());
                return result;
            }

            List<String> errors = extractErrors(fullLog);
            logger.warn("LaTeX compilation failed (id={}): {}", compileId,
                    errors.isEmpty() ? "no PDF produced" : errors.get(0));
            return new LatexCompilationResult(false, null, capLog(fullLog), errors, 0);

        } catch (Exception e) {
            logger.error("Error during LaTeX compilation", e);
            return new LatexCompilationResult(false, null,
                    "Internal compilation error: " + e.getMessage(),
                    List.of("Internal compilation error: " + e.getMessage()), 0);
        } finally {
            cleanupWorkDir(workDir);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Engine execution                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the engine commands in sequence and returns the combined diagnostics,
     * or {@code null} when the timeout was hit.
     *
     * <p>Engine output is redirected to a FILE rather than read from the process
     * pipe. Reading the pipe with {@code readAllBytes()} blocks until the child
     * exits, which made the subsequent {@code waitFor(timeout)} pointless: a TeX
     * process that hung (unclosed environment, recursive macro) blocked the HTTP
     * worker thread forever instead of timing out. Writing to a file also removes
     * the risk of the child stalling on a full pipe buffer.
     */
    private String runEngine(List<List<String>> runs, Path workDir, String compileId, String baseName) throws Exception {
        Path outFile = workDir.resolve("engine.out");
        String lastRunOutput = "";

        for (List<String> cmd : runs) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(outFile.toFile());
            // TeX wraps log lines at 79 columns by default, which cuts error
            // messages in half. Widening keeps diagnostics readable.
            pb.environment().put("max_print_line", "1000");
            pb.environment().put("error_line", "254");
            pb.environment().put("half_error_line", "238");

            // Paranoid file access. TeX's default is openin_any=a ("any"), which
            // lets \input{/etc/passwd} read ANY file the server user can read and
            // typeset it into the PDF — no shell escape required, so
            // -no-shell-escape does not stop it. Paranoid mode refuses absolute
            // paths, parent directories and dotfiles, which confines the engine
            // to the project it is compiling.
            //
            // This belongs here rather than in the LaTeX sanitizer because the
            // sanitizer can only inspect the strings it is given, while the tree
            // files written by materialise() reach the engine untouched — and
            // because \input has many aliases (\InputIfFileExists,
            // \input\detokenize{...}) that a regex will keep losing to. The
            // engine enforcing its own limits cannot be worded around.
            // Verified: \input{/etc/passwd} leaked into the PDF without this and
            // was refused with it, while a legitimate multi-file project with
            // \input, \includegraphics, a subfolder .sty and bibtex produced a
            // byte-identical PDF.
            pb.environment().put("openin_any", "p");
            pb.environment().put("openout_any", "p");
            pb.environment().put("shell_escape", "f");
            // Never wait for terminal input: without this a single missing brace
            // can leave the engine sitting at its "?" prompt until the timeout.
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

            Process process = pb.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // Kill the whole tree: latexmk spawns pdflatex as a child, and
                // destroying only the parent would leave the engine running.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return null;
            }
            lastRunOutput = readIfExists(outFile);   // keep the last run's output
        }

        // latexmk in force mode keeps engine errors in document.log and only
        // prints a summary to stdout, so the .log file is the authoritative
        // source for diagnostics (same approach Overleaf takes).
        String texLog = readIfExists(workDir.resolve(baseName + ".log"));
        return texLog.isBlank() ? lastRunOutput : texLog + "\n" + lastRunOutput;
    }

    private static boolean hasPdf(Path workDir, String baseName) {
        try {
            Path pdf = workDir.resolve(baseName + ".pdf");
            return Files.exists(pdf) && Files.size(pdf) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Removes engine leftovers before a fallback run. A stale .aux/.fls from the
     * failed attempt is itself a common cause of the retry failing too.
     */
    private static void clearIntermediateFiles(Path workDir, String baseName) {
        for (String ext : new String[]{".aux", ".log", ".out", ".toc", ".fls", ".fdb_latexmk", ".lof", ".lot"}) {
            try {
                Files.deleteIfExists(workDir.resolve(baseName + ext));
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Engine selection                                                    */
    /* ------------------------------------------------------------------ */

    private boolean resolveUseLocal() {
        if ("local".equalsIgnoreCase(mode)) return true;
        if ("docker".equalsIgnoreCase(mode)) return false;
        return hasPdflatex();   // auto
    }

    // Probing the PATH spawns a process. Doing it on every compilation (three
    // times, as the previous version did) added real latency to every preview,
    // so the answer is resolved once per JVM.
    private boolean hasPdflatex() {
        if (pdflatexAvailable == null) pdflatexAvailable = isOnPath("pdflatex");
        return pdflatexAvailable;
    }

    private boolean hasLatexmk() {
        if (latexmkAvailable == null) latexmkAvailable = isOnPath("latexmk");
        return latexmkAvailable;
    }

    private boolean isOnPath(String program) {
        try {
            Process p = new ProcessBuilder(program, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** pdflatex invocation for a given root file. Without latexmk we run it twice
     *  so the table of contents and \ref values settle. */
    private static List<String> pdflatexRun(String rootFile) {
        return List.of("pdflatex", "-no-shell-escape", "-interaction=nonstopmode",
                "-file-line-error", "-output-directory=.", safeRootArgument(rootFile));
    }

    private List<List<String>> localRuns(boolean latexmk, String rootFile) {
        // Relative paths (the process runs with cwd = workDir): TeX wraps log
        // lines at ~79 chars, so a long temp path would truncate the error text.
        // No -halt-on-error on purpose: like Overleaf, a document with
        // recoverable errors should still render, with the errors reported.
        if (latexmk) {
            return List.of(List.of(
                    "latexmk",
                    // -norc is a SECURITY control, not a preference. latexmk reads
                    // ./.latexmkrc from the directory it runs in and executes it as
                    // Perl, before compiling and regardless of -no-shell-escape. The
                    // working directory is filled with user-uploaded files, so
                    // without this a .latexmkrc in an imported ZIP is remote code
                    // execution on this host. Verified: a .latexmkrc calling
                    // system() ran without -norc and did not run with it.
                    "-norc",
                    "-pdf", "-interaction=nonstopmode",
                    "-no-shell-escape", "-file-line-error", "-f",
                    "-outdir=.", safeRootArgument(rootFile)));
        }
        return List.of(pdflatexRun(rootFile), pdflatexRun(rootFile));
    }

    private List<String> dockerCommand(Path workDir, String rootFile) {
        // The host daemon resolves -v paths on the HOST, so /tmp/latex-compile
        // must be bind-mounted at an identical path in this container.
        return List.of(
                "docker", "run", "--rm",
                "--network", "none",
                "--memory", maxMemory,
                "--read-only",
                "--tmpfs", "/tmp:size=100M",
                // Same paranoid file access as the local mode, since the tree
                // inside /data is user-controlled either way.
                "-e", "openin_any=p", "-e", "openout_any=p", "-e", "shell_escape=f",
                "-v", workDir.toAbsolutePath() + ":/data:rw",
                dockerImage,
                "pdflatex", "-no-shell-escape", "-interaction=nonstopmode", "-file-line-error",
                // The root used to be hardcoded to document.tex, so in docker mode
                // every multi-file project compiled a file that did not exist.
                "-output-directory=/data", "/data/" + safeRootArgument(rootFile).substring(2));
    }

    /* ------------------------------------------------------------------ */
    /* Log parsing                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * True when the log contains real engine errors (not just warnings).
     * With -file-line-error the "!" prefix is replaced by "file:line:", and the
     * failing file may be a package (.sty/.cls), not the user's document.
     */
    static boolean hasErrorMarkers(String log) {
        if (log == null) return false;
        return log.contains("\n! ")
                || log.startsWith("! ")
                || log.contains(" Error:")
                || log.matches("(?s).*\\.(tex|sty|cls|def|ldf|cfg):\\d+:.*");
    }

    /** Extracts the engine errors the user actually needs to fix. */
    static List<String> extractErrors(String log) {
        if (log == null || log.isBlank()) return List.of("No output from the LaTeX engine.");
        List<String> errors = new ArrayList<>();
        Matcher m = ERROR_LINE.matcher(log);
        while (m.find() && errors.size() < 20) {
            String line = cleanErrorLine(m.group().trim());
            if (!line.isEmpty() && !errors.contains(line)) errors.add(line);
        }
        if (errors.isEmpty()) {
            // No "!" markers: surface the tail so the user still sees something useful
            String[] lines = log.strip().split("\n");
            for (int i = Math.max(0, lines.length - 5); i < lines.length; i++) {
                if (!lines[i].isBlank()) errors.add(lines[i].trim());
            }
        }
        return errors;
    }

    /**
     * Replaces the internal working path with "line N:" so the user sees
     * "line 12: Undefined control sequence" instead of a temp directory.
     */
    private static String cleanErrorLine(String line) {
        return line
                // the user's own document: "line 12: message"
                .replaceAll("^.*document\\.tex:(\\d+):\\s*", "line $1: ")
                // a package/class file: keep its name — "babel.sty:4254: message"
                .replaceAll("^.*?([A-Za-z0-9_-]+\\.(?:sty|cls|def|ldf|cfg)):(\\d+):\\s*", "$1:$2: ")
                .replaceAll("^l\\.(\\d+)\\s*", "line $1: ")
                .trim();
    }

    static int extractPages(String log) {
        if (log == null) return 0;
        Matcher m = PAGES.matcher(log);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Reads an engine log, tolerating invalid UTF-8.
     *
     * <p>TeX logs are NOT UTF-8. They mix the document's encoding with Latin-1
     * bytes emitted by the engine itself — Portuguese hyphenation patterns alone
     * produce raw 0xE7 ("ç"). {@code Files.readString(path, UTF_8)} throws
     * MalformedInputException on the first such byte, and the previous version
     * swallowed that and returned "". The effect was silent and severe: for any
     * Portuguese document the log was discarded, so the page count was always 0
     * and — far worse — a failed compilation reported NO error message at all,
     * because there was no log left to parse.
     *
     * <p>Decoding with REPLACE keeps every diagnostic line intact; at worst a
     * single accented character in a message renders as a placeholder.
     */
    private static String readIfExists(Path path) {
        try {
            if (!Files.exists(path)) return "";
            byte[] raw = Files.readAllBytes(path);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            return decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception e) {
            return "";   // logs are diagnostics only; never fail the compilation for them
        }
    }

    /** Keeps API responses small: the tail is where the useful output lives. */
    private static String capLog(String log) {
        int max = 20_000;
        return log.length() <= max ? log : "...(truncated)...\n" + log.substring(log.length() - max);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * Deletes work directories left behind by a previous run (container killed
     * mid-compilation). Without this they pile up in /tmp until the filesystem
     * is full and every compilation starts failing.
     */
    private void purgeStaleWorkDirs() {
        Path base = Paths.get(TEMP_BASE_DIR);
        if (!Files.isDirectory(base)) return;
        try (var dirs = Files.list(base)) {
            dirs.filter(Files::isDirectory).forEach(this::cleanupWorkDir);
        } catch (IOException e) {
            logger.warn("Could not purge stale LaTeX work directories", e);
        }
    }

    private void cleanupWorkDir(Path workDir) {
        try {
            if (Files.exists(workDir)) {
                try (var paths = Files.walk(workDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            logger.warn("Could not delete file during cleanup: {}", path);
                        }
                    });
                }
            }
        } catch (IOException e) {
            logger.warn("Could not cleanup LaTeX compile directory: {}", workDir, e);
        }
    }
}
