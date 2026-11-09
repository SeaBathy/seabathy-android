// npm test: the terrain's blocks and the map stream's sizing, without a GPU.
import assert from 'assert';
import { planChunks, chunkIndex, strideFor, lodPixelsFor, STRIDES, CHUNK_CELLS } from '../src/chunks.js';
import { baseZoom, levelPlan, mercBox } from '../src/imagery.js';

// Every level of detail exactly covers its block, faces up, and uses every edge
// vertex, so neighbours at different levels always meet without cracks.
for (const [nx, ny] of [[128, 128], [128, 37], [5, 128], [17, 19], [2, 2], [1, 1], [127, 64]]) {
  const cols = nx + 1;
  const values = new Float32Array(cols * (ny + 1)).fill(-5);
  for (const stride of STRIDES) {
    const index = chunkIndex(values, cols, { c0: 0, r0: 0, c1: nx, r1: ny }, stride);
    let area = 0;
    const used = new Set();
    for (let i = 0; i < index.length; i += 3) {
      const p = [index[i], index[i + 1], index[i + 2]].map((v) => {
        used.add(v);
        return [v % cols, Math.floor(v / cols)];
      });
      const turn = (p[1][0] - p[0][0]) * (p[2][1] - p[0][1]) - (p[1][1] - p[0][1]) * (p[2][0] - p[0][0]);
      assert(turn < 0, `winding ${nx}x${ny} at ${stride}`);
      area += Math.abs(turn) / 2;
    }
    assert.strictEqual(area, nx * ny, `coverage ${nx}x${ny} at ${stride}`);
    for (let c = 0; c <= nx; c++) assert(used.has(c) && used.has(ny * cols + c), `edge column ${c}`);
    for (let r = 0; r <= ny; r++) assert(used.has(r * cols) && used.has(r * cols + nx), `edge row ${r}`);
  }
}

// Coarser levels really are lighter.
{
  const values = new Float32Array(129 * 129).fill(-1);
  const counts = STRIDES.map((s) => chunkIndex(values, 129, { c0: 0, r0: 0, c1: 128, r1: 128 }, s).length / 3);
  assert(counts[0] === 32768 && counts[1] < counts[0] / 2.5 && counts[3] < counts[0] / 15, String(counts));
}

// No-data stays open at every level.
{
  const values = new Float32Array(33 * 33).fill(-1);
  const hole = 10 * 33 + 10;
  values[hole] = NaN;
  for (const s of STRIDES) assert(!chunkIndex(values, 33, { c0: 0, r0: 0, c1: 32, r1: 32 }, s).includes(hole));
}

// Blocks tile the grid exactly, and fit 16-bit indices.
{
  let cells = 0;
  for (const b of planChunks(1025, 300)) {
    cells += (b.c1 - b.c0) * (b.r1 - b.r0);
    assert((b.c1 - b.c0 + 1) * (b.r1 - b.r0 + 1) <= 65536);
    assert(b.c1 - b.c0 <= CHUNK_CELLS);
  }
  assert.strictEqual(cells, 1024 * 299);
}

// Detail by how big a cell looks.
assert.strictEqual(strideFor(10, 1, 4), 1);
assert.strictEqual(strideFor(10, 0.1, 4), 4);
assert.strictEqual(strideFor(10, 0.01, 4), 8);
assert.strictEqual(strideFor(10, 1, 4, 2), 4);
assert.strictEqual(strideFor(10, 0.01, 4, 2), 8);

// Each Detail keeps fine terrain further away than the one below it; Normal is unchanged.
assert.deepStrictEqual([256, 512, 1024, 2048].map(lodPixelsFor), [6, 4, 3, 2]);

// The whole-survey picture: the sharpest zoom that fits.
const degree = mercBox({ west: 149, east: 150, south: -21, north: -20 });
assert.strictEqual(baseZoom(degree, 0, 19, 2048), 11);
assert.strictEqual(baseZoom(degree, 14, 17, 2048), null, 'a pack of close zooms only');
assert.strictEqual(baseZoom(mercBox({ west: 149.4, east: 149.42, south: -20.02, north: -20 }), 10, 12, 2048), 12, "the map's deepest zoom");

// Memory set aside by what the phone allows.
assert.deepStrictEqual(levelPlan(2048), { slots: 8, levels: 3 });
assert.deepStrictEqual(levelPlan(512), { slots: 3, levels: 2 });
console.log('all pass');
