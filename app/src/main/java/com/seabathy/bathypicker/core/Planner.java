package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** "Check size": the same rules as assess() in bathy_picker.py. */
public final class Planner {
    public static final double GB = 1024.0 * 1024 * 1024;
    public static final double DEFAULT_MAX_GB = 3.0;
    static final double[] RES_LADDER = {0.5, 1, 2, 5, 10, 15, 20, 25, 30, 50, 100, 200, 250, 500, 1000, 2000, 5000};
    public static final String[] RESAMPLING = {"auto", "near", "average", "bilinear", "cubic"};
    public static final String FORMAT = "Tiled GeoTIFF with overviews (Deflate)";

    private Planner() {}

    public static final class Params {
        public double w, s, e, n;
        public List<String> zones;
        public String crs = "wgs84";
        public String resolution = "auto";
        public String resampling = "auto";
        /** "default" (the usual picks, with the page's choices on top) or "none" (IMAS only). */
        public String surveys = "default";
        public Map<String, Boolean> surveyPrefs = new HashMap<>();
        public List<String> surveyOrder = new ArrayList<>();
        public boolean blend = true;

        public double[] bbox() {
            return new double[]{w, s, e, n};
        }
    }

    /** A checked request, ready to build. */
    public static final class Plan {
        public Params p;
        public double resM, finestM;
        public String crs, filename;
        public List<Catalog.Zone> order = new ArrayList<>();  // coarse first, finest last = finest wins
        public List<Catalog.Info> orderInfo = new ArrayList<>();
        public Map<String, Object> json;
        public long estBytes;
        /** IMAS's finest detail in the box (metres), or null when IMAS isn't used. */
        public Double imasResM;
        /** The ticked surveys, top of the list (wins) first. */
        public List<Surveys.Survey> chosen = new ArrayList<>();
        /** Survey file headers, by url. */
        public Map<String, RemoteTiff> surveyTiffs = new HashMap<>();
        /** Survey edges blended into what's underneath, and each survey's band in metres. */
        public boolean blend;
        public Map<String, Double> bandM = new HashMap<>();
        /** Filled in while building: the band each survey really got (narrower for small surveys). */
        public final Map<String, Double> bandUsedM = Collections.synchronizedMap(new HashMap<>());
        /** Bottom layers in a coordinate system of their own, laid under the surveys instead of IMAS (see World). */
        public List<Base> bases = new ArrayList<>();
        /** What the progress line calls the bottom layer, and each layer on top. */
        public String baseLabel = "the IMAS composite";
        public String layerWord = "survey";
        /** Count, while building, which layer each finished pixel came from (Builder.layerPixels, basePixels). */
        public boolean countLayers;
        /** The size limit the detail was chosen under, bytes. */
        public double capBytes;
        /** What changed after planning (a survey the warehouse didn't send, and the detail chosen again), for the user. */
        public final List<String> notes = new ArrayList<>();
    }

    /** A bottom layer read through its own projection: GMRT under the overseas sources. */
    public static final class Base {
        public RemoteTiff tiff;
        public Proj proj;
        public String label;
    }

    public static String crsLabel(String crs) {
        return "albers".equals(crs) ? "GDA2020 Australian Albers (metres)" : "WGS84 lat/long";
    }

    public static Params parse(double[] bbox, List<String> zones, String crs, String resolution, String resampling) {
        return parse(bbox, zones, true, crs, resolution, resampling, "default", null, null, true);
    }

