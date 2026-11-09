// SeaBathy Android 3D viewer. Runs inside the app's WebView: the grid and its
// metadata are served by Terrain3DScreen from memory at /terrain/*, and the
// window.SeaBathy3D bridge carries close / isolate / detail / GPS requests
// back. Kotlin calls window.reloadGrid, window.updateBoat and
// window.onWaypointAdded.

import { Terrain3D } from './terrain3d.js';
import { PALETTES, paletteLut, registerPalette } from './palette.js';
import { contourLabelPoints, labelStep } from './contours.js';
import { lonLatToGrid, gridToLonLat, sampleGrid } from './grid.js';
import { ImageryStream } from './imagery.js';

const $ = (id) => document.getElementById(id);
const bridge = window.SeaBathy3D || {
  close() { history.back(); },
  isolate() { toast('Isolate needs the app'); },
  addWaypoint() { toast('Waypoints need the app'); },
  editWaypoint() { toast('Editing waypoints needs the app'); },
  startGps() { toast('GPS needs the app'); },
  showAll() {},
  setDetail() {},
  addCustomDrape() { return JSON.stringify({ error: 'Custom maps need the app' }); },
  removeCustomDrape() { return '[]'; },
  saveOffline() { toast('Saving offline needs the app'); },
  contextLost() { location.reload(); },
  ready() {},
  noWebGL() {},
  log() {},
  resetSafety() {}
};

// With the GPU out of memory, or 3D blocked after a GPU failure, the view
// can't start at all. Say so, so the app can act, rather than sit blank.
function webglAvailable() {
  try {
    const gl = document.createElement('canvas').getContext('webgl2');
    gl?.getExtension('WEBGL_lose_context')?.loseContext();
    return Boolean(gl);
  } catch {
    return false;
  }
}
if (!webglAvailable()) {
  bridge.noWebGL();
  throw new Error('WebGL is not available');
}

const CONTOUR_STEPS = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500];
const settings = loadSettings();
let model = null;
let profileMode = false;
// Waypoint key on: each tap on the seabed saves a waypoint there.
let waypointMode = false;
let lastTap = null;
let profileStart = null;
let lastFix = null;
/**
 * The colour range ({ lo, hi }, metres, negative down): the chart's to start
 * with, then whatever is typed. Null fits the data (or the visible band).
 * Kept when the terrain reloads (isolating depths, Show all, detail).
 */
let colourRange = null;

function loadSettings() {
  const defaults = { exaggeration: 3, palette: 'ocean', colorMode: 'depth', slopeMax: 20, sunAz: 315, sunEl: 35, shadows: true, sea: false,
    waypoints: true, contourOn: true, contourLabels: true, contourMetres: null, followBoat: false, tab: 'view',
    surface: 'depth', drapeMix: 1, drapeDetail: true };
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem('terrain3d') || '{}') };
  } catch {
    return defaults;
  }
}

function saveSettings() {
  try { localStorage.setItem('terrain3d', JSON.stringify(settings)); } catch { /* storage unavailable */ }
}

let toastTimer;
/** True when clearly less detail was given than asked for, because of this phone (not just a survey with fewer cells). */
const detailLimited = (meta) => meta.detailLimit < meta.detail * 0.95 && Math.max(meta.cols, meta.rows) >= meta.detailLimit * 0.9;

function detailLimitNote(meta) {
  if (!detailLimited(meta)) return '';
  return meta.safety >= 2
    ? ` Limited to ${meta.detailLimit} after a graphics problem on this phone.`
    : ` ${meta.detailLimit} is the most this phone holds safely.`;
}

function toast(text, ms = 3000) {
  $('toast').textContent = text;
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, ms);
}

function busy(text) {
  $('busy').hidden = !text;
  if (text) $('busy-text').textContent = text;
}
window.showBusy = busy;

const round1 = (v) => Math.round(v * 10) / 10;
// The app's depth and speed units (Boat & navigation); metres and knots without the app.
const units = (() => {
  try {
    if (bridge.units) return JSON.parse(bridge.units());
  } catch (e) { /* fall through */ }
  return { depthPerM: 1, depthLabel: 'm', speedPerKnot: 1, speedLabel: 'kn' };
})();
/** A distance up or down, metres in, in the chosen depth unit. */
const depthUnits = (metres) => `${round1(metres * units.depthPerM)} ${units.depthLabel}`;
// Levels as on the chart: negative below sea level, + above it.
const depthText = (v) => (v < 0 ? `-${depthUnits(-v)}` : v > 0 ? `+${depthUnits(v)}` : `0 ${units.depthLabel}`);
// Depth boxes are typed in the chosen unit; the page works in metres.
const toInput = (v) => String(round1(v * units.depthPerM));
const fromInput = (text) => {
  const n = parseFloat(text);
  return Number.isFinite(n) ? n / units.depthPerM : null;
};
document.querySelectorAll('.depth-unit').forEach((el) => { el.textContent = units.depthLabel; });

