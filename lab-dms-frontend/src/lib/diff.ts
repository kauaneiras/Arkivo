/* Minimal line-based diff (LCS) used to compare document versions. */

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
