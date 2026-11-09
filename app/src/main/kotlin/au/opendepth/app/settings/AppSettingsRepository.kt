package au.opendepth.app.settings

import android.content.SharedPreferences
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/** Crosshair, coordinate and depth readout appearance. */
data class VisualSettings(
    val cursorColor: String = DEFAULT_ACCENT,
    val coordinateColor: String = DEFAULT_ACCENT,
    val depthDisplayMode: String = DepthDisplayMode.BOTH,
    val depthTextSize: Float = 12f,
    val coordinateTextSize: Float = 13f
) {
    companion object {
        const val DEFAULT_ACCENT = "#19C6B3"
    }
}

/** The named depth readout modes, kept in one place to avoid stray literals. */
object DepthDisplayMode {
    const val BOTH = "both"
    const val NORMAL = "normal"
    const val WAYPOINT = "waypoint"
    const val OFF = "off"

    val VALUES = listOf(BOTH, NORMAL, WAYPOINT, OFF)
    val LABELS = listOf("On the map and with the waypoint tool", "On the map only", "With the waypoint tool only", "Off")

    fun labelFor(value: String): String {
        val index = VALUES.indexOf(value)
        return if (index >= 0) LABELS[index] else LABELS[0]
    }
}

/** The base style drawn beneath every overlay. */
object BasemapType {
    const val DARK = "dark"
    const val OSM = "osm"
    const val CUSTOM = "custom"
    const val NONE = "none"
}

data class BasemapSettings(
    val current: String = BasemapType.DARK,
    val customUrl: String = "",
    val customAttribution: String = ""
)

class AppSettingsRepository(private val preferences: SharedPreferences) {

    init {
        // Appearance is a fixed graphite theme; drop the theme keys written by
        // pre-1.0 builds once rather than on every launch.
        if (preferences.contains(KEY_LEGACY_THEME_MODE)) {
            preferences.edit()
                .remove(KEY_LEGACY_THEME_MODE)
                .remove("ui_accent_hex")
                .remove("ui_panel_hex")
                .remove("ui_compact")
                .remove("ui_rounded")
                .apply()
        }
    }

    fun loadVisual(): VisualSettings {
        val legacyShowDepth = preferences.getBoolean(KEY_LEGACY_SHOW_DEPTH, true)
        val legacyDefaultMode =
            if (legacyShowDepth) DepthDisplayMode.BOTH else DepthDisplayMode.OFF
        val storedMode = preferences.getString(KEY_DEPTH_DISPLAY_MODE, legacyDefaultMode)
            ?: legacyDefaultMode

        return VisualSettings(
            cursorColor = preferences.getString(KEY_CURSOR_COLOR, VisualSettings.DEFAULT_ACCENT)
                ?: VisualSettings.DEFAULT_ACCENT,
            coordinateColor = preferences.getString(KEY_COORD_COLOR, VisualSettings.DEFAULT_ACCENT)
                ?: VisualSettings.DEFAULT_ACCENT,
            depthDisplayMode = storedMode.takeIf { it in DepthDisplayMode.VALUES }
                ?: DepthDisplayMode.BOTH,
            depthTextSize = preferences.getFloat(KEY_DEPTH_TEXT_SIZE, 12f),
            coordinateTextSize = preferences.getFloat(KEY_COORD_TEXT_SIZE, 13f)
        )
    }

    fun saveVisual(settings: VisualSettings) {
        preferences.edit()
            .putString(KEY_CURSOR_COLOR, settings.cursorColor)
            .putString(KEY_COORD_COLOR, settings.coordinateColor)
            .putBoolean(KEY_LEGACY_SHOW_DEPTH, settings.depthDisplayMode != DepthDisplayMode.OFF)
            .putString(KEY_DEPTH_DISPLAY_MODE, settings.depthDisplayMode)
            .putFloat(KEY_DEPTH_TEXT_SIZE, settings.depthTextSize)
            .putFloat(KEY_COORD_TEXT_SIZE, settings.coordinateTextSize)
            .apply()
    }

    fun loadBasemap(): BasemapSettings {
        val settings = BasemapSettings(
            current = preferences.getString(KEY_LAST_BASEMAP, BasemapType.DARK)
                ?: BasemapType.DARK,
            customUrl = preferences.getString(KEY_CUSTOM_BASEMAP_URL, "").orEmpty(),
            customAttribution =
                preferences.getString(KEY_CUSTOM_BASEMAP_ATTRIBUTION, "").orEmpty()
        )

        // A saved "custom" selection is meaningless without a URL to load.
        return if (settings.current == BasemapType.CUSTOM && settings.customUrl.isBlank()) {
            settings.copy(current = BasemapType.DARK)
        } else {
            settings
        }
    }

