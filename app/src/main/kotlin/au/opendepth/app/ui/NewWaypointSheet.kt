package au.opendepth.app.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import au.opendepth.app.model.Waypoint
import au.opendepth.app.model.WaypointIcon
import au.opendepth.app.util.CoordinateFormatter

/**
 * The inline "new waypoint" sheet at the bottom of the map.
 *
 * The pending position is held here rather than being re-read from the camera
 * on save, so opening the keyboard or nudging the map cannot move the waypoint
 * out from under the user.
 */
class NewWaypointSheet(
    private val views: MainViews,
    private val coordinateEditor: CoordinateEditorDialog,
    private val onSave: (Request) -> String?
) {
    /** What the user typed, ready to be turned into a [Waypoint]. */
    data class Request(
        val name: String,
        val description: String,
        val lat: Double,
        val lng: Double,
        val color: String,
        val icon: String
    )

    private val context: Context = views.waypointSheet.context

    private var pendingLat = 0.0
    private var pendingLng = 0.0
    private var selectedColor = Waypoint.DEFAULT_COLOR
    private var selectedIcon = WaypointIcon.DEFAULT_ICON_ID
    private lateinit var iconSwatches: List<ImageView>

    val isVisible: Boolean
        get() = views.waypointSheetScrim.visibility == View.VISIBLE

    /** Registers the listeners once, during activity setup. */
    fun bind() {
        views.colorSwatches.forEachIndexed { index, swatch ->
            swatch.setOnClickListener {
                selectedColor = Waypoint.PALETTE[index]
                refreshColorSelection()
            }
        }

        iconSwatches = buildIconSwatches()
        iconSwatches.forEachIndexed { index, swatch ->
            swatch.setOnClickListener {
                selectedIcon = WaypointIcon.ALL[index].id
                refreshIconSelection()
            }
        }

        views.btnWaypointCancel.setOnClickListener { hide() }
        views.btnWaypointSave.setOnClickListener { save() }
        views.waypointName.setOnEditorActionListener { _, _, _ ->
            save()
            true
        }

        views.waypointCoordinates.setOnClickListener {
            coordinateEditor.show(
                title = "Waypoint coordinates",
                initialLat = pendingLat,
                initialLng = pendingLng,
                positiveLabel = "Use coordinates"
            ) { latitude, longitude ->
                pendingLat = latitude
                pendingLng = longitude
                views.waypointCoordinates.text =
                    CoordinateFormatter.formatDdm(latitude, longitude)
            }
        }
    }

    fun show(lat: Double, lng: Double) {
        pendingLat = lat
        pendingLng = lng
        selectedColor = Waypoint.DEFAULT_COLOR
        selectedIcon = WaypointIcon.DEFAULT_ICON_ID

        views.waypointName.text.clear()
        views.waypointName.error = null
        views.waypointDescription.text.clear()
        views.waypointCoordinates.text = CoordinateFormatter.formatDdm(lat, lng)
        views.waypointCoordinates.visibility = View.VISIBLE

        refreshColorSelection()
        refreshIconSelection()
        views.waypointSheetScrim.visibility = View.VISIBLE
        hideKeyboard()
        views.waypointName.clearFocus()
    }

    fun hide() {
        views.waypointSheetScrim.visibility = View.GONE
        hideKeyboard()
    }

    private fun save() {
        // A stray repeat call (e.g. an IME action arriving after the sheet
        // has already been dismissed) must not add a second waypoint.
        if (!isVisible) return

        val error = onSave(
            Request(
                name = views.waypointName.text.toString().trim(),
                description = views.waypointDescription.text.toString().trim(),
                lat = pendingLat,
                lng = pendingLng,
                color = selectedColor,
                icon = selectedIcon
            )
        )

        if (error != null) {
            views.waypointName.error = error
            return
        }

        hide()
    }

    private fun refreshColorSelection() {
        views.colorSwatches.forEachIndexed { index, swatch ->
            val selected = Waypoint.PALETTE[index] == selectedColor
            val scale = if (selected) SELECTED_SCALE else 1f
            swatch.scaleX = scale
            swatch.scaleY = scale
        }
    }

    /** The icon choices, built from [WaypointIcon.ALL] so the list lives in one place. */
    private fun buildIconSwatches(): List<ImageView> = WaypointIcon.ALL.map { icon ->
        ImageView(context).apply {
            setImageResource(icon.drawableRes)
            contentDescription = icon.label
            background = context.roundedBackground(
                AppTheme.palette.surface,
                AppTheme.palette.border,
                UiRadius.CONTROL
            )
            val inset = context.dp(8)
            setPadding(inset, inset, inset, inset)
            views.iconPicker.addView(
                this,
                LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply {
                    marginStart = context.dp(4)
                    marginEnd = context.dp(4)
                }
            )
        }
    }

    private fun refreshIconSelection() {
        iconSwatches.forEachIndexed { index, swatch ->
            val selected = WaypointIcon.ALL[index].id == selectedIcon
            swatch.background = context.roundedBackground(
                if (selected) AppTheme.palette.accent else AppTheme.palette.surface,
                AppTheme.palette.border,
                UiRadius.CONTROL
            )
        }
    }

    private fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        val focused = views.waypointSheet.findFocus() ?: views.waypointSheet
        manager?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private companion object {
        const val SELECTED_SCALE = 1.25f
    }
}
