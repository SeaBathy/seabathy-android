package au.opendepth.app.storage

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Free depth data for anywhere in the world, as a GeoTIFF the app opens like
 * any survey: GMRT (Global Multi-Resolution Topography, CC BY 4.0), which
 * blends ship surveys with the GEBCO grid. Its server picks the finest detail
 * that fits about 55 MB, so a small area comes sharper than a big one.
 */
object SeabedDownload {

    const val CREDIT = "Depths: GMRT, Ryan et al. 2009 (CC BY 4.0)"
    private const val MAX_SPAN_DEG = 4.0
    const val MAX_SPAN_KM = 440
    /** Zoomed right in, still enough around the crosshair to be useful. */
    private const val MIN_SPAN_DEG = 0.25
    private const val MAX_LAT = 85.0
    private const val MAX_LNG = 180.0

    data class Area(val south: Double, val west: Double, val north: Double, val east: Double, val trimmed: Boolean) {
        /** "Seabed 18.05S 146.55E": the same area gets the same file. */
        val name: String
            get() {
                val lat = (south + north) / 2
                val lng = (west + east) / 2
                return String.format(
                    Locale.US, "Seabed %.2f%s %.2f%s (%.2f°)",
                    abs(lat), if (lat < 0) "S" else "N", abs(lng), if (lng < 0) "W" else "E", maxOf(north - south, east - west)
                )
            }
    }

    /**
     * The area to ask for, from what's on screen: within the size one download
     * holds, and on the crosshair's side of the date line when that's on screen.
     */
    fun areaFor(south: Double, west: Double, north: Double, east: Double, centreLat: Double, centreLng: Double): Area {
        var w = west
        var e = east
        if (e < w || e - w > 180) {
            if (centreLng >= 0) e = MAX_LNG else w = -MAX_LNG
        }
        val trimmed = north - south > MAX_SPAN_DEG || e - w > MAX_SPAN_DEG
        fun fit(lo: Double, hi: Double, centre: Double): Pair<Double, Double> {
            val span = (hi - lo).coerceIn(MIN_SPAN_DEG, MAX_SPAN_DEG)
            val middle = if (hi - lo > MAX_SPAN_DEG) centre else (lo + hi) / 2
            return middle - span / 2 to middle + span / 2
        }
        val (s, n) = fit(south, north, centreLat)
        val (a, b) = fit(w, e, centreLng)
        return Area(s.coerceAtLeast(-MAX_LAT), a.coerceAtLeast(-MAX_LNG), n.coerceAtMost(MAX_LAT), b.coerceAtMost(MAX_LNG), trimmed)
    }

    fun url(area: Area): String = String.format(
        Locale.US,
        "https://www.gmrt.org/services/GridServer?north=%.5f&south=%.5f&east=%.5f&west=%.5f&layer=topo&format=geotiff&resolution=high",
        area.north, area.south, area.east, area.west
    )

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(3, TimeUnit.MINUTES).build()
    }

    /**
     * Saves [area] to [dir], or returns the copy already there. Written beside
     * the final name first, so a dropped connection never leaves half a file.
     */
    fun download(area: Area, dir: File, onProgress: (Long) -> Unit = {}): File {
        dir.mkdirs()
        val target = File(dir, "${area.name}.tif")
        if (target.length() > 0) return target
        val partial = File(dir, "${target.name}.part")
        try {
            client.newCall(Request.Builder().url(url(area)).header("User-Agent", "SeaBathy (Android seabed depths)").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The depth service answered ${response.code}. Try again later.")
                val body = response.body ?: throw IOException("The depth service sent nothing")
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            total += read
                            onProgress(total)
                        }
                    }
                }
            }
            if (!looksLikeTiff(partial)) throw IOException("The depth service has no data for this area")
            if (!partial.renameTo(target)) throw IOException("Couldn't save the depths")
            return target
        } finally {
            partial.delete()
        }
    }

    private fun looksLikeTiff(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(4)
        input.read(head) == 4 && (
            (head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2].toInt() == 42) ||
                (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[3].toInt() == 42)
            )
    }
}
