import Foundation
import SQLite3

// Depth tile pyramids on disk (Android PyramidGeometry.kt, DepthTileCodec.kt,
// TiledDepthPyramidStore.kt, TiledDepthPyramid.kt). Android wrote these for surveys
// too big to hold in memory; iOS streams such files from the TIFF instead, but can
// still open (and write) a pyramid file, e.g. one carried over from Android.

/// One tile's coordinates, plus a sample's offset within it.
struct DepthTileAddress: Equatable {
    let zoom: Int
    let tileCol: Int
    let tileRow: Int
    let localCol: Int
    let localRow: Int
}

/// Zoom and tile maths: levels from the file's own resolution at `maxZoom` down to 0.
struct DepthPyramidGeometry {
    static let defaultTileSize = 256

    let sourceWidth: Int
    let sourceHeight: Int
    let tileSize: Int
    let maxZoom: Int

    /// Nil for non-positive sizes.
    init?(sourceWidth: Int, sourceHeight: Int, tileSize: Int = DepthPyramidGeometry.defaultTileSize) {
        guard sourceWidth > 0, sourceHeight > 0, tileSize > 0 else { return nil }
        self.sourceWidth = sourceWidth
        self.sourceHeight = sourceHeight
        self.tileSize = tileSize
        let longest = Double(max(sourceWidth, sourceHeight))
        maxZoom = longest <= Double(tileSize) ? 0 : max(0, Int(ceil(log(longest / Double(tileSize)) / log(2.0))))
    }

    func levelWidth(_ zoom: Int) -> Int { scaled(sourceWidth, zoom) }
    func levelHeight(_ zoom: Int) -> Int { scaled(sourceHeight, zoom) }
    func tilesAcross(_ zoom: Int) -> Int { (levelWidth(zoom) + tileSize - 1) / tileSize }
    func tilesDown(_ zoom: Int) -> Int { (levelHeight(zoom) + tileSize - 1) / tileSize }

    func tileFor(zoom: Int, levelCol: Int, levelRow: Int) -> DepthTileAddress {
        DepthTileAddress(zoom: zoom, tileCol: levelCol / tileSize, tileRow: levelRow / tileSize,
                         localCol: levelCol % tileSize, localRow: levelRow % tileSize)
    }

    /// A tile's size: `tileSize`, or less at a level's right and bottom edges (tiles aren't padded).
    func tilePixelSize(zoom: Int, tileCol: Int, tileRow: Int) -> (width: Int, height: Int) {
        (min(max(levelWidth(zoom) - tileCol * tileSize, 0), tileSize),
         min(max(levelHeight(zoom) - tileRow * tileSize, 0), tileSize))
    }

    /// The coarsest zoom with at least `minDimension` cells along its longer side.
    func previewZoom(minDimension: Int) -> Int {
        for zoom in 0...maxZoom where max(levelWidth(zoom), levelHeight(zoom)) >= minDimension {
            return zoom
        }
        return maxZoom
    }

    private func scaled(_ dimension: Int, _ zoom: Int) -> Int {
        let shift = max(maxZoom - zoom, 0)
        if shift == 0 { return dimension }
        let scale = Int64(1) << Int64(shift)
        return max(1, Int((Int64(dimension) + scale - 1) / scale))
    }
}

/// One tile's depths as little-endian Float32 bytes, row by row.
enum DepthTileCodec {
    static func encode(_ values: [Float]) -> Data {
        var data = Data(capacity: values.count * 4)
        for v in values {
            var bits = v.bitPattern.littleEndian
            withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// Nil when the byte count is not a whole number of Float32 samples.
    static func decode(_ data: Data) -> [Float]? {
        if data.count % 4 != 0 { return nil }
        let bytes = [UInt8](data)
        var out = [Float](repeating: 0, count: bytes.count / 4)
        for i in out.indices {
            let o = i * 4
            let bits = UInt32(bytes[o]) | UInt32(bytes[o + 1]) << 8 | UInt32(bytes[o + 2]) << 16 | UInt32(bytes[o + 3]) << 24
            out[i] = Float(bitPattern: bits)
        }
        return out
    }
}

private let depthPyramidTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// The SQLite file behind `TiledDepthPyramid`: like MBTiles, but holding depths.
final class TiledDepthPyramidStore {
    static let fileExtension = "sbdp"

    let url: URL
    let geometry: DepthPyramidGeometry
    let bounds: GeoBounds
    let shallowestMetres: Double
    let deepestMetres: Double
    let projectionLabel: String
    private var db: OpaquePointer?
    private let lock = NSLock()

    private init(url: URL, db: OpaquePointer?) throws {
        self.url = url
        self.db = db
        func text(_ key: String) -> String? { TiledDepthPyramidStore.metadata(db, key) }
        func number(_ key: String) throws -> Double {
            guard let s = text(key), let v = Double(s) else { throw GeoTiffImportError("This depth pyramid is damaged (missing \(key)).") }
            return v
        }
        guard let w = Int(text("source_width") ?? ""), let h = Int(text("source_height") ?? ""),
              let t = Int(text("tile_size") ?? ""),
              let g = DepthPyramidGeometry(sourceWidth: w, sourceHeight: h, tileSize: t) else {
            throw GeoTiffImportError("This depth pyramid is damaged (missing its size).")
        }
        geometry = g
        bounds = GeoBounds(west: try number("bounds_west"), south: try number("bounds_south"),
                           east: try number("bounds_east"), north: try number("bounds_north"))
        shallowestMetres = try number("shallowest_metres")
        deepestMetres = try number("deepest_metres")
        projectionLabel = text("projection_label") ?? ""
    }

    deinit { close() }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        if let db { sqlite3_close(db) }
        db = nil
    }

    static func open(_ url: URL) throws -> TiledDepthPyramidStore {
        var db: OpaquePointer?
        if sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) != SQLITE_OK {
            sqlite3_close(db)
            throw GeoTiffImportError("This depth pyramid couldn't be opened.", kind: .unreadable)
        }
        do {
            return try TiledDepthPyramidStore(url: url, db: db)
        } catch {
            sqlite3_close(db)
            throw error
        }
    }