    fun saveCurrentBasemap(value: String) {
        preferences.edit().putString(KEY_LAST_BASEMAP, value).apply()
    }

    fun saveCustomBasemap(url: String, attribution: String) {
        preferences.edit()
            .putString(KEY_CUSTOM_BASEMAP_URL, url)
            .putString(KEY_CUSTOM_BASEMAP_ATTRIBUTION, attribution)
            .apply()
    }

    fun clearCustomBasemap() {
        preferences.edit()
            .remove(KEY_CUSTOM_BASEMAP_URL)
            .remove(KEY_CUSTOM_BASEMAP_ATTRIBUTION)
            .apply()
    }

    /** Off by default. */
    fun loadRasterQuality(context: android.content.Context): RasterQuality =
        RasterQuality.byId(preferences.getString(KEY_RASTER_QUALITY, null)) ?: RasterQuality.defaultFor(context)

    fun saveRasterQuality(quality: RasterQuality) {
        preferences.edit().putString(KEY_RASTER_QUALITY, quality.id).apply()
    }

    fun loadShowWaypointNames(): Boolean = preferences.getBoolean(KEY_SHOW_WAYPOINT_NAMES, false)

    fun saveShowWaypointNames(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_WAYPOINT_NAMES, value).apply()
    }

    /** The lat/lon grid. Off by default. */
    fun loadShowGrid(): Boolean = preferences.getBoolean(KEY_SHOW_GRID, false)

    fun saveShowGrid(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_GRID, value).apply()
    }

    fun loadCameraPosition(): CameraPosition = CameraPosition.Builder()
        .target(
            LatLng(
                preferences.getFloat(KEY_CAMERA_LAT, DEFAULT_LAT).toDouble(),
                preferences.getFloat(KEY_CAMERA_LNG, DEFAULT_LNG).toDouble()
            )
        )
        .zoom(preferences.getFloat(KEY_CAMERA_ZOOM, DEFAULT_ZOOM).toDouble())
        .bearing(preferences.getFloat(KEY_CAMERA_BEARING, 0f).toDouble())
        .tilt(preferences.getFloat(KEY_CAMERA_TILT, 0f).toDouble())
        .build()

    fun saveCameraPosition(position: CameraPosition) {
        val target = position.target ?: return
        preferences.edit()
            .putFloat(KEY_CAMERA_LAT, target.latitude.toFloat())
            .putFloat(KEY_CAMERA_LNG, target.longitude.toFloat())
            .putFloat(KEY_CAMERA_ZOOM, position.zoom.toFloat())
            .putFloat(KEY_CAMERA_BEARING, position.bearing.toFloat())
            .putFloat(KEY_CAMERA_TILT, position.tilt.toFloat())
            .apply()
    }

    private companion object {
        const val KEY_CURSOR_COLOR = "cursor_color"
        const val KEY_COORD_COLOR = "coord_color"
        const val KEY_DEPTH_DISPLAY_MODE = "depth_display_mode"
        const val KEY_DEPTH_TEXT_SIZE = "depth_text_size"
        const val KEY_COORD_TEXT_SIZE = "coord_text_size"
        const val KEY_LAST_BASEMAP = "last_basemap"
        const val KEY_CUSTOM_BASEMAP_URL = "custom_basemap_url"
        const val KEY_CUSTOM_BASEMAP_ATTRIBUTION = "custom_basemap_attribution"
        const val KEY_CAMERA_LAT = "camera_lat"
        const val KEY_CAMERA_LNG = "camera_lng"
        const val KEY_CAMERA_ZOOM = "camera_zoom"
        const val KEY_CAMERA_BEARING = "camera_bearing"
        const val KEY_CAMERA_TILT = "camera_tilt"
        const val KEY_LEGACY_SHOW_DEPTH = "show_depth"
        const val KEY_LEGACY_THEME_MODE = "ui_theme_mode"
        const val KEY_SHOW_WAYPOINT_NAMES = "show_waypoint_names"
        const val KEY_RASTER_QUALITY = "raster_quality"
        const val KEY_SHOW_GRID = "show_grid"

        // A first start shows the world: nobody's home water is special.
        const val DEFAULT_LAT = 15f
        const val DEFAULT_LNG = 0f
        const val DEFAULT_ZOOM = 1.5f
    }
}
