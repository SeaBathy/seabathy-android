package au.opendepth.app.conditions

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** What the colours on the map show. */
enum class ColourChoice(val label: String, val forecast: Boolean, val fixedRange: Boolean = false) {
    NONE("None", false),
    CURRENT("Current speed", true, fixedRange = true),
    WAVES("Wave height", true, fixedRange = true),
    SWELL("Swell height", true, fixedRange = true),
    WIND("Wind speed", true, fixedRange = true),
    SEA_TEMP_SATELLITE("Sea temperature (satellite)", false),
    SEA_TEMP_BREAKS("Temperature breaks (satellite)", false),
    SEA_TEMP_FORECAST("Sea temperature (forecast)", true),
    TEMP_BELOW("Temperature at depth (model)", false),
    CHLOROPHYLL("Chlorophyll (satellite, 5-day blend)", false),
    SEA_LEVEL("Sea level and eddies (satellite)", false),
    SALINITY("Salinity (satellite)", false);

    val satellite: SatelliteKind?
        get() = when (this) {
            SEA_TEMP_SATELLITE, SEA_TEMP_BREAKS -> SatelliteKind.SEA_TEMP
            TEMP_BELOW -> SatelliteKind.TEMP_BELOW
            CHLOROPHYLL -> SatelliteKind.CHLOROPHYLL
            SEA_LEVEL -> SatelliteKind.SEA_LEVEL
            SALINITY -> SatelliteKind.SALINITY
            else -> null
        }

    /** A temperature, so it follows the chosen unit and the range set for the colours. */
    val isTemperature: Boolean get() = this == SEA_TEMP_SATELLITE || this == SEA_TEMP_FORECAST || this == TEMP_BELOW
}

/** Which way the flow lines (or arrows) show things moving. */
enum class ArrowChoice(val label: String, val short: String) {
    /** Whatever the map layer is: wind for wind speed, the current for the rest. */
    SAME("Same as the map layer", ""),
    CURRENT("Current", "current flow"),
    WIND("Wind", "wind flow"),
    WAVES("Waves", "wave flow"),
    SWELL("Swell", "swell flow"),
    NONE("Off", "");

    /** What [SAME] stands for with [layer] on the map. */
    fun resolve(layer: ColourChoice): ArrowChoice = if (this != SAME) this else when (layer) {
        ColourChoice.WIND -> WIND
        ColourChoice.WAVES -> WAVES
        ColourChoice.SWELL -> SWELL
        else -> CURRENT
    }

    /** The strength that gets a full-size arrow and the fastest flow lines: m/s for current and wind, metres for waves. */
    val reference: Double
        get() = when (this) {
            CURRENT -> 1.5
            WIND -> 15.0
            else -> 3.0
        }

    val fields: Pair<ForecastField, ForecastField>?
        get() = when (this) {
            NONE, SAME -> null
            CURRENT -> ForecastField.CURRENT_U to ForecastField.CURRENT_V
            WAVES -> ForecastField.WAVE_U to ForecastField.WAVE_V
            SWELL -> ForecastField.SWELL_U to ForecastField.SWELL_V
            WIND -> ForecastField.WIND_U to ForecastField.WIND_V
        }
}

/** How the direction is drawn. */
enum class DirectionStyle(val label: String, val arrows: Boolean, val flow: Boolean) {
    FLOW("Moving lines", arrows = false, flow = true),
    ARROWS("Arrows", arrows = true, flow = false),
    BOTH("Moving lines and arrows", arrows = true, flow = true),
    NONE("Off", arrows = false, flow = false)
}

/**
 * The colour range for a layer. Speeds and heights use fixed ranges so a
 * colour always means the same; sea temperature and chlorophyll are
 * stretched to the area, as a 1 °C temperature break (where fish gather) is
 * invisible on a range that covers the whole ocean.
 */
