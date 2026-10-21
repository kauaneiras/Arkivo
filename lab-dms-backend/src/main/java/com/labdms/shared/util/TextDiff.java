package com.labdms.shared.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Line-based diff of two texts, in the shape Git shows it.
 *
 * <p>Myers' O(ND) algorithm, so a 3 000-line chapter with a handful of edits
 * costs a few thousand steps rather than the nine million cells of an LCS
 * table. The output is a list of hunks with the old and new line numbers of
 * every line, which is what a viewer needs to draw the two gutters GitHub
 * Desktop draws.
 *
 * <p>Pure function, no Spring: it is used by the diff service and tested on
 * its own.
 */
public final class TextDiff {

    private TextDiff() {}

    /** What happened to one line. */
    public enum Kind { CONTEXT, ADDED, REMOVED }

    /**
     * One line of a hunk.
     *
     * @param oldLine 1-based line number in the old text, or null for ADDED
     * @param newLine 1-based line number in the new text, or null for REMOVED
     */
    public record Line(Kind kind, Integer oldLine, Integer newLine, String text) {}

    /** A group of changed lines with context around it, like a {@code @@} block. */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {}

    /** The whole comparison: hunks plus the totals a file list shows as +N −M. */
    public record Result(List<Hunk> hunks, int additions, int deletions, boolean truncated) {}

    /** Above this many lines on either side the diff is not computed line by line. */
    public static final int MAX_LINES = 20_000;

    /** Default number of unchanged lines kept around each change. */
    public static final int CONTEXT = 3;

    public static Result diff(String oldText, String newText) {
        return diff(oldText, newText, CONTEXT);
    }

    /**
     * @param context unchanged lines kept on each side of a change; a negative
     *                value keeps every line (one hunk with the whole file)
     */
    public static Result diff(String oldText, String newText, int context) {
        String[] a = splitLines(oldText == null ? "" : oldText);
        String[] b = splitLines(newText == null ? "" : newText);

        if (a.length > MAX_LINES || b.length > MAX_LINES) {
            // Too big for a meaningful on-screen diff: report the sizes honestly.
            return new Result(List.of(), b.length, a.length, true);
        }

        List<Line> all = myers(a, b);
        int additions = 0, deletions = 0;
        for (Line l : all) {
            if (l.kind() == Kind.ADDED) additions++;
            else if (l.kind() == Kind.REMOVED) deletions++;
        }
        return new Result(toHunks(all, context), additions, deletions, false);
    }

    /** Only the numbers, for file lists where the hunks are not shown. */
    public static int[] stats(String oldText, String newText) {
        Result r = diff(oldText, newText, 0);
        return new int[] { r.additions(), r.deletions() };
    }

    /* --------------------------------------------------------------------- */

    static String[] splitLines(String text) {
        if (text.isEmpty()) return new String[0];
        // A trailing newline does not create an extra empty line — the same
        // convention Git uses, so the counts match what people expect.
        String[] lines = text.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = Arrays.copyOf(lines, lines.length - 1);
        }
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].endsWith("\r")) lines[i] = lines[i].substring(0, lines[i].length() - 1);
        }
        return lines;
    }

    /**
     * Myers' shortest edit script, iterative, with the V arrays kept per step
     * so the path can be traced back without recursion.
     */
    private static List<Line> myers(String[] a, String[] b) {
        int n = a.length, m = b.length;
        int max = n + m;
        if (max == 0) return List.of();

        // Interning line strings makes the equality checks below pointer
        // comparisons for identical lines, which is the common case.
        List<int[]> trace = new ArrayList<>();
        int[] v = new int[2 * max + 2];
        int offset = max;
        v[offset + 1] = 0;

        int dFound = -1;
        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];          // move down (insertion)
                } else {
                    x = v[offset + k - 1] + 1;      // move right (deletion)
                }
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) { x++; y++; }
                v[offset + k] = x;
                if (x >= n && y >= m) { dFound = d; break outer; }
            }
        }

        // Backtrack from (n, m) to (0, 0) through the saved V arrays.
        List<Line> reversed = new ArrayList<>(n + m);
        int x = n, y = m;
        for (int d = dFound; d >= 0; d--) {
            int[] vd = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = vd[offset + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--; y--;
                reversed.add(new Line(Kind.CONTEXT, x + 1, y + 1, a[x]));
            }
            if (d > 0) {
                if (x == prevX) {
                    y--;
                    reversed.add(new Line(Kind.ADDED, null, y + 1, b[y]));
                } else {
                    x--;
                    reversed.add(new Line(Kind.REMOVED, x + 1, null, a[x]));
                }
            }
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static List<Hunk> toHunks(List<Line> lines, int context) {
        List<Hunk> hunks = new ArrayList<>();
        if (lines.isEmpty()) return hunks;

        if (context < 0) {
            hunks.add(makeHunk(lines, 0, lines.size()));
            return hunks;
        }

        boolean[] keep = new boolean[lines.size()];
        boolean any = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() != Kind.CONTEXT) {
                any = true;
                for (int k = Math.max(0, i - context); k <= Math.min(lines.size() - 1, i + context); k++) {
                    keep[k] = true;
                }
            }
        }
        if (!any) return hunks;

        int start = -1;
        for (int i = 0; i <= lines.size(); i++) {
            boolean in = i < lines.size() && keep[i];
            if (in && start < 0) start = i;
            if (!in && start >= 0) {
                hunks.add(makeHunk(lines, start, i));
                start = -1;
            }
        }
        return hunks;
    }

    private static Hunk makeHunk(List<Line> lines, int from, int to) {
        List<Line> slice = new ArrayList<>(lines.subList(from, to));
        int oldStart = 0, newStart = 0, oldCount = 0, newCount = 0;
        for (Line l : slice) {
            if (l.oldLine() != null) { if (oldStart == 0) oldStart = l.oldLine(); oldCount++; }
            if (l.newLine() != null) { if (newStart == 0) newStart = l.newLine(); newCount++; }
        }
        return new Hunk(oldStart, oldCount, newStart, newCount, slice);
    }
}
