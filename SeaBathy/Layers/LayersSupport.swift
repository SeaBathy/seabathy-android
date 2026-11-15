import Foundation

// Pure logic behind the layers feature: tile maths, presets, the download
// estimate, retries, the seabed area, file names. No map, no UI, so unit tested.

/// A user-facing failure from the layers code; the message was written for the user.
struct LayersError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Turns a failure into text that is safe to show (Android util/UserFacingErrors).
enum LayersErrorText {
    static func describe(_ error: Error, fallback: String) -> String {
        if let own = error as? LayersError { return own.message.isEmpty ? fallback : own.message }
        if error is GeoTiffImportError {
            let text = error.localizedDescription
            return text.isEmpty ? fallback : text
        }
        if let url = error as? URLError {
            switch url.code {
            case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .timedOut,
                 .networkConnectionLost, .dnsLookupFailed:
                return "Couldn't reach the server. Check your internet connection and try again."
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
                 .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
                return "Couldn't open a secure connection to the server. Check the device's date and time, then try again."
            default:
                return fallback
            }
        }
        let ns = error as NSError
        if ns.domain == NSCocoaErrorDomain {
            if ns.code == NSFileWriteOutOfSpaceError {
                return "There isn't enough free storage on this device. Free up some space and try again."
            }
            if ns.code == NSFileNoSuchFileError || ns.code == NSFileReadNoSuchFileError {
                return "The file couldn't be found. It may have been moved, renamed or deleted."
            }
        }
        return fallback
    }
}

// MARK: - Web Mercator tile ranges (Android tiles/WebMercatorTiles)

enum LayersTiles {
    static let tileSize = 256
    static let maxSaneZoom = 22
    private static let earthRadius = 6378137.0
    private static let halfWorld = Double.pi * earthRadius
    private static let mercatorLatLimit = 85.05112878
    private static let edgeEpsilon = 1e-3

    struct TileRange: Equatable {
        let z: Int, minX: Int, maxX: Int, minY: Int, maxY: Int
        var tileCount: Int64 { Int64(maxX - minX + 1) * Int64(maxY - minY + 1) }
    }

    static func forward(lat: Double, lng: Double) -> (x: Double, y: Double) {
        let clamped = min(max(lat, -mercatorLatLimit), mercatorLatLimit)
        return (earthRadius * lng * .pi / 180, earthRadius * log(tan(.pi / 4 + clamped * .pi / 180 / 2)))
    }

    static func inverse(x: Double, y: Double) -> (lat: Double, lng: Double)? {
        let lng = x / earthRadius * 180 / .pi
        let lat = (2 * atan(exp(y / earthRadius)) - .pi / 2) * 180 / .pi
        if abs(lng) > 180 || abs(lat) > 90 { return nil }
        return (lat, lng)
    }

    /// The tile's WGS84 bounds, or nil when out of range for `z`.
    static func tileBounds(z: Int, x: Int, y: Int) -> GeoBounds? {
        guard z >= 0, z <= maxSaneZoom else { return nil }
        let n = 1 << z
        guard x >= 0, y >= 0, x < n, y < n else { return nil }
        let size = 2 * halfWorld / Double(n)
        let minX = -halfWorld + Double(x) * size
        let maxX = minX + size
        let maxY = halfWorld - Double(y) * size
        let minY = maxY - size
        guard let sw = inverse(x: minX + edgeEpsilon, y: minY + edgeEpsilon),
              let ne = inverse(x: maxX - edgeEpsilon, y: maxY - edgeEpsilon) else { return nil }
        return GeoBounds(west: sw.lng, south: sw.lat, east: ne.lng, north: ne.lat)
    }

    /// The tiles at zoom `z` that overlap `bounds`.
    static func tileRangeCovering(_ bounds: GeoBounds, z: Int) -> TileRange {
        let n = max(1, 1 << z)
        let maxIndex = n - 1
        let size = 2 * halfWorld / Double(n)
        func xAt(_ lng: Double) -> Int {
            let x = forward(lat: 0, lng: lng).x
            return min(max(Int((x + halfWorld) / size), 0), maxIndex)
        }
        func yAt(_ lat: Double) -> Int {
            let y = forward(lat: lat, lng: 0).y
            return min(max(Int((halfWorld - y) / size), 0), maxIndex)
        }
        let xw = xAt(bounds.west), xe = xAt(bounds.east)
        let yn = yAt(bounds.north), ys = yAt(bounds.south)
        return TileRange(z: z, minX: min(xw, xe), maxX: max(xw, xe), minY: min(yn, ys), maxY: max(yn, ys))
    }
}