    /// A new, empty pyramid file with its schema and metadata written, ready for `writeTile`.
    static func create(_ url: URL, geometry: DepthPyramidGeometry, bounds: GeoBounds, shallowestMetres: Double,
                       deepestMetres: Double, projectionLabel: String) throws -> TiledDepthPyramidStore {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: url)
        var db: OpaquePointer?
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) == SQLITE_OK else {
            sqlite3_close(db)
            throw GeoTiffImportError("Couldn't create the depth pyramid file.")
        }
        let schema = "CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT);"
            + "CREATE TABLE tiles (zoom_level INTEGER NOT NULL, tile_col INTEGER NOT NULL, tile_row INTEGER NOT NULL, "
            + "data BLOB NOT NULL, PRIMARY KEY (zoom_level, tile_col, tile_row));"
        sqlite3_exec(db, schema, nil, nil, nil)
        let entries: [(String, String)] = [
            ("format_version", "1"),
            ("source_width", String(geometry.sourceWidth)), ("source_height", String(geometry.sourceHeight)),
            ("tile_size", String(geometry.tileSize)),
            ("bounds_west", String(bounds.west)), ("bounds_south", String(bounds.south)),
            ("bounds_east", String(bounds.east)), ("bounds_north", String(bounds.north)),
            ("shallowest_metres", String(shallowestMetres)), ("deepest_metres", String(deepestMetres)),
            ("projection_label", projectionLabel),
        ]
        for (name, value) in entries {
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, "INSERT INTO metadata (name, value) VALUES (?, ?)", -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, name, -1, depthPyramidTransient)
                sqlite3_bind_text(stmt, 2, value, -1, depthPyramidTransient)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        }
        sqlite3_close(db)
        // Reopen read-write so tiles can be added.
        var rw: OpaquePointer?
        guard sqlite3_open_v2(url.path, &rw, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK else {
            sqlite3_close(rw)
            throw GeoTiffImportError("Couldn't reopen the depth pyramid file.")
        }
        do {
            return try TiledDepthPyramidStore(url: url, db: rw)
        } catch {
            sqlite3_close(rw)
            throw error
        }
    }

    func writeTile(zoom: Int, tileCol: Int, tileRow: Int, values: [Float]) {
        lock.lock()
        defer { lock.unlock() }
        guard let db else { return }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = "INSERT OR REPLACE INTO tiles (zoom_level, tile_col, tile_row, data) VALUES (?, ?, ?, ?)"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_int(stmt, 1, Int32(zoom))
        sqlite3_bind_int(stmt, 2, Int32(tileCol))
        sqlite3_bind_int(stmt, 3, Int32(tileRow))
        let blob = DepthTileCodec.encode(values)
        blob.withUnsafeBytes { raw in
            _ = sqlite3_bind_blob(stmt, 4, raw.baseAddress, Int32(blob.count), depthPyramidTransient)
        }
        sqlite3_step(stmt)
    }

    /// One tile's samples, or nil if it was never written.
    func readTile(zoom: Int, tileCol: Int, tileRow: Int) -> [Float]? {
        lock.lock()
        defer { lock.unlock() }
        guard let db else { return nil }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = "SELECT data FROM tiles WHERE zoom_level = ? AND tile_col = ? AND tile_row = ? LIMIT 1"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int(stmt, 1, Int32(zoom))
        sqlite3_bind_int(stmt, 2, Int32(tileCol))
        sqlite3_bind_int(stmt, 3, Int32(tileRow))
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        let count = Int(sqlite3_column_bytes(stmt, 0))
        guard count > 0, let pointer = sqlite3_column_blob(stmt, 0) else { return [] }
        return DepthTileCodec.decode(Data(bytes: pointer, count: count))
    }

    private static func metadata(_ db: OpaquePointer?, _ key: String) -> String? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT value FROM metadata WHERE name = ? LIMIT 1", -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, key, -1, depthPyramidTransient)
        guard sqlite3_step(stmt) == SQLITE_ROW, let c = sqlite3_column_text(stmt, 0) else { return nil }
        return String(cString: c)
    }
}

