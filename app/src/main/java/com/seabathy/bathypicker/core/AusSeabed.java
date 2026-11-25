package com.seabathy.bathypicker.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High detail in Australian waters when IMAS's composite can't be had (its servers closed on
 * 25 November 2026): the same depths put together from the sources IMAS built its composite from,
 * all published by Geoscience Australia's AusSeabed. The list (ausseabed.json, shared with the
 * web app) holds:
 *   base         GA's national 250 m model (AusBathyTopo 2026), the whole EEZ,
 *   regional     GA's 30 m models (Great Barrier Reef, Northern Australia, Torres Strait, Bass
 *                Strait, Perth, North West Shelf),
 *   compilations GA's big regional grids (seismic- and satellite-derived),
 *   surveys      every AusSeabed survey (map-server layers and cloud-optimised files on S3).
 * The national model is laid at the bottom, then the rest coarsest first, so the finest wins, each
 * fading into what is under it at its edges (the Builder's blending, as overseas).
 */
public final class AusSeabed {
    private AusSeabed() {
    }

    public static final String CREDIT_MODEL = "Depths: Geoscience Australia, AusBathyTopo depth models and compilations (CC BY 4.0)";
    public static final String CREDIT_SURVEY = "Depths: AusSeabed surveys, Geoscience Australia and partners (CC BY 4.0)";
    /** At most this many surveys in one box: the finest and newest. */
    static final int MAX_SURVEYS = 30;

    /** One source in the list. */
    public static final class Layer {
        public String k, g, name, cov, url, crs;
        public int year;
        public double res;
        public double[] bbox;
        public List<double[]> cells = new ArrayList<>();

        boolean inBox(double[] b) {
            if (!Surveys.intersects(b, bbox)) return false;
            if (cells.isEmpty()) return true;
            for (double[] c : cells) if (Surveys.intersects(b, c)) return true;
            return false;
        }
    }

    /** The list: the map server's address, the models (base, regional, compilations) and the surveys. */
    public static final class Sources {
        public String wcs;
        public final List<Layer> models = new ArrayList<>();
        public final List<Layer> surveys = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Sources parse(String json) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        Sources s = new Sources();
        s.wcs = (String) m.get("wcs");
        for (String key : new String[]{"models", "surveys"}) {
            for (Object o : (List<Object>) m.get(key)) {
                Map<String, Object> r = (Map<String, Object>) o;
                Layer L = new Layer();
                L.k = (String) r.get("k");
                L.g = r.containsKey("g") ? (String) r.get("g") : (key.equals("surveys") ? "surveys" : "regional");
                L.name = (String) r.get("name");
                L.cov = (String) r.get("cov");
                L.url = (String) r.get("url");
                L.crs = (String) r.get("crs");
                L.year = ((Number) r.getOrDefault("year", 0)).intValue();
                L.res = ((Number) r.get("res")).doubleValue();
                L.bbox = box((List<Object>) r.get("bbox"));
                Object cells = r.get("cells");
                if (cells instanceof List) for (Object c : (List<Object>) cells) L.cells.add(box((List<Object>) c));
                (key.equals("surveys") ? s.surveys : s.models).add(L);
            }
        }
        return s;
    }

    private static double[] box(List<Object> v) {
        return new double[]{((Number) v.get(0)).doubleValue(), ((Number) v.get(1)).doubleValue(),
                ((Number) v.get(2)).doubleValue(), ((Number) v.get(3)).doubleValue()};
    }

    private static int groupRank(String g) {
        switch (g) {
            case "base": return 0;
            case "regional": return 1;
            case "compilations": return 2;
            default: return 3;
        }
    }

    /** What covers box b, in the order laid (bottom first): the models, then the surveys coarsest and oldest first. */
    public static List<Layer> choose(Sources src, double[] b) {
        List<Layer> models = new ArrayList<>();
        for (Layer L : src.models) if (L.inBox(b)) models.add(L);
        models.sort(Comparator.<Layer>comparingInt(L -> groupRank(L.g)).thenComparing(L -> -L.res));
        List<Layer> surveys = new ArrayList<>();
        for (Layer L : src.surveys) if (L.inBox(b)) surveys.add(L);
        if (surveys.size() > MAX_SURVEYS) {
            surveys.sort(Comparator.<Layer>comparingDouble(L -> L.res).thenComparing(L -> -L.year));
            surveys = new ArrayList<>(surveys.subList(0, MAX_SURVEYS));
        }
        surveys.sort(Comparator.<Layer>comparingDouble(L -> -L.res).thenComparingInt(L -> L.year));
        List<Layer> out = new ArrayList<>(models);
        out.addAll(surveys);
        return out;
    }

    /** Box b's width and height in metres. */
    public static double[] boxMetres(double[] b) {
        return Planner.boxMetres(b);
    }

    /** The grid's detail for box b: 10 m, as IMAS's composite is on the shelf, coarser only to keep within capBytes. */
    public static double resolution(double[] b, double capBytes) {
        double[] m = Planner.boxMetres(b);
        double res = 10;
        while (m[0] / res * (m[1] / res) * 4 > capBytes * 0.9) res *= 1.25;
        return Math.round(res * 10) / 10.0;
    }

    /** A plan ready for the Builder, with the downloaded files it reads and what it was made from. */
    public static final class Prepared {
        public final Planner.Plan plan;
        public final List<Layer> layers = new ArrayList<>();
        /** Each layer of the plan by its survey id. */
        public final Map<String, Layer> byId = new LinkedHashMap<>();
        final List<File> temp = new ArrayList<>();

        Prepared(Planner.Plan plan) {
            this.plan = plan;
        }

        public void cleanup() {
            for (File f : temp) f.delete();
        }
    }

    /**
     * Downloads what AusSeabed's map server cuts for the box, reads the headers of the files on S3,
     * and makes the Builder's plan: the national model as the base, the rest on top.
     */
    public static Prepared prepare(Sources src, double[] bbox, double resM, File workDir, World.Net net, ExecutorService pool, Job job,
                                   AtomicLong downloaded) throws IOException {
        List<Layer> chosen = choose(src, bbox);
        Layer baseLayer = null;
        for (Layer L : chosen) if ("base".equals(L.g)) { baseLayer = L; break; }
        if (baseLayer == null) throw new IOException("AusSeabed has no depths for this box.");
        double[] g = World.grid(bbox, resM);
        double res = (g[2] - g[0]) / g[4];
        String tag = Long.toHexString(System.nanoTime());
        Planner.Params p = Planner.parse(bbox, null, false, "wgs84", "auto", "cubic", "default", null, null, true);
        Planner.Plan plan = new Planner.Plan();
        plan.p = p;
        plan.crs = "wgs84";
        plan.resM = resM;
        plan.baseLabel = "Geoscience Australia's national depth model";
        plan.layerWord = "layer";
        plan.countLayers = true;
        plan.blend = true;
        Prepared out = new Prepared(plan);
        boolean ok = false;
        try {
            Map<String, File> files = new LinkedHashMap<>();
            Map<String, String> urls = new LinkedHashMap<>();
            Map<String, Layer> owner = new LinkedHashMap<>();
            double margin = Math.max(0.01, 4 * res);
            double[] gm = {g[0] - margin, g[1] - margin, g[2] + margin, g[3] + margin};
            int n = 0;
            for (Layer L : chosen) {
                if (!"model".equals(L.k) && !"wcs".equals(L.k)) continue;
                double[] cut = {Math.max(gm[0], L.bbox[0]), Math.max(gm[1], L.bbox[1]), Math.min(gm[2], L.bbox[2]), Math.min(gm[3], L.bbox[3])};
                if (!(cut[2] > cut[0] && cut[3] > cut[1])) continue;
                if (L.res >= resM * 0.9) {
                    // At its own cells (coarser than the grid): one request, or pieces when big.
                    double cell = L.res / Catalog.M_PER_DEG;
                    int cols = (int) Math.ceil((cut[2] - cut[0]) / cell), rows = (int) Math.ceil((cut[3] - cut[1]) / cell);
                    if (cols <= World.CHUNK && rows <= World.CHUNK) {
                        put(files, urls, owner, workDir, tag, "w" + n++, World.wcsUrl(src.wcs, L.cov, cut, 0, 0, true), L);
                    } else {
                        for (World.Piece pc : World.pieces(g, (int) Math.max(1, Math.floor(L.res / resM))))
                            put(files, urls, owner, workDir, tag, "w" + n++, World.wcsUrl(src.wcs, L.cov, pc.box, 0, 0, true), L);
                    }
                } else {
                    // Finer than the grid: thinned by the server to the grid's cells, piece by piece.
                    for (World.Piece pc : World.pieces(g, 1)) {
                        double[] c = {Math.max(pc.box[0], L.bbox[0]), Math.max(pc.box[1], L.bbox[1]), Math.min(pc.box[2], L.bbox[2]), Math.min(pc.box[3], L.bbox[3])};
                        if (!(c[2] > c[0] && c[3] > c[1])) continue;
                        int cols = Math.max(2, (int) Math.round(pc.cols * (c[2] - c[0]) / (pc.box[2] - pc.box[0])));
                        int rows = Math.max(2, (int) Math.round(pc.rows * (c[3] - c[1]) / (pc.box[3] - pc.box[1])));
                        put(files, urls, owner, workDir, tag, "w" + n++, World.wcsUrl(src.wcs, L.cov, c, cols, rows, true), L);
                    }
                }
            }
            out.temp.addAll(files.values());
            Map<String, RemoteTiff> got = fetch(files, urls, owner, baseLayer, net, pool, job, downloaded);

            // The cloud-optimised files on S3: their headers, read in parallel.
            Map<String, Future<RemoteTiff>> heads = new LinkedHashMap<>();
            for (Layer L : chosen) if ("cog".equals(L.k)) heads.put(L.url, pool.submit(() -> RemoteTiff.open(L.url, job)));
            job.progress(0, "Reading the survey files' headers");
            Map<String, RemoteTiff> cogs = new LinkedHashMap<>();
            for (Map.Entry<String, Future<RemoteTiff>> e : heads.entrySet()) {
                try {
                    cogs.put(e.getKey(), e.getValue().get());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Cancelled");
                } catch (Exception ex) {
                    // A survey file that can't be read just now is left out; the models are under it anyway.
                    job.add("Left out (couldn't read its header): " + e.getKey());
                }
            }

            // The base, then everything else bottom-up; the Builder takes the surveys top (wins) first.
            List<Surveys.Survey> bottomUp = new ArrayList<>();
            int id = 0;
            for (Layer L : chosen) {
                List<Surveys.SFile> sf = new ArrayList<>();
                if ("cog".equals(L.k)) {
                    RemoteTiff t = cogs.get(L.url);
                    Proj proj = Proj.parse(L.crs);
                    if (t == null || proj == null) continue;
                    Surveys.SFile f = new Surveys.SFile();
                    f.url = L.url;
                    f.crs = L.crs;
                    f.proj = proj;
                    f.bbox = L.bbox;
                    f.cells = L.cells.isEmpty() ? Collections.singletonList(L.bbox) : L.cells;
                    f.tiled = true;
                    sf.add(f);
                    plan.surveyTiffs.put(f.url, t);
                } else {
                    for (Map.Entry<String, Layer> e : owner.entrySet()) {
                        if (e.getValue() != L || !got.containsKey(e.getKey())) continue;
                        Surveys.SFile f = new Surveys.SFile();
                        f.url = files.get(e.getKey()).toURI().toString();
                        f.crs = "EPSG:4326";
                        f.proj = Proj.parse("EPSG:4326");
                        f.bbox = L.bbox;
                        f.cells = Collections.singletonList(L.bbox);
                        f.tiled = true;
                        sf.add(f);
                        plan.surveyTiffs.put(f.url, got.get(e.getKey()));
                    }
                }
                if (sf.isEmpty()) continue;
                if (L == baseLayer) {
                    // The national model at the bottom, in place of IMAS's composite.
                    Planner.Base base = new Planner.Base();
                    base.tiff = plan.surveyTiffs.remove(sf.get(0).url);
                    base.proj = Proj.parse("EPSG:4326");
                    base.label = plan.baseLabel;
                    plan.bases.add(base);
                    plan.imasResM = L.res;
                    for (int i = 1; i < sf.size(); i++) plan.surveyTiffs.remove(sf.get(i).url);
                    if (sf.size() > 1) {
                        // A big box asked for in pieces: the other pieces lie over the first as one layer.
                        Surveys.Survey s = survey("aus" + id++, L, sf.subList(1, sf.size()));
                        for (Surveys.SFile f : s.files) plan.surveyTiffs.put(f.url, got.get(keyOf(files, f.url)));
                        bottomUp.add(s);
                        out.byId.put(s.id, L);
                        plan.bandM.put(s.id, Math.max(4 * resM, 200));
                    }
                    out.layers.add(L);
                    continue;
                }
                Surveys.Survey s = survey("aus" + id++, L, sf);
                bottomUp.add(s);
                out.layers.add(L);
                out.byId.put(s.id, L);
                // Edges fade over 200 m for models that cover most of the sea, 30 m for patchy surveys.
                plan.bandM.put(s.id, "surveys".equals(L.g) ? Math.max(3 * resM, 30) : Math.max(4 * resM, 200));
            }
            if (plan.bases.isEmpty()) throw new IOException("AusSeabed's server sent nothing for this box. Try again in a few minutes.");
            Collections.reverse(bottomUp);
            plan.chosen.addAll(bottomUp);
            double finest = plan.imasResM;
            for (Surveys.Survey s : plan.chosen) finest = Math.min(finest, s.resM);
            plan.finestM = Math.round(finest * 1000) / 1000.0;
            double[] px = {g[4], g[5]};
            plan.estBytes = (long) (px[0] * px[1] * 4);
            plan.filename = "depths.tif";
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("ok", true);
            json.put("res_m", resM);
            json.put("est_bytes", plan.estBytes);
            plan.json = json;
            ok = true;
            return out;
        } finally {
            if (!ok) out.cleanup();
        }
    }

    private static Surveys.Survey survey(String id, Layer L, List<Surveys.SFile> files) {
        Surveys.Survey s = new Surveys.Survey();
        s.id = id;
        s.name = L.name;
        s.resM = L.res;
        s.year = L.year;
        s.sign = 1;
        s.shiftM = 0;
        s.bbox = L.bbox;
        s.files = new ArrayList<>(files);
        return s;
    }

    private static String keyOf(Map<String, File> files, String url) {
        for (Map.Entry<String, File> e : files.entrySet()) if (e.getValue().toURI().toString().equals(url)) return e.getKey();
        return null;
    }

    private static void put(Map<String, File> files, Map<String, String> urls, Map<String, Layer> owner, File dir, String tag,
                            String key, String url, Layer L) {
        files.put(key, new File(dir, "aus-" + tag + "-" + key + ".tif"));
        urls.put(key, url);
        owner.put(key, L);
    }

    /**
     * All the map server's cuts, three at a time, with the progress line counting megabytes. The
     * national model must come; any other cut that fails (no data there, a busy moment) is left out.
     */
    private static Map<String, RemoteTiff> fetch(Map<String, File> files, Map<String, String> urls, Map<String, Layer> owner, Layer base,
                                                 World.Net net, ExecutorService pool, Job job, AtomicLong downloaded) throws IOException {
        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(3);
        AtomicLong count = downloaded != null ? downloaded : new AtomicLong();
        long start = count.get();
        Map<String, Future<RemoteTiff>> fs = new LinkedHashMap<>();
        for (Map.Entry<String, File> e : files.entrySet()) {
            String key = e.getKey();
            fs.put(key, pool.submit(() -> {
                sem.acquire();
                try {
                    net.save(urls.get(key), e.getValue(), job, count);
                    return RemoteTiff.open(e.getValue().toURI().toString(), job);
                } finally {
                    sem.release();
                }
            }));
        }
        Map<String, RemoteTiff> out = new LinkedHashMap<>();
        for (Map.Entry<String, Future<RemoteTiff>> e : fs.entrySet()) {
            while (true) {
                job.progress(0, "Downloading the depths from AusSeabed (" + Planner.fmtBytes(count.get() - start) + ")");
                try {
                    out.put(e.getKey(), e.getValue().get(500, java.util.concurrent.TimeUnit.MILLISECONDS));
                    break;
                } catch (java.util.concurrent.TimeoutException te) {
                    if (job.cancelled()) {
                        for (Future<RemoteTiff> f : fs.values()) f.cancel(true);
                        throw new java.io.InterruptedIOException("Cancelled");
                    }
                } catch (InterruptedException ie) {
                    for (Future<RemoteTiff> f : fs.values()) f.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Cancelled");
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof java.io.InterruptedIOException) throw (java.io.InterruptedIOException) c;
                    if (owner.get(e.getKey()) == base) {
                        for (Future<RemoteTiff> f : fs.values()) f.cancel(true);
                        throw new IOException("AusSeabed's server: " + (c != null ? c.getMessage() : "no answer"), c);
                    }
                    job.add("Left out (AusSeabed sent nothing usable): " + owner.get(e.getKey()).name);
                    break;
                }
            }
        }
        return out;
    }

    /** The credit line for what the build used: the models, and the surveys when any of them filled part of the file. */
    public static String creditLine(Prepared prep, Map<String, Long> layerPixels) {
        boolean surveys = false;
        for (Surveys.Survey s : prep.plan.chosen) {
            Long n = layerPixels.get(s.id);
            Layer L = prep.byId.get(s.id);
            if (n != null && n != 0 && L != null && "surveys".equals(L.g)) surveys = true;
        }
        return surveys ? CREDIT_SURVEY + " • " + CREDIT_MODEL : CREDIT_MODEL;
    }

    /** The sources file written next to the depths: what the build was made from, with the licence. */
    public static String creditsText(Prepared prep, Map<String, Long> layerPixels, String finalName, String madeWith) {
        List<String> lines = new ArrayList<>();
        lines.add(finalName);
        lines.add("Made with " + madeWith + ". Not for navigation.");
        lines.add("");
        lines.add(String.format(Locale.ROOT, "High-detail depths for Australian waters at %s m, put together from AusSeabed "
                + "(IMAS's composite was not available):", Planner.g(prep.plan.resM)));
        for (Layer L : prep.layers) {
            String where = L.cov != null ? "https://warehouse.ausseabed.gov.au/geoserver/ows (WCS " + L.cov + ")" : L.url;
            lines.add(String.format(Locale.ROOT, "  %s, about %s m. %s", L.name, Planner.g(L.res), where));
        }
        lines.add("");
        lines.add(CREDIT_MODEL);
        lines.add(CREDIT_SURVEY);
        lines.add("Licence: CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Each source keeps its own vertical datum.");
        return String.join("\n", lines) + "\n";
    }
}
