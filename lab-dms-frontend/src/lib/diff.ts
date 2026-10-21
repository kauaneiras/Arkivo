/* Minimal line-based diff (LCS) used by the screen when the server is not
   involved, plus the intra-line highlighter used by the diff viewer. */

export interface DiffLine {
  kind: 'same' | 'added' | 'removed';
  text: string;
}

export function diffLines(oldText: string, newText: string): DiffLine[] {
  const a = oldText.split('\n');
  const b = newText.split('\n');
  const n = a.length;
  const m = b.length;

  // LCS dynamic programming table (guard against huge inputs)
  if (n * m > 4_000_000) {
    return [
      ...a.map((text) => ({ kind: 'removed' as const, text })),
      ...b.map((text) => ({ kind: 'added' as const, text })),
    ];
  }

  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const result: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      result.push({ kind: 'same', text: a[i] });
      i++; j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      result.push({ kind: 'removed', text: a[i] });
      i++;
    } else {
      result.push({ kind: 'added', text: b[j] });
      j++;
    }
  }
  while (i < n) { result.push({ kind: 'removed', text: a[i] }); i++; }
  while (j < m) { result.push({ kind: 'added', text: b[j] }); j++; }

  return result;
}

/** Collapses long stretches of unchanged lines, keeping context around changes. */
export function collapseDiff(lines: DiffLine[], context = 3): (DiffLine | { kind: 'skip'; count: number })[] {
  const keep = new Array(lines.length).fill(false);
  lines.forEach((line, idx) => {
    if (line.kind !== 'same') {
      for (let k = Math.max(0, idx - context); k <= Math.min(lines.length - 1, idx + context); k++) {
        keep[k] = true;
      }
    }
  });

  const out: (DiffLine | { kind: 'skip'; count: number })[] = [];
  let skipped = 0;
  lines.forEach((line, idx) => {
    if (keep[idx]) {
      if (skipped > 0) {
        out.push({ kind: 'skip', count: skipped });
        skipped = 0;
      }
      out.push(line);
    } else {
      skipped++;
    }
  });
  if (skipped > 0) out.push({ kind: 'skip', count: skipped });
  return out;
}

/**
 * The changed span of a removed/added line pair, as [start, end) on each side.
 *
 * Common prefix and suffix are trimmed; what is left is the part that actually
 * changed. This is what GitHub highlights inside a modified line, and it is
 * what makes "corrigido de 0,5 para 0,05" visible at a glance instead of
 * requiring the reader to compare two nearly identical lines character by
 * character. Falls back to the whole line when the two have little in common.
 */
export function highlightPair(before: string, after: string): [[number, number], [number, number]] {
  const max = Math.min(before.length, after.length);
  let prefix = 0;
  while (prefix < max && before[prefix] === after[prefix]) prefix++;
  let suffix = 0;
  while (suffix < max - prefix
    && before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) suffix++;

  const b: [number, number] = [prefix, before.length - suffix];
  const a: [number, number] = [prefix, after.length - suffix];
  // Little in common: highlighting almost everything is noise, so highlight nothing.
  const common = prefix + suffix;
  if (common < Math.max(before.length, after.length) * 0.3) {
    return [[0, 0], [0, 0]];
  }
  return [b, a];
}
