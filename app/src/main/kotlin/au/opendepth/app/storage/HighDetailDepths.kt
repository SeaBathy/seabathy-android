package au.opendepth.app.storage

import android.content.Context
import au.opendepth.app.BuildConfig
import com.seabathy.bathypicker.core.Builder
import com.seabathy.bathypicker.core.Catalog
import com.seabathy.bathypicker.core.Credits
import com.seabathy.bathypicker.core.Http
import com.seabathy.bathypicker.core.Job
import com.seabathy.bathypicker.core.Planner
import com.seabathy.bathypicker.core.Surveys
import com.seabathy.bathypicker.core.TileSource
import com.seabathy.bathypicker.core.World
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-detail depths, built on the phone by the BathyPicker engine (com.seabathy.bathypicker.core).
 * In Australian waters: the IMAS multi-resolution composite for the whole EEZ, with AusSeabed and
 * state multibeam surveys laid on top where they exist, down to a metre or two. Overseas
 * ([World]): NOAA's coastal DEMs, CHS NONNA, EMODnet, LINZ coastal lidar or Digital Earth
 * Pacific's satellite depths, with GMRT under them. The engine reads only the parts of the source
 * files inside the area and writes one tiled GeoTIFF, which the app then opens like any survey.
 */
object HighDetailDepths {

    /** The size choices in Settings, in MB; 0 is Auto (from this phone's memory). */
    val LIMIT_CHOICES = listOf(0, 100, 250, 500, 1000)
    private const val KEY_LIMIT = "high_detail_limit_mb"

    fun limitChoice(context: Context): Int =
        context.getSharedPreferences("od", Context.MODE_PRIVATE).getInt(KEY_LIMIT, 0)

    fun setLimitChoice(context: Context, mb: Int) =
        context.getSharedPreferences("od", Context.MODE_PRIVATE).edit().putInt(KEY_LIMIT, mb).apply()

    /**
     * Auto: what this phone can build safely right now. The engine keeps a tile cache and a few
     * rows of the grid in memory, which grows with the file, so the limit follows the memory
     * Android gives the app, less what the app is already using (open layers, the map).
     */
    fun autoLimitMb(): Int {
        val rt = Runtime.getRuntime()
        val freeMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        return ((freeMb - AUTO_RESERVE_MB) * AUTO_MB_PER_FREE_MB).toInt().coerceIn(50, 1000) / 10 * 10
    }

    /** The limit in force: the Settings choice, or Auto. */
    fun limitMb(context: Context): Int = limitChoice(context).takeIf { it > 0 } ?: autoLimitMb()

    private const val AUTO_RESERVE_MB = 80
    // Measured: a build estimated at 360 MB peaked at about 375 MB of heap (emulator, 576 MB limit).
    private const val AUTO_MB_PER_FREE_MB = 0.8

    /** The credit of an Australian build. */
    const val CREDIT = "Depths: IMAS composite (Flukes 2026), AusSeabed and state surveys (CC BY 4.0)"

    /** Where High detail works, for the "Which depths?" card. */
    const val WHERE = "Australia: all waters (IMAS, with multibeam surveys on top), down to a metre or two where surveyed. " +
        "USA: all coasts, Hawaii and the US islands at about 3 m; Alaska in parts; the Great Lakes coarser. " +
        "Canada: surveyed coasts and harbours. " +
        "New Zealand: coasts surveyed by lidar since 2024, to about 25 m deep. " +
        "Pacific islands: reef shallows to about 10 m, a satellite estimate. " +
        "Europe: all seas, about 115 m. Elsewhere use Standard."

