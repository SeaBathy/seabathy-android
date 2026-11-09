package au.opendepth.app.geotiff

import au.opendepth.app.model.GeoBounds

/**
 * The depths behind one GeoTIFF layer: decoded into memory
 * ([InMemoryDepthGrid]) or a tile pyramid on disk (`TiledDepthPyramid`).
 * [shallowestMetres]/[deepestMetres] are the file's real extremes, and
 * [sampleMetres] reads the real stored depth, even when [previewGrid] is a
 * smaller copy.
 */
interface DepthSource {
    val bounds: GeoBounds
    val shallowestMetres: Double
    val deepestMetres: Double
    val hasData: Boolean

    /** Nearest-neighbour depth lookup at full source precision, or null outside coverage. */
    fun sampleMetres(lat: Double, lng: Double): Double?

    /** A grid to draw: the whole grid, or a smaller copy for a pyramid too big to draw in full. */
    fun previewGrid(): DepthGrid

    /**
     * How the depths are spread, for fitting the colour range to the bulk
     * of the layer. Can take a second on a big file: call off the main thread.
     */
    fun histogram(): DepthHistogram? = null
}

/** Wraps a fully in-memory decoded [DepthGrid] — the existing decode path, unchanged. */
class InMemoryDepthGrid(val grid: DepthGrid) : DepthSource {
    override val bounds: GeoBounds get() = grid.bounds
    override val shallowestMetres: Double get() = grid.shallowestMetres
    override val deepestMetres: Double get() = grid.deepestMetres
    override val hasData: Boolean get() = grid.hasData

    override fun sampleMetres(lat: Double, lng: Double): Double? = grid.sampleMetres(lat, lng)

    override fun previewGrid(): DepthGrid = grid

    private val spread by lazy { DepthHistogram.of(grid.values) }

    override fun histogram(): DepthHistogram? = spread
}
