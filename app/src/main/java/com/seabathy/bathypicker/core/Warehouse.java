package com.seabathy.bathypicker.core;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Surveys in the AusSeabed data warehouse that aren't in AusSeabed's public bucket as files.
 * Its map server (GeoServer WCS) cuts any part of a survey as a float GeoTIFF without an
 * account, so when a build starts, the part of each chosen coverage inside the box is asked
 * for at the build's detail (never finer than the survey's own), saved in the cache folder, and
 * then read by the Builder like any other survey file.
 */
public final class Warehouse {
    private Warehouse() {}

    public static final String WCS_URL = "https://warehouse.ausseabed.gov.au/geoserver/ows";
    static final String PSEUDO = "wcs:";
    /**
     * The most of the survey's own pixels each way in one request; bigger areas are asked for in
     * pieces. The server reads every one of them even when it sends fewer (its surveys have no
     * overviews): 2700 x 2700 took 7 to 9 s, and a request over 10,000 x 10,000 timed out (504).
     */
    static final int CHUNK = 2048;
    /**
     * The most of a survey's own pixels cut for one build (16 pieces). A box that holds more of
     * it leaves the survey unticked: a smaller box gets it.
     */
    static final double MAX_PX = 16.0 * CHUNK * CHUNK;
    /** Pixels added round each piece, so pieces overlap and the resampling has neighbours at the box edge. */
    static final int MARGIN_PX = 3;
    /** Bytes per pixel of a saved coverage, at most (float32; the server's Deflate usually makes it less). */
    static final double SAVED_BYTES_PER_PX = 4.0;
    /** Requests at once to the warehouse. */
    static final int PARALLEL = 3;

    /** What a warehouse coverage's file is called in the catalogue until it's been cut for a box. */
    static String pseudoUrl(String coverage) {
        return PSEUDO + coverage;
    }

    /**
     * The part of the box worth asking for: the box cut to the bounding box of the coverage's data
     * cells inside it, with marginDeg added round it (but no further than marginDeg outside the
     * box). Null when no cell is in the box.
     */
    static double[] area(Surveys.Survey s, double[] box, double marginDeg) {
        double w = Double.MAX_VALUE, so = Double.MAX_VALUE, e = -Double.MAX_VALUE, n = -Double.MAX_VALUE;
        for (Surveys.SFile f : s.files) {
            for (double[] c : f.cells) {
                if (!Surveys.intersects(box, c)) continue;
                w = Math.min(w, Math.max(c[0], box[0]));
                so = Math.min(so, Math.max(c[1], box[1]));
                e = Math.max(e, Math.min(c[2], box[2]));
                n = Math.max(n, Math.min(c[3], box[3]));
            }
        }
        if (w >= e || so >= n) return null;
        return new double[]{
                Math.max(w - marginDeg, box[0] - marginDeg), Math.max(so - marginDeg, box[1] - marginDeg),
                Math.min(e + marginDeg, box[2] + marginDeg), Math.min(n + marginDeg, box[3] + marginDeg)};
    }

    /** The detail asked for: the build's, but never finer than the survey's own (metres). */
    static double requestResM(Surveys.Survey s, double outResM) {
        return Math.max(outResM, s.resM);
    }

    /** GeoServer's scalefactor for that detail: the survey's own detail over it, 1 at most. */
    static double scaleFactor(Surveys.Survey s, double outResM) {
        return Math.min(1.0, s.resM / requestResM(s, outResM));
    }

    /** An area's size in metres: width at its middle latitude, height. */
    static double[] metres(double[] b) {
        return Planner.boxMetres(b);
    }