    /** parse_params in bathy_picker.py. base false leaves the IMAS composite out (surveys only). */
    public static Params parse(double[] bbox, List<String> zones, boolean base, String crs, String resolution, String resampling,
                               String surveys, Map<String, Boolean> prefs, List<String> order, boolean blend) {
        if (bbox == null || bbox.length != 4) throw new IllegalArgumentException("Pick an area first: west, south, east and north are all needed.");
        double w = bbox[0], s = bbox[1], e = bbox[2], n = bbox[3];
        for (double v : bbox) if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("The box edges must be numbers.");
        if (!(-180 <= w && w < e && e <= 180 && -90 <= s && s < n && n <= 90))
            throw new IllegalArgumentException("West must be less than east and south less than north (latitude -90 to 90, longitude -180 to 180).");
        if (e - w < 0.0005 || n - s < 0.0005) throw new IllegalArgumentException("The box is too small. Drag the corners further apart.");
        if (e - w > 90 || n - s > 70) throw new IllegalArgumentException("The box is too large. Zoom in and pick a smaller area.");
        List<String> keys = new ArrayList<>();
        if (zones == null) {
            for (Catalog.Zone z : Catalog.ZONES) keys.add(z.key);
        } else {
            for (String k : zones) if (Catalog.zone(k) == null) throw new IllegalArgumentException("Unknown depth layer: " + k);
            for (Catalog.Zone z : Catalog.ZONES) if (zones.contains(z.key)) keys.add(z.key);
        }
        if (!base) keys.clear();
        if (surveys == null) surveys = "default";
        if (!surveys.equals("default") && !surveys.equals("none")) throw new IllegalArgumentException("surveys must be default or none.");
        if ((prefs != null && prefs.size() > 2000) || (order != null && order.size() > 2000))
            throw new IllegalArgumentException("The survey choices weren't understood.");
        if (keys.isEmpty() && surveys.equals("none")) throw new IllegalArgumentException("Pick at least one depth layer.");
        if (crs == null) crs = "wgs84";
        if (!crs.equals("wgs84") && !crs.equals("albers")) throw new IllegalArgumentException("Unknown output coordinate system.");
        String res = resolution == null ? "auto" : resolution.trim().toLowerCase(Locale.ROOT);
        if (!res.equals("auto") && !res.equals("finest")) {
            double r;
            try {
                r = Double.parseDouble(res);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Resolution must be auto, finest, or a number of metres.");
            }
            if (!(r >= 0.5 && r <= 20000)) throw new IllegalArgumentException("Resolution must be between 0.5 and 20000 metres.");
            res = g(r);
        }
        if (resampling == null) resampling = "auto";
        if (!Arrays.asList(RESAMPLING).contains(resampling)) throw new IllegalArgumentException("Unknown resampling method.");
        Params p = new Params();
        p.w = w; p.s = s; p.e = e; p.n = n;
        p.zones = keys;
        p.crs = crs;
        p.resolution = res;
        p.resampling = resampling;
        p.surveys = surveys;
        if (prefs != null) p.surveyPrefs.putAll(prefs);
        if (order != null) p.surveyOrder.addAll(order);
        p.blend = blend;
        return p;
    }

    static double[] boxMetres(double[] b) {
        double mid = Math.toRadians((b[1] + b[3]) / 2);
        return new double[]{(b[2] - b[0]) * Catalog.M_PER_DEG * Math.cos(mid), (b[3] - b[1]) * Catalog.M_PER_DEG};
    }

    static long[] outputPixels(double[] b, double resM, String crs) {
        if (crs.equals("wgs84")) {
            double d = resM / Catalog.M_PER_DEG;
            return new long[]{Math.max(1, (long) Math.ceil((b[2] - b[0]) / d)), Math.max(1, (long) Math.ceil((b[3] - b[1]) / d))};
        }
        double[] m = boxMetres(b);
        return new long[]{Math.max(1, (long) Math.ceil(m[0] / resM)), Math.max(1, (long) Math.ceil(m[1] / resM))};
    }

    static boolean intersects(double[] a, double[] b) {
        return !(a[2] <= b[0] || a[0] >= b[2] || a[3] <= b[1] || a[1] >= b[3]);
    }

