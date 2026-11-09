package au.opendepth.app.nav

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DistanceUnit(val label: String, val perNm: Double) {
    NAUTICAL_MILES("nm", 1.0),
    KILOMETRES("km", 1.852),
    STATUTE_MILES("mi", 1.150779)
}

enum class SpeedUnit(val label: String, val perKnot: Double) {
    KNOTS("kn", 1.0),
    KMH("km/h", 1.852),
    MPH("mph", 1.150779)
}

enum class DepthUnit(val label: String, val perMetre: Double) {
    METRES("m", 1.0),
    FEET("ft", 3.280840),
    FATHOMS("fm", 0.546807)
}

enum class FuelUnit(val label: String, val perLitre: Double) {
    LITRES("L", 1.0),
    US_GALLONS("US gal", 0.264172),
    UK_GALLONS("gal", 0.219969)
}

/** Bearings are kept true internally; magnetic adds the local variation for display. */
enum class BearingReference { TRUE, MAGNETIC }

enum class TemperatureUnit(val label: String) {
    CELSIUS("°C"),
    FAHRENHEIT("°F");

    /** [celsius] in this unit. */
    fun of(celsius: Double): Double = if (this == FAHRENHEIT) celsius * 9 / 5 + 32 else celsius

    /** A value in this unit, back in °C. */
    fun toCelsius(value: Double): Double = if (this == FAHRENHEIT) (value - 32) * 5 / 9 else value

    /** A difference of [celsius] degrees, in this unit. */
    fun ofDifference(celsius: Double): Double = if (this == FAHRENHEIT) celsius * 9 / 5 else celsius

    companion object {
        /** Fahrenheit where people use it, Celsius everywhere else. */
        fun defaultFor(country: String): TemperatureUnit =
            if (country.uppercase(Locale.US) in setOf("US", "BS", "BZ", "KY", "PW", "FM", "MH", "LR")) FAHRENHEIT else CELSIUS
    }
}

/** Countries that use feet and Fahrenheit day to day. */
private val IMPERIAL_COUNTRIES = setOf("US", "BS", "BZ", "KY", "PW", "FM", "MH", "LR")

/** Heights at sea: tides, waves and swell. */
enum class HeightUnit(val label: String, val perMetre: Double) {
    METRES("m", 1.0),
    FEET("ft", 3.280840);

    fun of(metres: Double): Double = metres * perMetre

    fun toMetres(value: Double): Double = value / perMetre

    companion object {
        /** Feet for anyone already using feet or fathoms for depth, or where people use them. */
        fun defaultFor(depth: DepthUnit, country: String): HeightUnit =
            if (depth != DepthUnit.METRES || country.uppercase(Locale.US) in IMPERIAL_COUNTRIES) FEET else METRES
    }
}

/** Wind and current. Beaufort is for wind only. */
enum class SeaSpeedUnit(val label: String, val perKnot: Double) {
    KNOTS("kn", 1.0),
    KMH("km/h", 1.852),
    MPH("mph", 1.150779),
    METRES_PER_SECOND("m/s", 0.514444),
    BEAUFORT("Bft", 0.0);

    /** A speed given in knots, in this unit. */
    fun format(knots: Double?): String = when {
        knots == null || !knots.isFinite() -> "—"
        this == BEAUFORT -> "${beaufort(knots)} Bft"
        else -> String.format(Locale.US, "%.1f %s", knots * perKnot, label)
    }

    companion object {
        /** Each Beaufort force's top speed, in knots. */
        private val BEAUFORT_TOPS = doubleArrayOf(1.0, 4.0, 7.0, 11.0, 17.0, 22.0, 28.0, 34.0, 41.0, 48.0, 56.0, 64.0)

        fun beaufort(knots: Double): Int = BEAUFORT_TOPS.indexOfFirst { knots < it }.let { if (it < 0) 12 else it }

        /** Until one is chosen, wind and current stay in the boat's speed unit. */
        fun like(speed: SpeedUnit): SeaSpeedUnit = when (speed) {
            SpeedUnit.KNOTS -> KNOTS
            SpeedUnit.KMH -> KMH
            SpeedUnit.MPH -> MPH
        }
    }
}

