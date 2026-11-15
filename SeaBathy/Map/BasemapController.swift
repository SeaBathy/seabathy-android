import Foundation
import MapLibre

/**
 Owns every basemap and style change (Android MapStyleCoordinator + MapStyleFactory).

 MapLibre discards custom sources and layers when the style changes, so every
 change goes through `rebuild`; features re-add their layers in their
 `map.onStyleLoaded` handlers. With no internet the online style can only fail
 after a long timeout, so the blank offline canvas is used straight away and the
 basemap comes back when the connection does.
 */
@MainActor
final class BasemapController: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var currentType: String
    @Published private(set) var usingOfflineFallback = false
    private var pendingIsRemote = false

    /// Credits for data drawn outside the overlay stack, gathered from features.
    private var extraCreditProviders: [() -> [String]] = []

    static let darkStyle = URL(string: "https://tiles.openfreemap.org/styles/dark")!
    static let osmStyle = URL(string: "https://tiles.openfreemap.org/styles/liberty")!
    static let emptyBackground = "#111418"
    /// Bundled glyphs (Resources/glyphs) so the app's own labels draw on the None/Custom/offline styles.
    static let bundledGlyphs = "asset://glyphs/{fontstack}/{range}.pbf"

    static let options: [(type: String, label: String)] = [
        (BasemapType.dark, "Dark"), (BasemapType.osm, "OpenStreetMap"),
        (BasemapType.custom, "Custom"), (BasemapType.none, "None")
    ]

    init(app: AppModel) {
        self.app = app
        currentType = app.settings.basemap.current
    }

    var customUrl: String { app.settings.basemap.customUrl }
    var customAttribution: String { app.settings.basemap.customAttribution }
    var customBasemapConfigured: Bool { !customUrl.trimmingCharacters(in: .whitespaces).isEmpty }

    /// The style the map view is created with (before `start`).
    var initialStyleURL: URL {
        let type = resolve(currentType, quiet: true)
        if Self.requiresNetwork(type) && !app.isOnline { return Self.writeStyle(Self.emptyStyleJSON(), name: "offline") }
        return styleURL(for: type)
    }

    func start() {
        pendingIsRemote = Self.requiresNetwork(currentType)
        app.map.onStyleFailed { [weak self] in self?.handleStyleLoadFailure() }
        app.map.onStyleLoaded { [weak self] _ in self?.refreshAttribution() }
        app.onConnectivityRestored { [weak self] in self?.onConnectivityRestored() }
    }

    /// Registers credits that appear on the credits line (sea conditions, nav marks).
    func addCredits(_ provider: @escaping () -> [String]) { extraCreditProviders.append(provider) }

    // Switching ------------------------------------------------------------------------

    func select(_ requested: String) {
        rebuild(requested)
    }

    func saveCustomBasemap(url: String, attribution: String) {
        app.settings.basemap.customUrl = url
        app.settings.basemap.customAttribution = attribution
    }

    func clearCustomBasemap() {
        app.settings.basemap.customUrl = ""
        app.settings.basemap.customAttribution = ""
        if currentType == BasemapType.custom { rebuild(BasemapType.dark) } else { refreshAttribution() }
    }

    func rebuild(_ requested: String? = nil) {
        let type = resolve(requested ?? currentType, quiet: false)
        currentType = type
        app.settings.basemap.current = type
        pendingIsRemote = Self.requiresNetwork(type)
        DiagnosticLog.log("Style", "rebuild: type=\(type) remote=\(pendingIsRemote)")
        if pendingIsRemote && !app.isOnline {
            usingOfflineFallback = true
            app.toast("No internet — showing your own layers. The online basemap comes back when you're connected.")
            app.map.setStyle(url: Self.writeStyle(Self.emptyStyleJSON(), name: "offline"))
            return
        }
        usingOfflineFallback = false
        app.map.setStyle(url: styleURL(for: type))
    }

    private func handleStyleLoadFailure() {
        guard pendingIsRemote, !usingOfflineFallback else { return }
        usingOfflineFallback = true
        app.toast("Couldn't load the online basemap (no internet, or the basemap address isn't working) — showing offline layers only")
        app.map.setStyle(url: Self.writeStyle(Self.emptyStyleJSON(), name: "offline"))
    }

    private func onConnectivityRestored() {
        guard usingOfflineFallback, Self.requiresNetwork(currentType) else { return }
        DiagnosticLog.log("Style", "connectivity restored: reloading \(currentType)")
        rebuild(currentType)
    }

    private func resolve(_ requested: String, quiet: Bool) -> String {
        if requested != BasemapType.custom || customBasemapConfigured { return requested }
        if !quiet { app.toast("Set a custom basemap link in Settings first") }
        return currentType == BasemapType.custom ? BasemapType.dark : currentType
    }

    // Attribution ----------------------------------------------------------------------

    func refreshAttribution() {
        var credits: [String] = []
        if let base = baseAttribution() { credits.append(base) }
        credits += app.layers?.visibleAttributions() ?? []
        for provider in extraCreditProviders { credits += provider() }
        var seen = Set<String>()
        app.attributionText = credits.filter { !$0.isEmpty && seen.insert($0).inserted }.joined(separator: " • ")
    }

    private func baseAttribution() -> String? {
        if usingOfflineFallback { return nil }
        switch currentType {
        case BasemapType.dark, BasemapType.osm:
            return "OpenFreeMap © OpenMapTiles • © OpenStreetMap contributors"
        case BasemapType.custom:
            var text = customAttribution.trimmingCharacters(in: .whitespaces)
            if text.isEmpty { text = "Custom online tiles" }
            if customUrl.range(of: "arcgis", options: .caseInsensitive) != nil && !text.contains("Powered by Esri") {
                text += " · Powered by Esri"
            }
            return text
        default:
            return nil
        }
    }

    /// The font for the app's own labels: each style's server has its own, and a missing one draws nothing.
    var labelFont: String {
        (currentType == BasemapType.dark || currentType == BasemapType.osm) && !usingOfflineFallback
            ? "Noto Sans Regular" : "Open Sans Regular"
    }

    // Styles -------------------------------------------------------------------------

    static func requiresNetwork(_ type: String) -> Bool { type == BasemapType.dark || type == BasemapType.osm }

    private func styleURL(for type: String) -> URL {
        switch type {
        case BasemapType.dark: return Self.darkStyle
        case BasemapType.osm: return Self.osmStyle
        case BasemapType.custom where customBasemapConfigured:
            return Self.writeStyle(Self.customStyleJSON(customUrl), name: "custom")
        default:
            return Self.writeStyle(Self.emptyStyleJSON(), name: "none")
        }
    }

    static func emptyStyleJSON() -> [String: Any] {
        ["version": 8, "glyphs": bundledGlyphs, "sources": [String: Any](),
         "layers": [["id": "bg", "type": "background", "paint": ["background-color": emptyBackground]]]]
    }

    static func customStyleJSON(_ urlTemplate: String) -> [String: Any] {
        ["version": 8, "glyphs": bundledGlyphs,
         "sources": ["custom": ["type": "raster", "tiles": [urlTemplate], "tileSize": 256, "maxzoom": 22]],
         "layers": [["id": "bg", "type": "background", "paint": ["background-color": emptyBackground]],
                    ["id": "custom", "type": "raster", "source": "custom"]]]
    }

    /// MLNMapView loads styles by URL, so local styles are written to a file first.
    /// A fresh file name each time makes MapLibre reload even when the content is unchanged.
    static func writeStyle(_ json: [String: Any], name: String) -> URL {
        let folder = AppPaths.caches.appendingPathComponent("styles", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if let old = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
            old.filter { $0.lastPathComponent.hasPrefix(name + "-") }.forEach { try? FileManager.default.removeItem(at: $0) }
        }
        let url = folder.appendingPathComponent("\(name)-\(UUID().uuidString.prefix(8)).json")
        if let data = try? JSONSerialization.data(withJSONObject: json) { try? data.write(to: url, options: .atomic) }
        return url
    }
}
