import Foundation

// Shared data types, ported from the Android app's `model` package.
// Field names match the Android JSON so saved data and GPX/exports line up.

/// An axis-aligned geographic bounding box in WGS84 degrees.
struct GeoBounds: Codable, Equatable, Hashable {
    var west: Double
    var south: Double
    var east: Double
    var north: Double

    var isValid: Bool {
        east > west && north > south && west >= -180 && east <= 180 && south >= -90 && north <= 90
    }

    func contains(lat: Double, lng: Double) -> Bool {
        lat >= south && lat <= north && lng >= west && lng <= east
    }

    var centerLat: Double { (south + north) / 2 }
    var centerLng: Double { (west + east) / 2 }
}

/**
 Colour-ramp configuration for a depth raster.

 Depths are negative-down metres (bathymetric GeoTIFF convention), so `shallowMetres`
 is the value closest to the surface and numerically greater than `deepMetres`.
 */
struct DepthRampSettings: Codable, Equatable, Hashable {
    var paletteId: String = DepthRampSettings.defaultPaletteId
    var shallowMetres: Double = DepthRampSettings.defaultShallowMetres
    var deepMetres: Double = DepthRampSettings.defaultDeepMetres
    var invert: Bool = false
    /// Depths outside the range take the end colours instead of being hidden.
    var showOutside: Bool = false
    /// Slope-shading strength (name kept from Android's saved layers).
    var hillshadeExaggeration: Double = DepthRampSettings.defaultHillshadeExaggeration

    static let defaultPaletteId = "rainbow"
    static let defaultShallowMetres = -6.0
    static let defaultDeepMetres = -100.0
    static let defaultHillshadeExaggeration = 0.6
    static let minHillshadeExaggeration = 0.0
    static let maxHillshadeExaggeration = 5.0

    /// Normalises the pair so shallow is always the surface-most value.
    func normalised() -> DepthRampSettings {
        if shallowMetres >= deepMetres { return self }
        var copy = self
        copy.shallowMetres = deepMetres
        copy.deepMetres = shallowMetres
        return copy
    }

    var spanMetres: Double { shallowMetres - deepMetres }

    static func coerceHillshadeExaggeration(_ value: Double) -> Double {
        min(max(value, minHillshadeExaggeration), maxHillshadeExaggeration)
    }

    init(paletteId: String = DepthRampSettings.defaultPaletteId,
         shallowMetres: Double = DepthRampSettings.defaultShallowMetres,
         deepMetres: Double = DepthRampSettings.defaultDeepMetres,
         invert: Bool = false,
         showOutside: Bool = false,
         hillshadeExaggeration: Double = DepthRampSettings.defaultHillshadeExaggeration) {
        self.paletteId = paletteId
        self.shallowMetres = shallowMetres
        self.deepMetres = deepMetres
        self.invert = invert
        self.showOutside = showOutside
        self.hillshadeExaggeration = hillshadeExaggeration
    }

    // Tolerant decoding: a missing key keeps its default (as Gson does on Android).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        paletteId = try c.decodeIfPresent(String.self, forKey: .paletteId) ?? Self.defaultPaletteId
        shallowMetres = try c.decodeIfPresent(Double.self, forKey: .shallowMetres) ?? Self.defaultShallowMetres
        deepMetres = try c.decodeIfPresent(Double.self, forKey: .deepMetres) ?? Self.defaultDeepMetres
        invert = try c.decodeIfPresent(Bool.self, forKey: .invert) ?? false
        showOutside = try c.decodeIfPresent(Bool.self, forKey: .showOutside) ?? false
        hillshadeExaggeration = try c.decodeIfPresent(Double.self, forKey: .hillshadeExaggeration) ?? Self.defaultHillshadeExaggeration
    }
}

/// The kind of data backing an overlay entry in the layer stack.
enum LayerKind: String, Codable {
    /// An imported MBTiles archive held in app storage.
    case mbtiles = "MBTILES"
    /// An imported GeoTIFF depth raster.
    case geotiff = "GEOTIFF"
    /// A remote XYZ raster tile template.
    case xyz = "XYZ"
}

/**
 One layer in the stack. `source` is a file path (relative to the app's documents
 folder for files the app owns) or a tile link for `.xyz`. `ramp` is for GeoTIFF
 depth layers only. `maxZoom` is the most detail there is.
 */
struct OverlayLayer: Codable, Identifiable, Equatable {
    var id: String
    var kind: LayerKind
    var name: String
    var source: String
    var visible: Bool = true
    var opacity: Double = 1
    var attribution: String = ""
    var ramp: DepthRampSettings? = nil
    var maxZoom: Int = OverlayLayer.defaultMaxZoom

    static let defaultMaxZoom = 22

    var sanitisedOpacity: Double { min(max(opacity, 0), 1) }
    var sanitisedMaxZoom: Double { Double(min(max(maxZoom, 1), OverlayLayer.defaultMaxZoom)) }
}

/// A vertex of a planned route or a measurement. `name` is set when it came from a waypoint.
struct RoutePoint: Codable, Equatable, Hashable {
    var lat: Double
    var lng: Double
    var name: String? = nil
}

