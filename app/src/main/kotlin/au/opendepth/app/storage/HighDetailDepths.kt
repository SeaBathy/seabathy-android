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
 * High-detail depths for Australian waters, built on the phone by the BathyPicker engine
 * (com.seabathy.bathypicker.core): the IMAS multi-resolution composite for the whole EEZ, with
 * AusSeabed and state multibeam surveys laid on top where they exist, down to a metre or two.
 * The engine reads only the parts of the source files inside the area and writes one tiled
 * GeoTIFF, which the app then opens like any survey.
 */
object HighDetailDepths {

    /** Australian waters, roughly: the EEZ and the external territories nearest the mainland. */
    private const val W = 92.0
    private const val E = 172.0
    private const val S = -60.0
    private const val N = -8.0

    /** The most one build may write. Auto resolution picks the finest detail that fits. */
    private const val MAX_GB = 0.3

    const val CREDIT = "Depths: IMAS composite (Flukes 2026), AusSeabed and state surveys (CC BY 4.0)"

    private val net: ExecutorService by lazy { Executors.newFixedThreadPool(8, named("depth-net")) }
    private val workers: ExecutorService by lazy {
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4), named("depth-work"))
    }
    private val catalog by lazy { Catalog(net) }
    @Volatile private var surveys: Surveys? = null

    /** The most one high-detail build covers, in degrees (about 110 km): more runs a phone out of memory. */
    const val MAX_SPAN_DEG = 1.0
    const val MAX_SPAN_KM = 110

    fun covers(area: SeabedDownload.Area): Boolean =
        area.east > W && area.west < E && area.north > S && area.south < N

    /** [area], or its middle [MAX_SPAN_DEG] when it's bigger. */
    fun fit(area: SeabedDownload.Area): SeabedDownload.Area {
        if (area.north - area.south <= MAX_SPAN_DEG && area.east - area.west <= MAX_SPAN_DEG) return area
        fun mid(lo: Double, hi: Double): Pair<Double, Double> {
            val h = minOf(hi - lo, MAX_SPAN_DEG) / 2
            val c = (lo + hi) / 2
            return c - h to c + h
        }
        val (s, n) = mid(area.south, area.north)
        val (w, e) = mid(area.west, area.east)
        return SeabedDownload.Area(s, w, n, e, trimmed = true)
    }

    /**
     * Builds the depths for [area] into [dir] and returns the file and its credits. Reuses a
     * file already built for the same area. [onProgress] gets a percentage and a stage line.
     * Cancelling the coroutine stops the build and deletes the part-written file.
     */
    suspend fun build(context: Context, wanted: SeabedDownload.Area, dir: File, onProgress: (Int, String) -> Unit): Pair<File, String> {
        val area = fit(wanted)
        dir.mkdirs()
        val target = File(dir, "${area.name.replace("Seabed", "High detail")}.tif")
        // Named as the depth file's sidecar, so it stays and goes with that layer.
        val credits = File(dir, target.name + AppOwnedArtifacts.SOURCES_SUFFIX)
        if (target.length() > 0) return target to (credits.takeIf { it.exists() }?.readText() ?: CREDIT)

        Http.userAgent = "SeaBathy-Android/${BuildConfig.VERSION_NAME} (+https://github.com/SeaBathy/seabathy-android)"
        onProgress(0, "Finding the depth data for this area…")
        val params = Planner.parse(doubleArrayOf(area.west, area.south, area.east, area.north), null, "wgs84", "auto", "auto")
        val job = Job()
        val plan = Planner.assess(params, catalog, surveys(context), MAX_GB, dir.usableSpace, "SeaBathy", job)
        if (plan.json["ok"] != true) {
            val errors = (plan.json["errors"] as? List<*>).orEmpty().joinToString(" ")
            throw IOException(errors.ifBlank { "No high-detail depths for this area." })
        }
        currentCoroutineContext().ensureActive()

        val partial = File(dir, "${target.name}.part")
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
            RandomAccessFile(partial, "rw").use { raf ->
                raf.setLength(0)
                val budget = maxOf(48L shl 20, Runtime.getRuntime().maxMemory() / 3)
                val builder = Builder(plan, job, TileSource(net, budget), workers)
                builder.workDir = context.cacheDir
                builder.run(raf.channel, raf.channel)
            }
            if (job.cancelled() || !ctx.isActive) throw InterruptedIOException("Cancelled")
            job.done(HashMap())
            if (!partial.renameTo(target)) throw IOException("Couldn't save the depths file.")
            val text = Credits.text(plan, target.name, "SeaBathy ${BuildConfig.VERSION_NAME}")
                .replace("Made with BathyPicker for Android SeaBathy", "Made with SeaBathy")
            runCatching { credits.writeText(text) }
            return target to text
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
