package com.labdms.shared.util;

import com.labdms.domain.exception.LatexSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression suite for the sanitizer rewrite: the naive substring-matching
 * version used to false-positive on legitimate constructs (e.g. blocking
 * \readline because it contains "read", or blocking any relative \input).
 */
class InputSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "\\documentclass{article}\\begin{document}Hello\\end{document}",
            "\\input{secoes/introducao}",
            "\\include{capitulo1}",
            "\\includegraphics[width=0.5\\textwidth]{imagens/logo.png}",
            "\\newcommand{\\readline}{}", // contains "read" as substring, must NOT trip \read-style rules
            "\\begin{tabular}{c|c}A & B\\\\ 1 & 2\\end{tabular}",
            "$\\sigma_r = \\frac{F_{max}}{A_0}$",
            "% comentário sobre write, openin e include no meio do texto",
    })
    void allowsLegitimateConstructs(String source) {
        assertThat(InputSanitizer.sanitizeLatex(source)).isEqualTo(source);
    }

    @Test
    void blocksShellEscape() {
        assertBlocked("\\immediate\\write18{rm -rf /}", "shell command execution");
    }

    @Test
    void blocksLuaExecution() {
        assertBlocked("\\directlua{os.execute('ls')}", "Lua code execution");
    }

    @Test
    void blocksDirectFileAccess() {
        assertBlocked("\\openout1=secret.txt", "direct file access");
    }

    @Test
    void blocksShellPipeInFileName() {
        assertBlocked("\\input{|/bin/sh}", "shell pipe in file name");
    }

    @Test
    void blocksParentDirectoryTraversal() {
        assertBlocked("\\input{../../../etc/passwd}", "parent-directory traversal");
    }

    @Test
    void blocksAbsolutePaths() {
        assertBlocked("\\include{/etc/passwd}", "absolute file path");
    }

    @Test
    void blocksObfuscatedInputViaCsname() {
        assertBlocked("\\csname @@input\\endcsname{/etc/passwd}", "obfuscated file inclusion");
    }

    @Test
    void rejectsBlankSource() {
        assertThatThrownBy(() -> InputSanitizer.sanitizeLatex("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorDetailIncludesLineNumber() {
        // getMessage() is a fixed i18n key ("latex.security.violation"); the
        // human-readable command/reason/line lives in getDetail().
        String source = "\\documentclass{article}\n\\begin{document}\n\\write18{ls}\n\\end{document}";
        assertThatThrownBy(() -> InputSanitizer.sanitizeLatex(source))
                .isInstanceOf(LatexSecurityException.class)
                .extracting(ex -> ((LatexSecurityException) ex).getDetail())
                .satisfies(detail -> assertThat((String) detail).contains("line 3"));
    }

    private void assertBlocked(String source, String expectedReason) {
        assertThatThrownBy(() -> InputSanitizer.sanitizeLatex(source))
                .isInstanceOf(LatexSecurityException.class)
                .extracting(ex -> ((LatexSecurityException) ex).getDetail())
                .satisfies(detail -> assertThat((String) detail).contains(expectedReason));
    }
}