/// One tile address, XYZ (y = 0 at the north).
struct LayersTileCoord: Hashable {
    let z: Int, x: Int, y: Int
}

// MARK: - Download size estimate (Android download/OfflineDownloadEstimator)

enum OfflineDownloadEstimator {
    static let assumedAverageTileBytes: Int64 = 25_000

    struct TileEstimate { let tileCount: Int64; let estimatedBytes: Int64 }

    static func estimateTiles(_ bounds: GeoBounds, zoomRange: ClosedRange<Int>,
                              averageTileBytes: Int64 = assumedAverageTileBytes) -> TileEstimate {
        let count = zoomRange.reduce(Int64(0)) { $0 + LayersTiles.tileRangeCovering(bounds, z: $1).tileCount }
        return TileEstimate(tileCount: count, estimatedBytes: count * averageTileBytes)
    }

    /// Every tile in the range: the one list both the size check and the download use.
    static func tilesToFetch(_ bounds: GeoBounds, zoomRange: ClosedRange<Int>) -> [LayersTileCoord] {
        var out: [LayersTileCoord] = []
        for z in zoomRange {
            let r = LayersTiles.tileRangeCovering(bounds, z: z)
            for x in r.minX...r.maxX {
                for y in r.minY...r.maxY { out.append(LayersTileCoord(z: z, x: x, y: y)) }
            }
        }
        return out
    }

    static func formatBytes(_ bytes: Int64) -> String {
        let kb = 1024.0, mb = kb * 1024, gb = mb * 1024
        let b = Double(bytes)
        if b >= gb { return String(format: "%.1f GB", b / gb) }
        if b >= mb { return String(format: "%.0f MB", b / mb) }
        if b >= kb { return String(format: "%.0f KB", b / kb) }
        return "\(bytes) bytes"
    }

    static func formatTileCount(_ count: Int64) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.groupingSeparator = ","
        f.usesGroupingSeparator = true
        return f.string(from: NSNumber(value: count)) ?? "\(count)"
    }
}

// MARK: - Retries (Android download/RetryingFetcher)

enum FetchOutcome {
    case success(Data)
    /// No tile there (404 and other client errors): not retried.
    case permanent
    /// Network trouble, 429 or 5xx: worth another go.
    case retryable
}

/// Retries a fetch while it reports `.retryable`, with a short backoff.
/// `shouldStop` is checked before each try and wait, so a cancelled download stops at once.
final class RetryingFetcher {
    static let defaultMaxAttempts = 3
    static let defaultBackoffMillis: UInt64 = 300

    private let maxAttempts: Int
    private let backoffMillis: (Int) -> UInt64
    private let sleep: (UInt64) async -> Void
    private let shouldStop: () -> Bool
    private let attempt: (String) async -> FetchOutcome

    init(maxAttempts: Int = RetryingFetcher.defaultMaxAttempts,
         backoffMillis: @escaping (Int) -> UInt64 = { UInt64($0) * RetryingFetcher.defaultBackoffMillis },
         sleep: @escaping (UInt64) async -> Void = { ms in try? await Task.sleep(nanoseconds: ms * 1_000_000) },
         shouldStop: @escaping () -> Bool = { false },
         attempt: @escaping (String) async -> FetchOutcome) {
        self.maxAttempts = maxAttempts
        self.backoffMillis = backoffMillis
        self.sleep = sleep
        self.shouldStop = shouldStop
        self.attempt = attempt
    }

    func fetch(_ url: String) async -> Data? {
        guard maxAttempts >= 1 else { return nil }
        for attemptNumber in 1...maxAttempts {
            if shouldStop() { return nil }
            switch await attempt(url) {
            case .success(let data): return data
            case .permanent: return nil
            case .retryable:
                if attemptNumber < maxAttempts && !shouldStop() { await sleep(backoffMillis(attemptNumber)) }
            }
        }
        return nil
    }
}

