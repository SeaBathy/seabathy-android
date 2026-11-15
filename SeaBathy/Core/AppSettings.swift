import Foundation
import SwiftUI

/// Crosshair, coordinate and depth readout appearance.
struct VisualSettings: Equatable {
    var cursorColor: String = VisualSettings.defaultAccent
    var coordinateColor: String = VisualSettings.defaultAccent
    var depthDisplayMode: String = DepthDisplayMode.both
    var depthTextSize: Double = 12
    var coordinateTextSize: Double = 13

    static let defaultAccent = "#19C6B3"
}

/// The named depth readout modes.
enum DepthDisplayMode {
    static let both = "both"
    static let normal = "normal"
    static let waypoint = "waypoint"
    static let off = "off"

    static let values = [both, normal, waypoint, off]
    static let labels = ["On the map and with the waypoint tool", "On the map only", "With the waypoint tool only", "Off"]

    static func label(for value: String) -> String {
        if let i = values.firstIndex(of: value) { return labels[i] }
        return labels[0]
    }
}

/// The base style drawn beneath every overlay.
enum BasemapType {
    static let dark = "dark"
    static let osm = "osm"
    static let custom = "custom"
    static let none = "none"
}

struct BasemapSettings: Equatable {
    var current: String = BasemapType.dark
    var customUrl: String = ""
    var customAttribution: String = ""
}

/// How finely a GeoTIFF is decoded: the longest edge, in cells, of the depth grid
/// and of the image drawn on the chart (Android RasterQuality, same values).
enum RasterQuality: String, CaseIterable, Identifiable {
    case battery = "battery"
    case standard = "standard"
    case high = "high"
    case maximum = "maximum"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .battery: return "Battery saver"
        case .standard: return "Standard"
        case .high: return "High"
        case .maximum: return "Maximum"
        }
    }

    var detail: String {
        switch self {
        case .battery: return "older or low-memory phones"
        case .standard: return "most phones"
        case .high: return "sharp — needs more memory"
        case .maximum: return "full detail — recent phones"
        }
    }

    var maxDimension: Int {
        switch self {
        case .battery: return 1024
        case .standard: return 1600
        case .high: return 2560
        case .maximum: return 4096
        }
    }

    /// Starting detail of the 3D view, in cells along the longer side.
    var terrainDetail: Int {
        switch self {
        case .battery: return 256
        case .standard: return 512
        case .high: return 1024
        case .maximum: return 2048
        }
    }

    static var defaultForDevice: RasterQuality {
        let gb = Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824
        if gb < 3 { return .battery }
        return gb >= 6 ? .high : .standard
    }
}

/**
 App-wide settings, saved in UserDefaults under the same keys as Android's
 SharedPreferences, so the meaning of each value matches the Android app.
 */
@MainActor
final class AppSettings: ObservableObject {
    private let defaults: UserDefaults