    /** {resolution in metres, note}. */
    static Object[] chooseResolution(double[] b, double finestM, String crs, String mode, double cap) {
        double finest = Math.max(Math.round(finestM * 1000) / 1000.0, 0.5);
        if (mode.equals("finest")) return new Object[]{finest, "Finest detail in the layers you picked."};
        if (!mode.equals("auto")) return new Object[]{Double.parseDouble(mode), "As you asked."};
        List<Double> ladder = new ArrayList<>();
        ladder.add(finest);
        for (double r : RES_LADDER) if (r > finest * 1.01) ladder.add(r);
        for (double r : ladder) {
            long[] px = outputPixels(b, r, crs);
            if (px[0] * px[1] * 4.0 <= cap)
                return new Object[]{r, r == finest ? "Full detail." : "Finest detail that fits under the size limit."};
        }
        return new Object[]{ladder.get(ladder.size() - 1), "Even the coarsest option is over the size limit."};
    }

    static String makeFilename(double[] b, double resM, String crs, int nSurveys) {
        String res = g(resM).replace(".", "p");
        String suffix = crs.equals("wgs84") ? "" : "_albers";
        if (nSurveys > 0) suffix += "_plus" + nSurveys + "survey" + (nSurveys > 1 ? "s" : "");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        return "IMAS-bathymetry_" + lat(b[1]) + "-" + lat(b[3]) + "_" + lon(b[0]) + "-" + lon(b[2]) + "_" + res + "m" + suffix + "_" + stamp + ".tif";
    }

    private static String lat(double v) {
        return String.format(Locale.ROOT, "%.2f%s", Math.abs(v), v >= 0 ? "N" : "S");
    }

    private static String lon(double v) {
        return String.format(Locale.ROOT, "%.2f%s", Math.abs(v), v >= 0 ? "E" : "W");
    }

