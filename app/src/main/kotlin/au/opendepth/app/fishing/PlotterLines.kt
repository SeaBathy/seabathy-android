package au.opendepth.app.fishing

import au.opendepth.app.map.DepthContours
import au.opendepth.app.nav.DepthUnit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * Gathers the lines and points the plotter export writes ([PlotterGpx]):
 * depth contours at chosen depths, and the sea conditions' lines as the map
 * draws them. Pure Kotlin, unit-tested on the JVM.
 */
object PlotterLines {

    /** The most contour depths one export takes. */
    const val MAX_DEPTHS = 20

    /**
     * The lines where [values] ([cols] to a row, metres negative down, NaN
     * for none) cross [depthM] metres below the surface, as lng, lat pairs
     * through [placement]. Specks a cell or so across are left out.
     */
    fun contourAt(values: FloatArray, cols: Int, rows: Int, depthM: Double, placement: DepthContours.Placement): List<DoubleArray> {
        if (!(depthM > 0.0)) return emptyList()
        // Shifted so the depth wanted is 0, and traced with a step so wide that 0 is the only level crossed.
        val shift = depthM.toFloat()
        val shifted = FloatArray(values.size) { values[it] + shift }
        val levels = DepthContours.trace(shifted, cols, rows, step = ONE_LEVEL_STEP, top = 0.0)
        return levels.filter { it.level == 0.0 }.flatMap { it.lines }.filter { it.size >= 4 && !DepthContours.isSpeck(it) }.map { line ->
            DoubleArray(line.size) { i -> if (i % 2 == 0) placement.lng(line[i]) else placement.lat(line[i]) }
        }
    }

    /**
     * Depths typed in [unit] ("10, 20, 30" or "10 20 30"), as metres: positive,
     * each once, shallowest first, at most [MAX_DEPTHS].
     */
    fun parseDepths(text: String, unit: DepthUnit): List<Double> =
        // "20m" and "60 ft" read as 20 and 60: units are dropped, the unit is [unit].
        text.split(',', ';', ' ', '\n', '\t').mapNotNull { part -> part.filter { it.isDigit() || it == '.' }.toDoubleOrNull() }
            .filter { it > 0 && it.isFinite() }
            .map { it / unit.perMetre }
            .distinctBy { Math.round(it * 1000) }
            .sorted()
            .take(MAX_DEPTHS)

    /** Depths as they are typed back: "10, 20, 30", in [unit]. */
    fun depthsText(depthsM: List<Double>, unit: DepthUnit): String = depthsM.joinToString(", ") { number(it * unit.perMetre) }

    /**
     * Depths to suggest over water from [shallowM] to [deepM] (positive):
     * round numbers of [unit], about six of them.
     */
    fun suggestDepths(shallowM: Double, deepM: Double, unit: DepthUnit): List<Double> {
        if (!(deepM > shallowM) || !(deepM > 0)) return emptyList()
        val step = DepthContours.stepFor(-deepM, -shallowM.coerceAtLeast(0.0), DepthContours.AUTO, unit).let { auto ->
            // Auto aims at about 16 lines; a plotter wants fewer.
            listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000).firstOrNull { it >= auto && (deepM - shallowM) * unit.perMetre / it <= 6 } ?: auto
        }
        val first = Math.floor(shallowM * unit.perMetre / step).toLong() + 1
        val last = Math.floor(deepM * unit.perMetre / step).toLong()
        return (first..last).map { it * step / unit.perMetre }.take(MAX_DEPTHS)
    }

    /** A contour's track name: "20 m contour". */
    fun contourName(depthM: Double, unit: DepthUnit): String = "${number(depthM * unit.perMetre)} ${unit.label} contour"

    /** A line from the sea conditions' map lines: a track name and its points (lng, lat pairs). */
    class SeaLine(val name: String, val points: DoubleArray)

    /** A point from them: an eddy's middle. */
    class SeaPoint(val name: String, val lat: Double, val lng: Double)

    /**
     * The sea conditions' map lines ([geoJson], as drawn: features with
     * "kind" and "label") named for a plotter: temperature lines "21.5 °C",
     * the target band's edges "Band edge 21.5 °C", eddy outlines "Warm eddy
     * edge +20 cm"; eddy middles as points. [temperatureLabel] is "°C" or "°F".
     */
    fun seaLines(geoJson: String, temperatureLabel: String): Pair<List<SeaLine>, List<SeaPoint>> {
        val lines = ArrayList<SeaLine>()
        val points = ArrayList<SeaPoint>()
        val root = runCatching { JsonParser.parseString(geoJson).asJsonObject }.getOrNull() ?: return lines to points
        val features = root.getAsJsonArray("features") ?: return lines to points
        for (element in features) {
            val feature = element as? JsonObject ?: continue
            val properties = feature.getAsJsonObject("properties") ?: continue
            val kind = properties.get("kind")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val label = properties.get("label")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
            val geometry = feature.getAsJsonObject("geometry") ?: continue
            val coordinates = geometry.getAsJsonArray("coordinates") ?: continue
            when (geometry.get("type")?.asString) {
                "LineString" -> {
                    val name = when (kind) {
                        "temp", "minor" -> "${label.removeSuffix("°").trim()} $temperatureLabel"
                        "band" -> "Band edge " + label.replace("°", " °")
                        "warm", "warm_light" -> "Warm eddy edge $label"
                        "cold", "cold_light" -> "Cold eddy edge $label"
                        else -> continue
                    }
                    val xy = DoubleArray(coordinates.size() * 2)
                    for (i in 0 until coordinates.size()) {
                        val pair = coordinates[i].asJsonArray
                        xy[2 * i] = pair[0].asDouble
                        xy[2 * i + 1] = pair[1].asDouble
                    }
                    if (xy.size >= 4) lines += SeaLine(name, xy)
                }
                "Point" -> {
                    if (coordinates.size() < 2) continue
                    val name = when (kind) {
                        "warm" -> "Warm eddy " + label.removePrefix("W").trim()
                        "cold" -> "Cold eddy " + label.removePrefix("C").trim()
                        else -> continue
                    }
                    points += SeaPoint(name, coordinates[1].asDouble, coordinates[0].asDouble)
                }
            }
        }
        return lines to points
    }

    private fun number(v: Double): String =
        if (Math.abs(v - Math.round(v)) < 0.05) Math.round(v).toString() else String.format(Locale.US, "%.1f", v)

    /** Wider than any sea is deep: only the level at 0 is traced. */
    private const val ONE_LEVEL_STEP = 1e6
}
