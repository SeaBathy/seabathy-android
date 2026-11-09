package au.opendepth.app.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object CoordinateFormatter {

    const val MODE_DDM = "DDM"
    const val MODE_DD = "DD"
    const val MODE_DMS = "DMS"

    /** Selectable coordinate entry formats, in the order shown in the UI. */
    val MODES = listOf(MODE_DDM, MODE_DD, MODE_DMS)

    val MODE_LABELS = listOf(
        "Marine \u2014 degrees and decimal minutes",
        "Decimal degrees",
        "Degrees, minutes and seconds"
    )

    fun formatDdm(lat: Double, lng: Double): String =
        "${ddmPart(lat, "N", "S")} ${ddmPart(lng, "E", "W")}"

    fun formatDd(lat: Double, lng: Double): String =
        "%.6f, %.6f".format(Locale.US, lat, lng)

    fun formatDms(lat: Double, lng: Double): String =
        "${dmsPart(lat, "N", "S")} ${dmsPart(lng, "E", "W")}"

    fun formatPair(lat: Double, lng: Double, mode: String): String = when (mode) {
        MODE_DD -> formatDd(lat, lng)
        MODE_DMS -> formatDms(lat, lng)
        else -> formatDdm(lat, lng)
    }

    fun parseSingle(raw: String, isLat: Boolean): Double? {
        val text = raw.trim().uppercase()
        val hemisphere = text.firstOrNull { it in "NSEW" }
        val numbers = Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(text)
            .map { it.value.toDouble() }
            .toList()
        if (numbers.isEmpty()) return null
        // Minutes and seconds past 59.99 are a typo, not a position.
        if (numbers.drop(1).any { it < 0 || it >= 60.0 }) return null

        var value = when (numbers.size) {
            1 -> abs(numbers[0])
            2 -> abs(numbers[0]) + numbers[1] / 60.0
            else -> abs(numbers[0]) + numbers[1] / 60.0 + numbers[2] / 3600.0
        }

        // Checked on the text, not the number: "-0 30.5" parses its degrees as -0.0, which is not < 0.
        val explicitNegative = text.trimStart().startsWith("-")
        if (explicitNegative || hemisphere == 'S' || hemisphere == 'W') value = -value
        if (hemisphere == 'N' || hemisphere == 'E') value = abs(value)

        val limit = if (isLat) 90.0 else 180.0
        return value.takeIf { it in -limit..limit }
    }

    fun parsePair(raw: String): Pair<Double, Double>? {
        val text = raw.trim().replace('\n', ' ')
        if (text.isBlank()) return null

        val comma = text.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (comma.size == 2) {
            val lat = parseSingle(comma[0], true)
            val lng = parseSingle(comma[1], false)
            if (lat != null && lng != null) return lat to lng
        }

        val latMatch = Regex("(.+?[NS])", RegexOption.IGNORE_CASE).find(text)
        val lngStart = latMatch?.range?.last?.plus(1) ?: 0
        val lngMatch = Regex("(.+?[EW])", RegexOption.IGNORE_CASE).find(text.substring(lngStart))
        if (latMatch != null && lngMatch != null) {
            val lat = parseSingle(latMatch.value, true)
            val lng = parseSingle(lngMatch.value, false)
            if (lat != null && lng != null) return lat to lng
        }

        val numbers = Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(text).map { it.value }.toList()
        if (numbers.size == 2) {
            val lat = numbers[0].toDoubleOrNull()
            val lng = numbers[1].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return lat to lng
            }
        }
        return null
    }

    // Both helpers round to the displayed precision before splitting into
    // degrees/minutes/seconds, so 33.99999999 shows as 34°00.000' rather
    // than the impossible 33°60.000'.

    private fun ddmPart(value: Double, positive: String, negative: String): String {
        val thousandthsOfMinute = (abs(value) * 60_000.0).roundToLong()
        val degrees = thousandthsOfMinute / 60_000
        val minutes = (thousandthsOfMinute % 60_000) / 1000.0
        return "%d°%06.3f'%s".format(Locale.US, degrees, minutes, if (value >= 0) positive else negative)
    }

    private fun dmsPart(value: Double, positive: String, negative: String): String {
        val hundredthsOfSecond = (abs(value) * 360_000.0).roundToLong()
        val degrees = hundredthsOfSecond / 360_000
        val minutes = (hundredthsOfSecond % 360_000) / 6_000
        val seconds = (hundredthsOfSecond % 6_000) / 100.0
        return "%d°%02d'%05.2f\"%s".format(
            Locale.US, degrees, minutes, seconds, if (value >= 0) positive else negative
        )
    }
}