/// Maps `items` with `transform`, at most `concurrency` at a time; results keep the order of `items`.
func layersBoundedConcurrentMap<T, R>(_ items: [T], concurrency: Int,
                                      _ transform: @escaping (T) async -> R) async -> [R] {
    if items.isEmpty { return [] }
    let limit = max(1, concurrency)
    var results = [R?](repeating: nil, count: items.count)
    await withTaskGroup(of: (Int, R).self) { group in
        var next = 0
        while next < min(limit, items.count) {
            let index = next
            group.addTask { (index, await transform(items[index])) }
            next += 1
        }
        while let (index, value) = await group.next() {
            results[index] = value
            if next < items.count {
                let i = next
                group.addTask { (i, await transform(items[i])) }
                next += 1
            }
        }
    }
    return results.map { $0! }
}

// MARK: - Sample tiles for the size check (Android download/TileSampler)

final class TileSampler {
    static let defaultSampleCount = 8
    private let fetch: (String) async -> Data?

    init(fetch: @escaping (String) async -> Data?) { self.fetch = fetch }

    struct Result {
        let sampledSizes: [Int]
        /// Nil only when every sample failed.
        var averageBytes: Int64? {
            sampledSizes.isEmpty ? nil : Int64(Double(sampledSizes.reduce(0, +)) / Double(sampledSizes.count))
        }
    }

    func sample(urlTemplate: String, bounds: GeoBounds, zoomRange: ClosedRange<Int>,
                sampleCount: Int = TileSampler.defaultSampleCount) async -> Result {
        let all = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange: zoomRange)
        var byZoom: [Int: [LayersTileCoord]] = [:]
        var order: [Int] = []
        for t in all {
            if byZoom[t.z] == nil { order.append(t.z) }
            byZoom[t.z, default: []].append(t)
        }
        if order.isEmpty { return Result(sampledSizes: []) }
        let perZoom = max(1, sampleCount / order.count)
        var sizes: [Int] = []
        for z in order {
            for t in Self.spreadPicks(byZoom[z] ?? [], count: perZoom) {
                if let data = await fetch(Self.urlFor(urlTemplate, z: t.z, x: t.x, y: t.y)) { sizes.append(data.count) }
            }
        }
        return Result(sampledSizes: sizes)
    }

    static func spreadPicks<T>(_ candidates: [T], count: Int) -> [T] {
        if candidates.isEmpty || count <= 0 { return [] }
        let n = min(count, candidates.count)
        return (0..<n).map { candidates[($0 * candidates.count) / n] }
    }

    /// Substitutes by token, so `{z}/{y}/{x}` templates work too.
    static func urlFor(_ template: String, z: Int, x: Int, y: Int) -> String {
        template.replacingOccurrences(of: "{z}", with: String(z))
            .replacingOccurrences(of: "{x}", with: String(x))
            .replacingOccurrences(of: "{y}", with: String(y))
    }
}

/// A link safe for the shareable debug log: no query string or user-info (API keys live there).
func layersRedactUrl(_ url: String) -> String {
    var withoutQuery = url
    if let q = withoutQuery.firstIndex(of: "?") { withoutQuery = String(withoutQuery[..<q]) }
    if let h = withoutQuery.firstIndex(of: "#") { withoutQuery = String(withoutQuery[..<h]) }
    guard let schemeRange = withoutQuery.range(of: "://") else { return withoutQuery }
    let scheme = String(withoutQuery[..<schemeRange.lowerBound])
    if scheme.isEmpty { return withoutQuery }
    let rest = String(withoutQuery[schemeRange.upperBound...])
    let authority: String
    let path: String
    if let slash = rest.firstIndex(of: "/") {
        authority = String(rest[..<slash]); path = String(rest[slash...])
    } else {
        authority = rest; path = ""
    }
    let host = authority.components(separatedBy: "@").last ?? authority
    let redacted = url.count != withoutQuery.count ? "\(path)?…" : path
    return "\(scheme)://\(host)\(redacted)"
}

// MARK: - XYZ presets (Android settings/XyzTilePresets)

