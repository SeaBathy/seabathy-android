package au.opendepth.app.model

/**
 * Colour-ramp configuration for a depth raster.
 *
 * Depths are stored as negative-down metres to match the sign convention used
 * by bathymetric GeoTIFF products, so [shallowMetres] is the value closest to
 * the surface and is therefore numerically greater than [deepMetres].
 */
data class DepthRampSettings(
    val paletteId: String = DEFAULT_PALETTE_ID,
    val shallowMetres: Double = DEFAULT_SHALLOW_METRES,
    val deepMetres: Double = DEFAULT_DEEP_METRES,
    val invert: Boolean = false,
    /**
     * Depths outside the range take the end colours instead of being hidden.
     * Off by default: hiding shows just the band picked. (False is also what
     * ramps saved before this existed read back as.)
     */
    val showOutside: Boolean = false,
    /**
     * Valid from [MIN_HILLSHADE_EXAGGERATION] to [MAX_HILLSHADE_EXAGGERATION]; out-of-range values
     * aren't rejected here, only by [coerceHillshadeExaggeration].
     */
    val hillshadeExaggeration: Double = DEFAULT_HILLSHADE_EXAGGERATION,
    /**
     * Colours the seabed by how steep it is (flat blue, through yellow, to red
     * at [steepestDegrees]) instead of by its depth, so ledges and drop-offs
     * stand out whatever their depth. The depth range still decides which
     * depths are shown. False, by depth, is also what ramps saved before this
     * existed read back as.
     */
    val colourBySlope: Boolean = false,
    /**
     * The slope drawn fully red, in degrees. A ramp saved before this existed
     * can read back as 0, so read [steepestDegrees] rather than this.
     */
    val slopeMaxDegrees: Double = DEFAULT_SLOPE_MAX_DEGREES
) {
    /** Normalises the pair so shallow is always the surface-most value. */
    fun normalised(): DepthRampSettings {
        if (shallowMetres >= deepMetres) return this
        return copy(shallowMetres = deepMetres, deepMetres = shallowMetres)
    }

    val spanMetres: Double
        get() = shallowMetres - deepMetres

    /** [slopeMaxDegrees], or the default where none was saved. */
    val steepestDegrees: Double
        get() = if (slopeMaxDegrees.isFinite() && slopeMaxDegrees > 0.0) slopeMaxDegrees else DEFAULT_SLOPE_MAX_DEGREES

    companion object {
        const val DEFAULT_SLOPE_MAX_DEGREES = 20.0

        /** The slopes offered for "Red from", as the 3D view offers them. */
        val SLOPE_MAX_CHOICES: List<Pair<Double, String>> = listOf(
            5.0 to "5° (gentle seabeds)",
            10.0 to "10°",
            20.0 to "20°",
            35.0 to "35°",
            60.0 to "60° (cliffs only)"
        )

        const val DEFAULT_PALETTE_ID = "rainbow"
        const val DEFAULT_SHALLOW_METRES = -6.0
        const val DEFAULT_DEEP_METRES = -100.0

        /** Matches the exaggeration this app used before it was per-layer adjustable. */
        const val DEFAULT_HILLSHADE_EXAGGERATION = 0.6
        const val MIN_HILLSHADE_EXAGGERATION = 0.0
        const val MAX_HILLSHADE_EXAGGERATION = 5.0

        /**
         * Clamps a slope-shading strength to the slider's range: the slider
         * can't leave it, but a value typed in the "exact values" field can.
         * (Still named for the MapLibre hillshade it once drove, as saved
         * layers store it under that name; it now sets [au.opendepth.app.geotiff.ReliefShading].)
         */
        fun coerceHillshadeExaggeration(value: Double): Double =
            value.coerceIn(MIN_HILLSHADE_EXAGGERATION, MAX_HILLSHADE_EXAGGERATION)
    }
}
