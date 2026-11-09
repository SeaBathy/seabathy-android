package au.opendepth.app.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Shared handle on the live map and its current style.
 *
 * MapLibre destroys every custom source and layer when `setStyle()` runs, so
 * anything added to a style has to be re-added afterwards. [generation] is
 * bumped on each rebuild and carried through the asynchronous restore work, so
 * results from a superseded style are discarded instead of being attached to
 * the wrong style object.
 */
class MapHolder {

    var map: MapLibreMap? = null
    var style: Style? = null

    var generation: Int = 0
        private set

    fun beginRebuild(): Int {
        generation++
        return generation
    }

    fun isCurrent(generation: Int): Boolean = generation == this.generation

    /** True when [generation] is still current and a style is loaded. */
    fun styleFor(generation: Int): Style? =
        if (isCurrent(generation)) style else null

    fun clear() {
        map = null
        style = null
    }
}
