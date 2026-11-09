package au.opendepth.app.model

/** An axis-aligned geographic bounding box in WGS84 degrees. */
data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    val isValid: Boolean
        get() = east > west &&
            north > south &&
            west >= -180.0 &&
            east <= 180.0 &&
            south >= -90.0 &&
            north <= 90.0

    fun contains(lat: Double, lng: Double): Boolean =
        lat in south..north && lng in west..east
}