/// A user-placed mark on the chart.
struct Waypoint: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var lat: Double
    var lng: Double
    var color: String = Waypoint.defaultColor
    var desc: String? = nil
    /// One of `WaypointIcon.all`'s ids.
    var icon: String = WaypointIcon.defaultIconId

    static let defaultColor = "#EF4444"
    static let palette = ["#EF4444", "#F97316", "#EAB308", "#22C55E", "#3B82F6", "#A855F7"]

    init(id: String, name: String, lat: Double, lng: Double, color: String = Waypoint.defaultColor,
         desc: String? = nil, icon: String = WaypointIcon.defaultIconId) {
        self.id = id; self.name = name; self.lat = lat; self.lng = lng
        self.color = color; self.desc = desc; self.icon = icon
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        lat = try c.decode(Double.self, forKey: .lat)
        lng = try c.decode(Double.self, forKey: .lng)
        color = try c.decodeIfPresent(String.self, forKey: .color) ?? Waypoint.defaultColor
        desc = try c.decodeIfPresent(String.self, forKey: .desc)
        icon = try c.decodeIfPresent(String.self, forKey: .icon) ?? WaypointIcon.defaultIconId
    }
}

/// A waypoint symbol, drawn white on the waypoint's coloured dot. `symbol` is an SF Symbol name.
struct WaypointIcon: Identifiable, Hashable {
    let id: String
    let label: String
    let symbol: String

    static let defaultIconId = "marker"

    static let all: [WaypointIcon] = [
        WaypointIcon(id: "marker", label: "Marker", symbol: "mappin"),
        WaypointIcon(id: "diamond", label: "Diamond", symbol: "diamond.fill"),
        WaypointIcon(id: "flag", label: "Flag", symbol: "flag.fill"),
        WaypointIcon(id: "anchor", label: "Anchor", symbol: "anchor"),
        WaypointIcon(id: "fish", label: "Fishing spot", symbol: "fish.fill"),
        WaypointIcon(id: "wreck", label: "Wreck", symbol: "xmark.octagon.fill"),
        WaypointIcon(id: "mob", label: "Man overboard", symbol: "figure.wave"),
    ]

    static func byId(_ id: String?) -> WaypointIcon {
        all.first { $0.id == id } ?? all[0]
    }
}

/// An online `{z}/{x}/{y}` tile source that an area can be downloaded from as MBTiles.
struct DataSource: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var urlTemplate: String
    var attribution: String = ""
    var builtIn: Bool = false

    /// EOX's Sentinel-2 cloudless (CC BY-NC-SA 4.0 for the 2024 edition).
    static let defaultPreset = DataSource(
        id: "eox-s2cloudless",
        name: "Sentinel-2 cloudless (EOX)",
        urlTemplate: "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg",
        attribution: "Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)",
        builtIn: true)
}

/**
 The depths behind one depth layer. `sampleMetres` returns the stored depth
 (negative-down metres) at a point, or nil outside coverage. Implemented by the
 GeoTIFF engine (`DepthGrid`).
 */
protocol DepthSource: AnyObject {
    var bounds: GeoBounds { get }
    var shallowestMetres: Double { get }
    var deepestMetres: Double { get }
    var hasData: Bool { get }
    func sampleMetres(lat: Double, lng: Double) -> Double?
}

/**
 A map that can be laid over the 3D terrain (Android terrain/TerrainDrape.kt): an
 offline pack whose tiles come from `readTile`, or an online tile link
 (`urlTemplate`, `{z}/{x}/{y}`). Produced by LayersFeature, used by TerrainFeature.
 */
final class TerrainDrape {
    enum Group: String { case defaultMap = "default", layer = "layer", custom = "custom" }

    let id: String
    let label: String
    let minZoom: Int
    let maxZoom: Int
    let urlTemplate: String?
    let group: Group
    let attribution: String
    /// Where the map has tiles, when known: an offline pack's area.
    let bounds: GeoBounds?
    /// The swatch colour in the Map list, "#RRGGBB"; nil lets the page pick one.
    let colour: String?
    /// One XYZ-addressed tile's image bytes, or nil where there is none. Called off the main thread.
    let readTile: ((Int, Int, Int) -> Data?)?

    init(id: String, label: String, minZoom: Int, maxZoom: Int, urlTemplate: String? = nil, group: Group = .layer,
         attribution: String = "", bounds: GeoBounds? = nil, colour: String? = nil,
         readTile: ((Int, Int, Int) -> Data?)? = nil) {
        self.id = id; self.label = label; self.minZoom = minZoom; self.maxZoom = maxZoom
        self.urlTemplate = urlTemplate; self.group = group; self.attribution = attribution
        self.bounds = bounds; self.colour = colour; self.readTile = readTile
    }

    var isOffline: Bool { readTile != nil }

    /// "Save this area offline" is offered only for online maps whose provider allows offline copies.
    var isSavable: Bool {
        guard let urlTemplate else { return false }
        return TerrainDrape.allowsOfflineCopies(urlTemplate)
    }

    /// Tile hosts whose terms don't allow bulk or offline copies.
    private static let viewOnlyHosts = ["arcgisonline.com", "arcgis.com", "openstreetmap.org", "opentopomap.org"]

    static func allowsOfflineCopies(_ urlTemplate: String) -> Bool {
        guard let range = urlTemplate.range(of: "://") else { return true }
        var host = String(urlTemplate[range.upperBound...])
        if let slash = host.firstIndex(of: "/") { host = String(host[..<slash]) }
        if let colon = host.firstIndex(of: ":") { host = String(host[..<colon]) }
        host = host.lowercased()
        return !viewOnlyHosts.contains { host == $0 || host.hasSuffix("." + $0) }
    }
}
