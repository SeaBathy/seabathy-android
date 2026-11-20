package au.opendepth.app.map

import au.opendepth.app.nav.DistanceUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * What the chart's scale bar says: a round distance (1, 2 or 5 times a power
 * of ten) in the distance unit chosen in Boat & navigation, no longer than
 * the stretch of chart it was worked out for.
 *
 * Short distances drop to the small unit the rest of the app writes them in
 * (see [au.opendepth.app.nav.NavFormat.distance]): metres under 0.1 nm or
 * 1 km, feet under 0.1 mi.
 */
object ChartScale {

    /** The bar: its [label] ("0.5 nm", "200 m") and the distance on the ground it stands for. */
    data class Bar(val label: String, val metres: Double)

    private const val METRES_PER_NM = 1852.0
    private const val METRES_PER_KM = 1000.0
    private const val METRES_PER_MILE = 1609.344
    private const val METRES_PER_FOOT = 0.3048

    /**
     * The bar for a stretch of chart [spanMetres] long on the ground: the
     * longest round distance that fits in it, so between 40% and all of it.
     * Null when there is nothing to measure (no span, or not a number).
     */
    fun pick(spanMetres: Double, unit: DistanceUnit): Bar? {
        if (!spanMetres.isFinite() || spanMetres <= 0.0) return null
        return when (unit) {
            DistanceUnit.NAUTICAL_MILES -> inUnits(spanMetres, METRES_PER_NM, "nm", smallest = 0.1)
                ?: inUnits(spanMetres, 1.0, "m")
            DistanceUnit.KILOMETRES -> inUnits(spanMetres, METRES_PER_KM, "km", smallest = 1.0)
                ?: inUnits(spanMetres, 1.0, "m")
            DistanceUnit.STATUTE_MILES -> inUnits(spanMetres, METRES_PER_MILE, "mi", smallest = 0.1)
                ?: inUnits(spanMetres, METRES_PER_FOOT, "ft")
        }
    }

    /** The bar in units of [metresPerUnit], or null when it would be shorter than [smallest] of them. */
    private fun inUnits(spanMetres: Double, metresPerUnit: Double, label: String, smallest: Double = 0.0): Bar? {
        val round = roundDown(spanMetres / metresPerUnit) ?: return null
        // A hair under, for a figure such as 0.1 that has no exact form.
        if (round.value < smallest * (1 - 1e-9)) return null
        return Bar("${round.text} $label", round.value * metresPerUnit)
    }

    /** A round number: [value], and how it is written (no stray digits, no needless ".0"). */
    data class Round(val value: Double, val text: String)

    /** The largest of 1, 2 or 5 times a power of ten that is no more than [amount]; null unless [amount] is above zero. */
    fun roundDown(amount: Double): Round? {
        if (!amount.isFinite() || amount <= 0.0) return null
        var exponent = floor(log10(amount)).toInt()
        // log10 can land a hair either side of a whole power of ten.
        if (10.0.pow(exponent) > amount) exponent--
        if (10.0.pow(exponent + 1) <= amount) exponent++
        val power = 10.0.pow(exponent)
        val leading = when {
            amount >= 5 * power -> 5
            amount >= 2 * power -> 2
            else -> 1
        }
        val text = if (exponent >= 0) {
            (leading * 10.0.pow(exponent)).toLong().toString()
        } else {
            String.format(Locale.US, "%.${-exponent}f", leading * power)
        }
        return Round(leading * power, text)
    }
}
