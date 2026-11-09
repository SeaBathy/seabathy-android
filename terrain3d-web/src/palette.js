// Depth colour ramps. Each is a list of stops from deepest (t=0) to
// shallowest (t=1); a 256-entry RGB lookup is built from them.

const cache = new Map();

export const PALETTES = {
  ocean: {
    label: 'Ocean (navy → cyan → sand)',
    stops: ['#08142e', '#0c2c63', '#14519b', '#1f7fc4', '#35b0d8', '#6fd6d2', '#b7ecc4', '#f2e6a9']
  },
  turbo: {
    label: 'Turbo (high contrast)',
    stops: ['#30123b', '#4662d7', '#36aaf9', '#1ae4b6', '#72fe5e', '#c8ef34', '#faba39', '#f66b19', '#ca2a04', '#7a0403']
  },
  viridis: {
    label: 'Viridis',
    stops: ['#440154', '#46327e', '#365c8d', '#277f8e', '#1fa187', '#4ac16d', '#a0da39', '#fde725']
  },
  depthbands: {
    label: 'Chart (depth bands)',
    stops: ['#1d5a8a', '#1d5a8a', '#3d82b8', '#3d82b8', '#79b4dc', '#79b4dc', '#b7dcf0', '#b7dcf0', '#e7f4fb']
  },
  thermal: {
    label: 'Thermal',
    stops: ['#000004', '#320a5e', '#781b6c', '#bb3754', '#ec6824', '#fbb41a', '#fcffa4']
  },
  gray: {
    label: 'Greyscale',
    stops: ['#101010', '#f0f0f0']
  }
};

/** Adds (or replaces) a palette, e.g. the chart's own colours passed in by the app. */
export function registerPalette(id, label, stops) {
  PALETTES[id] = { label, stops };
  cache.delete(id);
}

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}


/** Uint8Array of 256*3 RGB values, deep → shallow. */
export function paletteLut(name) {
  if (cache.has(name)) return cache.get(name);
  const stops = (PALETTES[name] || PALETTES.ocean).stops.map(hexToRgb);
  const lut = new Uint8Array(256 * 3);
  for (let i = 0; i < 256; i++) {
    const t = (i / 255) * (stops.length - 1);
    const a = Math.floor(t);
    const b = Math.min(stops.length - 1, a + 1);
    const f = t - a;
    for (let c = 0; c < 3; c++) lut[i * 3 + c] = Math.round(stops[a][c] + (stops[b][c] - stops[a][c]) * f);
  }
  cache.set(name, lut);
  return lut;
}

export function drawLegend(canvas, name) {
  const ctx = canvas.getContext('2d');
  const lut = paletteLut(name);
  for (let x = 0; x < canvas.width; x++) {
    const i = Math.round((x / (canvas.width - 1)) * 255) * 3;
    ctx.fillStyle = `rgb(${lut[i]},${lut[i + 1]},${lut[i + 2]})`;
    ctx.fillRect(x, 0, 1, canvas.height);
  }
}
