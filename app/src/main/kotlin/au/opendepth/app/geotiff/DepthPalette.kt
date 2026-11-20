package au.opendepth.app.geotiff

/**
 * A named colour ramp expressed as stops running from the shallow end of the
 * depth range (position 0) to the deep end (position 1). Colours are ARGB
 * ints worked out with plain arithmetic, so the ramp is unit-tested on the JVM.
 */
class DepthPalette(
    val id: String,
    val label: String,
    val stops: IntArray
) {
    init {
        require(stops.size >= 2) { "A palette needs at least two stops" }
    }

    /** Interpolates the ramp at [position], clamped to `0..1`. */
    fun colorAt(position: Double): Int {
        val clamped = position.coerceIn(0.0, 1.0)
        val scaled = clamped * (stops.size - 1)
        val lowerIndex = scaled.toInt().coerceAtMost(stops.size - 2)
        val blend = (scaled - lowerIndex).toFloat()
        return blend(stops[lowerIndex], stops[lowerIndex + 1], blend)
    }

    /** Pre-computes a lookup table of [size] ARGB entries. */
    fun toLookupTable(size: Int): IntArray {
        require(size >= 2) { "Lookup table needs at least two entries" }
        return IntArray(size) { index -> colorAt(index.toDouble() / (size - 1)) }
    }

    private fun blend(from: Int, to: Int, amount: Float): Int =
        (0xFF shl 24) or
            (channel((from shr 16) and 0xFF, (to shr 16) and 0xFF, amount) shl 16) or
            (channel((from shr 8) and 0xFF, (to shr 8) and 0xFF, amount) shl 8) or
            channel(from and 0xFF, to and 0xFF, amount)

    private fun channel(from: Int, to: Int, amount: Float): Int =
        (from + (to - from) * amount).toInt().coerceIn(0, 255)

    companion object {
        /** Every palette selectable from the depth ramp dialog. */
        val ALL: List<DepthPalette> = listOf(
            DepthPalette(
                id = "rainbow",
                label = "Rainbow",
                stops = intArrayOf(
                    0xFFB3002D.toInt(),
                    0xFFFF3B30.toInt(),
                    0xFFFF9500.toInt(),
                    0xFFFFE000.toInt(),
                    0xFF34C759.toInt(),
                    0xFF00C8B4.toInt(),
                    0xFF0A84FF.toInt(),
                    0xFF1B2A6B.toInt()
                )
            ),
            DepthPalette(
                id = "ocean",
                label = "Ocean blues",
                stops = intArrayOf(
                    0xFFD9F5FF.toInt(),
                    0xFF8FE0F5.toInt(),
                    0xFF3EB8E0.toInt(),
                    0xFF1573B8.toInt(),
                    0xFF0B3A7A.toInt(),
                    0xFF04143D.toInt()
                )
            ),
            DepthPalette(
                id = "chart",
                label = "Nautical chart",
                stops = intArrayOf(
                    0xFFFFF6C9.toInt(),
                    0xFFCFEAF7.toInt(),
                    0xFF9CD3EF.toInt(),
                    0xFF66B6E2.toInt(),
                    0xFFE8F4FA.toInt(),
                    0xFFFFFFFF.toInt()
                )
            ),
            DepthPalette(
                id = "viridis",
                label = "Viridis",
                stops = intArrayOf(
                    0xFFFDE725.toInt(),
                    0xFFA0DA39.toInt(),
                    0xFF4AC16D.toInt(),
                    0xFF1FA187.toInt(),
                    0xFF277F8E.toInt(),
                    0xFF365C8D.toInt(),
                    0xFF46327E.toInt(),
                    0xFF440154.toInt()
                )
            ),
            DepthPalette(
                id = "inferno",
                label = "Inferno",
                stops = intArrayOf(
                    0xFFFCFFA4.toInt(),
                    0xFFF9C932.toInt(),
                    0xFFED6925.toInt(),
                    0xFFBB3754.toInt(),
                    0xFF781C6D.toInt(),
                    0xFF320A5E.toInt(),
                    0xFF000004.toInt()
                )
            ),
            DepthPalette(
                id = "reef",
                label = "Reef contrast",
                stops = intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0xFFFFD166.toInt(),
                    0xFF06D6A0.toInt(),
                    0xFF118AB2.toInt(),
                    0xFF073B4C.toInt()
                )
            ),
            DepthPalette(
                id = "grayscale",
                label = "Greyscale",
                stops = intArrayOf(
                    0xFFF5F5F5.toInt(),
                    0xFF9A9A9A.toInt(),
                    0xFF3C3C3C.toInt(),
                    0xFF000000.toInt()
                )
            )
        )

        /**
         * Slope colours, as the 3D view's and the web app's: flat seabed
         * (blue) through moderate (yellow) to steep (red). Not one of [ALL]:
         * it is used when a layer is coloured by slope, not picked as a palette.
         */
        val SLOPE = DepthPalette(
            id = "slope",
            label = "Slope",
            stops = intArrayOf(
                0xFF173880.toInt(),
                0xFF149E9E.toInt(),
                0xFFF5D642.toInt(),
                0xFFF27321.toInt(),
                0xFFC7141A.toInt()
            )
        )

        private val BY_ID = ALL.associateBy { it.id }

        val DEFAULT: DepthPalette = BY_ID.getValue("rainbow")

        fun byId(id: String?): DepthPalette = BY_ID[id] ?: DEFAULT
    }
}
