// Places depth labels along contour lines: scans the grid on a coarse lattice
// for cells a contour level passes through, then keeps a well-spread subset.

/** A round label interval giving roughly `target` labelled levels across the range. */
export function labelStep(min, max, contourStep, target = 8) {
  const base = contourStep > 0 ? contourStep : 1;
  for (const k of [1, 2, 5, 10, 20, 50, 100, 200, 500]) {
    if ((max - min) / (base * k) <= target) return base * k;
  }
  return base * 1000;
}

/** Returns [{ col, row, level }] in fractional grid coordinates. */
export function contourLabelPoints(model, step, { maxLabels = 60, maxPerLevel = 4 } = {}) {
  const { cols, rows, values } = model;
  if (!(step > 0)) return [];
  const stride = Math.max(2, Math.floor(Math.min(cols, rows) / 70));
  const candidates = [];
  for (let r = stride; r < rows - stride; r += stride) {
    for (let c = stride; c < cols - stride; c += stride) {
      const a = values[r * cols + c];
      const b = values[r * cols + c + stride];
      if (Number.isNaN(a) || Number.isNaN(b)) continue;
      const lo = Math.min(a, b);
      const hi = Math.max(a, b);
      const level = Math.ceil(lo / step) * step;
      if (level > hi || level === lo) continue;
      const t = (level - a) / (b - a);
      candidates.push({ col: c + t * stride, row: r, level, score: Math.abs(b - a) });
    }
  }
  // Prefer gentle slopes (labels on a cliff face pile up), and spread them out.
  candidates.sort((p, q) => p.score - q.score);
  const minSpacing = Math.max(cols, rows) / 9;
  const chosen = [];
  const perLevel = new Map();
  for (const p of candidates) {
    if ((perLevel.get(p.level) || 0) >= maxPerLevel) continue;
    if (chosen.some((q) => Math.hypot(q.col - p.col, q.row - p.row) < minSpacing)) continue;
    chosen.push(p);
    perLevel.set(p.level, (perLevel.get(p.level) || 0) + 1);
    if (chosen.length >= maxLabels) break;
  }
  return chosen;
}
