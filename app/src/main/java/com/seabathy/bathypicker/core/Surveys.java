package com.seabathy.bathypicker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The list of original surveys (catalogue/surveys.json), read the same way as the desktop
 * BathyPicker does (clean_survey, in_box and the helpers next to them in bathy_picker.py), so
 * both list and pick the same surveys.
 */
public final class Surveys {
    public static final String SCHEMA = "bathypicker-surveys/v1";
    public static final String URL = "https://raw.githubusercontent.com/SeaBathy/BathyPicker/main/catalogue/surveys.json";
    public static final String REPORT_URL = "https://github.com/SeaBathy/BathyPicker/issues/new";
    static final String HOST = "https://ausseabed-public-bathymetry.s3.ap-southeast-2.amazonaws.com/";
    /** IMAS's Bathymetry of the Australian Marine Parks: one cloud-optimised GeoTIFF per park. */
    public static final String PARK_HOST = "https://data.imas.utas.edu.au/attachments/AMP_bathymetry_derivatives/bathy/";
    static final double BLEND_MIN_M = 200.0, BLEND_MAX_M = 500.0;
    /** Survey kinds: an original survey file read in place, a marine park mosaic, or a coverage the warehouse cuts for the box. */
    public static final String SURVEY = "", PARK = "park", WCS = "wcs";

    public static final class SFile {
        public String url, crs;
        public long bytes;
        public double[] bbox;
        public List<double[]> cells;
        public boolean tiled;
        public Proj proj;
        /** Bytes of an all-NoData tile in this file (0 if not known), to tell from the tile sizes alone where it has depths. */
        public long emptyTile;
        /**
         * The header emptyTile was measured on: compression, predictor, tile width and height,
         * image width and height. A file republished differently has other empty tiles, so then
         * the size isn't trusted. Null if not known.
         */
        public int[] emptyFor;
        /** How many overviews the file has, -1 if not known: one without is read at full detail whatever the output. */
        public int overviews = -1;
    }

    public static final class Survey {
        public String id, name, statedDatum, verdict, updated, credit, source, licence, note;
        public Integer year;
        public double resM, shiftM, stepM;
        public int sign;
        public double[] bbox;
        public boolean defaultOff;
        public List<SFile> files;
        /** SURVEY, PARK or WCS. */
        public String kind = SURVEY;
        /** WCS: the warehouse's coverage id. */
        public String coverage;
        /**
         * The IMAS detail (metres) this layer is up against where it has data, when that's coarser
         * than the box's finest IMAS: a park mosaic that lies mostly in deep water, where IMAS is
         * 32 m or coarser. 0 for the usual comparison with the box's finest IMAS.
         */
        public double imasM;

        public Survey withFiles(List<SFile> f) {
            Survey s = new Survey();
            s.id = id; s.name = name; s.statedDatum = statedDatum; s.verdict = verdict; s.updated = updated;
            s.credit = credit; s.source = source; s.licence = licence; s.note = note; s.year = year;
            s.resM = resM; s.shiftM = shiftM; s.stepM = stepM; s.sign = sign; s.bbox = bbox; s.defaultOff = defaultOff;
            s.kind = kind; s.coverage = coverage; s.imasM = imasM;
            s.files = f;
            return s;
        }
    }

    public final List<Survey> list;
    public final String built;
    public String origin;

    private Surveys(List<Survey> list, String built, String origin) {
        this.list = list;
        this.built = built;
        this.origin = origin;
    }

    /** Null if the text isn't a catalogue this app understands. */
    @SuppressWarnings("unchecked")
    public static Surveys parse(String text, String origin) {
        Object o;
        try {
            o = Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(o instanceof Map) || !SCHEMA.equals(((Map<String, Object>) o).get("schema"))) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        List<Survey> out = new ArrayList<>();
        Object raw = m.get("surveys");
        if (raw instanceof List) {
            for (Object s : (List<Object>) raw) {
                if (!(s instanceof Map)) continue;
                Survey c = clean((Map<String, Object>) s);
                if (c != null) out.add(c);
            }
        }
        Object built = m.get("built");
        return new Surveys(out, built == null ? "" : String.valueOf(built), origin);
    }