    /** Python's {:g}: six significant digits, no trailing zeros. */
    public static String g(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e6) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.6g", v);
        if (s.contains("e")) {
            // Python writes e+06 / e-05 with two exponent digits at least, like Java's %g
            String[] parts = s.split("e");
            String m = parts[0].contains(".") ? parts[0].replaceAll("0+$", "").replaceAll("\\.$", "") : parts[0];
            return m + "e" + parts[1];
        }
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    public static String fmtBytes(double n) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        for (String u : units) {
            if (n < 1024 || u.equals("TB"))
                return (u.equals("B") || u.equals("KB")) ? String.format(Locale.ROOT, "%.0f %s", n, u) : String.format(Locale.ROOT, "%.1f %s", n, u);
            n /= 1024;
        }
        return String.format(Locale.ROOT, "%.1f TB", n);
    }

    /** About how many compressed bytes a source tile holds per pixel (float32 depths, Deflate): 1 to 2.7 measured. */
    static final double BYTES_PER_PX = 2.5;

    /** Source tiles are read whole: a box w x h pixels reads up to (w + 512) x (h + 512). */
    static double tileRound(double w, double h) {
        return (w + 512) * (h + 512) / Math.max(1.0, w * h);
    }

    /**
     * Rough download for a build at resM: each survey or park file's bytes inside the box, from
     * the overview read at that detail, rounded out to whole tiles; the warehouse's coverages
     * (saved at wcsSaved bytes, compressed on the way); and the IMAS zones, at no finer than
     * their own detail, about one and a half boxes' worth (zones overlap at their depth limits).
     */
    static double downloadBytes(List<Surveys.Survey> chosen, double[] bbox, double resM, Double imasRes, double wcsSaved) {
        double[] m = boxMetres(bbox);
        double total = wcsSaved * BYTES_PER_PX / Warehouse.SAVED_BYTES_PER_PX;
        for (Surveys.Survey s : chosen) {
            if (Surveys.WCS.equals(s.kind)) continue;
            for (Surveys.SFile f : s.files) {
                double read = readResM(s.resM, resM, f.overviews);
                double shrink = (s.resM / read) * (s.resM / read);
                total += Surveys.readBytes(bbox, f) * shrink * tileRound(m[0] / read, m[1] / read);
            }
        }
        if (imasRes != null) {
            double px = readResM(imasRes, resM, -1);
            total += 1.5 * (m[0] / px + 512) * (m[1] / px + 512) * BYTES_PER_PX;
        }
        return total;
    }

    /**
     * The detail a file is read at for an output at resM: its overview just finer than that (each
     * overview halves), or its own detail when it has no overviews (overviews 0; -1 is not known).
     */
    static double readResM(double ownM, double resM, int overviews) {
        if (resM <= ownM || overviews == 0) return ownM;
        double k = Math.floor(Math.log(resM / ownM) / Math.log(2) + 1e-9);
        if (overviews > 0) k = Math.min(k, overviews);
        return ownM * Math.pow(2, k);
    }

    /**
     * After layers have dropped out of a checked plan (the warehouse didn't send one): when what's
     * left is coarser than the detail chosen, chooses the detail again from it, as assess does, so
     * the file isn't built far finer than its data. Returns true when the detail changed.
     */
    public static boolean replan(Plan plan) {
        Double finestLeft = plan.order.isEmpty() ? null : plan.imasResM;
        for (Surveys.Survey s : plan.chosen) finestLeft = finestLeft == null ? s.resM : Math.min(finestLeft, s.resM);
        if (finestLeft == null || finestLeft <= plan.resM * 1.01) return false;
        String mode = plan.p.resolution;
        if (!mode.equals("auto") && !mode.equals("finest")) return false;   // a detail the user asked for stays
        double[] bbox = plan.p.bbox();
        double cap = plan.capBytes > 0 ? plan.capBytes : Double.MAX_VALUE;
        double old = plan.resM;
        Object[] ch = chooseResolution(bbox, finestLeft, plan.crs, mode, cap);
        plan.resM = (Double) ch[0];
        plan.finestM = Math.round(finestLeft * 1000) / 1000.0;
        long[] px = outputPixels(bbox, plan.resM, plan.crs);
        plan.estBytes = px[0] * px[1] * 4;
        plan.blend = plan.p.blend && !plan.chosen.isEmpty() && !plan.order.isEmpty();
        plan.bandM.clear();
        if (plan.blend) for (Surveys.Survey s : plan.chosen) plan.bandM.put(s.id, Surveys.blendBandM(s, plan.resM));
        plan.filename = makeFilename(bbox, plan.resM, plan.crs, plan.chosen.size());
        if (plan.json != null) {
            plan.json.put("res_m", plan.resM == Math.rint(plan.resM) ? (Object) (long) plan.resM : (Object) plan.resM);
            plan.json.put("res_note", ch[1]);
            plan.json.put("finest_res_m", plan.finestM);
            plan.json.put("output_px", Arrays.asList(px[0], px[1]));
            plan.json.put("est_bytes", plan.estBytes);
            plan.json.put("blend", plan.blend);
            plan.json.put("filename", plan.filename);
        }
        return plan.resM != old;
    }

    /** Some file of the survey has depths in the box, as far as its tile sizes tell (true when they can't). */
    private static boolean anyData(Surveys.Survey s, Map<String, Object> heads, double[] bbox, Http.Cancel cancel) throws IOException {
        if (Surveys.WCS.equals(s.kind)) return true;
        for (Surveys.SFile f : s.files) {
            Object h = heads.get(f.url);
            if (!(h instanceof RemoteTiff) || Surveys.hasData((RemoteTiff) h, f.emptyTile, f.emptyFor, bbox, cancel)) return true;
        }
        return false;
    }

    /** plan_surveys: rows for the page, and the chosen surveys top to bottom. */
    private static void planSurveys(Params p, Plan plan, Surveys cat, Catalog headers, double[] bbox, Double imasRes,
                                    List<Object> rows, List<String> warnings, Http.Cancel cancel) throws IOException {
        if (cat == null || p.surveys.equals("none")) return;
        List<Surveys.Survey> found = cat.inBox(bbox);
        Surveys.defaultOrder(found);
        if (!p.surveyOrder.isEmpty()) {
            Map<String, Integer> pos = new HashMap<>();
            for (int i = 0; i < p.surveyOrder.size(); i++) pos.put(p.surveyOrder.get(i), i);   // like a Python dict: last wins
            int last = pos.size();
            found.sort((a, b) -> Integer.compare(pos.getOrDefault(a.id, last), pos.getOrDefault(b.id, last)));
        }
        // The phone also needs to read each file's header: is it in a form this app can read?
        // (A warehouse coverage has no file yet: Warehouse cuts it for the box when the build starts.)
        List<String> urls = new ArrayList<>();
        for (Surveys.Survey s : found) {
            if (Surveys.WCS.equals(s.kind)) continue;
            for (Surveys.SFile f : s.files) if (f.proj != null) urls.add(f.url);
        }
        Map<String, Object> heads = urls.isEmpty() ? Collections.emptyMap() : headers.surveyHeaders(urls, cancel);
        for (Surveys.Survey s : found) {
            String why = null;
            for (Surveys.SFile f : s.files) {
                if (Surveys.WCS.equals(s.kind)) continue;
                if (f.proj == null) why = "Its coordinate system isn't one the phone knows. The computer version of BathyPicker can use it.";
                else if (heads.get(f.url) instanceof String) why = (String) heads.get(f.url);
            }
            boolean usable = why == null;
            // A park mosaic's data cells are coarse: its tile sizes tell whether it really has depths in the box.
            if (usable && !anyData(s, heads, bbox, cancel)) continue;
            boolean coarser = Surveys.coarser(s, imasRes);
            boolean tooBig = Warehouse.tooBig(s, bbox);
            boolean defaultOn = !s.defaultOff && !coarser && !tooBig;
            boolean on = usable && p.surveyPrefs.getOrDefault(s.id, defaultOn);
            double extra = 0, cover = 0;
            List<Object> cells = new ArrayList<>();
            for (Surveys.SFile f : s.files) {
                extra += Surveys.readBytes(bbox, f);
                cover += Surveys.cellsCover(bbox, f);
                for (double[] c : f.cells) {
                    if (cells.size() < 200 && Surveys.intersects(bbox, c)) cells.add(Arrays.asList(c[0], c[1], c[2], c[3]));
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", s.id);
            r.put("name", s.name);
            r.put("year", s.year);
            r.put("res_m", s.resM);
            r.put("on", on);
            r.put("default_on", defaultOn);
            r.put("usable", usable);
            r.put("unusable_note", why);
            r.put("shift_m", s.shiftM);
            r.put("note", p.blend && imasRes != null ? Surveys.blendedNote(s.note) : s.note);
            r.put("stated_datum", s.statedDatum);
            r.put("verdict", s.verdict);
            r.put("bbox", Arrays.asList(s.bbox[0], s.bbox[1], s.bbox[2], s.bbox[3]));
            r.put("cells", cells);
            r.put("cover_pct", Math.min(100.0, Surveys.pyRound(100 * cover, 1)));
            r.put("read_bytes", (long) extra);
            r.put("coarser", coarser);
            r.put("too_big", tooBig);
            rows.add(r);
            if (tooBig) warnings.add(s.name + " is left out: the AusSeabed warehouse can only cut a smaller area of it. Draw a smaller box to include it.");
            if (on) {
                plan.chosen.add(s);
                if (!Surveys.WCS.equals(s.kind)) for (Surveys.SFile f : s.files) plan.surveyTiffs.put(f.url, (RemoteTiff) heads.get(f.url));
            }
            if (!usable) warnings.add(s.name + " can't be read on the phone, so it's left out. " + why);
        }
    }

    /**
     * Check a request. freeBytes is the free space where the file will go, outDirLabel is shown on the page.
     */
    public static Plan assess(Params p, Catalog cat, double maxGb, long freeBytes, String outDirLabel, Http.Cancel cancel)
            throws IOException {
        return assess(p, cat, null, maxGb, freeBytes, outDirLabel, cancel);
    }

    public static Plan assess(Params p, Catalog cat, Surveys surveys, double maxGb, long freeBytes, String outDirLabel,
                              Http.Cancel cancel) throws IOException {
        List<Catalog.Zone> zones = new ArrayList<>();
        for (Catalog.Zone z : Catalog.ZONES) if (p.zones.contains(z.key)) zones.add(z);
        Map<String, Catalog.Info> infos = zones.isEmpty() ? new HashMap<>() : cat.infos(zones, cancel);
        double[] bbox = p.bbox();
        double[] bm = boxMetres(bbox);
        double cap = maxGb * GB;
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        List<Object> rows = new ArrayList<>();
        List<Catalog.Zone> covered = new ArrayList<>();
        for (Catalog.Zone z : zones) {
            Catalog.Info info = infos.get(z.key);
            boolean hit = info.extent == null || intersects(bbox, info.extent);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", z.key);
            r.put("label", z.label);
            r.put("file", z.file);
            r.put("native_res_m", Math.round(info.pxM * 1000) / 1000.0);
            r.put("window_px", Arrays.asList((long) Math.ceil(bm[0] / info.pxM), (long) Math.ceil(bm[1] / info.pxM)));
            r.put("covered", hit);
            rows.add(r);
            if (hit) covered.add(z);
        }
        Plan plan = new Plan();
        plan.p = p;
        plan.crs = p.crs;
        plan.capBytes = cap;
        Map<String, Object> out = new LinkedHashMap<>();
        plan.json = out;
        Double imasRes = null;
        for (Catalog.Zone z : covered) imasRes = imasRes == null ? infos.get(z.key).pxM : Math.min(imasRes, infos.get(z.key).pxM);
        plan.imasResM = imasRes;
        List<Object> surveyRows = new ArrayList<>();
        planSurveys(p, plan, surveys, cat, bbox, imasRes, surveyRows, warnings, cancel);
        out.put("surveys", surveyRows);
        out.put("imas_res_m", imasRes);
        Map<String, Object> catInfo = new LinkedHashMap<>();
        catInfo.put("built", surveys == null ? "" : surveys.built);
        catInfo.put("origin", surveys == null ? "none" : surveys.origin);
        catInfo.put("count", surveys == null ? 0 : surveys.list.size());
        catInfo.put("report_url", Surveys.REPORT_URL);
        out.put("catalogue", catInfo);
        if (covered.isEmpty() && plan.chosen.isEmpty()) {
            errors.add(zones.isEmpty() ? "Tick the IMAS composite or at least one survey."
                    : "This box is outside the area the dataset covers (Australia's exclusive economic zone).");
            out.put("ok", false);
            out.put("errors", errors);
            out.put("warnings", warnings);
            out.put("zones", rows);
            return plan;
        }
        double finest = Double.MAX_VALUE;
        if (imasRes != null) finest = imasRes;
        for (Surveys.Survey s : plan.chosen) finest = Math.min(finest, s.resM);
        Object[] ch = chooseResolution(bbox, finest, p.crs, p.resolution, cap);
        double resM = (Double) ch[0];
        String note = (String) ch[1];
        long[] px = outputPixels(bbox, resM, p.crs);
        long est = px[0] * px[1] * 4;
        if (!p.resolution.equals("auto") && !p.resolution.equals("finest") && resM < finest * 0.99)
            warnings.add(g(resM) + " m is finer than the data itself (" + g(Surveys.pyRound(finest, 1))
                    + " m). The file gets bigger with no extra detail.");
        plan.blend = p.blend && !plan.chosen.isEmpty() && !covered.isEmpty();
        if (plan.blend) for (Surveys.Survey s : plan.chosen) plan.bandM.put(s.id, Surveys.blendBandM(s, resM));
        if (!plan.chosen.isEmpty()) {
            double cover = 0;
            for (Object o : surveyRows) {
                @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
                if ((Boolean) r.get("on")) cover += (Double) r.get("cover_pct");
            }
            cover = Math.min(100.0, cover);
            String rest = imasRes != null ? " The rest is the IMAS composite at " + g(imasRes) + " m." : " The rest stays empty.";
            String edge;
            if (plan.blend) {
                double mx = 0;
                for (double v : plan.bandM.values()) mx = Math.max(mx, v);
                edge = String.format(Locale.ROOT, " Survey edges and gaps are blended into what's underneath over up to %.0f m, "
                        + "so there's no step; further in, the survey is used exactly.", mx);
            } else {
                edge = " Survey edges may show a small step.";
            }
            warnings.add(String.format(Locale.ROOT, "The surveys you ticked cover up to %.0f%% of the box.", cover) + rest + edge);
        }
        if (est > cap)
            errors.add("About " + fmtBytes(est) + " is over the " + g(maxGb) + " GB limit. Pick a smaller box or a coarser resolution.");
        List<String> skipped = new ArrayList<>();
        for (Object o : rows) {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
            if (!(Boolean) r.get("covered")) skipped.add((String) r.get("label"));
        }
        if (!skipped.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String sk : skipped) sb.append(sb.length() > 0 ? ", " : "").append(sk);
            warnings.add("No coverage in this box for: " + sb + ".");
        }
        warnings.add("Only seafloor inside Australian waters has data. Land and other countries' waters stay empty (NoData).");
        // Room for the file, its overviews and a margin; blending also keeps three working copies while it runs,
        // and the warehouse's coverages are saved while the build reads them.
        double wcsBytes = Warehouse.savedBytes(plan.chosen, bbox, resM);
        double need = est * 0.7 + (plan.blend ? 3.0 * est : 0) + wcsBytes;
        if (freeBytes >= 0) {
            if (freeBytes < need)
                errors.add("Not enough free space on this phone (" + fmtBytes(freeBytes) + " free, roughly " + fmtBytes(need) + " needed"
                        + (plan.blend ? ", including working space for blending survey edges" : "") + ").");
            else if (freeBytes < need + est * 0.5)
                warnings.add("Storage space is tight on this phone (" + fmtBytes(freeBytes) + " free).");
        }

        List<Catalog.Zone> ordered = new ArrayList<>(covered);
        ordered.sort((a, b) -> {
            int c = Double.compare(infos.get(b.key).pxM, infos.get(a.key).pxM);
            return c != 0 ? c : Integer.compare(b.idx, a.idx);
        });
        List<String> files = new ArrayList<>();
        for (Catalog.Zone z : ordered) {
            files.add(z.file);
            plan.order.add(z);
            plan.orderInfo.add(infos.get(z.key));
        }
        plan.resM = resM;
        plan.finestM = Math.round(finest * 1000) / 1000.0;
        plan.filename = makeFilename(bbox, resM, p.crs, plan.chosen.size());
        plan.estBytes = est;
        out.put("blend", plan.blend);
        out.put("ok", errors.isEmpty());
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("zones", rows);
        out.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
        out.put("box_km", Arrays.asList(Math.round(bm[0] / 100) / 10.0, Math.round(bm[1] / 100) / 10.0));
        out.put("crs", p.crs);
        out.put("crs_label", crsLabel(p.crs));
        out.put("finest_res_m", plan.finestM);
        out.put("res_m", resM == Math.rint(resM) ? (Object) (long) resM : (Object) resM);
        out.put("res_note", note);
        out.put("output_px", Arrays.asList(px[0], px[1]));
        out.put("est_bytes", est);
        out.put("download_bytes", (long) downloadBytes(plan.chosen, bbox, resM, imasRes, wcsBytes));
        out.put("cap_bytes", cap);
        out.put("free_bytes", freeBytes);
        out.put("format", FORMAT);
        out.put("files", files);
        out.put("filename", plan.filename);
        out.put("out_dir", outDirLabel);
        return plan;
    }
}