struct XyzTilePreset: Identifiable, Equatable {
    enum Category { case satellite, topographic, street, nautical
        var label: String {
            switch self {
            case .satellite: return "Satellite imagery"
            case .topographic: return "Topographic"
            case .street: return "Street map"
            case .nautical: return "Nautical"
            }
        }
    }
    let id: String
    let label: String
    let description: String
    let urlTemplate: String
    let attribution: String
    let category: Category
    var maxZoom: Int = 19
}

enum XyzTilePresets {
    private static let gibsCredit = "Imagery: NASA Global Imagery Browse Services (GIBS), part of NASA's ESDIS"

    private static func gibsTrueColour(_ layer: String) -> String {
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/\(layer)/default/default/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpg"
    }

    static let all: [XyzTilePreset] = [
        XyzTilePreset(id: "eox_s2cloudless", label: "Sentinel-2 satellite",
                      description: "Cloud-free satellite picture of the world, 10 m detail (EOX, 2024). Can be saved offline",
                      urlTemplate: "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg",
                      attribution: "Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)",
                      category: .satellite, maxZoom: 15),
        XyzTilePreset(id: "gibs_true_colour_n20", label: "Latest true colour (NOAA-20)",
                      description: "Yesterday or today's satellite pass, about 250 m: water colour, clouds, river plumes. Worldwide",
                      urlTemplate: gibsTrueColour("VIIRS_NOAA20_CorrectedReflectance_TrueColor"),
                      attribution: gibsCredit, category: .satellite, maxZoom: 9),
        XyzTilePreset(id: "gibs_true_colour_n21", label: "Latest true colour (NOAA-21)",
                      description: "The same from another satellite, passing at another time: try it if clouds hide the first",
                      urlTemplate: gibsTrueColour("VIIRS_NOAA21_CorrectedReflectance_TrueColor"),
                      attribution: gibsCredit, category: .satellite, maxZoom: 9),
        XyzTilePreset(id: "usgs_imagery", label: "USGS Imagery",
                      description: "Public domain aerial imagery, best over the USA",
                      urlTemplate: "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}",
                      attribution: "USGS The National Map", category: .satellite, maxZoom: 16),
        XyzTilePreset(id: "openstreetmap", label: "OpenStreetMap", description: "Standard OSM street map",
                      urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                      attribution: "(c) OpenStreetMap contributors", category: .street, maxZoom: 19),
        XyzTilePreset(id: "opentopomap", label: "OpenTopoMap", description: "Topographic contours and hillshading",
                      urlTemplate: "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                      attribution: "(c) OpenStreetMap contributors, SRTM, OpenTopoMap (CC-BY-SA)",
                      category: .topographic, maxZoom: 17),
    ]

    static var defaultSatellite: XyzTilePreset { all.first { $0.id == "eox_s2cloudless" }! }
    static var satellite: [XyzTilePreset] { all.filter { $0.category == .satellite } }
    static func byId(_ id: String?) -> XyzTilePreset? { all.first { $0.id == id } }

    /// MapLibre substitutes {z}, {x} and {y}; a template missing one requests nothing.
    static func validationError(_ urlTemplate: String) -> String? {
        let url = urlTemplate.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = url.lowercased()
        if url.isEmpty { return "Enter an XYZ tile URL" }
        if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") { return "URL must start with http:// or https://" }
        if !url.contains("{z}") || !url.contains("{x}") || !url.contains("{y}") { return "URL must contain {z}, {x} and {y}" }
        return nil
    }

    /// Imagery goes on part-transparent so reef detail blends with depths underneath.
    static let satelliteDefaultOpacity = 0.7
}

// MARK: - Seabed depths (Android storage/SeabedDownload)

enum SeabedDownload {
    static let credit = "Depths: GMRT, Ryan et al. 2009 (CC BY 4.0)"
    static let maxSpanKm = 440
    private static let maxSpanDeg = 4.0
    private static let minSpanDeg = 0.25
    private static let maxLat = 85.0
    private static let maxLng = 180.0

    struct Area: Equatable {
        let south: Double, west: Double, north: Double, east: Double, trimmed: Bool