    private val net: ExecutorService by lazy { Executors.newFixedThreadPool(8, named("depth-net")) }
    private val workers: ExecutorService by lazy {
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4), named("depth-work"))
    }
    private val catalog by lazy { Catalog(net) }
    @Volatile private var surveys: Surveys? = null

    /** The most one high-detail build covers, in degrees (about 110 km): more runs a phone out of memory. */
    const val MAX_SPAN_KM = 110

    private fun bbox(area: SeabedDownload.Area) = doubleArrayOf(area.west, area.south, area.east, area.north)

    /** Some of [area] is where High detail works: Australian waters or an overseas source's. */
    fun covers(area: SeabedDownload.Area): Boolean = World.touches(bbox(area))

    /** [area]'s middle is in Australian waters: the IMAS composite with surveys on top. */
    fun australian(area: SeabedDownload.Area): Boolean = World.inAustralia(bbox(area))

    /** [area], or its middle [MAX_SPAN_KM] each way when it's bigger. */
    fun fit(area: SeabedDownload.Area): SeabedDownload.Area {
        val kmPerDegLng = 111.32 * kotlin.math.cos(Math.toRadians((area.south + area.north) / 2)).coerceAtLeast(0.05)
        val maxLat = MAX_SPAN_KM / 110.57 * 0.98
        val maxLng = MAX_SPAN_KM / kmPerDegLng * 0.98
        if (area.north - area.south <= maxLat && area.east - area.west <= maxLng) return area
        fun mid(lo: Double, hi: Double, cap: Double): Pair<Double, Double> {
            val h = minOf(hi - lo, cap) / 2
            val c = (lo + hi) / 2
            return c - h to c + h
        }
        val (s, n) = mid(area.south, area.north, maxLat)
        val (w, e) = mid(area.west, area.east, maxLng)
        return SeabedDownload.Area(s, w, n, e, trimmed = true)
    }

    /** The line under the box, and whether the box can be built. */
    data class Estimate(val text: String, val ok: Boolean)

    /**
     * A line for the box picker: about how big the file will be and how fine, or why it can't be
     * built. Reads only the source headers and lists (a second or two online), never the depths.
     */
    fun estimate(context: Context, area: SeabedDownload.Area): Estimate {
        if (!australian(area)) {
            val a = World.assess(bbox(area), limitMb(context) * 1048576.0, context.filesDir.usableSpace, context.cacheDir, World.HTTP, null)
            return Estimate(a.line, a.ok)
        }
        val params = Planner.parse(doubleArrayOf(area.west, area.south, area.east, area.north), null, "wgs84", "auto", "auto")
        val plan = Planner.assess(params, catalog, surveys(context), limitMb(context) / 1024.0, context.filesDir.usableSpace, "SeaBathy", null)
        if (plan.json["ok"] != true) {
            return Estimate((plan.json["errors"] as? List<*>).orEmpty().joinToString(" ").ifBlank { "No high-detail depths here." }, true)
        }
        val mb = ((plan.json["est_bytes"] as? Number)?.toDouble() ?: 0.0) / 1e6
        val res = (plan.json["res_m"] as? Number)?.toDouble() ?: plan.resM
        val surveysIn = plan.chosen.size
        return Estimate(String.format(java.util.Locale.US, "Up to about %.0f MB at %s m detail · %s", mb.coerceAtLeast(1.0),
            if (res < 10) String.format(java.util.Locale.US, "%.1f", res).removeSuffix(".0") else String.format(java.util.Locale.US, "%.0f", res),
            if (surveysIn == 0) "the IMAS composite (no multibeam surveys in this box)" else "$surveysIn multibeam survey${if (surveysIn == 1) "" else "s"} on top"), true)
    }

    /** A finished build: the GeoTIFF, its sources file's text, and the credit shown under the chart. */
    data class Built(val file: File, val sources: String, val credit: String)

    /**
     * Builds the depths for [area] into [dir] and returns the file and its credits. Reuses a
     * file already built for the same area. [onProgress] gets a percentage and a stage line.
     * Cancelling the coroutine stops the build and deletes the part-written file.
     */
    suspend fun build(context: Context, wanted: SeabedDownload.Area, dir: File, onProgress: (Int, String) -> Unit): Built {
        val area = fit(wanted)
        dir.mkdirs()
        val target = File(dir, "${area.name.replace("Seabed", "High detail")}.tif")
        // Named as the depth file's sidecar, so it stays and goes with that layer.
        val credits = File(dir, target.name + AppOwnedArtifacts.SOURCES_SUFFIX)
        if (target.length() > 0) {
            val text = credits.takeIf { it.exists() }?.readText()
            val shown = text?.lineSequence()?.firstOrNull { it.startsWith(ON_THE_CHART) }?.removePrefix(ON_THE_CHART)?.trim()
            return Built(target, text ?: CREDIT, shown ?: CREDIT)
        }

        Http.userAgent = "SeaBathy-Android/${BuildConfig.VERSION_NAME} (+https://github.com/SeaBathy/seabathy-android)"
        onProgress(0, "Finding the depth data for this area…")
        return if (australian(area)) buildAustralia(context, area, dir, target, credits, onProgress)
        else buildOverseas(context, area, dir, target, credits, onProgress)
    }

    private const val ON_THE_CHART = "On the chart: "

    private suspend fun buildAustralia(
        context: Context, area: SeabedDownload.Area, dir: File, target: File, credits: File, onProgress: (Int, String) -> Unit
    ): Built {
        val params = Planner.parse(doubleArrayOf(area.west, area.south, area.east, area.north), null, "wgs84", "auto", "auto")
        val job = Job()
        val plan = Planner.assess(params, catalog, surveys(context), limitMb(context) / 1024.0, dir.usableSpace, "SeaBathy", job)
        if (plan.json["ok"] != true) {
            val errors = (plan.json["errors"] as? List<*>).orEmpty().joinToString(" ")
            throw IOException(errors.ifBlank { "No high-detail depths for this area." })
        }
        currentCoroutineContext().ensureActive()
        return buildInto(job, target, onProgress) { raf, src ->
            val builder = Builder(plan, job, src, workers)
            builder.workDir = context.cacheDir
            builder.run(raf.channel, raf.channel)
            val text = Credits.text(plan, target.name, "SeaBathy ${BuildConfig.VERSION_NAME}")
                .replace("Made with BathyPicker for Android SeaBathy", "Made with SeaBathy")
            runCatching { credits.writeText(text) }
            Built(target, text, CREDIT)
        }
    }

    /** Outside Australia: the sources [World] finds for the box, with GMRT under them. */
    private suspend fun buildOverseas(
        context: Context, area: SeabedDownload.Area, dir: File, target: File, credits: File, onProgress: (Int, String) -> Unit
    ): Built {
        val job = Job()
        val work = File(context.cacheDir, "world-depths").apply { mkdirs() }
        val a = World.assess(bbox(area), limitMb(context) * 1048576.0, dir.usableSpace, work, World.HTTP, job)
        if (!a.ok) throw IOException(a.line)
        currentCoroutineContext().ensureActive()
        var prepared: World.Prepared? = null
        try {
            return buildInto(job, target, onProgress) { raf, src ->
                val prep = World.prepare(a, work, World.HTTP, net, job, src.bytesDownloaded)
                prepared = prep
                val builder = Builder(prep.plan, job, src, workers)
                builder.workDir = context.cacheDir
                builder.run(raf.channel, raf.channel)
                val used = World.used(prep, builder.layerPixels, builder.basePixels)
                val text = World.creditsText(prep, used, target.name, "SeaBathy ${BuildConfig.VERSION_NAME}")
                runCatching { credits.writeText(text) }
                Built(target, text, World.creditLine(used))
            }
        } finally {
            prepared?.cleanup()
        }
    }

    /**
     * Runs [body] with progress shown and the coroutine's cancel passed to the [job], writing to
     * a part file that becomes [target] when it's done (and is deleted if it isn't).
     */
    private suspend fun buildInto(job: Job, target: File, onProgress: (Int, String) -> Unit, body: (RandomAccessFile, TileSource) -> Built): Built {
        val partial = File(target.parentFile, "${target.name}.part")
        if (!job.start()) throw IOException("A depth build is already running.")
        val ctx = currentCoroutineContext()
        val watcher = Thread({
            while (job.running()) {
                if (!ctx.isActive) job.requestCancel()
                onProgress(job.percent().toInt().coerceIn(0, 99), job.stage())
                try { Thread.sleep(700) } catch (_: InterruptedException) { return@Thread }
            }
        }, "depth-progress").apply { isDaemon = true; start() }
        try {
            val built = RandomAccessFile(partial, "rw").use { raf ->
                raf.setLength(0)
                val rt = Runtime.getRuntime()
                val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
                val budget = maxOf(32L shl 20, minOf(rt.maxMemory() / 3, free / 3))
                body(raf, TileSource(net, budget))
            }
            if (job.cancelled() || !ctx.isActive) throw InterruptedIOException("Cancelled")
            job.done(HashMap())
            if (!partial.renameTo(target)) throw IOException("Couldn't save the depths file.")
            return built
        } catch (e: Throwable) {
            if (job.running()) job.fail(e.message ?: e.toString())
            partial.delete()
            val oom = e is OutOfMemoryError || (e.message ?: "").contains("out of memory", ignoreCase = true)
            throw if (oom) IOException("The phone ran out of memory. Zoom in to a smaller area and try again.") else e
        } finally {
            watcher.interrupt()
        }
    }

    /** The survey list: the copy fetched last time, else the one in the app; refreshed online once per run. */
    private fun surveys(context: Context): Surveys? {
        surveys?.let { return it }
        val saved = File(context.filesDir, "bathypicker_surveys.json")
        val cached = runCatching { Surveys.parse(saved.readText(), "cached") }.getOrNull()
        val bundled = runCatching {
            context.assets.open("bathypicker/surveys.json").use { Surveys.parse(it.readBytes().decodeToString(), "bundled") }
        }.getOrNull()
        var best = listOfNotNull(cached, bundled).maxByOrNull { it.built }
        runCatching {
            val con = URL(Surveys.URL).openConnection() as HttpURLConnection
            con.connectTimeout = 15_000
            con.readTimeout = 30_000
            con.setRequestProperty("User-Agent", Http.userAgent)
            try {
                if (con.responseCode == 200) {
                    val bytes = con.inputStream.use { it.readBytes() }
                    if (bytes.size < 8_000_000) {
                        val text = bytes.decodeToString()
                        val fresh = Surveys.parse(text, "online")
                        if (fresh != null && (best == null || fresh.built >= best!!.built)) {
                            best = fresh
                            saved.writeText(text)
                        }
                    }
                }
            } finally {
                con.disconnect()
            }
        }
        surveys = best
        return best
    }

    private fun named(prefix: String) = object : java.util.concurrent.ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true }
    }
}
