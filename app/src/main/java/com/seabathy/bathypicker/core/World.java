package com.seabathy.bathypicker.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-detail depths outside Australia, from open sources that serve any part of their data:
 * NOAA's coastal DEMs (USA), CHS NONNA (Canada), EMODnet (Europe), LINZ coastal lidar (New
 * Zealand) and Digital Earth Pacific's satellite depths (Pacific islands), with GMRT under them
 * all so the sea has no holes.
 *
 * The servers that make a GeoTIFF for a box (NOAA, CHS, EMODnet, GMRT) are asked for one at
 * the build's detail and it is saved in the cache folder; LINZ and Digital Earth Pacific keep
 * cloud-optimised GeoTIFFs that are read in place, by byte range, like the AusSeabed surveys.
 * Then the usual Builder lays them onto one grid: GMRT at the bottom, the finest source on top,
 * each one's edges blended into what's under it.
 */
public final class World {
    private World() {}

    // ------------------------------------------------------------------ sources

    public static final String GMRT = "gmrt", EMODNET = "emodnet", NOAA = "noaa", NONNA = "nonna", LINZ = "linz", DEP = "dep";

    public static final String CREDIT_GMRT = "Depths: GMRT, Ryan et al. 2009 (CC BY 4.0)";
    public static final String CREDIT_NOAA = "Depths: NOAA NCEI coastal DEMs (public domain)";
    public static final String CREDIT_NONNA =
            "Contains information licensed under the Open Government Licence - Canada (CHS NONNA, not for navigation)";
    public static final String CREDIT_EMODNET =
            "Depths: EMODnet Bathymetry Consortium (2024), EMODnet Digital Bathymetry (DTM 2024), CC BY 4.0";
    public static final String CREDIT_LINZ = "Depths: Toitū Te Whenua Land Information New Zealand, Coastal LiDAR DEM, CC BY 4.0";
    public static final String CREDIT_DEP =
            "Depths (estimate): Digital Earth Pacific, Pacific Community (SPC), satellite-derived bathymetry, CC BY 4.0";

    static final String NOAA_SERVICE = "https://gis.ngdc.noaa.gov/arcgis/rest/services/DEM_mosaics/DEM_global_mosaic/ImageServer";
    static final String NONNA_WCS = "https://nonna-geoserver.data.chs-shc.ca/geoserver/wcs";
    static final String EMODNET_WCS = "https://ows.emodnet-bathymetry.eu/wcs";
    static final String LINZ_BASE = "https://nz-coastal.s3.ap-southeast-2.amazonaws.com/new-zealand/new-zealand/dem_1m/2193/";
    static final String DEP_SEARCH = "https://stac.digitalearthpacific.org/collections/dep_s2_sdb/items";
    static final String DEP_HOST = "https://dep-public-data.s3.us-west-2.amazonaws.com/";
    static final String GMRT_SERVER = "https://www.gmrt.org/services/GridServer";

    /** NOAA items coarser than this (degrees, about 220 m) are the global fill (ETOPO), not high detail. */
    static final double NOAA_MAX_DEG = 0.002;
    static final double NONNA_RES_M = 10, EMODNET_RES_M = 115, LINZ_RES_M = 1, DEP_RES_M = 10;
    /** The most pixels each way asked of a server in one request; bigger areas are asked for in pieces. */
    static final int CHUNK = 4000;
    /** Pixels added round each piece, so neighbouring pieces overlap and the resampling has neighbours. */
    static final int MARGIN = 2;
    /**
     * How far (metres) each layer's edges fade into the one under it, at least: 200 m at the edge
     * of a source that covers most of the sea (NOAA, EMODnet), but only 30 m for the patchy ones
     * (surveys, lidar, satellite), which have many gaps where the fade would wear away their detail.
     */
    static final double BLEND_M = 200, BLEND_PATCHY_M = 30;

    static double blendM(String source, double resM) {
        boolean patchy = source.equals(NONNA) || source.equals(LINZ) || source.equals(DEP);
        return patchy ? Math.max(3 * resM, BLEND_PATCHY_M) : Math.max(4 * resM, BLEND_M);
    }

    // ------------------------------------------------------------------ places

    /** A named box: west, south, east, north (degrees). */
    static final class Box {
        final String name;
        final double w, s, e, n;

        Box(String name, double w, double s, double e, double n) {
            this.name = name;
            this.w = w;
            this.s = s;
            this.e = e;
            this.n = n;
        }

        boolean contains(double lon, double lat) {
            return lon >= w && lon <= e && lat >= s && lat <= n;
        }

        boolean meets(double[] b) {
            return b[0] < e && b[2] > w && b[1] < n && b[3] > s;
        }

        /** Rough distance in degrees (longitude shrunk by latitude) from a point to this box; 0 inside. */
        double distance(double lon, double lat) {
            double dx = Math.max(Math.max(w - lon, 0), lon - e), dy = Math.max(Math.max(s - lat, 0), lat - n);
            return Math.hypot(dx * Math.cos(Math.toRadians(lat)), dy);
        }
    }

    static Box box(String name, double w, double s, double e, double n) {
        return new Box(name, w, s, e, n);
    }

    /** NOAA's DEMs, by where they are (first match wins, so the order matters). */
    static final Box[] US = {
            box("Great Lakes", -92.3, 41.3, -75.8, 49.1),
            box("Florida", -83.5, 24.3, -79.8, 30.8),
            box("Gulf of Mexico", -98.0, 24.0, -83.5, 31.0),
            box("Atlantic coast", -81.6, 30.8, -71.0, 40.3),
            box("Atlantic coast", -75.0, 40.3, -66.5, 47.6),
            box("Pacific coast", -125.5, 32.0, -116.5, 49.2),
            box("Alaska", -179.999, 50.5, -129.5, 72.0),
            box("Alaska", 172.0, 50.5, 180.0, 56.0),
            box("Hawaii", -179.999, 18.0, -154.0, 29.0),
            box("Puerto Rico and US Virgin Islands", -68.0, 17.5, -64.3, 18.8),
            box("Guam and Northern Marianas", 144.4, 13.0, 146.2, 20.6),
            box("American Samoa", -171.2, -14.7, -168.0, -10.9),
    };

    static final Box[] CANADA = {
            box("Great Lakes", -92.5, 41.6, -76.0, 49.0),
            box("St Lawrence", -76.0, 45.6, -58.5, 51.8),
            box("Hudson Bay", -95.5, 51.0, -76.0, 66.0),
            box("Pacific coast", -139.5, 48.0, -122.5, 60.0),
            box("Atlantic coast", -71.5, 41.0, -52.0, 61.0),
            box("Arctic", -141.0, 60.0, -55.0, 84.0),
    };

    /** EMODnet's extent. */
    static final Box[] EUROPE = {box("Europe", -36.0, 15.0, 43.0, 90.0)};
    static final Box[] EUROPE_PLACES = {box("Red Sea", 32.0, 12.0, 44.0, 30.2)};

