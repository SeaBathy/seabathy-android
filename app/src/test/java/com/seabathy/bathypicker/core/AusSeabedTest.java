package com.seabathy.bathypicker.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Assume;
import org.junit.Test;

/** Australia from AusSeabed while IMAS's composite is closed: the list, what a box gets, and (live) a real build. */
public class AusSeabedTest {

    private static AusSeabed.Sources sources() throws Exception {
        return AusSeabed.parse(new String(Files.readAllBytes(new File("src/main/assets/bathypicker/ausseabed.json").toPath()), StandardCharsets.UTF_8));
    }

    private static final double[] MONKEY_MIA = {113.70, -25.82, 113.78, -25.74};
    private static final double[] HOBART = {147.30, -43.10, 147.45, -42.95};

    @Test
    public void listHasTheNationalModelRegionalModelsAndSurveys() throws Exception {
        AusSeabed.Sources s = sources();
        assertTrue(s.wcs.startsWith("https://warehouse.ausseabed.gov.au/"));
        long bases = s.models.stream().filter(m -> "base".equals(m.g)).count();
        assertEquals(1, bases);
        assertTrue(s.models.stream().anyMatch(m -> "regional".equals(m.g) && m.res == 30));
        assertTrue(s.surveys.size() > 300);
        // GA's national 50 m compilation stays out (ship-track streaks and wrong values near the coast).
        assertFalse(s.models.stream().anyMatch(m -> m.name.contains("national multibeam compilation")));
    }

    @Test
    public void monkeyMiaGetsTheNationalModelUnderTheSharkBaySurvey() throws Exception {
        List<AusSeabed.Layer> got = AusSeabed.choose(sources(), MONKEY_MIA);
        assertEquals("base", got.get(0).g);
        assertTrue(got.stream().anyMatch(l -> l.url != null && l.url.contains("20170029S_SharkBay")));
        // Laid bottom first: models, then surveys coarsest first.
        double last = Double.MAX_VALUE;
        for (AusSeabed.Layer l : got) {
            if (!"surveys".equals(l.g)) continue;
            assertTrue(l.res <= last);
            last = l.res;
        }
    }

    @Test
    public void detailIsTenMetresUnlessTheSizeLimitNeedsCoarser() {
        assertEquals(10.0, AusSeabed.resolution(HOBART, 500.0 * 1048576), 0.01);
        assertTrue(AusSeabed.resolution(new double[]{146.0, -44.0, 147.0, -43.0}, 100.0 * 1048576) > 10);
    }

    /** A real build from AusSeabed's servers. Run with -Dausseabed.live=true (it downloads a few megabytes). */
    @Test
    public void liveBuildsMonkeyMiaAndHobart() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("ausseabed.live") || "1".equals(System.getenv("AUSSEABED_LIVE")));
        AusSeabed.Sources src = sources();
        ExecutorService net = Executors.newFixedThreadPool(8);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            for (double[] box : new double[][]{MONKEY_MIA, HOBART}) {
                File work = Files.createTempDirectory("aus").toFile();
                File out = new File(work, "out.tif");
                Job job = new Job();
                assertTrue(job.start());
                double res = AusSeabed.resolution(box, 500.0 * 1048576);
                AusSeabed.Prepared prep = AusSeabed.prepare(src, box, res, work, World.HTTP, net, job, null);
                Builder builder;
                try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                    builder = new Builder(prep.plan, job, new TileSource(net, 256L << 20), workers);
                    builder.workDir = work;
                    builder.run(raf.getChannel(), raf.getChannel());
                } finally {
                    prep.cleanup();
                }
                long surveyPx = 0;
                for (java.util.Map.Entry<String, Long> e : builder.layerPixels.entrySet()) {
                    AusSeabed.Layer l = prep.byId.get(e.getKey());
                    if (l != null && "surveys".equals(l.g)) surveyPx += e.getValue();
                }
                System.out.printf("%s: %d bytes at %s m, base %d px, survey %d px, layers %s, credit %s%n",
                        java.util.Arrays.toString(box), out.length(), res, builder.basePixels, surveyPx, builder.layerPixels,
                        AusSeabed.creditLine(prep, builder.layerPixels));
                assertTrue(out.length() > 100_000);
                assertTrue("the surveys gave nothing", surveyPx > 0);
            }
        } finally {
            net.shutdownNow();
            workers.shutdownNow();
        }
    }
}
