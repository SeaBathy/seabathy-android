// Streams map tiles (satellite, an offline pack, a custom link) onto the 3D
// terrain without ever stalling the view.
//
// The GPU memory is set aside once and never grows: one picture of the whole
// survey, plus a few "levels" around the view, each twice as detailed and half
// as wide as the last. Each level is a grid of 256-pixel slots addressed by tile
// number (x mod slots, y mod slots), so moving the view only overwrites the
// slots of tiles that newly came into range, one small upload at a time; what's
// already there stays put. A slot is marked usable only once its tile is in,
// and the shader falls back to the next coarser level, then the whole-survey
// picture, so there is always something to show however fast the view moves.
//
// Nothing is drawn into a canvas, and nothing is ever re-uploaded whole.

import * as THREE from 'three';

const TILE = 256;
const TILES_AT_ONCE_ONLINE = 4;
const TILES_AT_ONCE_OFFLINE = 6;
/** Decoded tiles waiting for their upload, at most: each holds 256 KB. */
const MAX_READY = 6;
const RETRY_MISSING_MS = 30_000;
/** Keep a level's area until the view has moved this many tiles from its middle. */
const RECENTRE_TILES = 1;
const ZOOM_HYSTERESIS = 0.25;

const mercX = (lng) => (lng + 180) / 360;
const mercY = (lat) => {
  const s = Math.sin((Math.max(-85.05, Math.min(85.05, lat)) * Math.PI) / 180);
  return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
};
const mod = (v, n) => ((v % n) + n) % n;

/** A survey's or map's { west, south, east, north } as a Web Mercator box, 0..1 across the world. */
export function mercBox(b) {
  return { x0: mercX(b.west), x1: mercX(b.east), y0: mercY(b.north), y1: mercY(b.south) };
}

function overlap(a, b) {
  if (!b) return a;
  const box = { x0: Math.max(a.x0, b.x0), x1: Math.min(a.x1, b.x1), y0: Math.max(a.y0, b.y0), y1: Math.min(a.y1, b.y1) };
  return box.x1 > box.x0 && box.y1 > box.y0 ? box : null;
}

function tileRange(box, z) {
  const scale = 2 ** z;
  const last = scale - 1;
  const range = {
    x0: Math.max(0, Math.floor(box.x0 * scale)), x1: Math.min(last, Math.ceil(box.x1 * scale) - 1),
    y0: Math.max(0, Math.floor(box.y0 * scale)), y1: Math.min(last, Math.ceil(box.y1 * scale) - 1)
  };
  range.nx = range.x1 - range.x0 + 1;
  range.ny = range.y1 - range.y0 + 1;
  return range;
}

/**
 * The zoom for the whole-survey picture: the sharpest that fits [maxSize]
 * pixels a side at the tiles' own size, so no resampling. Null when even the
 * map's least zoom doesn't fit (a pack made only for close zooms, over a wide
 * survey): then only the levels around the view are used.
 */
export function baseZoom(box, minzoom, maxzoom, maxSize) {
  for (let z = maxzoom; z >= minzoom; z--) {
    const r = tileRange(box, z);
    if (r.nx * TILE <= maxSize && r.ny * TILE <= maxSize) return z;
  }
  return null;
}

/** The sizes to set aside, from the longer side the app allows this phone for a map. */
export function levelPlan(maxSize) {
  if (maxSize >= 2048) return { slots: 8, levels: 3 };
  if (maxSize >= 1536) return { slots: 6, levels: 3 };
  if (maxSize >= 1024) return { slots: 4, levels: 3 };
  return { slots: 3, levels: 2 };
}