/// Depths from a pyramid file: `sampleMetres` reads one finest tile, `previewGrid` one coarse level.
final class TiledDepthPyramid: DepthLayerSource {
    private let store: TiledDepthPyramidStore
    private let lock = NSLock()
    private var tileCache: [String: (values: [Float]?, tick: Int)] = [:]
    private var tick = 0
    private var cachedPreview: DepthGrid?
    private static let tileCacheCapacity = 16
    private static let previewMinDimension = GeoTiffImport.defaultMaxDimension

    private init(store: TiledDepthPyramidStore) { self.store = store }

    static func open(_ url: URL) throws -> TiledDepthPyramid {
        TiledDepthPyramid(store: try TiledDepthPyramidStore.open(url))
    }

    /// Whether `url` is a pyramid this app generated, by extension alone.
    static func isPyramidFile(_ url: URL) -> Bool {
        url.pathExtension.lowercased() == TiledDepthPyramidStore.fileExtension
    }

    var bounds: GeoBounds { store.bounds }
    var shallowestMetres: Double { store.shallowestMetres }
    var deepestMetres: Double { store.deepestMetres }
    var hasData: Bool { shallowestMetres.isFinite && deepestMetres.isFinite }
    var projectionLabel: String { store.projectionLabel }

    func sampleMetres(lat: Double, lng: Double) -> Double? {
        if !bounds.contains(lat: lat, lng: lng) { return nil }
        let geometry = store.geometry
        let zoom = geometry.maxZoom
        let width = geometry.levelWidth(zoom)
        let height = geometry.levelHeight(zoom)
        let lngSpan = bounds.east - bounds.west
        let latSpan = bounds.north - bounds.south
        if lngSpan <= 0 || latSpan <= 0 { return nil }
        let col = min(max(Int(((lng - bounds.west) / lngSpan * Double(width)).rounded(.down)), 0), width - 1)
        let row = min(max(Int(((bounds.north - lat) / latSpan * Double(height)).rounded(.down)), 0), height - 1)
        let address = geometry.tileFor(zoom: zoom, levelCol: col, levelRow: row)
        guard let tile = tile(zoom: zoom, col: address.tileCol, row: address.tileRow) else { return nil }
        let tileWidth = geometry.tilePixelSize(zoom: zoom, tileCol: address.tileCol, tileRow: address.tileRow).width
        if address.localCol >= tileWidth { return nil }
        let index = address.localRow * tileWidth + address.localCol
        if index >= tile.count { return nil }
        let value = tile[index]
        return value.isNaN ? nil : Double(value)
    }

    func previewGrid() -> DepthGrid {
        lock.lock()
        let existing = cachedPreview
        lock.unlock()
        if let existing { return existing }

        let geometry = store.geometry
        let zoom = geometry.previewZoom(minDimension: Self.previewMinDimension)
        let cols = geometry.levelWidth(zoom)
        let rows = geometry.levelHeight(zoom)
        var values = [Float](repeating: DepthGrid.noData, count: cols * rows)
        var covered = 0
        for tileRow in 0..<geometry.tilesDown(zoom) {
            for tileCol in 0..<geometry.tilesAcross(zoom) {
                let size = geometry.tilePixelSize(zoom: zoom, tileCol: tileCol, tileRow: tileRow)
                if size.width <= 0 || size.height <= 0 { continue }
                guard let tile = tile(zoom: zoom, col: tileCol, row: tileRow) else { continue }
                let originCol = tileCol * geometry.tileSize
                let originRow = tileRow * geometry.tileSize
                for localRow in 0..<size.height {
                    let dest = (originRow + localRow) * cols + originCol
                    let src = localRow * size.width
                    for localCol in 0..<size.width where src + localCol < tile.count {
                        let v = tile[src + localCol]
                        values[dest + localCol] = v
                        if !v.isNaN { covered += 1 }
                    }
                }
            }
        }
        let grid = DepthGrid(bounds: bounds, cols: cols, rows: rows, values: values,
                             shallowestMetres: shallowestMetres, deepestMetres: deepestMetres, coveredCells: covered)
        lock.lock()
        cachedPreview = grid
        lock.unlock()
        return grid
    }

    func histogram() -> DepthHistogram? { previewGrid().histogram() }

    private func tile(zoom: Int, col: Int, row: Int) -> [Float]? {
        let key = "\(zoom)/\(col)/\(row)"
        lock.lock()
        tick += 1
        if let hit = tileCache[key] {
            tileCache[key] = (hit.values, tick)
            lock.unlock()
            return hit.values
        }
        lock.unlock()
        let values = store.readTile(zoom: zoom, tileCol: col, tileRow: row)
        lock.lock()
        if tileCache.count >= Self.tileCacheCapacity,
           let oldest = tileCache.min(by: { $0.value.tick < $1.value.tick })?.key {
            tileCache.removeValue(forKey: oldest)
        }
        tileCache[key] = (values, tick)
        lock.unlock()
        return values
    }
}