    static final Box[] NZ_AREA = {box("New Zealand", 165.5, -48.0, 179.0, -33.8)};
    static final Box[] NZ_PLACES = {
            box("Northland", 172.5, -36.5, 174.8, -34.3),
            box("Auckland", 174.2, -37.3, 175.6, -36.0),
            box("Waikato", 174.5, -38.8, 176.2, -36.4),
            box("Bay of Plenty", 175.8, -38.5, 178.0, -37.2),
            box("Gisborne", 177.0, -39.0, 178.7, -37.5),
            box("Hawke's Bay", 176.0, -40.5, 178.0, -38.8),
            box("Taranaki", 173.6, -39.9, 175.0, -38.6),
            box("Manawatū-Whanganui", 174.8, -40.6, 175.6, -39.5),
            box("Wellington", 174.6, -41.7, 176.5, -40.6),
            box("Marlborough", 173.4, -42.2, 174.4, -40.7),
            box("Nelson and Tasman", 172.4, -41.6, 174.0, -40.4),
            box("West Coast", 166.4, -44.0, 172.5, -40.7),
            box("Canterbury", 171.0, -44.9, 174.0, -42.2),
            box("Otago", 169.4, -46.7, 171.3, -44.9),
            box("Southland", 166.3, -47.4, 169.4, -45.5),
    };

    /** Where Digital Earth Pacific is looked for: 130 E to 120 W, 25 S to 25 N, less Indonesian Papua. */
    static final Box[] PACIFIC = {box("Pacific", 130.0, -25.0, 180.0, 25.0), box("Pacific", -180.0, -25.0, -120.0, 25.0)};
    static final Box NOT_PACIFIC = box("Indonesia", 129.0, -9.0, 141.0, -0.8);
    static final Box[] PACIFIC_PLACES = {
            box("Fiji", 176.0, -21.0, 180.0, -12.0),
            box("Fiji", -180.0, -21.0, -178.0, -15.5),
            box("Papua New Guinea", 140.8, -12.0, 155.0, 0.5),
            box("Papua New Guinea", 154.0, -7.0, 155.8, -4.5),
            box("Solomon Islands", 155.5, -12.5, 170.5, -5.0),
            box("Vanuatu", 166.4, -20.4, 170.4, -13.0),
            box("New Caledonia", 158.0, -23.0, 169.0, -18.0),
            box("Tonga", -176.5, -23.5, -173.0, -15.0),
            box("Samoa", -173.0, -14.2, -171.3, -13.3),
            box("American Samoa", -171.2, -14.7, -168.0, -10.9),
            box("Wallis and Futuna", -178.5, -14.6, -175.8, -12.9),
            box("Tuvalu", 176.0, -11.0, 180.0, -5.5),
            box("Tokelau", -172.6, -9.5, -171.1, -8.5),
            box("Niue", -170.1, -19.3, -169.6, -18.8),
            box("Cook Islands", -166.5, -22.5, -157.0, -8.5),
            box("Kiribati", 172.5, -3.0, 177.0, 3.5),
            box("Kiribati", -175.0, -5.0, -170.0, -2.5),
            box("Kiribati", -161.0, -12.0, -150.0, 6.5),
            box("French Polynesia", -155.0, -28.5, -134.0, -7.0),
            box("Pitcairn Islands", -131.0, -25.5, -124.5, -23.8),
            box("Nauru", 166.8, -0.7, 167.1, -0.4),
            box("Micronesia", 137.5, 0.5, 163.5, 10.5),
            box("Marshall Islands", 160.5, 4.0, 172.5, 15.0),
            box("Palau", 131.0, 2.5, 135.0, 8.5),
            box("Guam and Northern Marianas", 144.4, 13.0, 146.2, 20.6),
            box("Hawaii", -179.999, 18.0, -154.0, 29.0),
    };

    /**
     * Australian waters, roughly (the IMAS composite's EEZ: mainland, Tasmania, the Coral Sea,
     * Lord Howe, Norfolk and Macquarie), drawn to keep out Indonesia, Timor-Leste, Papua New
     * Guinea, New Caledonia and New Zealand. Longitude, latitude.
     */
    static final double[][] AUSTRALIA = {
            {109.0, -15.0}, {114.0, -12.6}, {120.0, -12.0}, {123.3, -11.3}, {127.0, -10.3}, {130.5, -9.7}, {133.5, -9.4},
            {138.0, -9.8}, {141.0, -9.75}, {142.0, -9.15}, {143.6, -9.2}, {144.3, -9.6}, {147.0, -10.8}, {150.0, -12.0},
            {153.0, -12.6}, {155.5, -12.8}, {157.3, -14.0}, {158.0, -17.0}, {157.5, -21.0}, {157.3, -24.5}, {159.0, -25.5},
            {163.5, -26.5}, {171.5, -27.5}, {171.5, -32.0}, {166.0, -33.0}, {163.5, -34.0}, {163.5, -40.0}, {163.0, -47.0},
            {162.5, -50.5}, {163.5, -55.0}, {160.0, -60.0}, {150.0, -60.0}, {140.0, -50.0}, {112.0, -40.0}, {108.5, -30.0},
            {108.5, -20.0},
    };
    /** Christmas Island and the Cocos (Keeling) Islands. */
    static final Box[] AUSTRALIA_ISLANDS = {box("Christmas Island", 104.0, -12.5, 107.5, -9.0), box("Cocos Islands", 95.0, -14.5, 99.5, -9.5)};

    /** The box's middle is in Australian waters, where High detail is the IMAS composite with surveys on top. */
    public static boolean inAustralia(double[] b) {
        double lon = (b[0] + b[2]) / 2, lat = (b[1] + b[3]) / 2;
        if (inPolygon(AUSTRALIA, lon, lat)) return true;
        for (Box x : AUSTRALIA_ISLANDS) if (x.contains(lon, lat)) return true;
        return false;
    }

    /** Some of the box is in a place where High detail works: Australian waters or an overseas source's. */
    public static boolean touches(double[] b) {
        if (polygonMeetsBox(AUSTRALIA, b)) return true;
        for (Box x : AUSTRALIA_ISLANDS) if (x.meets(b)) return true;
        return any(US, b) || any(CANADA, b) || any(EUROPE, b) || any(NZ_AREA, b) || pacific(b);
    }

    static boolean any(Box[] boxes, double[] b) {
        for (Box x : boxes) if (x.meets(b)) return true;
        return false;
    }

    static boolean pacific(double[] b) {
        if (!any(PACIFIC, b)) return false;
        // Not when the whole box is in Indonesian Papua.
        return !(b[0] >= NOT_PACIFIC.w && b[2] <= NOT_PACIFIC.e && b[1] >= NOT_PACIFIC.s && b[3] <= NOT_PACIFIC.n);
    }

    /** The name of the place the box's middle is in, or the nearest within maxDeg, or null. */
    static String place(Box[] boxes, double[] b, double maxDeg) {
        double lon = (b[0] + b[2]) / 2, lat = (b[1] + b[3]) / 2;
        for (Box x : boxes) if (x.contains(lon, lat)) return x.name;
        Box best = null;
        double bd = Double.MAX_VALUE;
        for (Box x : boxes) {
            double d = x.distance(lon, lat);
            if (d < bd) {
                bd = d;
                best = x;
            }
        }
        return best != null && bd <= maxDeg ? best.name : null;
    }

