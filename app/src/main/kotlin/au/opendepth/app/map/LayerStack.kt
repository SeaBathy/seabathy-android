package au.opendepth.app.map

import au.opendepth.app.model.LayerKind
import au.opendepth.app.model.OverlayLayer

/**
 * What can be told about the layer stack from the saved layers alone, with
 * no map and no open file: where a layer goes among the others, and what a
 * hidden layer is while its file is closed.
 */
object LayerStack {

    /**
     * The first layer above [id] in [bottomToTop] that is on the map, or null
     * when none is. A layer put on the map goes just under that one (or on
     * top of the stack), so one that is shown again, or drawn again, keeps
     * its place while others around it are hidden.
     */
    fun firstOnMapAbove(bottomToTop: List<String>, id: String, isOnMap: (String) -> Boolean): String? {
        val index = bottomToTop.indexOf(id)
        if (index < 0) return null
        for (i in index + 1 until bottomToTop.size) {
            if (isOnMap(bottomToTop[i])) return bottomToTop[i]
        }
        return null
    }

    /**
     * Whether hiding [layer] closes its file and gives back all it held: a
     * GeoTIFF's decoded depths or picture, or a big survey's open file and
     * tile caches. Such a layer hidden when the app starts is not opened
     * until it is shown.
     *
     * An MBTiles pack keeps its file open while hidden (a file handle, no
     * tiles in memory), because the 3D view can still lay a pack over the
     * seabed that is hidden on the chart. An online layer has no file.
     */
    fun closesWhenHidden(layer: OverlayLayer): Boolean = layer.kind == LayerKind.GEOTIFF

    /**
     * Whether a saved layer holds depths, told without opening its file: a
     * big survey always does, and a smaller GeoTIFF does when colours were
     * saved with it (a picture has none).
     */
    fun holdsDepths(layer: OverlayLayer): Boolean =
        layer.kind == LayerKind.GEOTIFF && (layer.gdalOnDemand || layer.ramp != null)
}
