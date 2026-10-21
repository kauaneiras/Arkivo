package com.labdms.shared.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TextDiffTest {

    private static String replay(TextDiff.Result r, TextDiff.Kind keep) {
        return r.hunks().stream().flatMap(h -> h.lines().stream())
                .filter(l -> l.kind() == keep || l.kind() == TextDiff.Kind.CONTEXT)
                .map(TextDiff.Line::text)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void identicalTextsHaveNoHunks() {
        TextDiff.Result r = TextDiff.diff("a\nb\nc\n", "a\nb\nc\n");
        assertThat(r.hunks()).isEmpty();
        assertThat(r.additions()).isZero();
        assertThat(r.deletions()).isZero();
    }

    @Test
    void countsAdditionsAndDeletions() {
        TextDiff.Result r = TextDiff.diff("a\nb\nc\n", "a\nx\nc\nd\n");
        assertThat(r.additions()).isEqualTo(2);
        assertThat(r.deletions()).isEqualTo(1);
    }

    @Test
    void lineNumbersFollowEachSide() {
        TextDiff.Result r = TextDiff.diff("one\ntwo\nthree", "one\n2\nthree");
        List<TextDiff.Line> lines = r.hunks().get(0).lines();
        assertThat(lines).extracting(TextDiff.Line::kind).containsExactly(
                TextDiff.Kind.CONTEXT, TextDiff.Kind.REMOVED, TextDiff.Kind.ADDED, TextDiff.Kind.CONTEXT);
        assertThat(lines.get(1).oldLine()).isEqualTo(2);
        assertThat(lines.get(1).newLine()).isNull();
        assertThat(lines.get(2).oldLine()).isNull();
        assertThat(lines.get(2).newLine()).isEqualTo(2);
        assertThat(lines.get(3).oldLine()).isEqualTo(3);
        assertThat(lines.get(3).newLine()).isEqualTo(3);
    }

    @Test
    void replayingTheScriptReproducesBothSides() {
        String old = "\\section{A}\nline 1\nline 2\nline 3\n\\section{B}\nend\n";
        String neu = "\\section{A}\nline 1\nline two\nline 3\ninserted\n\\section{B}\nend\n";
        TextDiff.Result r = TextDiff.diff(old, neu, -1);   // whole file, one hunk
        assertThat(replay(r, TextDiff.Kind.REMOVED)).isEqualTo(old.strip());
        assertThat(replay(r, TextDiff.Kind.ADDED)).isEqualTo(neu.strip());
    }

    @Test
    void contextSplitsDistantChangesIntoSeparateHunks() {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            a.append("l").append(i).append('\n');
            b.append(i == 5 ? "changed5" : i == 35 ? "changed35" : "l" + i).append('\n');
        }
        TextDiff.Result r = TextDiff.diff(a.toString(), b.toString());
        assertThat(r.hunks()).hasSize(2);
        assertThat(r.hunks().get(0).oldStart()).isEqualTo(2);
        assertThat(r.hunks().get(1).newStart()).isEqualTo(32);
    }

    @Test
    void emptyToSomethingIsAllAdditions() {
        TextDiff.Result r = TextDiff.diff("", "a\nb");
        assertThat(r.additions()).isEqualTo(2);
        assertThat(r.deletions()).isZero();
        assertThat(r.hunks().get(0).lines()).allMatch(l -> l.kind() == TextDiff.Kind.ADDED);
    }

    @Test
    void windowsLineEndingsAreNotADifference() {
        TextDiff.Result r = TextDiff.diff("a\r\nb\r\n", "a\nb\n");
        assertThat(r.hunks()).isEmpty();
    }

    @Test
    void hugeInputsAreReportedAsTruncatedInsteadOfComputed() {
        String big = "x\n".repeat(TextDiff.MAX_LINES + 1);
        TextDiff.Result r = TextDiff.diff(big, "y");
        assertThat(r.truncated()).isTrue();
        assertThat(r.hunks()).isEmpty();
    }
}