export class ImageryStream {
  /**
   * layer: { id, tiles: '/drape/id/{z}/{x}/{y}', minzoom, maxzoom, bounds, offline }.
   * uniforms: the terrain material's. onWake: a decoded tile is ready for
   * pump(). onStatus(text, warn): what to tell the user.
   */
  constructor(renderer, uniforms, layer, { maxSize, sharpen, onWake, onStatus, onLog }) {
    this.renderer = renderer;
    this.gl = renderer.getContext();
    this.uniforms = uniforms;
    this.layer = layer;
    this.id = layer.id;
    this.maxSize = Math.min(maxSize, renderer.capabilities.maxTextureSize);
    const plan = levelPlan(this.maxSize);
    this.slots = plan.slots;
    this.levelCount = plan.levels;
    this.sharpen = sharpen;
    this.onWake = onWake;
    this.onStatus = onStatus;
    this.onLog = onLog;
    this.layerBox = layer.bounds ? mercBox(layer.bounds) : null;

    this.survey = null;
    this.base = null;
    this.clipTexture = null;
    this.clipFailed = false;
    /** For each layer of the texture: the tile in each slot, and the area and zoom it holds now. */
    this.slotTile = Array.from({ length: this.levelCount }, () => new Array(this.slots * this.slots).fill(null));
    this.regions = new Array(this.levelCount).fill(null);
    this.zTop = null;
    this.active = [];
    this.wanted = new Map();
    this.inflight = new Map();
    this.ready = [];
    this.missing = new Map();
    this.view = null;
    this.moving = false;
    this.disposed = false;
    this.lastStatus = null;
    this.stats = { requested: 0, uploaded: 0, cancelled: 0, uploadMs: 0, worstUploadMs: 0 };
    this.zeroUniforms();
  }

  zeroUniforms() {
    const u = this.uniforms;
    u.uBaseOn.value = 0;
    u.uLevels.value = 0;
    u.uSlots.value = this.slots;
  }

  // ---------------------------------------------------------------- the survey's own picture

  /** Sets the survey the terrain shows ({ west, south, east, north }); the mesh's map coordinates run 0..1 across it. */
  setSurvey(bounds) {
    const box = mercBox(bounds);
    const same = this.survey && ['x0', 'x1', 'y0', 'y1'].every((k) => Math.abs(this.survey[k] - box[k]) < 1e-12);
    this.survey = box;
    this.centreLat = (bounds.north + bounds.south) / 2;
    if (!same) {
      this.dropBase();
      this.planBase();
    }
    this.publishLevels();
    this.retarget();
    this.report();
  }

  dropBase() {
    if (this.base) this.gl.deleteTexture(this.base.texture);
    this.base = null;
    this.uniforms.uBaseOn.value = 0;
    this.uniforms.uBase.value = null;
  }

  planBase() {
    const area = overlap(this.survey, this.layerBox);
    this.noCover = !area;
    if (!area) return;
    const minzoom = this.layer.minzoom ?? 0;
    const maxzoom = this.layer.maxzoom ?? 19;
    const z = baseZoom(area, minzoom, maxzoom, Math.max(TILE, this.maxSize));
    this.baseZ = z;
    if (z === null) return;
    const range = tileRange(area, z);
    const width = range.nx * TILE;
    const height = range.ny * TILE;
    const gl = this.gl;
    const texture = gl.createTexture();
    this.renderer.state.bindTexture(gl.TEXTURE_2D, texture);
    const mips = Math.floor(Math.log2(Math.max(width, height))) + 1;
    gl.texStorage2D(gl.TEXTURE_2D, mips, gl.SRGB8_ALPHA8, width, height);
    if (gl.getError() === gl.OUT_OF_MEMORY) {
      gl.deleteTexture(texture);
      this.onLog?.(`no memory for a ${width}x${height} map picture`);
      this.baseZ = null;
      return;
    }
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.base = { z, range, texture, pending: range.nx * range.ny, total: range.nx * range.ny, loaded: 0, done: new Set(), mipped: false };
    const holder = new THREE.Texture();
    this.renderer.properties.get(holder).__webglTexture = texture;
    this.uniforms.uBase.value = holder;
    const scale = 2 ** z;
    const w = range.nx / scale;
    const h = range.ny / scale;
    const s = this.survey;
    this.uniforms.uBaseXf.value.set((s.x1 - s.x0) / w, (s.y1 - s.y0) / h, (s.x0 - range.x0 / scale) / w, (s.y0 - range.y0 / scale) / h);
    this.uniforms.uBaseOn.value = 1;
  }

