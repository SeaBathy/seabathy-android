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
 *
 * While a new style loads there is no style here: MapLibre has already
 * cleared the old one, and touching it throws. Whatever draws on the map does
 * nothing then; the rebuild puts it all back once the new style is in.
 */
class MapHolder {

    var map: MapLibreMap? = null

    /**
     * The style on the map, or null while none is ready: before the first,
     * and from the start of a rebuild until the new style has loaded.
     */
    var style: Style? = null
        // A style MapLibre is replacing reports itself not fully loaded.
        get() = field?.takeIf { it.isFullyLoaded }

    var generation: Int = 0
        private set

    /** A new style is about to be set: the old one is not handed out from now on. */
    fun beginRebuild(): Int {
        generation++
        style = null
        return generation
    }

    fun isCurrent(generation: Int): Boolean = generation == this.generation

    /** The style, when [generation] is still current and its style has loaded. */
    fun styleFor(generation: Int): Style? =
        if (isCurrent(generation)) style else null

    fun clear() {
        map = null
        style = null
    }
}
