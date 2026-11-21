package au.opendepth.app.conditions

import au.opendepth.app.nav.NavFormat

/**
 * Water clarity from NOAA CoastWatch's satellite Kd490 (VIIRS, about 4 km,
 * public domain): how fast blue-green light fades with depth, per metre.
 * Clear blue ocean is about 0.02 to 0.05 per metre; green coastal water
 * 0.1 to 0.3; a muddy river plume 1 or more.
 *
 * Shown as a rough visibility, the depth a white disc would go out of
 * sight (the Secchi depth): about 1.7 / Kd490 metres. That rule of thumb
 * comes from light meters, not divers' eyes, and the satellite sees only the
 * top few metres of a 4 km square, so it is always called an estimate.
 */
object WaterClarity {

    /** Secchi depth is about this over the attenuation coefficient. */
    const val SECCHI_FACTOR = 1.7

    /** What a visibility is shown as, at most and at least, metres: beyond these the estimate means little. */
    const val MIN_VISIBILITY_M = 0.5
    const val MAX_VISIBILITY_M = 60.0

    /** The colours run from this visibility (murky) ... */
    const val SCALE_LOW_M = 1.0

    /** ... to this one (clear), on a log scale: a fixed range, so a colour always means the same. */
    const val SCALE_HIGH_M = 30.0

    /** Rough visibility in metres for a Kd490 in m⁻¹; NaN for none. */
    fun visibilityMetres(kd490: Float): Float =
        if (!(kd490 > 0f)) Float.NaN else (SECCHI_FACTOR / kd490).toFloat().coerceIn(MIN_VISIBILITY_M.toFloat(), MAX_VISIBILITY_M.toFloat())

    /** Murky brown and green to clear blue. */
    val COLOURS = intArrayOf(
        0xFF6B4A2B.toInt(), 0xFF8A7B3C.toInt(), 0xFF5E9A4E.toInt(), 0xFF2E9E8F.toInt(),
        0xFF2A7FC1.toInt(), 0xFF2350A8.toInt(), 0xFF1B2A6B.toInt()
    )

    fun scale(): ColourScale = ColourScale(SCALE_LOW_M, SCALE_HIGH_M, true, COLOURS)

    /** A visibility as the box and the legend word it: whole metres (or the depth unit), "8 m". */
    fun text(visibilityMetres: Double, format: NavFormat): String {
        val rounded = if (visibilityMetres >= 3) Math.round(visibilityMetres).toDouble() else visibilityMetres
        return format.depth(rounded).replace(".0 ", " ")
    }
}