data class NavUnits(
    val distance: DistanceUnit = DistanceUnit.NAUTICAL_MILES,
    val speed: SpeedUnit = SpeedUnit.KNOTS,
    val depth: DepthUnit = DepthUnit.METRES,
    val fuel: FuelUnit = FuelUnit.LITRES,
    val bearing: BearingReference = BearingReference.TRUE,
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val tide: HeightUnit = HeightUnit.METRES,
    val waves: HeightUnit = HeightUnit.METRES,
    val wind: SeaSpeedUnit = SeaSpeedUnit.KNOTS,
    val current: SeaSpeedUnit = SeaSpeedUnit.KNOTS
)

/** Display formatting for navigation values in the user's chosen units. */
class NavFormat(private val units: NavUnits, private val timeZone: TimeZone = TimeZone.getDefault()) {

    /** A distance given in nautical miles; short ones in metres (or feet for miles). */
    fun distance(nm: Double): String {
        if (!nm.isFinite()) return "—"
        val metres = nm * METRES_PER_NM
        return when (units.distance) {
            DistanceUnit.NAUTICAL_MILES ->
                if (nm < 0.1) "${metres.roundToInt()} m" else String.format(Locale.US, "%.2f nm", nm)
            DistanceUnit.KILOMETRES ->
                if (metres < 1000) "${metres.roundToInt()} m" else String.format(Locale.US, "%.2f km", metres / 1000)
            DistanceUnit.STATUTE_MILES -> {
                val miles = nm * DistanceUnit.STATUTE_MILES.perNm
                if (miles < 0.1) "${(metres * FEET_PER_METRE).roundToInt()} ft" else String.format(Locale.US, "%.2f mi", miles)
            }
        }
    }

    /** A cross-track error in metres, always in a small unit, with no sign. */
    fun offset(metres: Double): String = when {
        !metres.isFinite() -> "—"
        units.distance == DistanceUnit.STATUTE_MILES -> "${(abs(metres) * FEET_PER_METRE).roundToInt()} ft"
        units.distance == DistanceUnit.NAUTICAL_MILES && abs(metres) >= 0.1 * METRES_PER_NM ->
            String.format(Locale.US, "%.2f nm", abs(metres) / METRES_PER_NM)
        abs(metres) >= 1000 -> String.format(Locale.US, "%.2f km", abs(metres) / 1000)
        else -> "${abs(metres).roundToInt()} m"
    }

    fun speed(knots: Double?): String =
        if (knots == null || !knots.isFinite()) "—"
        else String.format(Locale.US, "%.1f %s", knots * units.speed.perKnot, units.speed.label)

    /** A depth below the surface, in metres (positive down). */
    /** A depth of water; below zero the bottom is above the water ("dries 0.4 m"). */
    fun depth(metres: Double?): String = when {
        metres == null || !metres.isFinite() -> "—"
        metres < 0 -> String.format(Locale.US, "dries %.1f %s", -metres * units.depth.perMetre, units.depth.label)
        else -> String.format(Locale.US, "%.1f %s", metres * units.depth.perMetre, units.depth.label)
    }

    /** A temperature given in °C. */
    fun temperature(celsius: Double?): String =
        if (celsius == null || !celsius.isFinite()) "—"
        else String.format(Locale.US, "%.1f %s", units.temperature.of(celsius), units.temperature.label)

    fun wind(knots: Double?): String = units.wind.format(knots)

    fun current(knots: Double?): String = units.current.format(knots)

    /** A wave or swell height, in metres. */
    fun waveHeight(metres: Double?): String =
        if (metres == null || !metres.isFinite()) "—"
        else String.format(Locale.US, "%.1f %s", units.waves.of(metres), units.waves.label)