  // ---------------------------------------------------------------- following the view

  /**
   * view: { cx, cy, rect: { x0, y0, x1, y1 } | null, zoom } in Web Mercator:
   * where the detail should centre, what's on screen, and the zoom that gives
   * about one tile pixel per screen pixel there. moving: the camera is moving.
   */
  update(view, moving) {
    this.view = view;
    this.moving = moving;
    this.placeLevels();
    this.publishLevels();
    this.retarget();
    this.report();
  }

  setSharpen(on) {
    this.sharpen = on;
    if (!on) this.freeClip();
    if (this.view) this.update(this.view, this.moving);
  }

  freeClip() {
    if (this.clipTexture) this.gl.deleteTexture(this.clipTexture);
    this.clipTexture = null;
    this.uniforms.uClip.value = null;
    this.slotTile.forEach((s) => s.fill(null));
    this.regions.fill(null);
    this.active = [];
    this.zTop = null;
  }

  placeLevels() {
    const view = this.view;
    const minzoom = this.layer.minzoom ?? 0;
    const maxzoom = this.layer.maxzoom ?? 19;
    this.active = [];
    if (!view || !view.rect || !this.sharpen || this.clipFailed || this.noCover) {
      return;
    }
    // Only moves a whole zoom once it's clearly past the halfway mark, so a slow pinch doesn't flip back and forth.
    const ideal = Math.max(minzoom, Math.min(maxzoom, view.zoom));
    if (this.zTop === null || Math.abs(ideal - this.zTop) > 0.5 + ZOOM_HYSTERESIS) this.zTop = Math.round(ideal);
    const floor = this.baseZ === null ? minzoom - 1 : this.baseZ;
    for (let k = 0; k < this.levelCount; k++) {
      const z = this.zTop - k;
      if (z <= floor || z < minzoom) break;
      const layer = mod(z, this.levelCount);
      const scale = 2 ** z;
      const half = this.slots / 2;
      const cx = view.cx * scale;
      const cy = view.cy * scale;
      let region = this.regions[layer];
      if (!region || region.z !== z || Math.abs(cx - (region.ox + half)) > RECENTRE_TILES || Math.abs(cy - (region.oy + half)) > RECENTRE_TILES) {
        region = { z, ox: Math.round(cx - half), oy: Math.round(cy - half) };
        this.regions[layer] = region;
      }
      this.active.push({ z, layer, region });
    }
    if (this.active.length && !this.clipTexture) this.allocateClip();
    if (!this.clipTexture) this.active = [];
  }

