// Bilinear depth lookup on the grid sent by the app (values in metres,
// negative down, NaN = no data). Same contract as the desktop app's tif.js.

export function sampleGrid(model, col, row) {
  const { cols, rows, values } = model;
  if (col < 0 || row < 0 || col > cols - 1 || row > rows - 1) return null;
  const c0 = Math.floor(col);
  const r0 = Math.floor(row);
  const c1 = Math.min(cols - 1, c0 + 1);
  const r1 = Math.min(rows - 1, r0 + 1);
  const fc = col - c0;
  const fr = row - r0;
  const a = values[r0 * cols + c0];
  const b = values[r0 * cols + c1];
  const c = values[r1 * cols + c0];
  const d = values[r1 * cols + c1];
  if (Number.isNaN(a) || Number.isNaN(b) || Number.isNaN(c) || Number.isNaN(d)) {
    const nearest = values[Math.round(row) * cols + Math.round(col)];
    return Number.isNaN(nearest) ? null : nearest;
  }
  return (a * (1 - fc) + b * fc) * (1 - fr) + (c * (1 - fc) + d * fc) * fr;
}

/** Grid cell (fractional, cell centres) for a lat/lng inside the model's WGS84 bounds. */
export function lonLatToGrid(model, lng, lat) {
  const { west, south, east, north } = model.bounds;
  return {
    col: ((lng - west) / (east - west)) * model.cols - 0.5,
    row: ((north - lat) / (north - south)) * model.rows - 0.5
  };
}

export function gridToLonLat(model, col, row) {
  const { west, south, east, north } = model.bounds;
  return {
    lng: west + ((col + 0.5) / model.cols) * (east - west),
    lat: north - ((row + 0.5) / model.rows) * (north - south)
  };
}
