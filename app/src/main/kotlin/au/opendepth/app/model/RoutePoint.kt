package au.opendepth.app.model

/**
 * A single vertex of a planned route or an active measurement. [name] is
 * set when the point came from a waypoint (or a GPX route point), so
 * navigation can say where it is heading.
 */
data class RoutePoint(val lat: Double, val lng: Double, val name: String? = null)
