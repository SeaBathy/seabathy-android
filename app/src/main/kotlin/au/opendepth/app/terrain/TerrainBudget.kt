package au.opendepth.app.terrain

import kotlin.math.sqrt

/**
 * How much 3D terrain a phone is asked to hold, so that any Detail choice is
 * safe: Maximum gets the most this phone can take.
 */
object TerrainBudget {

    /**
     * Grid cells for a phone reporting [totalGib] of memory. Phones report a
     * little less than they're sold with: an 8 GB one reports 7 and up, and a
     * 12 GB one about 11. Short of memory right now ([lowMemory]) or after
     * graphics trouble ([safety] above 1), it holds less.
     */
    fun cells(totalGib: Double, lowMemory: Boolean, safety: Int): Double {
        val base = when {
            totalGib < 3.5 -> 300_000.0
            totalGib < 5.0 -> 600_000.0
            totalGib < 7.0 -> 1_000_000.0
            totalGib < 10.5 -> 1_500_000.0
            else -> 2_600_000.0
        }
        return base / (1 shl (safety - 1).coerceAtLeast(0)) / (if (lowMemory) 2 else 1)
    }

    /** The longest side that fits [cells], for an area [aspect] times as long as it is wide. */
    fun longestSide(cells: Double, aspect: Double): Int = sqrt(cells * aspect).toInt()
}
