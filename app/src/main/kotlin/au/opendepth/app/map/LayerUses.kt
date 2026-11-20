package au.opendepth.app.map

/** What a layer is opened for while it is hidden: one of each at a time. */
enum class LayerUse {
    /** The depth colours sheet, from the tap until the sheet is closed. */
    COLOURS,

    /** The 3D view, from the tap until the view is closed (or could not be built). */
    VIEW_3D,

    /** Going to the layer on the chart, from the tap until the chart has moved. */
    GO_TO
}

/**
 * Which tools are using each layer just now. A hidden layer whose file was
 * opened for its tools is closed again only once none is (see
 * [OverlayLayerController]), and a second tap on the same tool while the
 * first is still waiting or open is ignored.
 *
 * Only the bookkeeping, so it is tested without a map. Used on the main thread.
 */
class LayerUses {

    private val uses = HashMap<String, MutableSet<LayerUse>>()

    /** Starts [use] of layer [id]; false, changing nothing, when that use is already under way. */
    fun begin(id: String, use: LayerUse): Boolean = uses.getOrPut(id) { mutableSetOf() }.add(use)

    /**
     * Ends [use] of layer [id]. True when it was under way and was the
     * layer's last use, so a hidden layer can be closed now; false otherwise,
     * also for a use that was never begun or already ended.
     */
    fun end(id: String, use: LayerUse): Boolean {
        val active = uses[id] ?: return false
        if (!active.remove(use)) return false
        if (active.isNotEmpty()) return false
        uses.remove(id)
        return true
    }

    /** Whether any tool is using layer [id]. */
    fun inUse(id: String): Boolean = uses[id]?.isNotEmpty() == true

    /** Whether [use] of layer [id] is under way. */
    fun isUnderWay(id: String, use: LayerUse): Boolean = uses[id]?.contains(use) == true

    /** Forgets every use of a removed layer: ending one later changes nothing. */
    fun forget(id: String) {
        uses.remove(id)
    }

    fun clear() = uses.clear()
}