    private static String str(Object o, int max) {
        String s = o == null ? "" : String.valueOf(o);
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) return Double.parseDouble((String) o);
        throw new IllegalArgumentException("not a number");
    }

    private static double[] box(Object o) {
        List<?> l = (List<?>) o;
        if (l.size() != 4) throw new IllegalArgumentException("box");
        return new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2)), num(l.get(3))};
    }

    /**
     * clean_survey: keep an entry only if it is well-formed and points at the known public host:
     * AusSeabed's bucket for surveys, IMAS for park mosaics, and for a warehouse coverage only its
     * id (Warehouse makes the request).
     */
    @SuppressWarnings("unchecked")
    static Survey clean(Map<String, Object> s) {
        try {
            String kind = s.get("kind") == null ? SURVEY : String.valueOf(s.get("kind"));
            if (!kind.equals(SURVEY) && !kind.equals(PARK) && !kind.equals(WCS)) return null;
            String coverage = null;
            if (kind.equals(WCS)) {
                coverage = String.valueOf(s.get("coverage"));
                if (!coverage.matches("ausseabed__[A-Za-z0-9_\\-]{1,200}")) return null;
            }
            List<SFile> files = new ArrayList<>();
            for (Object fo : (List<Object>) s.get("files")) {
                Map<String, Object> f = (Map<String, Object>) fo;
                String url;
                if (kind.equals(WCS)) {
                    url = Warehouse.pseudoUrl(coverage);
                } else {
                    url = String.valueOf(f.get("url"));
                    if (!url.startsWith(kind.equals(PARK) ? PARK_HOST : HOST)) return null;
                    for (char ch : " \"<>\\?#&".toCharArray()) if (url.indexOf(ch) >= 0) return null;
                }
                SFile sf = new SFile();
                sf.url = url;
                sf.bbox = box(f.get("bbox"));
                sf.cells = new ArrayList<>();
                Object cells = f.get("cells");
                if (cells instanceof List) {
                    List<Object> cl = (List<Object>) cells;
                    for (int i = 0; i < Math.min(600, cl.size()); i++) {
                        List<?> c = (List<?>) cl.get(i);
                        if (c.size() == 4) sf.cells.add(box(c));
                    }
                }
                if (sf.cells.isEmpty()) sf.cells.add(sf.bbox);
                Object b = f.get("bytes");
                sf.bytes = Math.max(0, b == null ? 0 : (long) num(b));
                Object t = f.get("tiled");
                sf.tiled = t == null || Boolean.TRUE.equals(t);
                Object ov = f.get("overviews");
                sf.overviews = ov == null ? -1 : (int) Math.max(0, Math.min(30, num(ov)));
                Object empty = f.get("empty_tile_bytes");
                sf.emptyTile = empty == null ? 0 : Math.max(0, (long) num(empty));
                Object emptyFor = f.get("empty_tile_for");
                if (emptyFor instanceof Map) {
                    Map<String, Object> e = (Map<String, Object>) emptyFor;
                    List<?> tile = (List<?>) e.get("tile"), size = (List<?>) e.get("size");
                    sf.emptyFor = new int[]{(int) num(e.get("compression")), (int) num(e.get("predictor")),
                            (int) num(tile.get(0)), (int) num(tile.get(1)), (int) num(size.get(0)), (int) num(size.get(1))};
                }
                sf.crs = kind.equals(WCS) ? "EPSG:4326" : f.get("crs") == null ? null : String.valueOf(f.get("crs"));
                sf.proj = Proj.parse(sf.crs);
                files.add(sf);
            }
            Survey c = new Survey();
            c.kind = kind;
            c.coverage = coverage;
            Object imasM = s.get("imas_m");
            c.imasM = imasM == null ? 0 : Math.max(0, Math.min(1000, num(imasM)));
            c.sign = s.get("sign") == null ? 1 : (int) num(s.get("sign"));
            c.shiftM = s.get("shift_m") == null ? 0 : num(s.get("shift_m"));
            c.resM = num(s.get("res_m"));
            c.bbox = box(s.get("bbox"));
            if ((c.sign != 1 && c.sign != -1) || Math.abs(c.shiftM) > 10 || !(c.resM >= 0.05 && c.resM <= 10000) || files.isEmpty())
                return null;
            Object year = s.get("year");
            c.year = year instanceof Number && ((Number) year).doubleValue() == Math.rint(((Number) year).doubleValue())
                    ? (int) ((Number) year).doubleValue() : null;
            c.id = str(s.get("id"), 120);
            if (s.get("id") == null) return null;
            Object name = s.get("name");
            c.name = str(name == null || String.valueOf(name).isEmpty() ? s.get("id") : name, 120);
            c.files = files;
            c.statedDatum = s.get("stated_datum") == null ? null : String.valueOf(s.get("stated_datum"));
            Object check = s.get("check");
            Map<String, Object> ck = check instanceof Map ? (Map<String, Object>) check : Collections.emptyMap();
            c.verdict = str(ck.get("verdict"), 200);
            c.updated = str(s.get("updated"), 10);
            c.credit = str(s.get("credit"), 300);
            c.source = str(s.get("source"), 120);
            c.licence = str(s.get("licence"), 40);
            c.defaultOff = Boolean.TRUE.equals(s.get("default_off"));
            c.note = str(s.get("note"), 300);
            c.stepM = stepEstimate(ck, c.shiftM);
            return c;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** step_estimate: rough size of the depth step between a survey (after its shift) and IMAS, 0-3 m. */
    static double stepEstimate(Map<String, Object> check, double shift) {
        double diff, spread;
        try {
            diff = check.get("median_diff_m") == null ? 0 : num(check.get("median_diff_m"));
            spread = Math.abs(check.get("mad_m") == null ? 0 : num(check.get("mad_m")));
        } catch (RuntimeException e) {
            return 0.0;
        }
        double left = shift != 0 ? Math.min(Math.abs(diff - shift), Math.abs(diff + shift)) : Math.abs(diff);
        return pyRound(Math.min(3.0, left + spread), 2);
    }

    /** Python's round(x, n): to nearest, ties to even, on the decimal digits. */
    static double pyRound(double x, int n) {
        return new java.math.BigDecimal(x).setScale(n, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    /** blend_band_m: how far into a survey its edge fades: 200 m, up to 500 m where the step is bigger. */
    public static double blendBandM(Survey s, double resM) {
        return Math.max(4 * resM, Math.min(BLEND_MAX_M, BLEND_MIN_M + 100.0 * s.stepM));
    }

    static boolean intersects(double[] a, double[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** in_box: the surveys with a data cell in the box, each with only its files that do. */
    public List<Survey> inBox(double[] bbox) {
        List<Survey> out = new ArrayList<>();
        for (Survey s : list) {
            if (!intersects(bbox, s.bbox)) continue;
            List<SFile> files = new ArrayList<>();
            for (SFile f : s.files) {
                for (double[] c : f.cells) {
                    if (intersects(bbox, c)) { files.add(f); break; }
                }
            }
            if (!files.isEmpty()) out.add(s.withFiles(files));
        }
        return out;
    }

    /**
     * The catalogue with the app's own extra layers added (the IMAS marine park mosaics and the
     * warehouse coverages, bundled with the app), so they're used whichever copy of the catalogue
     * is newest. An extra whose id the catalogue already has is left out.
     */
    public static Surveys merge(Surveys base, Surveys extra) {
        if (extra == null || extra.list.isEmpty()) return base;
        if (base == null) return extra;
        List<Survey> all = new ArrayList<>(base.list);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Survey s : base.list) ids.add(s.id);
        for (Survey s : extra.list) if (ids.add(s.id)) all.add(s);
        return new Surveys(all, base.built, base.origin);
    }

    /**
     * Too coarse to be ticked by default: no finer than the IMAS it would cover. That's the box's
     * finest IMAS (10 m on the shelf), or for a layer lying mostly in deep water (imasM), the
     * coarser IMAS there.
     */
    public static boolean coarser(Survey s, Double imasRes) {
        if (imasRes == null) return false;
        return s.resM >= Math.max(imasRes, s.imasM) * 0.99;
    }

    /**
     * What's laid on the IMAS composite, for the line under the box: "the Solitary Islands marine
     * park mosaic and 2 multibeam surveys on top". Empty when there's nothing.
     */
    public static String onTop(List<Survey> chosen) {
        List<String> parks = new ArrayList<>();
        int surveys = 0;
        for (Survey s : chosen) {
            if (PARK.equals(s.kind)) parks.add(s.name.replaceFirst(" marine park mosaic.*$", ""));
            else surveys++;
        }
        List<String> parts = new ArrayList<>();
        if (parks.size() == 1) parts.add("the " + parks.get(0) + " marine park mosaic");
        else if (parks.size() > 1) parts.add(parks.size() + " marine park mosaics");
        if (surveys > 0) parts.add(surveys + " multibeam survey" + (surveys == 1 ? "" : "s"));
        return parts.isEmpty() ? "" : String.join(" and ", parts) + " on top";
    }

    /**
     * Whether a tiled EPSG:4326 file has depths in the box, from its tile sizes alone (an empty
     * tile compresses to emptyTile bytes, measured on the header emptyFor): the tile rows over the
     * box are read at the level where the box spans at most 8 tiles each way. True when it can't
     * tell, including when the file is no longer the one measured (republished, say, with other
     * compression), so a park is never left out on a stale size.
     */
    static boolean hasData(RemoteTiff t, long emptyTile, int[] emptyFor, double[] box, Http.Cancel cancel) throws java.io.IOException {
        if (emptyTile <= 0 || t.epsg != 4326 || !sameHeader(t, emptyFor)) return true;
        int lv = 0;
        for (int k = 0; k < t.levels.size(); k++) {
            RemoteTiff.Level L = t.levels.get(k);
            lv = k;
            if ((box[2] - box[0]) / L.resX / L.tileW <= 8 && (box[3] - box[1]) / L.resY / L.tileH <= 8) break;
        }
        RemoteTiff.Level L = t.levels.get(lv);
        int x0 = (int) Math.floor((box[0] - t.gt[0]) / L.resX), x1 = (int) Math.floor((box[2] - t.gt[0]) / L.resX);
        int y0 = (int) Math.floor((t.gt[3] - box[3]) / L.resY), y1 = (int) Math.floor((t.gt[3] - box[1]) / L.resY);
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(L.width - 1, x1); y1 = Math.min(L.height - 1, y1);
        if (x1 < x0 || y1 < y0) return false;
        long floor = emptyTile * 3 / 2 + 256;
        for (int ty = y0 / L.tileH; ty <= y1 / L.tileH; ty++) {
            long[][] idx = t.tileRow(L, ty, cancel);
            for (int tx = x0 / L.tileW; tx <= x1 / L.tileW; tx++) if (idx[1][tx] > floor) return true;
        }
        return false;
    }

    /** The file still has the header the empty-tile size was measured on (see SFile.emptyFor). */
    static boolean sameHeader(RemoteTiff t, int[] f) {
        if (f == null || f.length != 6) return false;
        RemoteTiff.Level L = t.levels.get(0);
        return L.compression == f[0] && L.predictor == f[1] && L.tileW == f[2] && L.tileH == f[3] && L.width == f[4] && L.height == f[5]
                && !L.strip;
    }

    /** overlap_fraction: share of box a covered by box b. */
    static double overlapFraction(double[] a, double[] b) {
        double w = Math.max(a[0], b[0]), s = Math.max(a[1], b[1]), e = Math.min(a[2], b[2]), n = Math.min(a[3], b[3]);
        if (e <= w || n <= s) return 0.0;
        return (e - w) * (n - s) / Math.max(1e-12, (a[2] - a[0]) * (a[3] - a[1]));
    }

    static double cellsCover(double[] bbox, SFile f) {
        double t = 0;
        for (double[] c : f.cells) t += overlapFraction(bbox, c);
        return t;
    }

    /** survey_read_bytes: rough bytes read from one file for this box. */
    static double readBytes(double[] bbox, SFile f) {
        double[] fb = f.bbox;
        if (!f.tiled) {
            double n = Math.min(bbox[3], fb[3]), s = Math.max(bbox[1], fb[1]);
            return f.bytes * Math.max(0.0, n - s) / Math.max(1e-12, fb[3] - fb[1]);
        }
        double area = 0, inside = 0;
        for (double[] c : f.cells) {
            double a = (c[2] - c[0]) * (c[3] - c[1]);
            area += a;
            inside += overlapFraction(c, bbox) * a;
        }
        if (area == 0) area = 1e-12;
        return f.bytes * inside / area;
    }

    /** default_survey_order: finest detail, then newest survey, then newest upload. */
    static void defaultOrder(List<Survey> l) {
        l.sort((a, b) -> {
            int c = Double.compare(a.resM, b.resM);
            if (c != 0) return c;
            c = Integer.compare(b.year == null ? 0 : b.year, a.year == null ? 0 : a.year);
            if (c != 0) return c;
            return descending(a.updated, b.updated);
        });
    }

    /** Python's tuple(-ord(c) for c in s) order: later characters first, and a prefix before the longer text. */
    static int descending(String a, String b) {
        for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
            if (a.charAt(i) != b.charAt(i)) return Character.compare(b.charAt(i), a.charAt(i));
        }
        return Integer.compare(a.length(), b.length());
    }

    /** blended_note: the catalogue's step warnings, reworded for blended edges. */
    static String blendedNote(String note) {
        note = note.replace("; expect small steps at its edges.", "; its edges are blended into IMAS.");
        if (note.contains("IMAS may not be on sea level at this spot."))
            note += " The edge is blended into IMAS, so there's no step, and the middle of the survey stays as surveyed.";
        return note;
    }
}