    @Published var visual: VisualSettings { didSet { saveVisual() } }
    @Published var basemap: BasemapSettings { didSet { saveBasemap() } }
    @Published var rasterQuality: RasterQuality { didSet { defaults.set(rasterQuality.rawValue, forKey: Keys.rasterQuality) } }
    @Published var showWaypointNames: Bool { didSet { defaults.set(showWaypointNames, forKey: Keys.showWaypointNames) } }
    @Published var showGrid: Bool { didSet { defaults.set(showGrid, forKey: Keys.showGrid) } }
    @Published var coordinateMode: String { didSet { defaults.set(coordinateMode, forKey: Keys.coordinateMode) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let mode = defaults.string(forKey: Keys.depthDisplayMode) ?? DepthDisplayMode.both
        visual = VisualSettings(
            cursorColor: defaults.string(forKey: Keys.cursorColor) ?? VisualSettings.defaultAccent,
            coordinateColor: defaults.string(forKey: Keys.coordColor) ?? VisualSettings.defaultAccent,
            depthDisplayMode: DepthDisplayMode.values.contains(mode) ? mode : DepthDisplayMode.both,
            depthTextSize: defaults.object(forKey: Keys.depthTextSize) as? Double ?? 12,
            coordinateTextSize: defaults.object(forKey: Keys.coordTextSize) as? Double ?? 13)
        var base = BasemapSettings(
            current: defaults.string(forKey: Keys.lastBasemap) ?? BasemapType.dark,
            customUrl: defaults.string(forKey: Keys.customBasemapUrl) ?? "",
            customAttribution: defaults.string(forKey: Keys.customBasemapAttribution) ?? "")
        // A saved "custom" selection is meaningless without a URL to load.
        if base.current == BasemapType.custom && base.customUrl.trimmingCharacters(in: .whitespaces).isEmpty {
            base.current = BasemapType.dark
        }
        basemap = base
        rasterQuality = RasterQuality(rawValue: defaults.string(forKey: Keys.rasterQuality) ?? "") ?? .defaultForDevice
        showWaypointNames = defaults.bool(forKey: Keys.showWaypointNames)
        showGrid = defaults.bool(forKey: Keys.showGrid)
        coordinateMode = defaults.string(forKey: Keys.coordinateMode) ?? CoordinateFormatter.modeDDM
    }

    private func saveVisual() {
        defaults.set(visual.cursorColor, forKey: Keys.cursorColor)
        defaults.set(visual.coordinateColor, forKey: Keys.coordColor)
        defaults.set(visual.depthDisplayMode, forKey: Keys.depthDisplayMode)
        defaults.set(visual.depthTextSize, forKey: Keys.depthTextSize)
        defaults.set(visual.coordinateTextSize, forKey: Keys.coordTextSize)
    }

    private func saveBasemap() {
        defaults.set(basemap.current, forKey: Keys.lastBasemap)
        defaults.set(basemap.customUrl, forKey: Keys.customBasemapUrl)
        defaults.set(basemap.customAttribution, forKey: Keys.customBasemapAttribution)
    }

    // Camera ---------------------------------------------------------------

    struct Camera { var lat: Double; var lng: Double; var zoom: Double; var bearing: Double; var tilt: Double }

    /// A first start shows the world: nobody's home water is special.
    static let defaultCamera = Camera(lat: 15, lng: 0, zoom: 1.5, bearing: 0, tilt: 0)

    func loadCamera() -> Camera {
        if let arg = Self.launchArgumentCamera() { return arg }
        guard defaults.object(forKey: Keys.cameraLat) != nil else { return Self.defaultCamera }
        return Camera(lat: defaults.double(forKey: Keys.cameraLat),
                      lng: defaults.double(forKey: Keys.cameraLng),
                      zoom: defaults.double(forKey: Keys.cameraZoom),
                      bearing: defaults.double(forKey: Keys.cameraBearing),
                      tilt: defaults.double(forKey: Keys.cameraTilt))
    }

    func saveCamera(_ c: Camera) {
        defaults.set(c.lat, forKey: Keys.cameraLat)
        defaults.set(c.lng, forKey: Keys.cameraLng)
        defaults.set(c.zoom, forKey: Keys.cameraZoom)
        defaults.set(c.bearing, forKey: Keys.cameraBearing)
        defaults.set(c.tilt, forKey: Keys.cameraTilt)
    }

    /// `--camera=<lat>,<lon>,<zoom>` on launch (used by the CI screenshots).
    private static func launchArgumentCamera() -> Camera? {
        let prefix = "--camera="
        guard let arg = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }) else { return nil }
        let p = arg.dropFirst(prefix.count).split(separator: ",").compactMap { Double(String($0)) }
        guard p.count == 3 else { return nil }
        return Camera(lat: p[0], lng: p[1], zoom: p[2], bearing: 0, tilt: 0)
    }

    private enum Keys {
        static let cursorColor = "cursor_color"
        static let coordColor = "coord_color"
        static let depthDisplayMode = "depth_display_mode"
        static let depthTextSize = "depth_text_size"
        static let coordTextSize = "coord_text_size"
        static let lastBasemap = "last_basemap"
        static let customBasemapUrl = "custom_basemap_url"
        static let customBasemapAttribution = "custom_basemap_attribution"
        static let cameraLat = "camera_lat"
        static let cameraLng = "camera_lng"
        static let cameraZoom = "camera_zoom"
        static let cameraBearing = "camera_bearing"
        static let cameraTilt = "camera_tilt"
        static let showWaypointNames = "show_waypoint_names"
        static let rasterQuality = "raster_quality"
        static let showGrid = "show_grid"
        static let coordinateMode = "coordinate_mode"
    }
}
