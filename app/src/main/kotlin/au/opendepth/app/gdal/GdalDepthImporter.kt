package au.opendepth.app.gdal

import au.opendepth.app.diagnostics.ResourceTracker
import android.content.Context
import au.opendepth.app.diagnostics.DiagnosticLog
import au.opendepth.app.geotiff.GeoTiffReader
import au.opendepth.app.geotiff.MapProjection
import au.opendepth.app.model.GeoBounds
import org.gdal.gdal.Band
import org.gdal.gdal.Dataset
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GDAL helpers behind [GdalTileSource], for GeoTIFFs too big for
 * [GeoTiffReader] (see [au.opendepth.app.geotiff.GeoTiffImportRouter]).
 *
 * Reprojection uses the app's own [MapProjection] rather than GDAL's warper:
 * GDAL only supplies the raster's EPSG code. [Dataset.GetProjection] still
 * needs `proj.db` for that (see [GdalSupport]). Native code, so not covered
 * by the JVM tests.
 */
object GdalDepthImporter {

    /**
     * GDAL init, path sanity logging and `gdal.Open`. [sourcePath] must be a
     * real, absolute filesystem path — see
     * [au.opendepth.app.storage.RealPathResolver]'s doc for why GDAL's
     * native `open()` cannot use a `/proc/self/fd/` descriptor path.
     */
    internal fun openDataset(context: Context, sourcePath: String): Dataset {
        GdalSupport.ensureInitialized(context)

        val sanityFile = File(sourcePath)
        DiagnosticLog.log(
            TAG,
            "path sanity check: path=$sourcePath exists=${sanityFile.exists()} " +
                "canRead=${sanityFile.canRead()} length=${sanityFile.length()}"
        )

        DiagnosticLog.log(TAG, "GDAL open start: path=$sourcePath")
        val openStart = System.currentTimeMillis()
        val dataset = gdal.Open(sourcePath, gdalconstConstants.GA_ReadOnly)
        if (dataset == null) {
            val errNo = gdal.GetLastErrorNo()
            val errMsg = gdal.GetLastErrorMsg()
            DiagnosticLog.log(
                TAG,
                "GDAL open failed: errno=$errNo message=$errMsg"
            )
            // errno/message (with the path) are in the log line above; keep them out of the user-facing text.
            throw IOException("This file couldn't be opened as a GeoTIFF. It may be damaged, or not a GeoTIFF.")
        }
        DiagnosticLog.log(TAG, "GDAL open finished: elapsedMs=${System.currentTimeMillis() - openStart}")
        ResourceTracker.gdalDatasetOpened()
        return dataset
    }

    /** Everything [resolveRasterInfo] reads off a dataset once, before any tile is served. */
    internal class RasterInfo(
        /** The band's raw min/max as stored, before any sign flip — what [resolveRasterInfo] caches. */
        val rawMin: Double,
        val rawMax: Double,
        val band: Band,
        val width: Int,
        val height: Int,
        val transform: GdalAffineTransform,
        val projection: MapProjection,
        val bounds: GeoBounds,
        val noData: Double?,
        val positiveDown: Boolean,
        val shallowestMetres: Double,
        val deepestMetres: Double
    )