    /** A tide height, in metres: to the centimetre, or to a tenth of a foot. */
    fun tideHeight(metres: Double?): String =
        if (metres == null || !metres.isFinite()) "—"
        else String.format(Locale.US, if (units.tide == HeightUnit.FEET) "%.1f %s" else "%.2f %s", units.tide.of(metres), units.tide.label)

    /** Tide chart height lines, in the tide unit: a step between lines that reads well for a tide of [rangeMetres]. */
    fun tideChartStep(rangeMetres: Double): Double {
        val range = units.tide.of(rangeMetres)
        return when {
            units.tide == HeightUnit.METRES -> if (range > 3) 1.0 else 0.5
            range > 12 -> 5.0
            range > 6 -> 2.0
            else -> 1.0
        }
    }

    /** Metres in one tide unit's worth of the chart's height lines. */
    fun tideUnitsPerMetre(): Double = units.tide.perMetre

    /** A tide height for a small label on the chart. */
    fun tideMark(metres: Double): String =
        String.format(Locale.US, "%.1f %s", units.tide.of(metres), units.tide.label)

    /** How fast the tide is moving, in metres an hour. */
    fun tideRate(metresPerHour: Double): String =
        String.format(Locale.US, if (units.tide == HeightUnit.FEET) "%.1f %s/h" else "%.2f %s/h", units.tide.of(metresPerHour), units.tide.label)

    /** Sea level against its average, given in cm: cm, or inches with tides in feet. */
    fun seaLevel(cm: Double): String =
        if (units.tide == HeightUnit.FEET) String.format(Locale.US, "%+.1f in", cm / 2.54)
        else String.format(Locale.US, "%+.0f cm", cm)

    fun fuel(litres: Double?): String =
        if (litres == null || !litres.isFinite()) "—"
        else String.format(Locale.US, "%.1f %s", litres * units.fuel.perLitre, units.fuel.label)

    /** A true bearing shown in the chosen reference; [variation] is east-positive degrees. */
    fun bearing(trueDegrees: Double?, variation: Double): String {
        if (trueDegrees == null || !trueDegrees.isFinite()) return "—"
        val shown = when (units.bearing) {
            BearingReference.TRUE -> trueDegrees
            BearingReference.MAGNETIC -> trueDegrees - variation
        }
        val suffix = if (units.bearing == BearingReference.TRUE) "T" else "M"
        return String.format(Locale.US, "%03d°%s", normaliseDegrees(shown).roundToInt() % 360, suffix)
    }

    /** "2h 05m", "45m", "<1m", or "—". */
    fun duration(hours: Double?): String {
        if (hours == null || !hours.isFinite() || hours < 0) return "—"
        val totalMinutes = (hours * 60).roundToInt()
        return when {
            hours * 60 < 1 -> "<1m"
            totalMinutes >= 60 * 48 -> "${totalMinutes / (60 * 24)}d ${(totalMinutes / 60) % 24}h"
            totalMinutes >= 60 -> String.format(Locale.US, "%dh %02dm", totalMinutes / 60, totalMinutes % 60)
            else -> "${totalMinutes}m"
        }
    }

    /** A clock time, with the weekday when it is not today. */
    fun clock(epochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis == null) return "—"
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = this@NavFormat.timeZone }
        val sameDay = day.format(Date(epochMillis)) == day.format(Date(nowMillis))
        val pattern = if (sameDay) "HH:mm" else "EEE HH:mm"
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = this@NavFormat.timeZone }.format(Date(epochMillis))
    }

    companion object {
        const val METRES_PER_NM = 1852.0
        const val FEET_PER_METRE = 3.280840

        fun normaliseDegrees(degrees: Double): Double = ((degrees % 360) + 360) % 360

        /** The signed smallest turn from [from] to [to], in -180..180 (positive = to starboard). */
        fun turnDegrees(from: Double, to: Double): Double {
            val diff = normaliseDegrees(to - from)
            return if (diff > 180) diff - 360 else diff
        }
    }
}