function formatDdm(lat, lng) {
  const part = (v, pos, neg) => {
    const thousandths = Math.round(Math.abs(v) * 60000);
    const d = Math.floor(thousandths / 60000);
    const m = (thousandths % 60000) / 1000;
    return `${d}°${m.toFixed(3).padStart(6, '0')}'${v >= 0 ? pos : neg}`;
  };
  return `${part(lat, 'N', 'S')} ${part(lng, 'E', 'W')}`;
}

// ------------------------------------------------------------------ terrain

let loweredSaid = false;
let detailJustChosen = false;

const terrain = new Terrain3D($('view'), {
  onQuality: (text) => bridge.log(text),
  // Said once a visit: it may come and go as the phone warms up.
  onLowered: () => {
    if (!loweredSaid) toast('Detail lowered for now to keep 3D smooth', 4000);
    loweredSaid = true;
  },
  // Contours read like the chart: "22 m" under water, "+5 m" on land.
  formatLevel: (level) => (level < 0 ? depthUnits(-level) : depthText(level)),
  onTap: (hit) => {
    if (profileMode) return profileTap(hit);
    const ll = gridToLonLat(model, hit.col, hit.row);
    const slope = terrain.slopeAt(hit.col, hit.row);
    lastTap = { lat: ll.lat, lng: ll.lng, depth: hit.depth };
    $('readout').innerHTML = `<span><b>${depthText(hit.depth)}</b>` +
      (slope === null ? '' : ` · slope ${slope.toFixed(slope < 10 ? 1 : 0)}°`) +
      `<br>${formatDdm(ll.lat, ll.lng)}</span>` +
      '<button id="readout-close" class="icon small" aria-label="Close depth readout">✕</button>';
    $('readout').hidden = false;
    if (waypointMode) bridge.addWaypoint(ll.lat, ll.lng, hit.depth);
  },
  // Moving the view or tapping off the terrain clears the depth readout.
  onDragStart: () => { $('readout').hidden = true; },
  onTapEmpty: () => { $('readout').hidden = true; },
  // Tapping a pin opens the app's editor: name, notes, Go to, delete.
  onWaypointClick: (id) => {
    if (bridge.editWaypoint) bridge.editWaypoint(id);
    else window.focusWaypoint(id);
  }
});

// ------------------------------------------------------------------ loading

async function loadGrid() {
  busy('Loading terrain…');
  try {
    const meta = await (await fetch('/terrain/meta.json', { cache: 'no-store' })).json();
    const buffer = await (await fetch('/terrain/grid.bin', { cache: 'no-store' })).arrayBuffer();
    const values = new Float32Array(buffer);
    if (values.length !== meta.cols * meta.rows) throw new Error('grid size mismatch');
    // Moving between an isolated range and the whole layer re-fits the height.
    const refit = Boolean(model) && Boolean(model.isolated) !== Boolean(meta.isolated);
    const first = !model;
    model = { ...meta, values };
    if (meta.pixelRatio) terrain.setMaxPixelRatio(meta.pixelRatio);
    $('safety-note').hidden = !meta.safety;
    // Opens with the chart's colours and range, so 2D and 3D match.
    if (first && meta.chart) {
      registerPalette('chart', `Same as the chart (${meta.chart.label})`, meta.chart.stops);
      settings.palette = 'chart';
      fillPalettes();
      colourRange = { lo: Math.min(meta.chart.deep, meta.chart.shallow), hi: Math.max(meta.chart.deep, meta.chart.shallow) };
    }
    $('readout').hidden = true;
    $('profile').hidden = true;
    $('title').textContent = meta.name;
    $('grid-info').textContent = (meta.isolated ? `Isolated ${depthText(meta.isolated.shallow)} – ${depthText(meta.isolated.deep)}. ` : '') +
      `${meta.cols}×${meta.rows} cells, ${Math.min(meta.cellX, meta.cellY).toFixed(1)} m each` +
      (meta.partial && !meta.isolated ? ' (area on screen).' : '.') + detailLimitNote(meta);
    if (detailJustChosen && detailLimited(meta)) toast(`This phone's safe limit is ${meta.detailLimit} cells`, 4000);
    detailJustChosen = false;
    terrain.setDetailLevel(meta.detail);
    $('btn-show-all').disabled = !meta.partial;
    $('detail').value = String(meta.detail);

    terrain.setModel(model);
    fillSurfaces(meta.drapes || []);
    if (meta.isolated || refit || settings.exaggeration === null) settings.exaggeration = terrain.autoExaggeration();
    autoContour(Boolean(meta.isolated) || refit);
    applyAll();
    resetClip();
    if (first && meta.chart && meta.chart.hide) {
      $('clip-shallow').value = clipSlider(Math.max(meta.chart.shallow, meta.chart.deep));
      $('clip-deep').value = clipSlider(Math.min(meta.chart.shallow, meta.chart.deep));
      $('clip-on').checked = true;
      showClipInputs();
      applyClip();
    }
    if (lastFix) window.updateBoat(lastFix);
    // Following was left on: the boat needs GPS, which is off after a restart.
    else if (settings.followBoat) bridge.startGps();
    terrain.activate();
    if (first) {
      const info = terrain.renderer.getContext();
      bridge.log(`page ready: ${meta.cols}x${meta.rows} cells, drape ${meta.drapeMaxSize}, safety ${meta.safety}, ` +
        `pixel ratio ${terrain.renderer.getPixelRatio()} of ${window.devicePixelRatio}, ` +
        `max texture ${terrain.maxTextureSize()}, GPU ${info.getParameter(info.RENDERER)}`);
    }
    bridge.ready();
  } catch (e) {
    toast(`Couldn't load the terrain: ${e.message}`, 6000);
  } finally {
    busy(null);
  }
  // A new grid (isolate, whole layer, detail) needs the map laid over it again.
  if (model) rebuildDrape();
}
window.reloadGrid = loadGrid;