    /**
     * Rotation/CRS/bounds resolution and one full-band statistics pass, done
     * once up front before [GdalTileSource] serves tiles on demand.
     */
    /**
     * [knownMinMax], when given, is the band's min/max from an earlier open of
     * the same unchanged file: computing it reads every pixel, which for a
     * 1 GB+ survey takes tens of seconds on a phone at every app start.
     */
    internal fun resolveRasterInfo(dataset: Dataset, knownMinMax: Pair<Double, Double>? = null): RasterInfo {
        val width = dataset.rasterXSize
        val height = dataset.rasterYSize
        if (width <= 0 || height <= 0) throw IOException("This GeoTIFF contains no image data")

        val band = dataset.GetRasterBand(1) ?: throw IOException("This raster has no readable band")

        val geoTransform = DoubleArray(6)
        dataset.GetGeoTransform(geoTransform)
        val transform = GdalAffineTransform(geoTransform)
        if (abs(transform.rotationX) > ROTATION_EPSILON || abs(transform.rotationY) > ROTATION_EPSILON) {
            throw GeoTiffReader.UnsupportedGeoTiffException(
                "This TIFF is rotated relative to north, which is not supported"
            )
        }

        val projection = resolveProjectionFromDataset(dataset)
        if (projection == null) {
            val rawWkt = dataset.GetProjection()
            DiagnosticLog.log(
                TAG,
                "CRS resolution failed: rawWktIsNull=${rawWkt == null} " +
                    "rawWktIsBlank=${rawWkt?.isBlank()} rawWktLength=${rawWkt?.length ?: -1} " +
                    "rawWkt=${rawWkt ?: "<null>"}"
            )
            val lastErrNo = gdal.GetLastErrorNo()
            val lastErrMsg = gdal.GetLastErrorMsg()
            if (lastErrNo != 0 || !lastErrMsg.isNullOrBlank()) {
                DiagnosticLog.log(TAG, "GDAL last error at CRS resolution time: errno=$lastErrNo message=$lastErrMsg")
            }
            throw GeoTiffReader.UnsupportedGeoTiffException(
                "GDAL could not determine a supported coordinate system for this raster"
            )
        }

        val bounds = computeBounds(transform, projection, width, height)
        if (!bounds.isValid) {
            throw GeoTiffReader.UnsupportedGeoTiffException(
                "The georeferencing in this TIFF does not resolve to a valid latitude/longitude extent"
            )
        }

        DiagnosticLog.log(
            TAG,
            "image params: width=$width height=$height projection=${projection.label}"
        )

        val noDataBox = arrayOf<Double?>(null)
        band.GetNoDataValue(noDataBox)
        val noData = noDataBox[0]

        val minOut = DoubleArray(1)
        val maxOut = DoubleArray(1)
        if (knownMinMax != null) {
            minOut[0] = knownMinMax.first
            maxOut[0] = knownMinMax.second
            DiagnosticLog.log(TAG, "band statistics: reused cached min=${minOut[0]} max=${maxOut[0]}")
        } else {
            val statsStart = System.currentTimeMillis()
            val meanOut = DoubleArray(1)
            val stdDevOut = DoubleArray(1)
            band.ComputeStatistics(false, minOut, maxOut, meanOut, stdDevOut)
            DiagnosticLog.log(
                TAG,
                "band statistics: elapsedMs=${System.currentTimeMillis() - statsStart} " +
                    "min=${minOut[0]} max=${maxOut[0]}"
            )
        }

        // Bathymetry ships in both sign conventions — flip to negative-down
        // if the real data never goes negative, matching GeoTiffReader.
        val positiveDown = minOut[0] >= 0.0 && maxOut[0] > 0.0
        val shallowestMetres = if (positiveDown) -minOut[0] else maxOut[0]
        val deepestMetres = if (positiveDown) -maxOut[0] else minOut[0]

        return RasterInfo(
            rawMin = minOut[0],
            rawMax = maxOut[0],
            band = band,
            width = width,
            height = height,
            transform = transform,
            projection = projection,
            bounds = bounds,
            noData = noData,
            positiveDown = positiveDown,
            shallowestMetres = shallowestMetres,
            deepestMetres = deepestMetres
        )
    }

    /** Reads a source window scaled down to [outWidth] x [outHeight] by GDAL as it reads. */
    internal fun readDecimatedWindow(
        band: Band,
        sourceXOffset: Int,
        sourceYOffset: Int,
        sourceXSize: Int,
        sourceYSize: Int,
        outWidth: Int,
        outHeight: Int,
        noData: Double?,
        positiveDown: Boolean
    ): FloatArray {
        val buffer = FloatArray(outWidth * outHeight)
        val result = band.ReadRaster(
            sourceXOffset, sourceYOffset, sourceXSize, sourceYSize,
            outWidth, outHeight, gdalconstConstants.GDT_Float32, buffer
        )
        if (result != gdalconstConstants.CE_None) {
            throw IOException("Couldn't read part of this GeoTIFF (GDAL error $result)")
        }

        for (index in buffer.indices) {
            val raw = buffer[index]
            val isNoData = raw.isNaN() || raw.isInfinite() ||
                (noData != null && !noData.isNaN() && abs(raw - noData) <= abs(noData) * 1e-6 + 1e-6)
            buffer[index] = if (isNoData) {
                Float.NaN
            } else if (positiveDown) {
                -raw
            } else {
                raw
            }
        }
        return buffer
    }

    // Georeferencing ---------------------------------------------------------

    /**
     * GDAL's own geotransform, in the same shape [au.opendepth.app.geotiff.GeoTiffReader]
     * derives by hand. `internal` so [GdalTileSource] can use the inverse
     * (projected-to-pixel) direction a tile request needs.
     */
    internal class GdalAffineTransform(gt: DoubleArray) {
        val originX = gt[0]
        val pixelWidth = gt[1]
        val rotationX = gt[2]
        val originY = gt[3]
        val rotationY = gt[4]
        val pixelHeight = gt[5]