    /**
     * The area in pieces of at most CHUNK x CHUNK of the survey's own pixels (nativeM), each with
     * MARGIN_PX pixels at the request's detail (padM) added round it so neighbouring pieces overlap.
     */
    static List<double[]> pieces(double[] area, double nativeM, double padM) {
        double[] m = metres(area);
        int nx = Math.max(1, (int) Math.ceil(m[0] / nativeM / CHUNK)), ny = Math.max(1, (int) Math.ceil(m[1] / nativeM / CHUNK));
        double dx = (area[2] - area[0]) / nx, dy = (area[3] - area[1]) / ny;
        double padY = MARGIN_PX * padM / Catalog.M_PER_DEG;
        double padX = padY / Math.max(0.05, Math.cos(Math.toRadians((area[1] + area[3]) / 2)));
        List<double[]> out = new ArrayList<>();
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double w = area[0] + i * dx, s = area[1] + j * dy;
                out.add(new double[]{
                        i == 0 ? w : w - padX, j == 0 ? s : s - padY,
                        i == nx - 1 ? area[2] : w + dx + padX, j == ny - 1 ? area[3] : s + dy + padY});
            }
        }
        return out;
    }

    private static String f6(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /**
     * A WCS 2.0 GetCoverage for the box in lat/long, answered in EPSG:4326 at the survey's own
     * detail times factor, as a tiled, Deflate-compressed float GeoTIFF. Nearest neighbour, so no
     * pixel at the survey's edge is a blend of a depth and the NoData value.
     */
    static String url(String coverage, double[] b, double factor) {
        String crs = "http://www.opengis.net/def/crs/EPSG/0/4326";
        String u = WCS_URL + "?service=WCS&version=2.0.1&request=GetCoverage&coverageId=" + coverage + "&format=image/tiff"
                + "&subset=Long(" + f6(b[0]) + "," + f6(b[2]) + ")&subset=Lat(" + f6(b[1]) + "," + f6(b[3]) + ")"
                + "&subsettingCrs=" + crs + "&outputCrs=" + crs;
        if (factor < 0.999) u += "&scalefactor=" + String.format(Locale.ROOT, "%.6f", factor);
        return u + "&interpolation=http://www.opengis.net/def/interpolation/OGC/1/nearest-neighbor"
                + "&geotiff:compression=Deflate&geotiff:tiling=true&geotiff:tilewidth=256&geotiff:tileheight=256";
    }

    /** The margin round the area asked for, in degrees: a few pixels at the request's detail. */
    static double marginDeg(double resM) {
        return MARGIN_PX * resM / Catalog.M_PER_DEG;
    }

    /** How many of the survey's own pixels the server would read for this box (0 when none of it is there). */
    static double ownPixels(Surveys.Survey s, double[] box) {
        double[] a = area(s, box, marginDeg(s.resM));
        if (a == null) return 0;
        double[] m = metres(a);
        return (m[0] / s.resM) * (m[1] / s.resM);
    }

    /** Too much of the survey in this box for the warehouse to cut in one build (see MAX_PX). */
    public static boolean tooBig(Surveys.Survey s, double[] box) {
        return Surveys.WCS.equals(s.kind) && ownPixels(s, box) > MAX_PX;
    }

    /** About how many bytes the chosen coverages take once saved for a build of this box at outResM. */
    static double savedBytes(List<Surveys.Survey> chosen, double[] box, double outResM) {
        double total = 0;
        for (Surveys.Survey s : chosen) {
            if (!Surveys.WCS.equals(s.kind)) continue;
            double res = requestResM(s, outResM);
            double[] a = area(s, box, marginDeg(res));
            if (a == null) continue;
            double[] m = metres(a);
            total += Math.ceil(m[0] / res) * Math.ceil(m[1] / res) * SAVED_BYTES_PER_PX;
        }
        return total;
    }

    /**
     * Cuts each chosen coverage for the plan's box from the warehouse into workDir and puts it in
     * the plan as an ordinary survey (local files, their headers read).
     *
     * Every file is added to written as soon as it's started, so the caller can always delete
     * them; and fetch deletes them itself when it doesn't finish normally (cancelled, or an error),
     * after stopping its downloads. A piece is tried twice. A coverage the warehouse doesn't send
     * (a failed piece, or no depths at all in the box) is left out whole, its files deleted at once,
     * with a note in plan.notes; then, if what's left is coarser than the plan's detail, the plan
     * is chosen again (Planner.replan). Requests run on their own threads, three at a time, so
     * they never hold up the shared network threads. Returns written.
     */
    public static List<File> fetch(Planner.Plan plan, File workDir, World.Net net, Job job, AtomicLong downloaded, List<File> written)
            throws IOException {
        List<File> files = written != null ? written : new ArrayList<>();
        int n = 0;
        for (Surveys.Survey s : plan.chosen) if (Surveys.WCS.equals(s.kind)) n++;
        if (n == 0) return files;
        double[] box = plan.p.bbox();
        String tag = Long.toHexString(System.nanoTime());
        AtomicLong count = downloaded != null ? downloaded : new AtomicLong();
        long start = count.get();
        String stage = n == 1 ? "Downloading a survey from the AusSeabed warehouse" : "Downloading " + n + " surveys from the AusSeabed warehouse";
        AtomicBoolean abort = new AtomicBoolean();
        ExecutorService exec = Executors.newFixedThreadPool(PARALLEL, r -> {
            Thread t = new Thread(r, "warehouse");
            t.setDaemon(true);
            return t;
        });
        List<Surveys.Survey> dropped = new ArrayList<>();
        boolean finished = false;
        try {
            for (int i = 0; i < plan.chosen.size(); i++) {
                Surveys.Survey s = plan.chosen.get(i);
                if (!Surveys.WCS.equals(s.kind)) continue;
                double res = requestResM(s, plan.resM);
                double[] area = area(s, box, marginDeg(res));
                if (area == null) {
                    dropped.add(s);
                    continue;
                }
                if (tooBig(s, box)) {
                    plan.notes.add(s.name + " was left out: too much of it is in this box for the AusSeabed warehouse to cut. A smaller box gets it.");
                    dropped.add(s);
                    continue;
                }
                double factor = scaleFactor(s, plan.resM);
                List<double[]> ps = pieces(area, s.resM, res);
                AtomicBoolean stop = new AtomicBoolean();
                Http.Cancel cancel = () -> stop.get() || abort.get() || job.cancelled();
                List<File> mine = new ArrayList<>();
                List<Future<RemoteTiff>> fs = new ArrayList<>();
                for (int k = 0; k < ps.size(); k++) {
                    File f = new File(workDir, "wcs-" + tag + "-" + i + "-" + k + ".tif");
                    mine.add(f);
                    synchronized (files) {
                        files.add(f);
                    }
                    String u = url(s.coverage, ps.get(k), factor);
                    fs.add(exec.submit(() -> piece(net, u, f, cancel, count)));
                }
                List<Surveys.SFile> got = new ArrayList<>();
                List<RemoteTiff> tiffs = new ArrayList<>();
                String failed = null;
                for (int k = 0; k < fs.size(); k++) {
                    RemoteTiff t;
                    try {
                        t = waitFor(fs.get(k), job, stage, count, start);
                    } catch (IOException e) {
                        if (e instanceof InterruptedIOException && !(e instanceof java.net.SocketTimeoutException)) throw e;
                        failed = e instanceof java.net.SocketTimeoutException ? "no answer in time" : e.getMessage();
                        break;
                    }
                    if (t == null) continue;
                    Surveys.SFile sf = new Surveys.SFile();
                    sf.url = mine.get(k).toURI().toString();
                    sf.crs = "EPSG:4326";
                    sf.proj = Proj.parse(sf.crs);
                    sf.bbox = ps.get(k);
                    sf.cells = new ArrayList<>();
                    sf.cells.add(sf.bbox);
                    sf.tiled = true;
                    sf.bytes = mine.get(k).length();
                    got.add(sf);
                    tiffs.add(t);
                }
                if (failed != null || got.isEmpty()) {
                    // Stop its other pieces (each deletes its own file when stopped) and delete what came.
                    stop.set(true);
                    for (Future<RemoteTiff> f : fs) f.cancel(true);
                    for (File f : mine) f.delete();
                    plan.notes.add(s.name + " was left out: " + (failed != null ? "the AusSeabed warehouse didn't send it (" + failed + ")."
                            : "the AusSeabed warehouse has no depths of it in this box."));
                    dropped.add(s);
                    continue;
                }
                for (int k = 0; k < got.size(); k++) plan.surveyTiffs.put(got.get(k).url, tiffs.get(k));
                plan.chosen.set(i, s.withFiles(got));
            }
            for (Iterator<Surveys.Survey> it = plan.chosen.iterator(); it.hasNext(); ) {
                Surveys.Survey s = it.next();
                if (dropped.contains(s)) {
                    it.remove();
                    plan.bandM.remove(s.id);
                }
            }
            if (!dropped.isEmpty()) {
                double was = plan.resM;
                if (Planner.replan(plan)) {
                    plan.notes.add("So the depths were built at " + Planner.g(plan.resM) + " m, the finest left, instead of " + Planner.g(was) + " m.");
                }
            }
            for (String note : plan.notes) job.add(note);
            finished = true;
            return files;
        } finally {
            if (!finished) {
                abort.set(true);
                exec.shutdownNow();
                try {
                    exec.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (files) {
                    for (File f : files) f.delete();
                }
            } else {
                exec.shutdown();
            }
        }
    }

    /** Waits before a piece's second try (ms). */
    static long retryWaitMs = 2000;

    /**
     * One piece, tried twice: its header once saved, or null when the box missed the survey (the
     * server says "Empty intersection"). Its file is deleted whenever it doesn't come back, so
     * nothing half-written is left behind.
     */
    static RemoteTiff piece(World.Net net, String url, File f, Http.Cancel cancel, AtomicLong count) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                cancel.check();
                if (attempt > 0) Thread.sleep(retryWaitMs);
                net.save(url, f, cancel, count);
                cancel.check();
                return RemoteTiff.open(f.toURI().toString(), cancel);
            } catch (InterruptedException e) {
                f.delete();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            } catch (IOException e) {
                f.delete();
                if (e instanceof InterruptedIOException && !(e instanceof java.net.SocketTimeoutException)) throw e;
                if (missed(e)) return null;
                last = e;
            } catch (RuntimeException e) {
                f.delete();
                throw e;
            }
        }
        throw last;
    }

    private static RemoteTiff waitFor(Future<RemoteTiff> f, Job job, String stage, AtomicLong count, long start) throws IOException {
        while (true) {
            job.progress(0, stage + " (" + Planner.fmtBytes(count.get() - start) + ")");
            try {
                return f.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                if (job.cancelled()) throw new InterruptedIOException("Cancelled");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            } catch (java.util.concurrent.CancellationException ce) {
                throw new InterruptedIOException("Cancelled");
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause();
                if (c instanceof IOException) throw (IOException) c;
                throw new IOException(String.valueOf(c), c);
            }
        }
    }

    /**
     * The box missed the survey: GeoServer's "Empty intersection after subsetting"
     * (InvalidSubsetting), as World.HTTP reports it from the reply. Anything else, other 404s,
     * a server's error report or a captive portal's page included, is a failure, not "nothing there".
     */
    static boolean missed(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("InvalidSubsetting") || m.contains("Empty intersection");
    }

    /** The coverage's address, for the sources file. */
    public static String sourceLine(Surveys.Survey s) {
        return WCS_URL + " (WCS coverage " + s.coverage + ")";
    }
}
