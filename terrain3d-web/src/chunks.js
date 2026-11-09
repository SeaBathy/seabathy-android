// Splits the terrain grid into blocks that can each be skipped when off screen,
// and drawn with fewer triangles when their cells are smaller than a pixel.
// Every block keeps all the vertices along its edges at every level of detail,
// so neighbours at different levels always meet without cracks.

/**
 * How small a block's triangles must look before it is drawn with fewer, in
 * pixels, for the chosen Detail: Normal is the balance, Fast lighter, and the
 * higher ones keep fine terrain further from the camera.
 */
export function lodPixelsFor(detail) {
  if (detail <= 256) return 6;
  if (detail <= 512) return 4;
  return detail <= 1024 ? 3 : 2;
}

/** Cells along a block's side. 129 vertices a side fit 16-bit indices, which halves the index memory. */
export const CHUNK_CELLS = 128;
export const STRIDES = [1, 2, 4, 8];

/** Blocks covering a cols x rows grid of vertices: { c0, r0, c1, r1 }, inclusive vertex positions, sharing their edges. */
export function planChunks(cols, rows) {
  const chunks = [];
  for (let r0 = 0; r0 < rows - 1; r0 += CHUNK_CELLS) {
    for (let c0 = 0; c0 < cols - 1; c0 += CHUNK_CELLS) {
      chunks.push({ c0, r0, c1: Math.min(cols - 1, c0 + CHUNK_CELLS), r1: Math.min(rows - 1, r0 + CHUNK_CELLS) });
    }
  }
  return chunks;
}

/** The inner positions along a side [n] cells long at spacing [stride]: stride, 2 x stride, ... up to n - stride. */
function innerAxis(n, stride) {
  const axis = [];
  for (let i = stride; i < n - stride; i += stride) axis.push(i);
  axis.push(n - stride);
  return axis;
}

/**
 * Triangle indices for one block at one level of detail, relative to the
 * block's own vertices. Triangles touching a no-data cell are left out, so
 * gaps stay open. [values] is the whole grid, [gridCols] its row length.
 */
export function chunkIndex(values, gridCols, chunk, stride) {
  const nx = chunk.c1 - chunk.c0;
  const ny = chunk.r1 - chunk.r0;
  const w = nx + 1;
  const out = new Uint16Array(nx * ny * 6);
  let n = 0;
  const missing = (c, r) => Number.isNaN(values[(chunk.r0 + r) * gridCols + chunk.c0 + c]);
  // Always the same way round (seen from above), whichever order it was found in.
  const tri = (ac, ar, bc, br, cc, cr) => {
    const turn = (bc - ac) * (cr - ar) - (br - ar) * (cc - ac);
    if (turn === 0 || missing(ac, ar) || missing(bc, br) || missing(cc, cr)) return;
    out[n++] = ar * w + ac;
    if (turn < 0) {
      out[n++] = br * w + bc;
      out[n++] = cr * w + cc;
    } else {
      out[n++] = cr * w + cc;
      out[n++] = br * w + bc;
    }
  };

  if (stride <= 1 || nx < stride * 2 || ny < stride * 2) {
    for (let r = 0; r < ny; r++) {
      for (let c = 0; c < nx; c++) {
        tri(c, r, c, r + 1, c + 1, r);
        tri(c + 1, r, c, r + 1, c + 1, r + 1);
      }
    }
    return out.slice(0, n);
  }

  const xs = innerAxis(nx, stride);
  const ys = innerAxis(ny, stride);
  // The coarse middle.
  for (let j = 0; j + 1 < ys.length; j++) {
    for (let i = 0; i + 1 < xs.length; i++) {
      tri(xs[i], ys[j], xs[i], ys[j + 1], xs[i + 1], ys[j]);
      tri(xs[i + 1], ys[j], xs[i], ys[j + 1], xs[i + 1], ys[j + 1]);
    }
  }
  // Four strips joining the full-detail edge to the coarse middle.
  const strip = (edgeCount, edge, inner, innerPoint, innerAt) => {
    let e = 0;
    let k = 0;
    while (e < edgeCount || k < inner.length - 1) {
      const nextEdge = e < edgeCount ? (e + 1) / edgeCount : Infinity;
      const nextInner = k < inner.length - 1 ? innerAt(k + 1) : Infinity;
      if (nextEdge <= nextInner) {
        const [ac, ar] = edge(e);
        const [bc, br] = edge(e + 1);
        const [cc, cr] = innerPoint(inner[k]);
        tri(ac, ar, bc, br, cc, cr);
        e++;
      } else {
        const [ac, ar] = edge(e);
        const [bc, br] = innerPoint(inner[k]);
        const [cc, cr] = innerPoint(inner[k + 1]);
        tri(ac, ar, bc, br, cc, cr);
        k++;
      }
    }
  };
  const topY = ys[0];
  const bottomY = ys[ys.length - 1];
  const leftX = xs[0];
  const rightX = xs[xs.length - 1];
  const alongX = (k) => (xs[k] - leftX) / Math.max(1, rightX - leftX);
  const alongY = (k) => (ys[k] - topY) / Math.max(1, bottomY - topY);
  strip(nx, (e) => [e, 0], xs, (x) => [x, topY], alongX);
  strip(nx, (e) => [e, ny], xs, (x) => [x, bottomY], alongX);
  strip(ny, (e) => [0, e], ys, (y) => [leftX, y], alongY);
  strip(ny, (e) => [nx, e], ys, (y) => [rightX, y], alongY);
  return out.slice(0, n);
}

/**
 * The level of detail for a block: the coarsest whose cells stay within
 * [maxPixels] on screen. [pixelsPerMetre] is at the block's nearest point.
 */
export function strideFor(cellMetres, pixelsPerMetre, maxPixels, bias = 0) {
  let index = 0;
  while (index < STRIDES.length - 1 && cellMetres * STRIDES[index + 1] * pixelsPerMetre <= maxPixels) index++;
  return STRIDES[Math.min(STRIDES.length - 1, index + bias)];
}