        fun toProjected(col: Double, row: Double): DoubleArray = doubleArrayOf(
            originX + col * pixelWidth,
            originY + row * pixelHeight
        )

        /**
         * The inverse of [toProjected] — valid because [rotationX]/[rotationY]
         * are asserted ~0 by [resolveRasterInfo], which rejects a rotated
         * TIFF up front, so column and row each solve independently.
         */
        fun toPixel(x: Double, y: Double): DoubleArray = doubleArrayOf(
            (x - originX) / pixelWidth,
            (y - originY) / pixelHeight
        )
    }

    private fun computeBounds(
        transform: GdalAffineTransform,
        projection: MapProjection,
        width: Int,
        height: Int
    ): GeoBounds {
        var west = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY

        fun consider(col: Double, row: Double) {
            val projected = transform.toProjected(col, row)
            val geographic = projection.inverse(projected[0], projected[1]) ?: return
            west = min(west, geographic[1])
            east = max(east, geographic[1])
            south = min(south, geographic[0])
            north = max(north, geographic[0])
        }

        for (step in 0..PERIMETER_SAMPLES) {
            val alongX = width.toDouble() * step / PERIMETER_SAMPLES
            val alongY = height.toDouble() * step / PERIMETER_SAMPLES
            consider(alongX, 0.0)
            consider(alongX, height.toDouble())
            consider(0.0, alongY)
            consider(width.toDouble(), alongY)
        }

        if (!west.isFinite() || !east.isFinite() || !south.isFinite() || !north.isFinite()) {
            throw GeoTiffReader.UnsupportedGeoTiffException(
                "The extent of this TIFF could not be converted to latitude and longitude"
            )
        }

        return GeoBounds(
            west = west.coerceIn(-180.0, 180.0),
            south = south.coerceIn(-90.0, 90.0),
            east = east.coerceIn(-180.0, 180.0),
            north = north.coerceIn(-90.0, 90.0)
        )
    }

    /**
     * The raster's EPSG code from GDAL's own `AutoIdentifyEPSG()`. Reading the
     * WKT text ([resolveProjection]) would pick the first nested code, such as
     * the spheroid's EPSG:7030 instead of the CRS's EPSG:4326.
     */
    private fun resolveProjectionFromDataset(dataset: Dataset): MapProjection? {
        val srs = dataset.GetSpatialRef() ?: return null
        try {
            srs.AutoIdentifyEPSG()
            val epsgCode = srs.GetAuthorityCode(null)?.toIntOrNull()
            val isProjected = srs.IsProjected() != 0
            DiagnosticLog.log(
                TAG,
                "CRS resolved via SpatialReference: epsgCode=$epsgCode isProjected=$isProjected " +
                    "isGeographic=${srs.IsGeographic() != 0}"
            )
            if (epsgCode == null) return null
            return if (isProjected) {
                MapProjection.fromProjectedCode(epsgCode)
            } else {
                MapProjection.fromGeographicCode(epsgCode)
            }
        } finally {
            srs.delete()
        }
    }

    /**
     * The first EPSG code in [wkt], resolved with [MapProjection]. Not used by
     * the app (see [resolveProjectionFromDataset]); kept for
     * `GdalDepthImporterWktTest`, which runs without native GDAL.
     */
    internal fun resolveProjection(wkt: String?): MapProjection? {
        if (wkt.isNullOrBlank()) return null

        val codes = AUTHORITY_EPSG_REGEX.findAll(wkt).map { it.groupValues[1].toIntOrNull() }.filterNotNull().toList()
        if (codes.isEmpty()) return null

        val isProjected = wkt.startsWith("PROJCS[") || wkt.contains("BASEGEOGCRS") ||
            wkt.trimStart().startsWith("PROJCRS[")

        return if (isProjected) {
            MapProjection.fromProjectedCode(codes.last())
        } else {
            MapProjection.fromGeographicCode(codes.first())
        }
    }

    private val AUTHORITY_EPSG_REGEX = Regex("""AUTHORITY\["EPSG",\s*"?(\d+)"?\]""")

    private const val TAG = "GdalDepthImporter"
    private const val ROTATION_EPSILON = 1.0e-9
    private const val PERIMETER_SAMPLES = 64
}