        /// The same area gets the same file.
        var name: String {
            let lat = (south + north) / 2, lng = (west + east) / 2
            return String(format: "Seabed %.2f%@ %.2f%@ (%.2f°)", abs(lat), lat < 0 ? "S" : "N",
                          abs(lng), lng < 0 ? "W" : "E", max(north - south, east - west))
        }
    }

    static func areaFor(south: Double, west: Double, north: Double, east: Double,
                        centreLat: Double, centreLng: Double) -> Area {
        var w = west, e = east
        if e < w || e - w > 180 {
            if centreLng >= 0 { e = maxLng } else { w = -maxLng }
        }
        let trimmed = north - south > maxSpanDeg || e - w > maxSpanDeg
        func fit(_ lo: Double, _ hi: Double, _ centre: Double) -> (Double, Double) {
            let span = min(max(hi - lo, minSpanDeg), maxSpanDeg)
            let middle = hi - lo > maxSpanDeg ? centre : (lo + hi) / 2
            return (middle - span / 2, middle + span / 2)
        }
        let (s, n) = fit(south, north, centreLat)
        let (a, b) = fit(w, e, centreLng)
        return Area(south: max(s, -maxLat), west: max(a, -maxLng), north: min(n, maxLat), east: min(b, maxLng), trimmed: trimmed)
    }

    static func url(_ area: Area) -> String {
        String(format: "https://www.gmrt.org/services/GridServer?north=%.5f&south=%.5f&east=%.5f&west=%.5f&layer=topo&format=geotiff&resolution=high",
               area.north, area.south, area.east, area.west)
    }

    static func looksLikeTiff(_ head: Data) -> Bool {
        guard head.count >= 4 else { return false }
        let b = [UInt8](head.prefix(4))
        return (b[0] == 0x49 && b[1] == 0x49 && b[2] == 42) || (b[0] == 0x4D && b[1] == 0x4D && b[3] == 42)
    }
}

// MARK: - File names (Android FilenamePolicy, download file helpers)

enum LayersFileNames {
    /// An untrusted display name reduced to a safe file name. Spaces become "_" too,
    /// since MBTiles files are opened through an `mbtiles://` link.
    static func safeBaseName(_ value: String, fallback: String = "layer") -> String {
        var out = ""
        for scalar in value.unicodeScalars {
            let c = Character(scalar)
            if scalar.isASCII, c.isLetter || c.isNumber || c == "." || c == "_" || c == "-" { out.append(c) } else { out.append("_") }
        }
        out = out.trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        if out.count > 100 { out = String(out.prefix(100)) }
        return out.isEmpty ? fallback : out
    }

    static func timestampedFallback(_ prefix: String) -> String {
        "\(prefix)-\(Int64(Date().timeIntervalSince1970 * 1000))"
    }

    /// `<base>.<ext>` in `dir`, or the first free `<base>-2.<ext>`, `-3`...
    static func uniqueFile(in dir: URL, base: String, ext: String) -> URL {
        let fm = FileManager.default
        var candidate = dir.appendingPathComponent("\(base).\(ext)")
        var n = 2
        while fm.fileExists(atPath: candidate.path) {
            candidate = dir.appendingPathComponent("\(base)-\(n).\(ext)")
            n += 1
        }
        return candidate
    }

    /// Where a download into `destination` is written until it completes.
    static func partFile(for destination: URL) -> URL { URL(fileURLWithPath: destination.path + ".part") }

    /// `<base>.mbtiles`, or the first free `<base>_2.mbtiles`... skipping names with a journal or part file.
    static func freeMbtilesFile(in dir: URL, baseName: String) -> URL {
        let fm = FileManager.default
        var n = 1
        while true {
            let file = dir.appendingPathComponent(n == 1 ? "\(baseName).mbtiles" : "\(baseName)_\(n).mbtiles")
            if !fm.fileExists(atPath: file.path) && !fm.fileExists(atPath: file.path + "-journal")
                && !fm.fileExists(atPath: partFile(for: file).path) {
                return file
            }
            n += 1
        }
    }