class ColourScale(
    val low: Double,
    val high: Double,
    val logarithmic: Boolean,
    private val stops: IntArray,
    /** Where each stop sits, 0..1 rising; evenly spaced when null. */
    private val at: DoubleArray? = null
) {

    /** 0..1 along the scale. */
    fun position(value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        val t = if (logarithmic) {
            if (value <= 0) 0.0 else (log10(value) - log10(low)) / (log10(high) - log10(low))
        } else {
            (value - low) / (high - low)
        }
        return t.coerceIn(0.0, 1.0)
    }

    /** ARGB for [value]; transparent for no value. */
    fun colour(value: Double): Int {
        val t = position(value)
        if (t.isNaN()) return 0
        return colourAt(t)
    }

    fun colourAt(t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        val i: Int
        val f: Double
        if (at == null) {
            val scaled = u * (stops.size - 1)
            i = scaled.toInt().coerceAtMost(stops.size - 2)
            f = scaled - i
        } else {
            i = (at.indexOfLast { it <= u }).coerceIn(0, stops.size - 2)
            f = ((u - at[i]) / (at[i + 1] - at[i])).coerceIn(0.0, 1.0)
        }
        val a = stops[i]
        val b = stops[i + 1]
        fun mix(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).roundToInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** The value at [t] (0..1) along the scale, for legend labels. */
    fun valueAt(t: Double): Double =
        if (logarithmic) 10.0.pow(log10(low) + (log10(high) - log10(low)) * t) else low + (high - low) * t

    companion object {
        /** Dark blue through cyan, green and yellow to red: easy to read in sunlight. */
        val SPECTRUM = intArrayOf(
            0xFF30123B.toInt(), 0xFF4662D7.toInt(), 0xFF36AAF9.toInt(), 0xFF1AE4B6.toInt(),
            0xFF72FE5E.toInt(), 0xFFC8EF34.toInt(), 0xFFFABA39.toInt(), 0xFFF66B19.toInt(), 0xFFCA2A04.toInt()
        )

        /**
         * Wind, coloured the way windy.com does: blue for light air through
         * green and yellow to red and purple for gales. Stops at 0, 1, 3, 5,
         * 7, 9, 11, 13, 15, 17, 19 and 21 m/s, on a 0–21 m/s (41 kn) scale.
         */
        val WIND_COLOURS = intArrayOf(
            0xFF6271B7.toInt(), 0xFF39619F.toInt(), 0xFF4A94A9.toInt(), 0xFF4D8D7B.toInt(),
            0xFF53A553.toInt(), 0xFF359F35.toInt(), 0xFFA79D51.toInt(), 0xFF9F7F3A.toInt(),
            0xFFA16C5C.toInt(), 0xFF813A4E.toInt(), 0xFFAF5088.toInt(), 0xFF754A93.toInt()
        )
        private val WIND_AT = doubleArrayOf(0.0, 1.0, 3.0, 5.0, 7.0, 9.0, 11.0, 13.0, 15.0, 17.0, 19.0, 21.0).map { it / 21.0 }.toDoubleArray()
        private const val KNOTS_PER_MS = 1.943844

        /** Clear blue water to green and yellow plankton-rich water. */
        val PLANKTON = intArrayOf(
            0xFF1B2A6B.toInt(), 0xFF1F5FA8.toInt(), 0xFF1F9AA6.toInt(), 0xFF2DB36B.toInt(),
            0xFF8CCB3A.toInt(), 0xFFE5D93A.toInt(), 0xFFB5651D.toInt()
        )

        /** Blue below the average, white on it, red above. */
        val DIVERGING = intArrayOf(
            0xFF1B3A8C.toInt(), 0xFF3B82C4.toInt(), 0xFF9CCBE6.toInt(), 0xFFF4F6F8.toInt(),
            0xFFF5B7A0.toInt(), 0xFFD9543C.toInt(), 0xFF8E1616.toInt()
        )

        /** Calm water dark, sharp temperature breaks bright. */
        val BREAKS = intArrayOf(
            0xFF0B1622.toInt(), 0xFF1B3A6B.toInt(), 0xFF2A7F9E.toInt(), 0xFF45C4A0.toInt(),
            0xFFE5D93A.toInt(), 0xFFF4F6F8.toInt()
        )

        /** For [choice] over [values] (the area's numbers, for the stretched layers); values in display units. */
        fun forChoice(choice: ColourChoice, values: FloatArray? = null): ColourScale = when (choice) {
            ColourChoice.CURRENT -> ColourScale(0.0, 3.0, false, SPECTRUM)
            ColourChoice.WAVES, ColourChoice.SWELL -> ColourScale(0.0, 4.0, false, SPECTRUM)
            ColourChoice.WIND -> ColourScale(0.0, 21.0 * KNOTS_PER_MS, false, WIND_COLOURS, WIND_AT)
            ColourChoice.SEA_TEMP_SATELLITE, ColourChoice.SEA_TEMP_FORECAST, ColourChoice.TEMP_BELOW -> {
                val (lo, hi) = stretch(values, minSpan = 1.0) ?: (10.0 to 30.0)
                ColourScale(lo, hi, false, SPECTRUM)
            }
            ColourChoice.SEA_TEMP_BREAKS -> {
                val (_, hi) = stretch(values, minSpan = 0.0) ?: (0.0 to 0.1)
                ColourScale(0.0, hi.coerceAtLeast(MIN_BREAK_RANGE), false, BREAKS)
            }
            ColourChoice.CHLOROPHYLL -> {
                val logs = values?.let { v -> FloatArray(v.size) { i -> if (v[i] > 0) log10(v[i].toDouble()).toFloat() else Float.NaN } }
                val (lo, hi) = stretch(logs, minSpan = 0.5) ?: (-1.5 to 1.0)
                ColourScale(10.0.pow(lo), 10.0.pow(hi), true, PLANKTON)
            }
            // Even about the average, so white always means average.
            ColourChoice.SEA_LEVEL -> {
                val (lo, hi) = stretch(values, minSpan = 0.0) ?: (-20.0 to 20.0)
                val reach = maxOf(abs(lo), abs(hi), MIN_SEA_LEVEL_REACH_CM)
                ColourScale(-reach, reach, false, DIVERGING)
            }
            ColourChoice.SALINITY -> {
                val (lo, hi) = stretch(values, minSpan = 0.5) ?: (33.0 to 37.0)
                ColourScale(lo, hi, false, SPECTRUM)
            }
            ColourChoice.NONE -> ColourScale(0.0, 1.0, false, SPECTRUM)
        }

        /** A range set by hand for the temperatures, in °C. */
        fun temperatureRange(lowCelsius: Double, highCelsius: Double): ColourScale =
            ColourScale(lowCelsius, highCelsius, false, SPECTRUM)

        /** °C per km: flat water shouldn't fill the whole colour range. */
        private const val MIN_BREAK_RANGE = 0.05
        private const val MIN_SEA_LEVEL_REACH_CM = 5.0

        /** The 2nd to 98th percentile of [values], widened to at least [minSpan]. */
        internal fun stretch(values: FloatArray?, minSpan: Double): Pair<Double, Double>? {
            val finite = values?.filter { it.isFinite() }?.sorted() ?: return null
            if (finite.isEmpty()) return null
            var lo = finite[(finite.size * 0.02).toInt().coerceAtMost(finite.size - 1)].toDouble()
            var hi = finite[(finite.size * 0.98).toInt().coerceAtMost(finite.size - 1)].toDouble()
            if (hi - lo < minSpan) {
                val mid = (hi + lo) / 2
                lo = mid - minSpan / 2
                hi = mid + minSpan / 2
            }
            return lo to hi
        }
    }
}

/**
 * Builds the colour picture of a layer for an area, as pixels laid out on
 * the web-Mercator map (rows evenly spaced in map height, not latitude), so
 * it lines up when MapLibre stretches it over the area's corners.
 */
object ConditionsPicture {

    /** Value in display units at a point, or NaN. */
    fun interface Sampler {
        fun at(lat: Double, lng: Double): Float
    }

    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    fun render(
        south: Double, west: Double, north: Double, east: Double, maxSide: Int, scale: ColourScale, sampler: Sampler,
        /** Checked each row: a picture nobody wants any more stops part-way. */
        cancelled: () -> Boolean = { false }
    ): Pixels {
        val yTop = mercatorY(north)
        val yBottom = mercatorY(south)
        val widthDeg = east - west
        // Width and height in the same map units (radians of longitude).
        val mapWidth = Math.toRadians(widthDeg)
        val mapHeight = yTop - yBottom
        val width: Int
        val height: Int
        if (mapWidth >= mapHeight) {
            width = maxSide
            height = (maxSide * mapHeight / mapWidth).roundToInt().coerceAtLeast(2)
        } else {
            height = maxSide
            width = (maxSide * mapWidth / mapHeight).roundToInt().coerceAtLeast(2)
        }
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            if (cancelled()) break
            val lat = latitudeOf(yTop - (y + 0.5) / height * mapHeight)
            for (x in 0 until width) {
                val lng = west + (x + 0.5) / width * widthDeg
                argb[y * width + x] = scale.colour(sampler.at(lat, lng).toDouble())
            }
        }
        return Pixels(width, height, argb)
    }

    fun mercatorY(lat: Double): Double = ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))

    fun latitudeOf(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - PI / 2)
}