    static boolean inPolygon(double[][] poly, double x, double y) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    static boolean polygonMeetsBox(double[][] poly, double[] b) {
        double[][] corners = {{b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}, {b[0], b[3]}};
        for (double[] c : corners) if (inPolygon(poly, c[0], c[1])) return true;
        for (double[] p : poly) if (p[0] >= b[0] && p[0] <= b[2] && p[1] >= b[1] && p[1] <= b[3]) return true;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            for (int k = 0; k < 4; k++) {
                double[] c = corners[k], d = corners[(k + 1) % 4];
                if (segmentsCross(poly[j], poly[i], c, d)) return true;
            }
        }
        return false;
    }

    private static boolean segmentsCross(double[] p1, double[] p2, double[] q1, double[] q2) {
        double d1 = cross(q1, q2, p1), d2 = cross(q1, q2, p2), d3 = cross(p1, p2, q1), d4 = cross(p1, p2, q2);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    // ------------------------------------------------------------------ the network

    /** What the planner needs from the internet; tests give it a fake. */
    public interface Net {
        /** A small answer (JSON) as text. */
        String text(String url, Http.Cancel cancel) throws IOException;

        /** Saves a GeoTIFF the server makes to file, adding the bytes to counter as they come. */
        void save(String url, File file, Http.Cancel cancel, AtomicLong counter) throws IOException;
    }

    /** The real internet, through HttpURLConnection like the rest of the engine. */
    public static final Net HTTP = new Net() {
        @Override public String text(String url, Http.Cancel cancel) throws IOException {
            HttpURLConnection con = open(url, 60_000);
            try {
                int code = con.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code + " from " + con.getURL().getHost());
                try (InputStream in = con.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 16 << 20) throw new IOException("The answer from " + con.getURL().getHost() + " is too big.");
                        if (cancel != null) cancel.check();
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                con.disconnect();
            }
        }

        @Override public void save(String url, File file, Http.Cancel cancel, AtomicLong counter) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (cancel != null) cancel.check();
                if (attempt > 0) {
                    try {
                        Thread.sleep(3000L * attempt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Cancelled");
                    }
                }
                long got = 0;
                boolean saved = false;
                HttpURLConnection con = null;
                try {
                    con = open(url, 240_000);
                    int code = con.getResponseCode();
                    String host = con.getURL().getHost();
                    if (code != 200) {
                        String why = reason(con);
                        last = new IOException("HTTP " + code + " from " + host + (why.isEmpty() ? "" : " (" + why + ")"));
                        if (code >= 400 && code < 500 && code != 408 && code != 429) throw last;
                        continue;
                    }
                    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            got += n;
                            if (counter != null) counter.addAndGet(n);
                            if (cancel != null && cancel.cancelled()) throw new InterruptedIOException("Cancelled");
                        }
                    }
                    checkTiff(file, host);
                    saved = true;
                    return;
                } catch (InterruptedIOException e) {
                    if (e instanceof java.net.SocketTimeoutException) {
                        last = e;
                    } else {
                        throw e;
                    }
                } catch (java.net.UnknownHostException e) {
                    throw new IOException("Can't reach " + e.getMessage() + ". Check the phone's internet connection.", e);
                } catch (IOException e) {
                    last = e;
                    String m = String.valueOf(e.getMessage());
                    if (m.startsWith("HTTP 4") || m.contains("sent no depths")) throw e;
                } finally {
                    if (con != null) con.disconnect();
                    if (counter != null && got > 0 && !saved) counter.addAndGet(-got);  // counted again on the next try
                }
            }
            throw last != null ? last : new IOException("Download failed");
        }
    };

    private static HttpURLConnection open(String url, int readMs) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(30_000);
        con.setReadTimeout(readMs);
        con.setRequestProperty("User-Agent", Http.userAgent);
        con.setRequestProperty("Accept-Encoding", "identity");
        return con;
    }

    /**
     * What a server's error reply says, briefly: an OGC exception's code and text
     * ("InvalidSubsetting: Empty intersection after subsetting"), or "" when it says nothing readable.
     */
    static String reason(HttpURLConnection con) {
        try (InputStream in = con.getErrorStream()) {
            if (in == null) return "";
            byte[] buf = new byte[4096];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return reason(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static final String EXCEPTION_TEXT = "ExceptionText>([^<]{1,200})<|\"message\"\\s*:\\s*\"([^\"]{1,200})\"";

    static String reason(String text) {
        java.util.regex.Matcher code = java.util.regex.Pattern.compile("exceptionCode=\"([A-Za-z]{1,60})\"").matcher(text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(EXCEPTION_TEXT).matcher(text);
        String why = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)).trim() : "";
        String c = code.find() ? code.group(1) : "";
        return c.isEmpty() ? why : why.isEmpty() ? c : c + ": " + why;
    }

    /** A server that can't make the file answers with a message (XML or JSON) instead: say what it said. */
    static void checkTiff(File f, String host) throws IOException {
        byte[] head = new byte[2048];
        int n;
        try (InputStream in = new java.io.FileInputStream(f)) {
            n = Math.max(0, in.read(head));
        }
        boolean tiff = n >= 4 && ((head[0] == 'I' && head[1] == 'I' && (head[2] == 42 || head[2] == 43) && head[3] == 0)
                || (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && (head[3] == 42 || head[3] == 43)));
        if (tiff) return;
        String why = reason(new String(head, 0, n, StandardCharsets.UTF_8));
        throw new IOException(host + " sent no depths for this area" + (why.isEmpty() ? "." : " (" + why + ")."));
    }

    // ------------------------------------------------------------------ links

    private static String f6(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String noaaQueryUrl(double[] b) {
        return NOAA_SERVICE + "/query?geometry=" + f6(b[0]) + "," + f6(b[1]) + "," + f6(b[2]) + "," + f6(b[3])
                + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects"
                + "&where=" + enc("Category=1 AND LowPS<" + NOAA_MAX_DEG)
                + "&outFields=Name,LowPS,VerticalDatum&returnGeometry=true&outSR=4326&geometryPrecision=5&maxAllowableOffset=0.0005&f=json";
    }

    /** NOAA's mosaic for a box at cols x rows, leaving out the global fill so GMRT shows there instead. */
    static String noaaExportUrl(double[] b, int cols, int rows) {
        String rule = "{\"mosaicMethod\":\"esriMosaicAttribute\",\"sortField\":\"LowPS\",\"sortValue\":\"0\",\"ascending\":true,"
                + "\"where\":\"LowPS<" + NOAA_MAX_DEG + "\"}";
        return NOAA_SERVICE + "/exportImage?bbox=" + f6(b[0]) + "," + f6(b[1]) + "," + f6(b[2]) + "," + f6(b[3])
                + "&bboxSR=4326&imageSR=4326&size=" + cols + "," + rows
                + "&format=tiff&pixelType=F32&compression=LZ77&interpolation=RSP_BilinearInterpolation&noData=-99999"
                + "&mosaicRule=" + enc(rule) + "&f=image";
    }

    /** A WCS 2.0 GetCoverage for a box, scaled to cols x rows (or as stored when cols is 0). */
    static String wcsUrl(String base, String coverage, double[] b, int cols, int rows, boolean crs) {
        String u = base + "?service=WCS&version=2.0.1&request=GetCoverage&coverageId=" + coverage + "&format=image/tiff"
                + "&subset=Long(" + f6(b[0]) + "," + f6(b[2]) + ")&subset=Lat(" + f6(b[1]) + "," + f6(b[3]) + ")";
        if (crs) u += "&subsettingCrs=http://www.opengis.net/def/crs/EPSG/0/4326";
        if (cols > 0 && rows > 0) u += "&scalesize=i(" + cols + "),j(" + rows + ")";
        return u + "&geotiff:compression=Deflate&geotiff:tiling=true&geotiff:tilewidth=256&geotiff:tileheight=256";
    }

    static String nonnaUrl(double[] b, int cols, int rows) {
        return wcsUrl(NONNA_WCS, "nonna__NONNA%2010%20Coverage", b, cols, rows, true);
    }

    static String emodnetUrl(double[] b, int cols, int rows) {
        return wcsUrl(EMODNET_WCS, "emodnet__mean", b, cols, rows, false);
    }

    static String gmrtUrl(double[] b) {
        return String.format(Locale.ROOT, GMRT_SERVER + "?north=%.5f&south=%.5f&east=%.5f&west=%.5f&layer=topo&format=geotiff&resolution=high",
                b[3], b[1], b[2], b[0]);
    }

    static String depSearchUrl(double[] b) {
        return DEP_SEARCH + "?bbox=" + f6(b[0]) + "," + f6(b[1]) + "," + f6(b[2]) + "," + f6(b[3]) + "&limit=50";
    }

    static String linzUrl(String code) {
        return LINZ_BASE + code + ".tiff";
    }

    // ------------------------------------------------------------------ LINZ sheets

    private static final String SHEET_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    /**
     * NZTM2000 extent of a 1:50,000 Topo50 sheet ("AV29"), which is how LINZ names its tiles:
     * 24 km wide, 36 km tall, columns from 1,084,000 E and rows (letters without I and O) down
     * from 6,234,000 N. Null if the name isn't one.
     */
    static double[] sheet(String code) {
        if (code == null || !code.matches("[A-Z]{2}\\d{2}")) return null;
        int a = SHEET_LETTERS.indexOf(code.charAt(0)), b = SHEET_LETTERS.indexOf(code.charAt(1));
        if (a < 0 || b < 0) return null;
        int row = a * 24 + b - 16, col = Integer.parseInt(code.substring(2));
        double x0 = 1084000 + (col - 4) * 24000.0, y1 = 6234000 - row * 36000.0;
        return new double[]{x0, y1 - 36000, x0 + 24000, y1};
    }

    /** The box in NZTM2000 (its outline projected, so the curve of the edges is kept). */
    static double[] nztmBox(double[] b) {
        Proj p = Proj.parse("EPSG:2193");
        double[] o = new double[2];
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i <= 8; i++) {
            double f = i / 8.0;
            double lon = b[0] + (b[2] - b[0]) * f, lat = b[1] + (b[3] - b[1]) * f;
            for (double[] ll : new double[][]{{lon, b[1]}, {lon, b[3]}, {b[0], lat}, {b[2], lat}}) {
                p.forward(ll[0], ll[1], o);
                x0 = Math.min(x0, o[0]); x1 = Math.max(x1, o[0]);
                y0 = Math.min(y0, o[1]); y1 = Math.max(y1, o[1]);
            }
        }
        return new double[]{x0, y0, x1, y1};
    }

    /** The tiles named in LINZ's collection.json ("./AV29.json" links). */
    static List<String> linzCodes(String collectionJson) {
        List<String> out = new ArrayList<>();
        Object o = Json.parse(stripBom(collectionJson));
        if (!(o instanceof Map)) return out;
        Object links = ((Map<?, ?>) o).get("links");
        if (!(links instanceof List)) return out;
        for (Object l : (List<?>) links) {
            if (!(l instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) l;
            if (!"item".equals(m.get("rel"))) continue;
            String href = String.valueOf(m.get("href"));
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^\\./([A-Z]{2}\\d{2})\\.json$").matcher(href);
            if (mm.find()) out.add(mm.group(1));
        }
        return out;
    }

    static List<String> linzTilesIn(List<String> codes, double[] b) {
        double[] nz = nztmBox(b);
        List<String> out = new ArrayList<>();
        for (String c : codes) {
            double[] s = sheet(c);
            if (s != null && s[0] < nz[2] && s[2] > nz[0] && s[1] < nz[3] && s[3] > nz[1]) out.add(c);
        }
        return out;
    }

    private static volatile List<String> linzCache;
    private static volatile long linzCacheTime;

    private static List<String> linzCodes(Net net, Http.Cancel cancel) throws IOException {
        List<String> c = linzCache;
        if (c != null && System.currentTimeMillis() - linzCacheTime < 6 * 3600_000L) return c;
        c = Collections.unmodifiableList(linzCodes(net.text(LINZ_BASE + "collection.json", cancel)));
        linzCache = c;
        linzCacheTime = System.currentTimeMillis();
        return c;
    }

    /** For tests: forget the LINZ tile list fetched earlier. */
    static void forgetCache() {
        linzCache = null;
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    // ------------------------------------------------------------------ the plan

    /** One overseas source used for a box. */
    public static final class Use {
        public final String source;
        /** Where, as the line under the box says it: "USA (Florida)", "Fiji". */
        public String place;
        /** The source's finest detail in the box (metres), and its usual detail there (NOAA mixes several). */
        public double resM, typicalM;
        /** Share of the box with data, 0 to 1, or -1 when not known beforehand. */
        public double cover = -1;
        /** Cloud-optimised GeoTIFFs read in place (LINZ, Digital Earth Pacific). */
        public final List<String> files = new ArrayList<>();
        /** For the credits: the source's own names for what it used (NOAA's DEMs, the satellite period). */
        public final Set<String> names = new LinkedHashSet<>();
        public final Set<String> datums = new LinkedHashSet<>();

        Use(String source) {
            this.source = source;
        }
    }

    /** What a box will get, before anything is downloaded. */
    public static final class Assessment {
        public final double[] bbox;
        /** Finest first. */
        public final List<Use> uses = new ArrayList<>();
        public boolean ok;
        /** The line under the box, or why it can't be built. */
        public String line;
        public double finestM, resM;
        public long estBytes;
        /** Regions looked at, for saying why there's nothing. */
        final List<String> looked = new ArrayList<>();

        Assessment(double[] bbox) {
            this.bbox = bbox.clone();
        }

        public Use use(String source) {
            for (Use u : uses) if (u.source.equals(source)) return u;
            return null;
        }
    }

    public static final String NONE = "No high-detail depths here. Cancel and choose Standard.";

    /**
     * What a box outside Australia will get: which sources have data there (a few small requests:
     * NOAA's list of DEMs, a tiny sample from CHS and EMODnet, LINZ's tile list, Digital Earth
     * Pacific's search), the detail that fits under capBytes, and the line saying so.
     */
    public static Assessment assess(double[] b, double capBytes, long freeBytes, File workDir, Net net, Http.Cancel cancel) throws IOException {
        Assessment a = new Assessment(b);
        // A server that doesn't answer leaves its source out; when none answers, the line says so.
        Map<String, String> down = new LinkedHashMap<>();
        if (any(US, b)) lookup("NOAA", down, () -> noaa(a, net, cancel));
        if (any(CANADA, b)) lookup("CHS", down, () -> sampled(a, NONNA, workDir, net, cancel));
        if (any(EUROPE, b)) lookup("EMODnet", down, () -> sampled(a, EMODNET, workDir, net, cancel));
        if (any(NZ_AREA, b)) lookup("LINZ", down, () -> linz(a, net, cancel));
        if (pacific(b)) lookup("Digital Earth Pacific", down, () -> dep(a, net, cancel));
        a.uses.sort((x, y) -> Double.compare(x.resM, y.resM));
        String missing = "";
        if (!down.isEmpty()) {
            String why = down.values().iterator().next();
            missing = String.join(" and ", down.keySet()) + " didn't answer" + (why.isEmpty() ? "" : " (" + why + ")");
        }
        if (a.uses.isEmpty()) {
            a.ok = false;
            if (!down.isEmpty()) {
                a.line = missing + ". Check the internet connection or try again later, or cancel and choose Standard.";
            } else {
                a.line = a.looked.size() == 1 ? "No high-detail depths here (" + a.looked.get(0) + "). Cancel and choose Standard." : NONE;
            }
            return a;
        }
        double finest = a.uses.get(0).resM;
        Object[] ch = Planner.chooseResolution(b, finest, "wgs84", "auto", capBytes);
        a.finestM = finest;
        a.resM = (Double) ch[0];
        long[] px = Planner.outputPixels(b, a.resM, "wgs84");
        a.estBytes = px[0] * px[1] * 4;
        List<String> errors = new ArrayList<>();
        if (a.estBytes > capBytes)
            errors.add("About " + Planner.fmtBytes(a.estBytes) + " is over the " + Planner.fmtBytes(capBytes) + " limit. Draw a smaller box.");
        // The file and its overviews, three working copies while blending, and the servers' files at this detail.
        boolean served = a.use(NOAA) != null || a.use(NONNA) != null;
        double need = a.estBytes * 3.7 + (served ? a.estBytes : 0) + 50e6;
        if (freeBytes >= 0 && freeBytes < need)
            errors.add("Not enough free space on this phone (" + Planner.fmtBytes(freeBytes) + " free, roughly " + Planner.fmtBytes(need) + " needed).");
        a.ok = errors.isEmpty();
        a.line = a.ok ? line(a) + (missing.isEmpty() ? "" : " " + missing + ", so it's left out.") : String.join(" ", errors);
        return a;
    }

    private interface Lookup {
        void run() throws IOException;
    }

    private static void lookup(String server, Map<String, String> down, Lookup l) throws IOException {
        try {
            l.run();
        } catch (InterruptedIOException e) {
            if (!(e instanceof java.net.SocketTimeoutException)) throw e;
            down.put(server, "no answer in time");
        } catch (IOException e) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(String.valueOf(e.getMessage()));
            down.put(server, m.find() ? "HTTP " + m.group(1) : "");
        } catch (RuntimeException e) {
            down.put(server, "an answer the app couldn't read");
        }
    }

    /** "USA (Florida): NOAA, 3.4 m. Up to about 120 MB at 3.4 m detail." */
    static String line(Assessment a) {
        Set<String> places = new LinkedHashSet<>();
        for (Use u : a.uses) {
            boolean inside = false;
            for (String p : places) if (p.contains(u.place)) inside = true;
            if (!inside) places.add(u.place);
        }
        List<String> parts = new ArrayList<>();
        boolean fills = false;
        for (Use u : a.uses) {
            parts.add(what(u));
            if (u.cover < 0.99) fills = true;
        }
        if (fills) parts.add("GMRT fills the rest");
        double mb = Math.max(1.0, a.estBytes / 1e6);
        String detail = metres(a.resM) + " m detail";
        if (a.resM > a.finestM * 1.01) detail += " (a smaller box comes sharper)";
        return String.join(" and ", places) + ": " + String.join("; ", parts) + ". "
                + String.format(Locale.ROOT, "Up to about %.0f MB at %s.", mb, detail);
    }

    private static String what(Use u) {
        switch (u.source) {
            case NOAA:
                return "NOAA, " + metres(u.resM) + " m" + (u.typicalM > u.resM * 1.3 ? " in parts, mostly " + metres(u.typicalM) + " m" : "");
            case NONNA: return "CHS surveys, 10 m where surveyed";
            case EMODNET: return "EMODnet, about 115 m";
            case LINZ: return "LINZ lidar near shore, 1 m";
            case DEP: return "satellite estimate of the reef shallows, 10 m";
            default: return u.source;
        }
    }

    /** 3.4, 10, 115: one decimal under 10 m. */
    static String metres(double m) {
        return m < 10 ? String.format(Locale.ROOT, "%.1f", m).replace(".0", "") : String.format(Locale.ROOT, "%.0f", m);
    }

    // ------------------------------------------------------------------ looking up each source

    @SuppressWarnings("unchecked")
    static void noaa(Assessment a, Net net, Http.Cancel cancel) throws IOException {
        a.looked.add("NOAA has no coastal DEM for this box");
        Object o = Json.parse(stripBom(net.text(noaaQueryUrl(a.bbox), cancel)));
        if (!(o instanceof Map)) return;
        Object feats = ((Map<String, Object>) o).get("features");
        if (!(feats instanceof List)) {
            Object err = ((Map<String, Object>) o).get("error");
            if (err != null) throw new IOException("NOAA's depth service answered with an error.");
            return;
        }
        List<double[][][]> rings = new ArrayList<>();
        List<Double> res = new ArrayList<>();
        Use u = new Use(NOAA);
        for (Object fo : (List<Object>) feats) {
            if (!(fo instanceof Map)) continue;
            Map<String, Object> f = (Map<String, Object>) fo;
            Map<String, Object> at = f.get("attributes") instanceof Map ? (Map<String, Object>) f.get("attributes") : Collections.emptyMap();
            if (!(at.get("LowPS") instanceof Number)) continue;
            double ps = ((Number) at.get("LowPS")).doubleValue();
            if (!(ps > 0 && ps < NOAA_MAX_DEG)) continue;
            double[][][] poly = null;
            if (f.get("geometry") instanceof Map && ((Map<String, Object>) f.get("geometry")).get("rings") instanceof List) {
                List<Object> rl = (List<Object>) ((Map<String, Object>) f.get("geometry")).get("rings");
                poly = new double[rl.size()][][];
                for (int i = 0; i < rl.size(); i++) {
                    List<Object> pts = (List<Object>) rl.get(i);
                    poly[i] = new double[pts.size()][];
                    for (int k = 0; k < pts.size(); k++) {
                        List<Object> pt = (List<Object>) pts.get(k);
                        poly[i][k] = new double[]{((Number) pt.get(0)).doubleValue(), ((Number) pt.get(1)).doubleValue()};
                    }
                }
            }
            rings.add(poly);
            res.add(ps * Catalog.M_PER_DEG);
            if (at.get("Name") != null && u.names.size() < 12) u.names.add(String.valueOf(at.get("Name")));
            if (at.get("VerticalDatum") != null) u.datums.add(String.valueOf(at.get("VerticalDatum")));
        }
        if (res.isEmpty()) return;
        // Share of the box with a DEM, and the finest DEM at each of 24 x 24 points across it.
        int N = 24, hit = 0;
        List<Double> best = new ArrayList<>();
        double[] b = a.bbox;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                double lon = b[0] + (b[2] - b[0]) * (j + 0.5) / N, lat = b[1] + (b[3] - b[1]) * (i + 0.5) / N;
                double m = Double.MAX_VALUE;
                for (int k = 0; k < rings.size(); k++) {
                    double[][][] poly = rings.get(k);
                    if (poly == null || inRings(poly, lon, lat)) m = Math.min(m, res.get(k));
                }
                if (m < Double.MAX_VALUE) {
                    hit++;
                    best.add(m);
                }
            }
        }
        double finest = Collections.min(res);
        u.resM = Math.round(finest * 10) / 10.0;
        if (!best.isEmpty()) {
            Collections.sort(best);
            u.typicalM = Math.round(best.get(best.size() / 2) * 10) / 10.0;
        } else {
            u.typicalM = u.resM;
        }
        u.cover = hit / (double) (N * N);
        u.place = "USA (" + nonNull(place(US, b, 3.0), "US waters") + ")";
        a.looked.remove(a.looked.size() - 1);
        a.uses.add(u);
    }

    private static boolean inRings(double[][][] rings, double x, double y) {
        boolean in = false;
        for (double[][] r : rings) if (inPolygon(r, x, y)) in = !in;
        return in;
    }

    /** CHS NONNA or EMODnet: a 48 x 48 sample of the box says whether, and how much of it, has depths. */
    static void sampled(Assessment a, String source, File workDir, Net net, Http.Cancel cancel) throws IOException {
        boolean nonna = source.equals(NONNA);
        a.looked.add(nonna ? "no CHS surveys in this box" : "EMODnet has no depths in this box");
        File f = new File(workDir, "sample-" + source + "-" + Long.toHexString(System.nanoTime()) + ".tif");
        double share;
        try {
            net.save(nonna ? nonnaUrl(a.bbox, 48, 48) : emodnetUrl(a.bbox, 48, 48), f, cancel, null);
            share = validShare(RemoteTiff.open(f.toURI().toString(), cancel), cancel);
        } finally {
            f.delete();
        }
        if (share <= 0) return;
        Use u = new Use(source);
        u.resM = u.typicalM = nonna ? NONNA_RES_M : EMODNET_RES_M;
        u.cover = share;
        if (nonna) {
            u.place = "Canada (" + nonNull(place(CANADA, a.bbox, 3.0), "Canadian waters") + ")";
        } else {
            u.place = nonNull(place(EUROPE_PLACES, a.bbox, 0), "Europe");
        }
        a.looked.remove(a.looked.size() - 1);
        a.uses.add(u);
    }

    /** Share of a (small) GeoTIFF's pixels that hold data. */
    static double validShare(RemoteTiff t, Http.Cancel cancel) throws IOException {
        RemoteTiff.Level L = t.levels.get(0);
        long valid = 0, total = (long) L.width * L.height;
        for (int ty = 0; ty < L.tilesY; ty++) {
            long[][] idx = t.tileRow(L, ty, cancel);
            for (int tx = 0; tx < L.tilesX; tx++) {
                if (idx[1][tx] <= 0) continue;
                byte[] raw = Http.range(t.url, idx[0][tx], (int) idx[1][tx], cancel);
                byte[] z = t.pack(L, ty, raw, idx[0][tx], cancel);
                float[] v = t.decode(L, z, 0, z.length);
                int x0 = tx * L.tileW, y0 = ty * L.tileH;
                for (int y = 0; y < L.tileH && y0 + y < L.height; y++)
                    for (int x = 0; x < L.tileW && x0 + x < L.width; x++)
                        if (!t.isNodata(v[y * L.tileW + x])) valid++;
            }
        }
        return total == 0 ? 0 : valid / (double) total;
    }

    static void linz(Assessment a, Net net, Http.Cancel cancel) throws IOException {
        a.looked.add("New Zealand's coastal lidar doesn't reach this box yet");
        List<String> tiles = linzTilesIn(linzCodes(net, cancel), a.bbox);
        if (tiles.isEmpty()) return;
        Use u = new Use(LINZ);
        u.resM = u.typicalM = LINZ_RES_M;
        for (String c : tiles) {
            u.files.add(linzUrl(c));
            u.names.add(c);
        }
        u.place = "New Zealand (" + nonNull(place(NZ_PLACES, a.bbox, 2.0), "coast") + ")";
        a.looked.remove(a.looked.size() - 1);
        a.uses.add(u);
    }

    @SuppressWarnings("unchecked")
    static void dep(Assessment a, Net net, Http.Cancel cancel) throws IOException {
        a.looked.add("no satellite depths for this box");
        Object o = Json.parse(stripBom(net.text(depSearchUrl(a.bbox), cancel)));
        if (!(o instanceof Map) || !(((Map<String, Object>) o).get("features") instanceof List)) return;
        // One item per tile: the newest period when there are several.
        Map<String, String[]> byTile = new LinkedHashMap<>();
        for (Object fo : (List<Object>) ((Map<String, Object>) o).get("features")) {
            if (!(fo instanceof Map)) continue;
            Map<String, Object> f = (Map<String, Object>) fo;
            Object assets = f.get("assets");
            if (!(assets instanceof Map) || !(((Map<String, Object>) assets).get("depth") instanceof Map)) continue;
            String href = String.valueOf(((Map<String, Object>) ((Map<String, Object>) assets).get("depth")).get("href"));
            if (!href.startsWith(DEP_HOST) || !href.endsWith(".tif")) continue;
            for (char c : " \"<>\\?#&".toCharArray()) if (href.indexOf(c) >= 0) href = null;
            if (href == null) continue;
            Map<String, Object> props = f.get("properties") instanceof Map ? (Map<String, Object>) f.get("properties") : Collections.emptyMap();
            String when = String.valueOf(props.get("start_datetime") != null ? props.get("start_datetime") : props.get("datetime"));
            Object pb = props.get("proj:bbox");
            String tile = pb != null ? String.valueOf(pb) : href;
            String[] prev = byTile.get(tile);
            if (prev == null || when.compareTo(prev[1]) > 0) byTile.put(tile, new String[]{href, when, String.valueOf(f.get("id"))});
        }
        if (byTile.isEmpty()) return;
        Use u = new Use(DEP);
        u.resM = u.typicalM = DEP_RES_M;
        for (String[] v : byTile.values()) {
            u.files.add(v[0]);
            u.names.add(v[2]);
        }
        u.place = nonNull(place(PACIFIC_PLACES, a.bbox, 1.5), "Pacific islands");
        a.looked.remove(a.looked.size() - 1);
        a.uses.add(u);
    }

    private static String nonNull(String s, String or) {
        return s != null ? s : or;
    }

    // ------------------------------------------------------------------ getting the data

    /** A plan ready for the Builder, with the files it reads and what each layer is. */
    public static final class Prepared {
        public final Planner.Plan plan;
        public final Assessment assessment;
        public final List<Use> uses = new ArrayList<>();
        public String gmrtUrl;
        public double gmrtResM;
        final List<File> temp = new ArrayList<>();

        Prepared(Planner.Plan plan, Assessment a) {
            this.plan = plan;
            this.assessment = a;
        }

        /** Deletes the servers' files once the build is done. */
        public void cleanup() {
            for (File f : temp) f.delete();
        }
    }

    /** The output grid the Builder will make for a plan at resM: west, south, east, north, width, height. */
    static double[] grid(double[] b, double resM) {
        double res = resM / Catalog.M_PER_DEG;
        int w = Math.max(1, (int) ((b[2] - b[0] + res / 2) / res)), h = Math.max(1, (int) ((b[3] - b[1] + res / 2) / res));
        return new double[]{b[0], b[3] - h * res, b[0] + w * res, b[3], w, h};
    }

    /** One request to a server: the box (degrees) and its size in pixels. */
    static final class Piece {
        final double[] box;
        final int cols, rows;

        Piece(double[] box, int cols, int rows) {
            this.box = box;
            this.cols = cols;
            this.rows = rows;
        }
    }

    /**
     * The output grid in pieces of at most CHUNK x CHUNK request pixels, each a request pixel
     * being step output pixels, with MARGIN request pixels round each piece.
     */
    static List<Piece> pieces(double[] g, int step) {
        int width = (int) g[4], height = (int) g[5];
        double res = (g[2] - g[0]) / width;
        int span = CHUNK * step;
        List<Piece> out = new ArrayList<>();
        for (int y0 = 0; y0 < height; y0 += span) {
            for (int x0 = 0; x0 < width; x0 += span) {
                int x1 = Math.min(width, x0 + span), y1 = Math.min(height, y0 + span);
                int m = MARGIN * step;
                double w = g[0] + (x0 - m) * res, e = g[0] + (x1 + m) * res;
                double n = g[3] - (y0 - m) * res, s = g[3] - (y1 + m) * res;
                int cols = (int) Math.ceil((x1 - x0 + 2.0 * m) / step), rows = (int) Math.ceil((y1 - y0 + 2.0 * m) / step);
                out.add(new Piece(new double[]{w, s, e, n}, cols, rows));
            }
        }
        return out;
    }

    /**
     * Downloads what the servers make for the box (GMRT, and NOAA, CHS or EMODnet when used),
     * reads the headers of the LINZ and Digital Earth Pacific files, and makes the Builder's plan.
     */
    public static Prepared prepare(Assessment a, File workDir, Net net, ExecutorService pool, Job job, AtomicLong downloaded)
            throws IOException {
        if (!a.ok) throw new IOException(a.line);
        double[] g = grid(a.bbox, a.resM);
        double res = (g[2] - g[0]) / g[4];
        String tag = Long.toHexString(System.nanoTime());
        Planner.Params p = Planner.parse(a.bbox, null, false, "wgs84", "auto", "auto", "default", null, null, true);
        Planner.Plan plan = new Planner.Plan();
        plan.p = p;
        plan.crs = "wgs84";
        plan.resM = a.resM;
        plan.finestM = Math.round(a.finestM * 1000) / 1000.0;
        plan.baseLabel = "GMRT";
        plan.layerWord = "layer";
        plan.countLayers = true;
        plan.blend = true;
        Prepared out = new Prepared(plan, a);
        boolean ok = false;
        try {
            // What to download: name -> url, in the order they're laid.
            Map<String, File> files = new LinkedHashMap<>();
            Map<String, String> urls = new LinkedHashMap<>();
            Map<String, String> owner = new LinkedHashMap<>();
            double margin = Math.max(0.01, 4 * res);
            double[] gm = {g[0] - margin, g[1] - margin, g[2] + margin, g[3] + margin};
            out.gmrtUrl = gmrtUrl(gm);
            add(files, urls, owner, workDir, tag, "gmrt", out.gmrtUrl, GMRT);
            for (Use u : a.uses) {
                if (u.source.equals(NOAA)) {
                    int i = 0;
                    for (Piece pc : pieces(g, 1)) add(files, urls, owner, workDir, tag, "noaa" + i++, noaaExportUrl(pc.box, pc.cols, pc.rows), NOAA);
                } else if (u.source.equals(NONNA)) {
                    int step = (int) Math.max(1, Math.floor(NONNA_RES_M / a.resM));
                    int i = 0;
                    for (Piece pc : pieces(g, step)) add(files, urls, owner, workDir, tag, "nonna" + i++, nonnaUrl(pc.box, pc.cols, pc.rows), NONNA);
                } else if (u.source.equals(EMODNET)) {
                    add(files, urls, owner, workDir, tag, "emodnet", emodnetUrl(gm, 0, 0), EMODNET);
                }
            }
            out.temp.addAll(files.values());
            Map<String, RemoteTiff> got = download(files, urls, owner, net, pool, job, downloaded);

            RemoteTiff gmrt = got.get("gmrt");
            Planner.Base base = new Planner.Base();
            base.tiff = gmrt;
            base.proj = Proj.parse("EPSG:4326");
            base.label = "GMRT";
            plan.bases.add(base);
            out.gmrtResM = Math.min(Math.abs(gmrt.gt[1]), Math.abs(gmrt.gt[5])) * Catalog.M_PER_DEG;
            plan.imasResM = out.gmrtResM;

            // The cloud-optimised files: their headers, read in parallel.
            Map<String, Future<RemoteTiff>> heads = new LinkedHashMap<>();
            for (Use u : a.uses) for (String url : u.files) heads.put(url, pool.submit(() -> RemoteTiff.open(url, job)));
            job.progress(0, "Reading the file headers");
            Map<String, RemoteTiff> cogs = new LinkedHashMap<>();
            for (Map.Entry<String, Future<RemoteTiff>> e : heads.entrySet()) cogs.put(e.getKey(), waitFor(e.getValue(), heads.values()));

            for (Use u : a.uses) {
                Surveys.Survey s = new Surveys.Survey();
                s.id = u.source;
                s.name = name(u.source);
                s.resM = u.resM;
                s.sign = 1;
                s.shiftM = 0;
                s.files = new ArrayList<>();
                if (u.files.isEmpty()) {
                    for (Map.Entry<String, String> e : owner.entrySet()) {
                        if (!e.getValue().equals(u.source)) continue;
                        Surveys.SFile f = new Surveys.SFile();
                        f.url = files.get(e.getKey()).toURI().toString();
                        f.proj = Proj.parse("EPSG:4326");
                        f.crs = "EPSG:4326";
                        s.files.add(f);
                        plan.surveyTiffs.put(f.url, got.get(e.getKey()));
                    }
                } else {
                    String crs = u.source.equals(LINZ) ? "EPSG:2193" : "EPSG:3832";
                    int want = u.source.equals(LINZ) ? 2193 : 3832;
                    for (String url : u.files) {
                        RemoteTiff t = cogs.get(url);
                        if (t.epsg != want) continue;  // not what the catalogue says: leave it out rather than misplace it
                        Surveys.SFile f = new Surveys.SFile();
                        f.url = url;
                        f.crs = crs;
                        f.proj = Proj.parse(crs);
                        s.files.add(f);
                        plan.surveyTiffs.put(url, t);
                    }
                }
                if (s.files.isEmpty()) continue;
                plan.chosen.add(s);
                plan.bandM.put(s.id, blendM(u.source, a.resM));
                out.uses.add(u);
            }
            if (plan.chosen.isEmpty()) throw new IOException(NONE);
            plan.estBytes = a.estBytes;
            plan.filename = "depths.tif";
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("ok", true);
            json.put("res_m", a.resM);
            json.put("est_bytes", a.estBytes);
            plan.json = json;
            ok = true;
            return out;
        } finally {
            if (!ok) out.cleanup();
        }
    }

    private static void add(Map<String, File> files, Map<String, String> urls, Map<String, String> owner, File dir, String tag,
                            String key, String url, String source) {
        files.put(key, new File(dir, "world-" + tag + "-" + key + ".tif"));
        urls.put(key, url);
        owner.put(key, source);
    }

    /** All the downloads at once (three at a time per server), with the progress line counting megabytes. */
    private static Map<String, RemoteTiff> download(Map<String, File> files, Map<String, String> urls, Map<String, String> owner,
                                                    Net net, ExecutorService pool, Job job, AtomicLong downloaded) throws IOException {
        Map<String, Semaphore> perSource = new LinkedHashMap<>();
        for (String s : owner.values()) perSource.putIfAbsent(s, new Semaphore(3));
        AtomicLong count = downloaded != null ? downloaded : new AtomicLong();
        long start = count.get();
        Map<String, Future<RemoteTiff>> fs = new LinkedHashMap<>();
        for (Map.Entry<String, File> e : files.entrySet()) {
            String key = e.getKey();
            Semaphore sem = perSource.get(owner.get(key));
            fs.put(key, pool.submit(() -> {
                sem.acquire();
                try {
                    try {
                        net.save(urls.get(key), e.getValue(), job, count);
                    } catch (InterruptedIOException ex) {
                        throw ex;
                    } catch (IOException ex) {
                        throw new IOException(serverName(owner.get(key)) + ": " + ex.getMessage(), ex);
                    }
                    return RemoteTiff.open(e.getValue().toURI().toString(), job);
                } finally {
                    sem.release();
                }
            }));
        }
        Set<String> names = new LinkedHashSet<>();
        for (String s : owner.values()) names.add(serverName(s));
        String stage = "Downloading the depths from " + String.join(", ", names);
        Map<String, RemoteTiff> out = new LinkedHashMap<>();
        for (Map.Entry<String, Future<RemoteTiff>> e : fs.entrySet()) {
            while (true) {
                job.progress(0, stage + " (" + Planner.fmtBytes(count.get() - start) + ")");
                try {
                    out.put(e.getKey(), e.getValue().get(500, TimeUnit.MILLISECONDS));
                    break;
                } catch (TimeoutException te) {
                    if (job.cancelled()) {
                        for (Future<RemoteTiff> f : fs.values()) f.cancel(true);
                        throw new InterruptedIOException("Cancelled");
                    }
                } catch (InterruptedException ie) {
                    for (Future<RemoteTiff> f : fs.values()) f.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Cancelled");
                } catch (ExecutionException ee) {
                    for (Future<RemoteTiff> f : fs.values()) f.cancel(true);
                    Throwable c = ee.getCause();
                    if (c instanceof IOException) throw (IOException) c;
                    throw new IOException(String.valueOf(c), c);
                }
            }
        }
        return out;
    }

    private static <T> T waitFor(Future<T> f, java.util.Collection<? extends Future<?>> all) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            for (Future<?> g : all) g.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelled");
        } catch (ExecutionException e) {
            for (Future<?> g : all) g.cancel(true);
            Throwable c = e.getCause();
            if (c instanceof IOException) throw new IOException("Couldn't read a depth file's header (" + c.getMessage() + ").", c);
            throw new IOException(String.valueOf(c), c);
        }
    }

    static String serverName(String source) {
        switch (source) {
            case GMRT: return "GMRT";
            case NOAA: return "NOAA";
            case NONNA: return "CHS";
            case EMODNET: return "EMODnet";
            case LINZ: return "LINZ";
            case DEP: return "Digital Earth Pacific";
            default: return source;
        }
    }

    /** What the progress line and the credits call each source. */
    static String name(String source) {
        switch (source) {
            case NOAA: return "NOAA NCEI coastal DEMs";
            case NONNA: return "CHS NONNA-10 surveys";
            case EMODNET: return "EMODnet Digital Bathymetry (DTM 2024)";
            case LINZ: return "LINZ coastal lidar";
            case DEP: return "Digital Earth Pacific satellite depths";
            case GMRT: return "GMRT";
            default: return source;
        }
    }

    public static String credit(String source) {
        switch (source) {
            case NOAA: return CREDIT_NOAA;
            case NONNA: return CREDIT_NONNA;
            case EMODNET: return CREDIT_EMODNET;
            case LINZ: return CREDIT_LINZ;
            case DEP: return CREDIT_DEP;
            default: return CREDIT_GMRT;
        }
    }

    // ------------------------------------------------------------------ credits

    /**
     * The sources a finished build used: each layer that had depths in it (layerPixels, from the
     * Builder), and GMRT when it filled any of the file (basePixels; -1 counts as yes).
     */
    public static List<String> used(Prepared prep, Map<String, Long> layerPixels, long basePixels) {
        List<String> out = new ArrayList<>();
        for (Use u : prep.uses) {
            Long n = layerPixels.get(u.source);
            if (n == null || n > 0) out.add(u.source);
        }
        if (basePixels != 0) out.add(GMRT);
        return out;
    }

    /** The one line shown under the chart while the layer is on: each used source's credit. */
    public static String creditLine(List<String> used) {
        List<String> parts = new ArrayList<>();
        for (String s : used) parts.add(credit(s));
        return String.join("; ", parts);
    }

    /** The sources file saved beside the GeoTIFF. */
    public static String creditsText(Prepared prep, List<String> used, String finalName, String madeWith) {
        List<String> lines = new ArrayList<>();
        lines.add("Sources for " + finalName);
        lines.add("Made with " + madeWith + " on " + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ". Not for navigation.");
        lines.add("On the chart: " + creditLine(used));
        lines.add("");
        List<Use> layers = new ArrayList<>();
        for (Use u : prep.uses) if (used.contains(u.source)) layers.add(u);
        if (!layers.isEmpty()) {
            double band = 0;
            for (Use u : layers) {
                Double b = prep.plan.bandUsedM.get(u.source);
                band = Math.max(band, b != null ? b : prep.plan.bandM.getOrDefault(u.source, blendM(u.source, prep.plan.resM)));
            }
            lines.add(String.format(Locale.ROOT, "Layers, finest first (each one's edges and gaps fade into the layer under it over up to %.0f m):", band));
            for (Use u : layers) {
                lines.add("  " + name(u.source) + ", " + detailText(u) + ".");
                lines.add("    " + credit(u.source));
                if (u.source.equals(NOAA)) {
                    if (!u.names.isEmpty()) lines.add("    DEMs in the box: " + String.join(", ", u.names));
                    if (!u.datums.isEmpty()) lines.add("    Heights as each DEM gives them (vertical datum " + String.join(", ", u.datums) + ").");
                    lines.add("    https://www.ncei.noaa.gov/products/coastal-elevation-models");
                } else if (u.source.equals(NONNA)) {
                    lines.add("    https://data.chs-shc.ca");
                } else if (u.source.equals(EMODNET)) {
                    lines.add("    https://emodnet.ec.europa.eu/en/bathymetry");
                } else if (u.source.equals(DEP)) {
                    lines.add("    A satellite estimate (Sentinel-2), product version 0.0.1, for shallow water only.");
                    for (String f : u.files) lines.add("    " + f);
                } else {
                    for (String f : u.files) lines.add("    " + f);
                }
            }
            lines.add("");
        }
        if (used.contains(GMRT)) {
            lines.add(layers.isEmpty() ? "Depths:" : "Under them, where they have no depths:");
            lines.add(String.format(Locale.ROOT, "  GMRT, Global Multi-Resolution Topography (Ryan et al. 2009, doi:10.1029/2008GC002332), about %s m here.",
                    metres(prep.gmrtResM)));
            lines.add("    " + CREDIT_GMRT);
            lines.add("    https://www.gmrt.org");
            lines.add("");
        }
        lines.add("Changes: the data above was resampled onto this file's grid and merged into one layer, with the edges "
                + "blended as noted. Each source keeps its own vertical datum; they are not corrected to one level.");
        List<String> lic = new ArrayList<>();
        boolean ccby = false;
        for (String s : used) if (s.equals(GMRT) || s.equals(EMODNET) || s.equals(LINZ) || s.equals(DEP)) ccby = true;
        if (ccby) lic.add("CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/) for GMRT, EMODnet, LINZ and Digital Earth Pacific data");
        if (used.contains(NOAA)) lic.add("NOAA NCEI DEMs are US public domain");
        if (used.contains(NONNA))
            lic.add("CHS NONNA under the Open Government Licence - Canada (https://open.canada.ca/en/open-government-licence-canada), not for navigation");
        lines.add("Licences: " + String.join("; ", lic) + ".");
        lines.add("");
        return String.join("\n", lines);
    }

    private static String detailText(Use u) {
        switch (u.source) {
            case NOAA: return metres(u.resM) + " m" + (u.typicalM > u.resM * 1.3 ? " in parts, mostly " + metres(u.typicalM) + " m" : "");
            case NONNA: return "10 m where surveyed";
            case EMODNET: return "about 115 m";
            case LINZ: return "1 m, near shore, to about 25 m deep (tiles " + String.join(", ", u.names) + ")";
            case DEP: return "10 m, reef shallows to about 10 m deep";
            default: return metres(u.resM) + " m";
        }
    }
}
