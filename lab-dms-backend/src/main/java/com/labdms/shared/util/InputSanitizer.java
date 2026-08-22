package com.labdms.shared.util;

import com.labdms.domain.exception.LatexSecurityException;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks LaTeX constructs that could escape the compilation sandbox.
 *
 * Design notes:
 * - Matching is done with command-boundary regexes, never naive substring
 *   containment: "\readline" must not trip a rule meant for "\read", and
 *   "\includegraphics" must not trip a rule meant for "\include".
 * - Legitimate authoring constructs stay allowed (relative \input/\include,
 *   \includegraphics, tables with "/" and "\\", accents, math).
 * - Every rule carries a human-readable reason so the UI can explain exactly
 *   what was rejected instead of showing an opaque error.
 *
 * Static utility — intentionally not a Spring bean.
 */
public final class InputSanitizer {

    /** A forbidden construct: detection pattern + explanation shown to the user. */
    private record Rule(Pattern pattern, String command, String reason) {}

    // (?<![a-zA-Z]) / (?![a-zA-Z]) keep the match on a whole control sequence.
    private static final List<Rule> RULES = List.of(
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\(immediate\\s*)?\\\\?write\\s*18(?![0-9])", Pattern.CASE_INSENSITIVE),
                "\\write18", "shell command execution"),
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\(directlua|latelua|luaexec)(?![a-zA-Z])", Pattern.CASE_INSENSITIVE),
                "\\directlua", "Lua code execution"),
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\(openin|openout)(?![a-zA-Z])", Pattern.CASE_INSENSITIVE),
                "\\openin / \\openout", "direct file access"),
        // \input{|"cmd"} pipes the file name through a shell
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\(input|include|includegraphics|openin)\\s*(\\[[^]]*])?\\s*\\{\\s*\\|", Pattern.CASE_INSENSITIVE),
                "\\input{|...}", "shell pipe in file name"),
        // Absolute paths or parent-directory traversal in file inclusion
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\(input|include|includegraphics)\\s*(\\[[^]]*])?\\s*\\{[^}]*\\.\\.[^}]*}", Pattern.CASE_INSENSITIVE),
                "\\input{../...}", "parent-directory traversal"),
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\(input|include|includegraphics)\\s*(\\[[^]]*])?\\s*\\{\\s*/", Pattern.CASE_INSENSITIVE),
                "\\input{/...}", "absolute file path"),
        // \csname @@input\endcsname smuggles \input past naive filters
        new Rule(Pattern.compile("(?<![a-zA-Z])\\\\csname\\s*@@input", Pattern.CASE_INSENSITIVE),
                "\\csname @@input", "obfuscated file inclusion")
    );

    private InputSanitizer() {
        // utility class
    }

    /**
     * Validates the LaTeX source and returns it unchanged when it is safe.
     *
     * @throws IllegalArgumentException  if the source is empty
     * @throws LatexSecurityException    if a forbidden construct is found
     */
    public static String sanitizeLatex(String latexSource) {
        if (latexSource == null || latexSource.isBlank()) {
            throw new IllegalArgumentException("LaTeX source cannot be empty");
        }

        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(latexSource);
            if (matcher.find()) {
                int line = countLines(latexSource, matcher.start());
                throw new LatexSecurityException(
                        rule.command() + " (" + rule.reason() + ") at line " + line);
            }
        }

        return latexSource;
    }

    private static int countLines(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }
}