    /// Deletes `.mbtiles.part` files (and journals) left by a download that was stopped part-way.
    @discardableResult
    static func deleteStaleDownloadParts(in dir: URL, olderThan cutoff: Date? = nil) -> [URL] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return [] }
        return files.filter { url in
            let name = url.lastPathComponent
            guard name.hasSuffix(".mbtiles.part") || name.hasSuffix(".mbtiles.part-journal") else { return false }
            if let cutoff {
                let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
                if date >= cutoff { return false }
            }
            return (try? fm.removeItem(at: url)) != nil
        }
    }

    /// "<source>_<lat>_<lng>_<yyyyMMdd>" for a download.
    static func downloadBaseName(sourceName: String, bounds: GeoBounds, date: Date = Date()) -> String {
        var slug = ""
        var lastDash = false
        for scalar in sourceName.unicodeScalars {
            let c = Character(scalar)
            if scalar.isASCII && (c.isLetter || c.isNumber) { slug.append(c); lastDash = false }
            else if !lastDash { slug.append("-"); lastDash = true }
        }
        slug = slug.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        if slug.isEmpty { slug = "source" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd"
        return String(format: "%@_%.1f_%.1f_%@", slug, bounds.centerLat, bounds.centerLng, f.string(from: date))
    }
}

/// The opacity text in the layer list (Android LayerPanelBinder.formatOpacity).
enum LayerPanelFormat {
    static let opacitySteps = 100
    static func formatOpacity(_ fraction: Double) -> String {
        "\(Int((min(max(fraction, 0), 1) * 100).rounded()))%"
    }
}

/// The MBTiles (TMS) row for an XYZ row; its own inverse.
func layersTmsRow(z: Int, xyzY: Int) -> Int { (1 << z) - 1 - xyzY }

// MARK: - Saved lists (Android OverlayLayerRepository, DataSourceRepository/Store)

/// The overlay stack saved as JSON under Android's key, bottom of the stack first.
struct OverlayLayerStore {
    static let key = "overlay_layers_v2"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func load() -> [OverlayLayer] {
        guard let raw = defaults.string(forKey: Self.key), let data = raw.data(using: .utf8) else { return [] }
        do {
            return try JSONDecoder().decode([OverlayLayer].self, from: data)
                .filter { !$0.id.isEmpty && !$0.source.isEmpty }
        } catch {
            // Kept aside rather than overwritten on the next save.
            defaults.set(raw, forKey: Self.key + "_unreadable")
            DiagnosticLog.log("Layers", "saved layers unreadable: \(error)")
            return []
        }
    }

    func save(_ layers: [OverlayLayer]) {
        guard let data = try? JSONEncoder().encode(layers), let text = String(data: data, encoding: .utf8) else { return }
        defaults.set(text, forKey: Self.key)
    }

    /// MapLibre ids derive from this, so it is a plain identifier.
    static func newLayerId() -> String { "layer_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() }
}

/// The offline map sources, seeded with the built-in preset on first use.
final class DataSourceStore {
    static let key = "offline_data_sources_v2"
    private let defaults: UserDefaults
    private(set) var all: [DataSource] = []

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func load() {
        guard let raw = defaults.string(forKey: Self.key) else {
            all = [DataSource.defaultPreset]
            save()
            return
        }
        if let data = raw.data(using: .utf8), let list = try? JSONDecoder().decode([DataSource].self, from: data) {
            all = list
        } else {
            defaults.set(raw, forKey: Self.key + "_unreadable")
            all = []
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(all), let text = String(data: data, encoding: .utf8) {
            defaults.set(text, forKey: Self.key)
        }
    }

    @discardableResult
    func add(name: String, urlTemplate: String, attribution: String) -> DataSource {
        let source = DataSource(id: "source_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
                                name: name, urlTemplate: urlTemplate, attribution: attribution, builtIn: false)
        all.append(source)
        save()
        return source
    }

    /// False, changing nothing, for a missing id or the built-in preset.
    @discardableResult
    func remove(id: String) -> Bool {
        guard let index = all.firstIndex(where: { $0.id == id }), !all[index].builtIn else { return false }
        all.remove(at: index)
        save()
        return true
    }

    static func isPlausibleTileTemplate(_ url: String) -> Bool {
        (url.hasPrefix("http://") || url.hasPrefix("https://")) && ["{z}", "{x}", "{y}"].allSatisfy { url.contains($0) }
    }
}
