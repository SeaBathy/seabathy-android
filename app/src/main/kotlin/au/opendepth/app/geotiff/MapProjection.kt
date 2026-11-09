package au.opendepth.app.geotiff

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Forward and inverse conversion between WGS84 geographic coordinates and the
 * projected coordinate system of an imported raster.
 *
 * Only the projections that bathymetric GeoTIFF products realistically ship in
 * are supported: geographic degrees, Web Mercator and WGS84 UTM. Anything else
 * is rejected at import time so the user gets a clear message instead of a
 * silently misplaced layer.
 */
sealed class MapProjection {

    /** Human readable name shown in the layer panel and error messages. */
    abstract val label: String

    /** Geographic degrees to projected units, as `[x, y]`. */
    abstract fun forward(lat: Double, lng: Double): DoubleArray

    /** Projected units to `[lat, lng]` degrees, or null when unrepresentable. */
    abstract fun inverse(x: Double, y: Double): DoubleArray?

    /** WGS84 latitude/longitude in decimal degrees (EPSG:4326). */
    object Geographic : MapProjection() {
        override val label: String = "WGS84 geographic"

        override fun forward(lat: Double, lng: Double): DoubleArray =
            doubleArrayOf(lng, lat)

        override fun inverse(x: Double, y: Double): DoubleArray? {
            if (abs(x) > 180.0 || abs(y) > 90.0) return null
            return doubleArrayOf(y, x)
        }
    }

    /** Spherical Web Mercator in metres (EPSG:3857). */
    object WebMercator : MapProjection() {
        override val label: String = "Web Mercator"

        override fun forward(lat: Double, lng: Double): DoubleArray {
            val clamped = lat.coerceIn(-MERCATOR_LAT_LIMIT, MERCATOR_LAT_LIMIT)
            return doubleArrayOf(
                EARTH_RADIUS * Math.toRadians(lng),
                EARTH_RADIUS * ln(tan(PI / 4.0 + Math.toRadians(clamped) / 2.0))
            )
        }

        override fun inverse(x: Double, y: Double): DoubleArray? {
            val lng = Math.toDegrees(x / EARTH_RADIUS)
            val lat = Math.toDegrees(2.0 * atan(exp(y / EARTH_RADIUS)) - PI / 2.0)
            if (abs(lng) > 180.0 || abs(lat) > 90.0) return null
            return doubleArrayOf(lat, lng)
        }
    }

    /**
     * WGS84 UTM (EPSG:326xx northern, EPSG:327xx southern), evaluated with the
     * Krueger series truncated at the fourth order. Accuracy inside a zone is
     * far below the resolution of any bathymetric raster.
     */
    class Utm(val zone: Int, val northernHemisphere: Boolean) : MapProjection() {

        override val label: String =
            "UTM zone $zone${if (northernHemisphere) "N" else "S"}"

        private val centralMeridian = Math.toRadians(zone * 6.0 - 183.0)
        private val falseNorthing =
            if (northernHemisphere) 0.0 else SOUTHERN_FALSE_NORTHING

        override fun forward(lat: Double, lng: Double): DoubleArray {
            val phi = Math.toRadians(lat.coerceIn(-89.999999, 89.999999))
            val deltaLambda = Math.toRadians(lng) - centralMeridian
            val n = FLATTENING / (2.0 - FLATTENING)
            val twoRootN = 2.0 * sqrt(n) / (1.0 + n)

            val t = sinh(atanh(sin(phi)) - twoRootN * atanh(twoRootN * sin(phi)))
            val xiPrime = atan(t / cos(deltaLambda))
            val etaPrime = atanh(sin(deltaLambda) / sqrt(1.0 + t * t))

            var xi = xiPrime
            var eta = etaPrime
            for (index in ALPHA.indices) {
                val order = 2.0 * (index + 1)
                xi += ALPHA[index] * sin(order * xiPrime) * cosh(order * etaPrime)
                eta += ALPHA[index] * cos(order * xiPrime) * sinh(order * etaPrime)
            }

            return doubleArrayOf(
                FALSE_EASTING + SCALE_FACTOR * MERIDIONAL_RADIUS * eta,
                falseNorthing + SCALE_FACTOR * MERIDIONAL_RADIUS * xi
            )
        }

        override fun inverse(x: Double, y: Double): DoubleArray? {
            val xi = (y - falseNorthing) / (SCALE_FACTOR * MERIDIONAL_RADIUS)
            val eta = (x - FALSE_EASTING) / (SCALE_FACTOR * MERIDIONAL_RADIUS)

            var xiPrime = xi
            var etaPrime = eta
            for (index in BETA.indices) {
                val order = 2.0 * (index + 1)
                xiPrime -= BETA[index] * sin(order * xi) * cosh(order * eta)
                etaPrime -= BETA[index] * cos(order * xi) * sinh(order * eta)
            }

            val chi = asin(sin(xiPrime) / cosh(etaPrime))
            var phi = chi
            for (index in DELTA.indices) {
                phi += DELTA[index] * sin(2.0 * (index + 1) * chi)
            }

            val lat = Math.toDegrees(phi)
            val lng = Math.toDegrees(
                centralMeridian + atan(sinh(etaPrime) / cos(xiPrime))
            )
            if (!lat.isFinite() || !lng.isFinite()) return null
            if (abs(lat) > 90.0 || abs(lng) > 180.0) return null
            return doubleArrayOf(lat, lng)
        }
    }

    companion object {
        const val EARTH_RADIUS = 6378137.0

        private const val MERCATOR_LAT_LIMIT = 85.05112878
        private const val FLATTENING = 1.0 / 298.257223563
        private const val FALSE_EASTING = 500000.0
        private const val SOUTHERN_FALSE_NORTHING = 10000000.0
        private const val SCALE_FACTOR = 0.9996
        private const val MERIDIONAL_RADIUS = 6367449.145823415

        private val ALPHA = doubleArrayOf(
            8.377318206244698e-4,
            7.608527773572309e-7,
            1.197645503329453e-9,
            2.429170728856296e-12
        )
        private val BETA = doubleArrayOf(
            8.377321640579486e-4,
            5.905869722089504e-8,
            1.673482665343455e-10,
            2.165020615537011e-13
        )
        private val DELTA = doubleArrayOf(
            3.356551485604286e-3,
            6.571873263127465e-6,
            1.764656426977931e-8,
            5.400482155864202e-11
        )

        /** Resolves a GeoTIFF projected CRS code, or null when unsupported. */
        fun fromProjectedCode(code: Int): MapProjection? = when {
            code == 3857 || code == 3785 || code == 900913 -> WebMercator
            code in 32601..32660 -> Utm(code - 32600, northernHemisphere = true)
            code in 32701..32760 -> Utm(code - 32700, northernHemisphere = false)
            // GDA94 and GDA2020 MGA zones: UTM south on GRS80, whose
            // flattening differs from WGS84's by far less than a raster cell.
            code in 28348..28358 -> Utm(code - 28300, northernHemisphere = false)
            code in 7846..7859 -> Utm(code - 7800, northernHemisphere = false)
            else -> null
        }

        /** Resolves a GeoTIFF geographic CRS code, or null when unsupported. */
        fun fromGeographicCode(code: Int): MapProjection? = when (code) {
            0, 4326, 4269, 4283, 4979, 7844 -> Geographic
            else -> null
        }

        private fun atanh(value: Double): Double =
            0.5 * ln((1.0 + value) / (1.0 - value))
    }
}