  allocateClip() {
    const gl = this.gl;
    const texture = gl.createTexture();
    const size = this.slots * TILE;
    this.renderer.state.bindTexture(gl.TEXTURE_2D_ARRAY, texture);
    gl.texStorage3D(gl.TEXTURE_2D_ARRAY, 1, gl.SRGB8_ALPHA8, size, size, this.levelCount);
    if (gl.getError() === gl.OUT_OF_MEMORY) {
      gl.deleteTexture(texture);
      this.clipFailed = true;
      this.onLog?.(`no memory for ${this.levelCount} detail levels of ${size}`);
      return;
    }
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_S, gl.REPEAT);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_T, gl.REPEAT);
    this.clipTexture = texture;
    const holder = new THREE.DataArrayTexture(null, 1, 1, 1);
    this.renderer.properties.get(holder).__webglTexture = texture;
    this.uniforms.uClip.value = holder;
  }

  /** Tells the shader where each level lies, and which of its slots are usable. */
  publishLevels() {
    const u = this.uniforms;
    u.uSlots.value = this.slots;
    u.uLevels.value = this.survey ? this.active.length : 0;
    if (!this.survey) return;
    const s = this.survey;
    this.active.forEach(({ z, layer, region }, k) => {
      const scale = 2 ** z;
      u.uLevelXf.value[k].set((s.x1 - s.x0) * scale, (s.y1 - s.y0) * scale, s.x0 * scale - region.ox, s.y0 * scale - region.oy);
      u.uLevelSlot.value[k].set(mod(region.ox, this.slots), mod(region.oy, this.slots), layer);
      let lo = 0;
      let hi = 0;
      for (let dy = 0; dy < this.slots; dy++) {
        for (let dx = 0; dx < this.slots; dx++) {
          const x = region.ox + dx;
          const y = region.oy + dy;
          const slot = mod(y, this.slots) * this.slots + mod(x, this.slots);
          if (this.slotTile[layer][slot] === `${z}/${x}/${y}`) {
            if (slot < 32) lo = (lo | (1 << slot)) >>> 0;
            else hi = (hi | (1 << (slot - 32))) >>> 0;
          }
        }
      }
      u.uValidLo.value[k] = lo;
      u.uValidHi.value[k] = hi;
    });
  }

  // ---------------------------------------------------------------- what to fetch

  /**
   * Works out which tiles are wanted, best first: the whole-survey picture,
   * then coarse levels before fine ones, each from the middle of the view out.
   * Whatever is no longer wanted is cancelled. While the camera moves, an
   * online map skips the sharpest level, which would be out of date by the
   * time it came.
   */
  retarget() {
    const wanted = new Map();
    this.missingInView = 0;
    const add = (item, priority) => {
      if (this.isMissing(item.key)) this.missingInView++;
      else wanted.set(item.key, { ...item, priority });
    };
    const base = this.base;
    const area = this.survey && overlap(this.survey, this.layerBox);
    const cx = this.view?.cx ?? (this.survey ? (this.survey.x0 + this.survey.x1) / 2 : 0);
    const cy = this.view?.cy ?? (this.survey ? (this.survey.y0 + this.survey.y1) / 2 : 0);
    if (base) {
      const scale = 2 ** base.z;
      const { x0, x1, y0, y1 } = base.range;
      for (let y = y0; y <= y1; y++) {
        for (let x = x0; x <= x1; x++) {
          const key = `${base.z}/${x}/${y}`;
          if (base.done.has(key)) continue;
          add({ key, z: base.z, x, y, base: true }, Math.hypot(x + 0.5 - cx * scale, y + 0.5 - cy * scale));
        }
      }
    }
    if (area && this.active.length) {
      const view = this.view;
      const margin = (view.rect.x1 - view.rect.x0) * 0.1;
      const seen = overlap(area, { x0: view.rect.x0 - margin, x1: view.rect.x1 + margin, y0: view.rect.y0 - margin, y1: view.rect.y1 + margin });
      if (seen) {
        // Coarsest first; the sharpest (index 0) last, and not at all while moving online.
        for (let k = this.active.length - 1; k >= 0; k--) {
          if (k === 0 && this.moving && !this.layer.offline && this.active.length > 1) continue;
          const { z, layer, region } = this.active[k];
          const scale = 2 ** z;
          const r = tileRange(seen, z);
          for (let y = Math.max(r.y0, region.oy); y <= Math.min(r.y1, region.oy + this.slots - 1); y++) {
            for (let x = Math.max(r.x0, region.ox); x <= Math.min(r.x1, region.ox + this.slots - 1); x++) {
              const key = `${z}/${x}/${y}`;
              const slot = mod(y, this.slots) * this.slots + mod(x, this.slots);
              if (this.slotTile[layer][slot] === key) continue;
              add({ key, z, x, y, layer, slot }, 1e6 * (this.active.length - k) + Math.hypot(x + 0.5 - cx * scale, y + 0.5 - cy * scale));
            }
          }
        }
      }
    }
    this.wanted = wanted;
    for (const [key, job] of this.inflight) {
      if (!wanted.has(key) && !job.item.base) {
        job.controller.abort();
        this.inflight.delete(key);
        this.stats.cancelled++;
      }
    }
    this.ready = this.ready.filter((entry) => {
      if (wanted.has(entry.item.key)) return true;
      entry.bitmap.close();
      return false;
    });
    this.startFetches();
  }

  isMissing(key) {
    const at = this.missing.get(key);
    if (at === undefined) return false;
    if (this.layer.offline || performance.now() - at < RETRY_MISSING_MS) return true;
    this.missing.delete(key);
    return false;
  }

  startFetches() {
    if (this.disposed) return;
    const limit = this.layer.offline ? TILES_AT_ONCE_OFFLINE : TILES_AT_ONCE_ONLINE;
    if (this.inflight.size >= limit || this.ready.length >= MAX_READY) return;
    const waiting = new Set(this.ready.map((e) => e.item.key));
    const queue = [...this.wanted.values()]
      .filter((item) => !this.inflight.has(item.key) && !waiting.has(item.key))
      .sort((a, b) => a.priority - b.priority);
    for (const item of queue) {
      if (this.inflight.size + this.ready.length >= limit + MAX_READY || this.inflight.size >= limit) break;
      this.fetchTile(item);
    }
  }

  async fetchTile(item) {
    const controller = new AbortController();
    const job = { controller, item };
    this.inflight.set(item.key, job);
    this.stats.requested++;
    const url = this.layer.tiles.replace('{z}', item.z).replace('{x}', item.x).replace('{y}', item.y);
    let bitmap = null;
    let failed = false;
    try {
      const response = await fetch(url, { signal: controller.signal });
      if (!response.ok) failed = true;
      else {
        // Decoded away from the page's main thread, at the slot's size whatever the map's tile size.
        bitmap = await createImageBitmap(await response.blob(), {
          premultiplyAlpha: 'premultiply', colorSpaceConversion: 'none', resizeWidth: TILE, resizeHeight: TILE, resizeQuality: 'medium'
        });
      }
    } catch (e) {
      if (e?.name !== 'AbortError') failed = true;
    }
    if (this.inflight.get(item.key) === job) this.inflight.delete(item.key);
    if (this.disposed || controller.signal.aborted) {
      bitmap?.close();
      return;
    }
    if (failed) this.tileFailed(item);
    else if (bitmap) {
      this.ready.push({ item, bitmap });
      this.ready.sort((a, b) => a.item.priority - b.item.priority);
      this.onWake?.();
    }
    this.startFetches();
    this.report();
  }

  tileFailed(item) {
    this.missing.set(item.key, performance.now());
    this.wanted.delete(item.key);
    this.missingInView = (this.missingInView || 0) + 1;
    if (item.base && this.base && item.z === this.base.z) {
      this.base.done.add(item.key);
      this.base.pending--;
      if (this.base.pending === 0) this.finishBase();
    }
  }

  // ---------------------------------------------------------------- uploads

  get hasReady() {
    return this.ready.length > 0;
  }

  /** Uploads decoded tiles for up to [budgetMs]. True when the picture changed. */
  pump(budgetMs) {
    if (!this.ready.length) return false;
    const start = performance.now();
    let changed = false;
    while (this.ready.length && (!changed || performance.now() - start < budgetMs)) {
      const { item, bitmap } = this.ready.shift();
      const t0 = performance.now();
      if (item.base ? this.uploadBase(item, bitmap) : this.uploadLevel(item, bitmap)) {
        changed = true;
        this.stats.uploaded++;
      }
      bitmap.close();
      const took = performance.now() - t0;
      this.stats.uploadMs += took;
      this.stats.worstUploadMs = Math.max(this.stats.worstUploadMs, took);
    }
    if (changed) this.publishLevels();
    this.startFetches();
    this.report();
    return changed;
  }

  uploadBase(item, bitmap) {
    const base = this.base;
    if (!base || item.z !== base.z || base.done.has(item.key)) return false;
    const gl = this.gl;
    this.renderer.state.bindTexture(gl.TEXTURE_2D, base.texture);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texSubImage2D(gl.TEXTURE_2D, 0, (item.x - base.range.x0) * TILE, (item.y - base.range.y0) * TILE, TILE, TILE, gl.RGBA, gl.UNSIGNED_BYTE, bitmap);
    base.done.add(item.key);
    base.loaded++;
    base.pending--;
    this.wanted.delete(item.key);
    if (base.pending === 0) this.finishBase();
    return true;
  }

  /** The whole picture is in: its smaller copies, for when it's seen from far away. Happens once. */
  finishBase() {
    const base = this.base;
    if (!base || base.mipped) return;
    base.mipped = true;
    if (!base.loaded) return;
    const gl = this.gl;
    this.renderer.state.bindTexture(gl.TEXTURE_2D, base.texture);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    const aniso = gl.getExtension('EXT_texture_filter_anisotropic');
    if (aniso) gl.texParameterf(gl.TEXTURE_2D, aniso.TEXTURE_MAX_ANISOTROPY_EXT, Math.min(4, this.renderer.capabilities.getMaxAnisotropy()));
    this.onWake?.();
  }

  uploadLevel(item, bitmap) {
    if (!this.clipTexture) return false;
    const region = this.regions[item.layer];
    // Its level has moved on since this was asked for.
    if (!region || region.z !== item.z || item.x < region.ox || item.y < region.oy || item.x >= region.ox + this.slots || item.y >= region.oy + this.slots) return false;
    const gl = this.gl;
    const sx = mod(item.x, this.slots);
    const sy = mod(item.y, this.slots);
    this.renderer.state.bindTexture(gl.TEXTURE_2D_ARRAY, this.clipTexture);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texSubImage3D(gl.TEXTURE_2D_ARRAY, 0, sx * TILE, sy * TILE, item.layer, TILE, TILE, 1, gl.RGBA, gl.UNSIGNED_BYTE, bitmap);
    this.slotTile[item.layer][sy * this.slots + sx] = item.key;
    this.wanted.delete(item.key);
    return true;
  }

  // ---------------------------------------------------------------- what to say

  report() {
    let text;
    let warn = false;
    const base = this.base;
    const metres = (z) => (156543.03 * Math.cos((this.centreLat * Math.PI) / 180)) / 2 ** z;
    const metresText = (m) => (m < 10 ? m.toFixed(1) : Math.round(m));
    const pendingLevels = [...this.wanted.values()].filter((i) => !i.base).length + (this.moving ? 1 : 0);
    if (this.noCover) {
      text = "This pack doesn't cover the area shown. Pick another map, or open 3D over the pack's area.";
      warn = true;
    } else if (base && base.pending > 0) {
      text = `Fetching map tiles… ${Math.round(((base.total - base.pending) / base.total) * 100)}%`;
    } else if (base && !base.loaded && !this.active.length) {
      text = this.layer.offline ? "This pack doesn't cover the area shown. Pick another map, or open 3D over the pack's area."
        : 'No map tiles came back: no signal, or nothing here.';
      warn = true;
    } else if (!base && !this.active.length) {
      text = 'Zoom in to see this map: it only has close-up detail.';
    } else if (this.active.length && pendingLevels > 0) {
      text = 'Sharpening the view…';
    } else {
      const z = this.active.length ? this.active[0].z : base.z;
      const missing = this.missingInView;
      text = `${this.active.length ? 'In view: zoom' : 'Zoom'} ${z}, about ${metresText(metres(z))} m per pixel${missing ? ` · ${missing} tiles missing` : ''}`;
    }
    if (text !== this.lastStatus) {
      this.lastStatus = text;
      this.onStatus?.(text, warn);
    }
  }

  dispose() {
    this.disposed = true;
    for (const job of this.inflight.values()) job.controller.abort();
    this.inflight.clear();
    this.ready.forEach((e) => e.bitmap.close());
    this.ready = [];
    this.dropBase();
    if (this.clipTexture) this.gl.deleteTexture(this.clipTexture);
    this.clipTexture = null;
    this.uniforms.uClip.value = null;
    this.uniforms.uLevels.value = 0;
  }
}