// A phone's GPU drops the 3D drawing when memory runs short, which leaves a
// blank view. The app reloads the page lighter (the grid stays in the app).
terrain.renderer.domElement.addEventListener('webglcontextlost', (e) => {
  e.preventDefault();
  busy('Restoring the 3D view…');
  bridge.contextLost();
}, { once: true });

/** The app has stepped this view down after the GPU ran short: no shadows from here on. */
const lighter = () => (model?.safety ?? 0) >= 1;

function applyAll() {
  setExaggeration(settings.exaggeration, false);
  terrain.setSun(settings.sunAz, settings.sunEl);
  terrain.setShadows(settings.shadows && !lighter());
  terrain.setSeaSurface(settings.sea);
  terrain.setFollowBoat(settings.followBoat);
  recolor();
  applyColorMode();
  updateContours();
  updateWaypoints();
}

// ------------------------------------------------------------------ tabs

let lastTab = settings.tab || 'view';

function showTab(tab) {
  // Tapping the open tab folds the panel away, leaving more room for the view.
  settings.tab = settings.tab === tab ? null : tab;
  if (settings.tab) lastTab = settings.tab;
  saveSettings();
  renderTabs();
}

function renderTabs() {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === settings.tab));
  document.querySelectorAll('.panel').forEach((p) => { p.hidden = p.dataset.panel !== settings.tab; });
  const handle = $('sheet-handle');
  handle.querySelector('.chev').textContent = settings.tab ? '▾' : '▴ Options';
  handle.setAttribute('aria-label', settings.tab ? 'Hide options' : 'Show options');
}
document.querySelectorAll('#tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
// The handle folds the options away (only the tab row stays) and back.
$('sheet-handle').addEventListener('click', () => showTab(settings.tab || lastTab));

// The 3D view stops at the top of the sheet; a smaller canvas is also less to draw.
new ResizeObserver(() => {
  document.documentElement.style.setProperty('--sheet-h', `${$('sheet').offsetHeight}px`);
}).observe($('sheet'));

// ------------------------------------------------------------------ colours

/** The depths the colour ramp spans: the visible band when hiding, else the user's range, else the data. */
function activeColourRange() {
  // A typed range wins; moving the depth sliders clears it so colours stretch over what's shown.
  if (colourRange) return colourRange;
  if ($('clip-on').checked) return readClip();
  return { lo: model.min, hi: model.max };
}

function recolor() {
  const { lo, hi } = normaliseRange(activeColourRange());
  terrain.setPalette(paletteLut(settings.palette), lo, hi);
  $('ramp-shallow').value = toInput(hi);
  $('ramp-deep').value = toInput(lo);
  drawLegend(lo, hi);
}

function normaliseRange(r) {
  const lo = 'lo' in r ? r.lo : r.deep;
  const hi = 'hi' in r ? r.hi : r.shallow;
  return { lo: Math.min(lo, hi), hi: Math.max(lo, hi) };
}

function drawLegend(lo, hi) {
  const canvas = $('legend');
  const ctx = canvas.getContext('2d');
  if (settings.colorMode === 'slope') {
    const grad = ctx.createLinearGradient(0, 0, canvas.width, 0);
    ['#173880', '#149e9e', '#f5d642', '#f27321', '#c7141a'].forEach((c, i) => grad.addColorStop(i / 4, c));
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    $('legend-lo').textContent = 'flat';
    $('legend-hi').textContent = `${settings.slopeMax}°+ steep`;
    return;
  }
  const lut = paletteLut(settings.palette);
  for (let x = 0; x < canvas.width; x++) {
    const i = Math.round((x / (canvas.width - 1)) * 255) * 3;
    ctx.fillStyle = `rgb(${lut[i]},${lut[i + 1]},${lut[i + 2]})`;
    ctx.fillRect(x, 0, 1, canvas.height);
  }
  $('legend-lo').textContent = `${depthText(lo)} (deep)`;
  $('legend-hi').textContent = `${depthText(hi)} (shallow)`;
}

function applyColorMode() {
  const slope = settings.colorMode === 'slope';
  terrain.setColorMode(settings.colorMode, settings.slopeMax);
  $('slope-row').hidden = !slope;
  $('depth-colour-controls').hidden = slope;
  recolor();
}

$('color-mode').addEventListener('change', (e) => { settings.colorMode = e.target.value; saveSettings(); applyColorMode(); });
$('slope-max').addEventListener('change', (e) => { settings.slopeMax = Number(e.target.value); saveSettings(); applyColorMode(); });
function fillPalettes() {
  $('palette').innerHTML = Object.entries(PALETTES).map(([id, p]) => `<option value="${id}">${p.label}</option>`).join('');
  $('palette').value = PALETTES[settings.palette] ? settings.palette : 'ocean';
}
fillPalettes();
$('palette').addEventListener('change', (e) => { settings.palette = e.target.value; saveSettings(); recolor(); });

for (const id of ['ramp-shallow', 'ramp-deep']) {
  $(id).addEventListener('change', () => {
    const a = fromInput($('ramp-shallow').value);
    const b = fromInput($('ramp-deep').value);
    if (a === null || b === null || a === b) return recolor();
    colourRange = { lo: Math.min(a, b), hi: Math.max(a, b) };
    recolor();
  });
}
$('ramp-auto').addEventListener('click', () => {
  // The middle 96% of depths, so a few spikes or holes don't wash the colours out.
  const sorted = model.values.filter((v) => !Number.isNaN(v)).sort();
  colourRange = { lo: sorted[Math.floor(sorted.length * 0.02)], hi: sorted[Math.floor(sorted.length * 0.98)] };
  if (colourRange.hi <= colourRange.lo) colourRange.hi = colourRange.lo + 1;
  recolor();
});
$('ramp-fit').addEventListener('click', () => { colourRange = null; recolor(); });

// ------------------------------------------------------------------ map on the terrain

// The app picks the size from the phone's memory (meta.drapeMaxSize).
const DRAPE_DEFAULT_SIZE = 2048;
let drapes = [];
/** The map being streamed over the terrain; see imagery.js. */
let stream = null;

const GROUPS = [['default', 'DEFAULT'], ['layer', 'YOUR LAYERS'], ['custom', 'CUSTOM']];
const DEPTH_CHOICE = { id: 'depth', label: 'Depth colours', sub: 'The survey coloured by depth, no map', swatch: '◐', colour: '#0e7490', group: 'default' };

const currentDrape = () => drapes.find((d) => d.id === settings.surface) || null;

function drapeSub(d) {
  const zooms = d.minzoom > 0 ? `zoom ${d.minzoom}–${d.maxzoom}` : `sharp to zoom ${d.maxzoom}`;
  return `${d.offline ? 'Offline' : 'Online'} · ${zooms}`;
}

/** The Map tab's list: depth colours and the app's default maps, then the layer stack's, then custom links. */
function fillSurfaces(list) {
  drapes = list;
  if (settings.surface !== 'depth' && !currentDrape()) settings.surface = 'depth';
  renderMapList();
  $('drape-mix').value = settings.drapeMix;
  $('drape-detail').checked = settings.drapeDetail;
}

function renderMapList() {
  const box = $('map-list');
  box.replaceChildren();
  for (const [group, title] of GROUPS) {
    const items = [...(group === 'default' ? [DEPTH_CHOICE] : []), ...drapes.filter((d) => d.group === group)];
    if (!items.length && group !== 'custom') continue;
    const header = document.createElement('div');
    header.className = 'map-group';
    header.textContent = title;
    box.append(header);
    for (const d of items) box.append(mapItem(d));
    if (group === 'custom') {
      const add = document.createElement('button');
      add.className = 'map-item map-add';
      add.textContent = '+ Add a map link';
      add.addEventListener('click', () => showCustomForm(true));
      box.append(add);
    }
  }
  $('drape-controls').hidden = !currentDrape();
  $('btn-save-offline').hidden = !currentDrape()?.savable;
  showCredit();
}

/** The chosen map's credit on screen, as its provider asks (the app names a custom link's server). */
function showCredit() {
  const d = currentDrape();
  const text = d ? d.attribution || d.label : '';
  $('map-credit').textContent = text;
  $('map-credit').hidden = !text;
}

function mapItem(d) {
  const item = document.createElement('div');
  item.className = 'map-item' + (d.id === settings.surface ? ' selected' : '');
  item.setAttribute('role', 'button');
  const swatch = document.createElement('span');
  swatch.className = 'map-swatch';
  swatch.style.setProperty('--sw', d.colour || (d.offline ? '#0f766e' : '#475569'));
  swatch.textContent = d.swatch || d.label.trim().charAt(0).toUpperCase();
  const text = document.createElement('span');
  text.className = 'map-text';
  const name = document.createElement('span');
  name.className = 'map-name';
  name.textContent = d.label;
  const sub = document.createElement('span');
  sub.className = 'map-sub';
  sub.textContent = d.sub || drapeSub(d);
  text.append(name, sub);
  const tick = document.createElement('span');
  tick.className = 'map-tick';
  tick.textContent = '✓';
  item.append(swatch, text, tick);
  if (d.group === 'custom') {
    const remove = document.createElement('button');
    remove.className = 'map-remove';
    remove.textContent = '✕';
    remove.setAttribute('aria-label', `Remove ${d.label}`);
    remove.addEventListener('click', (e) => {
      e.stopPropagation();
      fillSurfaces(JSON.parse(bridge.removeCustomDrape(d.id)));
      rebuildDrape();
    });
    item.append(remove);
  }
  item.addEventListener('click', () => {
    if (settings.surface === d.id) return;
    settings.surface = d.id;
    saveSettings();
    renderMapList();
    rebuildDrape();
  });
  return item;
}

function showCustomForm(on) {
  $('custom-form').hidden = !on;
  $('custom-error').hidden = true;
  if (on) {
    $('custom-name').value = '';
    $('custom-url').value = '';
    $('custom-url').focus();
  }
}
$('custom-cancel').addEventListener('click', () => showCustomForm(false));
$('custom-add').addEventListener('click', () => {
  const result = JSON.parse(bridge.addCustomDrape($('custom-name').value.trim(), $('custom-url').value.trim()));
  if (result.error) {
    $('custom-error').textContent = result.error;
    $('custom-error').hidden = false;
    return;
  }
  showCustomForm(false);
  settings.surface = result.id;
  saveSettings();
  fillSurfaces(result.drapes);
  rebuildDrape();
});

function showDrapeStatus(text, warn = false) {
  $('drape-status').textContent = text || '';
  $('drape-status').hidden = !text;
  $('drape-status').classList.toggle('warn', warn);
}

const drapeMaxSize = () => Math.min(model.drapeMaxSize || DRAPE_DEFAULT_SIZE, terrain.maxTextureSize());

/** Sharpening stops only at the app's very lowest settings: it costs little now. */
const sharpening = () => settings.drapeDetail && (model.safety ?? 0) < 3;

/** Puts the chosen map on the terrain, starting its stream if it's a different map from the last. */
function rebuildDrape() {
  const drape = currentDrape();
  if (stream && (!drape || stream.id !== drape.id)) {
    terrain.setImagery(null);
    stream.dispose();
    stream = null;
  }
  if (!drape) {
    showDrapeStatus(null);
    return;
  }
  if (!stream) {
    stream = new ImageryStream(terrain.renderer, terrain.uniforms, {
      id: drape.id,
      tiles: `/drape/${encodeURIComponent(drape.id)}/{z}/{x}/{y}`,
      minzoom: drape.minzoom,
      maxzoom: drape.maxzoom,
      bounds: drape.bounds,
      offline: drape.offline
    }, {
      maxSize: drapeMaxSize(),
      sharpen: sharpening(),
      onWake: () => terrain.wake(),
      onStatus: (text, warn) => showDrapeStatus(text, warn),
      onLog: (text) => bridge.log(text)
    });
    stream.setSurvey(model.bounds);
    terrain.setImagery(stream, settings.drapeMix);
  } else {
    // The same map over a new grid (isolate, whole layer, detail).
    stream.setSurvey(model.bounds);
    terrain.setImagery(stream, settings.drapeMix);
  }
}

$('drape-mix').addEventListener('input', (e) => {
  settings.drapeMix = Number(e.target.value);
  terrain.setDrapeMix(settings.drapeMix);
  saveSettings();
});
$('drape-detail').addEventListener('change', (e) => {
  settings.drapeDetail = e.target.checked;
  saveSettings();
  stream?.setSharpen(sharpening());
  terrain.wake();
});
$('btn-full-quality').addEventListener('click', () => bridge.resetSafety());
$('btn-save-offline').addEventListener('click', () => {
  const d = currentDrape();
  if (d) bridge.saveOffline(d.id);
});

$('readout').addEventListener('click', (e) => {
  if (e.target.id === 'readout-close') $('readout').hidden = true;
});

function setWaypointMode(on) {
  waypointMode = on;
  $('btn-wpt-mode').classList.toggle('active', on);
  $('btn-wpt-mode').setAttribute('aria-pressed', String(on));
  if (on) {
    setProfileMode(false);
    toast('Tap the seabed to drop waypoints. Tap 📍 again when done.');
  }
}
$('btn-wpt-mode').addEventListener('click', () => setWaypointMode(!waypointMode));

// From the app after a waypoint is renamed, re-noted or deleted.
window.setWaypointList = (list) => {
  if (!model) return;
  model.waypoints = list;
  updateWaypoints();
};
window.focusWaypoint = (id) => {
  const w = model?.waypoints?.find((x) => x.id === id);
  if (!w) return;
  const g = lonLatToGrid(model, w.lng, w.lat);
  terrain.focusGrid(g.col, g.row);
};

window.onWaypointAdded = (w) => {
  model.waypoints = [...(model.waypoints || []), w];
  updateWaypoints();
  toast(`Saved waypoint ${w.name}`, 1800);
};

// ------------------------------------------------------------------ exaggeration

const exagFromSlider = (s) => 0.5 * 200 ** (s / 1000);
const sliderFromExag = (e) => Math.round((1000 * Math.log(e / 0.5)) / Math.log(200));

function setExaggeration(value, save = true) {
  value = Math.max(0.5, Math.min(100, value));
  settings.exaggeration = value;
  $('exag').value = sliderFromExag(value);
  $('exag-val').textContent = `${value < 10 ? round1(value) : Math.round(value)}×`;
  terrain.setExaggeration(value);
  if (save) saveSettings();
}
// Applied once a frame at most, however fast the slider's events come.
let pendingExag = null;
$('exag').addEventListener('input', (e) => {
  if (pendingExag === null) {
    requestAnimationFrame(() => {
      setExaggeration(pendingExag);
      pendingExag = null;
    });
  }
  pendingExag = exagFromSlider(Number(e.target.value));
});
$('exag-true').addEventListener('click', () => setExaggeration(1));
$('exag-auto').addEventListener('click', () => setExaggeration(terrain.autoExaggeration()));
document.querySelectorAll('[data-exag]').forEach((b) => b.addEventListener('click', () => setExaggeration(Number(b.dataset.exag))));

// ------------------------------------------------------------------ depth range

const clipValue = (s) => model.max - (s / 1000) * (model.max - model.min);
const clipSlider = (v) => Math.round(((model.max - v) / (model.max - model.min || 1)) * 1000);

function readClip() {
  const a = clipValue(Number($('clip-shallow').value));
  const b = clipValue(Number($('clip-deep').value));
  return { shallow: Math.max(a, b), deep: Math.min(a, b) };
}

function showClipInputs() {
  const { shallow, deep } = readClip();
  $('clip-from').value = toInput(shallow);
  $('clip-to').value = toInput(deep);
}

function resetClip() {
  $('clip-shallow').value = 0;
  $('clip-deep').value = 1000;
  $('clip-on').checked = false;
  terrain.setClip(null, null);
  showClipInputs();
}

function applyClip() {
  const range = readClip();
  const on = $('clip-on').checked;
  terrain.setClip(on ? range.deep : null, on ? range.shallow : null);
  // Colours stretch over just the visible depths, for the most contrast.
  recolor();
  terrain.refreshContourLabelVisibility();
}

for (const id of ['clip-shallow', 'clip-deep']) {
  $(id).addEventListener('input', () => {
    $('clip-on').checked = true;
    colourRange = null;
    showClipInputs();
    applyClip();
  });
}
for (const id of ['clip-from', 'clip-to']) {
  $(id).addEventListener('change', () => {
    const a = fromInput($('clip-from').value);
    const b = fromInput($('clip-to').value);
    if (a === null || b === null) return showClipInputs();
    $('clip-shallow').value = clipSlider(Math.max(a, b));
    $('clip-deep').value = clipSlider(Math.min(a, b));
    $('clip-on').checked = true;
    applyClip();
  });
}
$('clip-on').addEventListener('change', applyClip);
$('btn-isolate').addEventListener('click', () => {
  const typedFrom = fromInput($('clip-from').value);
  const typedTo = fromInput($('clip-to').value);
  // Typed values win over the slider positions (they can lie outside the data's range).
  const range = typedFrom !== null && typedTo !== null
    ? { deep: Math.min(typedFrom, typedTo), shallow: Math.max(typedFrom, typedTo) }
    : readClip();
  bridge.isolate(range.deep, range.shallow);
});
$('btn-show-all').addEventListener('click', () => bridge.showAll());
$('detail').addEventListener('change', (e) => {
  detailJustChosen = true;
  bridge.setDetail(Number(e.target.value));
});

// ------------------------------------------------------------------ contours

function autoContour(force) {
  if (settings.contourMetres !== null && !force) {
    $('contour').value = Math.max(0, CONTOUR_STEPS.indexOf(settings.contourMetres));
    return;
  }
  const range = model.max - model.min;
  const idx = CONTOUR_STEPS.findIndex((s) => range / s <= 25);
  $('contour').value = idx < 0 ? CONTOUR_STEPS.length - 1 : idx;
}

function updateContours() {
  const step = CONTOUR_STEPS[$('contour').value];
  $('contour-val').textContent = depthUnits(step);
  terrain.setContourInterval(settings.contourOn ? step : 0);
  const points = settings.contourOn && settings.contourLabels
    ? contourLabelPoints(model, labelStep(model.min, model.max, step, 6), { maxLabels: 30, maxPerLevel: 3 })
    : [];
  terrain.setContourLabels(points);
}

$('contour').addEventListener('input', () => {
  settings.contourMetres = CONTOUR_STEPS[$('contour').value];
  saveSettings();
  updateContours();
});
$('contour-on').addEventListener('change', (e) => { settings.contourOn = e.target.checked; saveSettings(); updateContours(); });
$('contour-labels').addEventListener('change', (e) => { settings.contourLabels = e.target.checked; saveSettings(); updateContours(); });

// ------------------------------------------------------------------ light

for (const id of ['sun-az', 'sun-el']) {
  $(id).addEventListener('input', () => {
    settings.sunAz = Number($('sun-az').value);
    settings.sunEl = Number($('sun-el').value);
    terrain.setSun(settings.sunAz, settings.sunEl);
    saveSettings();
  });
}
$('shadows').addEventListener('change', (e) => { settings.shadows = e.target.checked; terrain.setShadows(e.target.checked && !lighter()); saveSettings(); });
$('sea-surface').addEventListener('change', (e) => { settings.sea = e.target.checked; terrain.setSeaSurface(e.target.checked); saveSettings(); });
$('wpt').addEventListener('change', (e) => { settings.waypoints = e.target.checked; updateWaypoints(); saveSettings(); });

function updateWaypoints() {
  const items = (model.waypoints || []).map((w) => ({ ...w, ...lonLatToGrid(model, w.lng, w.lat) }));
  terrain.setWaypoints(items, settings.waypoints);
}

// ------------------------------------------------------------------ boat

/** A live GPS fix from the app: { lat, lng, heading, knots, accuracy }. */
window.updateBoat = (fix) => {
  lastFix = fix;
  if (!model) return;
  const g = lonLatToGrid(model, fix.lng, fix.lat);
  const inside = g.col >= 0 && g.row >= 0 && g.col <= model.cols - 1 && g.row <= model.rows - 1;
  const below = inside ? sampleGrid(model, g.col, g.row) : null;
  const speed = fix.knots === null || fix.knots === undefined ? '' : ` · ${round1(fix.knots * units.speedPerKnot)} ${units.speedLabel}`;
  if (!inside) {
    terrain.setBoat(null);
    $('boat-chip').innerHTML = `<b>⛵</b> outside this area${speed}`;
  } else {
    // Null inside the area means either a survey gap or a depth hidden by Isolate.
    const missing = model.isolated ? 'depth outside the shown range' : 'no depth data here';
    // A readout, like the chart's crosshair and the nav screen: water as a
    // positive depth, ground above the water line as drying.
    const depth = below === null ? missing : below < 0 ? `${depthUnits(-below)} deep` : `dries ${depthUnits(below)}`;
    terrain.setBoat({ col: g.col, row: g.row, heading: fix.heading, label: below === null ? 'You' : `You · ${depth}` });
    $('boat-chip').innerHTML = `<b>⛵ ${depth}</b>${speed}`;
  }
  $('boat-chip').hidden = false;
};

$('btn-boat').addEventListener('click', () => {
  if (lastFix) {
    terrain.setFollowBoat(true);
    terrain.setFollowBoat(settings.followBoat);
  } else {
    toast('Turning on GPS…');
    bridge.startGps();
  }
});
$('follow-boat').addEventListener('change', (e) => {
  settings.followBoat = e.target.checked;
  saveSettings();
  terrain.setFollowBoat(settings.followBoat);
  if (settings.followBoat && !lastFix) bridge.startGps();
});

// ------------------------------------------------------------------ profile

function setProfileMode(on) {
  profileMode = on;
  profileStart = null;
  $('btn-profile').classList.toggle('active', on);
  if (on) {
    setWaypointMode(false);
    toast('Tap the start of the profile, then the end');
  }
}
$('btn-profile').addEventListener('click', () => setProfileMode(!profileMode));

function profileTap(hit) {
  if (!profileStart) {
    profileStart = hit;
    toast('Now tap the end point');
    return;
  }
  drawProfile(terrain.setProfile(profileStart, hit));
  $('toast').hidden = true;
  $('readout').hidden = true;
  setProfileMode(false);
}

function drawProfile({ samples, lengthM }) {
  $('profile').hidden = false;
  const canvas = $('profile-canvas');
  const ctx = canvas.getContext('2d');
  const W = canvas.width;
  const H = canvas.height;
  const pad = { l: 60, r: 12, t: 12, b: 28 };
  ctx.clearRect(0, 0, W, H);
  const valid = samples.filter((s) => s.depth !== null);
  if (valid.length < 2) return;
  let lo = Math.min(...valid.map((s) => s.depth));
  let hi = Math.max(...valid.map((s) => s.depth));
  if (hi - lo < 1) { hi += 0.5; lo -= 0.5; }
  const x = (d) => pad.l + (d / lengthM) * (W - pad.l - pad.r);
  const y = (v) => pad.t + ((hi - v) / (hi - lo)) * (H - pad.t - pad.b);
  let maxSlope = 0;
  for (let i = 1; i < valid.length; i++) {
    const run = valid[i].distance - valid[i - 1].distance;
    if (run > 0) maxSlope = Math.max(maxSlope, Math.abs(valid[i].depth - valid[i - 1].depth) / run);
  }
  const length = lengthM >= 1000 ? `${(lengthM / 1000).toFixed(2)} km` : `${lengthM.toFixed(0)} m`;
  $('profile-title').textContent = `${length} · ${depthText(lo)} – ${depthText(hi)} · steepest ${(Math.atan(maxSlope) * 180 / Math.PI).toFixed(0)}°`;
  ctx.font = '20px system-ui';
  ctx.fillStyle = '#aeb7c2';
  ctx.strokeStyle = '#2a3644';
  for (let i = 0; i <= 3; i++) {
    const v = lo + ((hi - lo) * i) / 3;
    ctx.beginPath();
    ctx.moveTo(pad.l, y(v));
    ctx.lineTo(W - pad.r, y(v));
    ctx.stroke();
    ctx.fillText(depthText(v), 2, y(v) + 6);
  }
  const grad = ctx.createLinearGradient(0, pad.t, 0, H - pad.b);
  grad.addColorStop(0, 'rgba(25,198,179,.55)');
  grad.addColorStop(1, 'rgba(12,44,99,.35)');
  ctx.beginPath();
  ctx.moveTo(x(valid[0].distance), H - pad.b);
  valid.forEach((s) => ctx.lineTo(x(s.distance), y(s.depth)));
  ctx.lineTo(x(valid[valid.length - 1].distance), H - pad.b);
  ctx.fillStyle = grad;
  ctx.fill();
  ctx.beginPath();
  valid.forEach((s, i) => (i ? ctx.lineTo(x(s.distance), y(s.depth)) : ctx.moveTo(x(s.distance), y(s.depth))));
  ctx.strokeStyle = '#ffd23f';
  ctx.lineWidth = 3;
  ctx.stroke();
}
$('profile-close').addEventListener('click', () => { $('profile').hidden = true; terrain.clearProfile(); });

// ------------------------------------------------------------------ chrome

$('btn-close').addEventListener('click', () => bridge.close());
document.querySelectorAll('[data-cam]').forEach((b) => b.addEventListener('click', () => terrain.cameraPreset(b.dataset.cam)));
document.querySelectorAll('[data-face]').forEach((b) => b.addEventListener('click', () => terrain.face(Number(b.dataset.face))));
$('auto-rotate').addEventListener('change', (e) => terrain.setAutoRotate(e.target.checked));

$('palette').value = settings.palette;
$('color-mode').value = settings.colorMode;
$('slope-max').value = String(settings.slopeMax);
$('sun-az').value = settings.sunAz;
$('sun-el').value = settings.sunEl;
$('shadows').checked = settings.shadows;
$('sea-surface').checked = settings.sea;
$('wpt').checked = settings.waypoints;
$('contour-on').checked = settings.contourOn;
$('contour-labels').checked = settings.contourLabels;
$('follow-boat').checked = settings.followBoat;
renderTabs();

// Hooks for the instrumented emulator test.
window.__terrainTest = {
  state: () => ({ cols: model?.cols, rows: model?.rows, min: model?.min, max: model?.max, exaggeration: settings.exaggeration,
    labels: document.querySelectorAll('.contour-label').length, isolated: model?.isolated || null, partial: model?.partial,
    cell: model && Math.min(model.cellX, model.cellY) }),
  tapCentre: () => {
    const r = document.querySelector('#view canvas').getBoundingClientRect();
    const hit = terrain.pick(r.left + r.width / 2, r.top + r.height / 2);
    return hit && { depth: hit.depth };
  },
  frames: () => terrain.renderCount
};
// Frames actually drawn, for the performance check.
window.__perf = {
  get frames() { return terrain.renderCount; },
  get triangles() { return terrain.lastTriangles; },
  get load() { return { bias: terrain.lodBias, ms: terrain.lastLoadMs, ratio: terrain.renderer.getPixelRatio() }; },
  get stream() { return stream && { ...stream.stats, levels: stream.active.map((l) => l.z), base: stream.base?.z, loaded: stream.base?.loaded, wanted: stream.wanted.size, inflight: stream.inflight.size }; }
};

loadGrid();
