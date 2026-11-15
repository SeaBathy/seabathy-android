import Foundation
import SQLite3

// MBTiles reading and writing with SQLite (Android MbtilesImporter, MbtilesWriter
// and OverlayLayerController's metadata reads).

private let layersSqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// What an MBTiles file says about itself.
struct LayersMbtilesMetadata {
    var minZoom: Int = 0
    var maxZoom: Int = 22
    var isVector: Bool = false
    var bounds: GeoBounds? = nil
    /// The pack's own credit, for a layer loaded without one.
    var attribution: String = ""
    var name: String = ""
}

/**
 An open, read-only MBTiles file. Thread safe: the 3D view reads tiles off the
 main thread, and a layer can be removed (and this closed) while it does.
 */
final class LayersMbtilesDatabase {
    let url: URL
    private var db: OpaquePointer?
    private let lock = NSLock()

    /// Opens and checks the file; throws a `LayersError` with a message for the user.
    init(url: URL) throws {
        self.url = url
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(url.path, &handle, flags, nil) != SQLITE_OK {
            if let handle { sqlite3_close(handle) }
            throw LayersError("Could not open this MBTiles file")
        }
        db = handle
        do {
            try validate()
        } catch {
            close()
            throw error
        }
    }

    deinit { close() }

    func close() {
        lock.lock(); defer { lock.unlock() }
        if let db { sqlite3_close_v2(db) }
        db = nil
    }

    private static let requiredColumns: Set<String> = ["zoom_level", "tile_column", "tile_row", "tile_data"]

    private func validate() throws {
        let hasTiles = !rows("SELECT 1 FROM sqlite_master WHERE name = 'tiles' AND type IN ('table', 'view') LIMIT 1").isEmpty
        if !hasTiles { throw LayersError("This MBTiles database has no tiles table or view") }
        var found = Set<String>()
        for row in rows("PRAGMA table_info(tiles)") where row.count > 1 {
            if let name = row[1] { found.insert(name) }
        }
        if Self.requiredColumns.isSubset(of: found) { return }
        // Some valid files expose `tiles` as a view; selecting the columns is the final check.
        if !canPrepare("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles LIMIT 1") {
            let missing = Self.requiredColumns.subtracting(found).sorted().joined(separator: ", ")
            throw LayersError("Invalid MBTiles tiles structure. Missing: \(missing)")
        }
    }

    private func canPrepare(_ sql: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let db else { return false }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        return sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK
    }

    /// Every row as text columns.
    private func rows(_ sql: String, _ args: [Int] = []) -> [[String?]] {
        lock.lock(); defer { lock.unlock() }
        guard let db else { return [] }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        for (i, value) in args.enumerated() { sqlite3_bind_int64(stmt, Int32(i + 1), Int64(value)) }
        var out: [[String?]] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let count = sqlite3_column_count(stmt)
            var row: [String?] = []
            for c in 0..<count {
                if sqlite3_column_type(stmt, c) == SQLITE_NULL {
                    row.append(nil)
                } else if let text = sqlite3_column_text(stmt, c) {
                    row.append(String(cString: text))
                } else {
                    row.append(nil)
                }
            }
            out.append(row)
        }
        return out
    }

    func metadata() -> LayersMbtilesMetadata {
        var meta = LayersMbtilesMetadata()
        var format = "png"
        for row in rows("SELECT name, value FROM metadata") where row.count >= 2 {
            guard let key = row[0]?.lowercased() else { continue }
            let value = row[1]
            switch key {
            case "minzoom": if let v = value.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }) { meta.minZoom = v }
            case "maxzoom": if let v = value.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }) { meta.maxZoom = v }
            case "format": if let value { format = value }
            case "attribution": meta.attribution = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            case "name": meta.name = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            case "bounds":
                // "left,bottom,right,top" in degrees, per the MBTiles spec.
                let parts = (value ?? "").split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
                if parts.count == 4 {
                    let b = GeoBounds(west: parts[0], south: parts[1], east: parts[2], north: parts[3])
                    if b.isValid { meta.bounds = b }
                }
            default: break
            }
        }
        meta.isVector = ["pbf", "mvt"].contains(format.lowercased())
        return meta
    }

    /// The area the tiles at `zoom` cover, for files without a "bounds" entry.
    func tileBounds(zoom: Int) -> GeoBounds? {
        let r = rows("SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level = ?", [zoom])
        guard let row = r.first, row.count == 4,
              let minX = row[0].flatMap({ Int($0) }), let maxX = row[1].flatMap({ Int($0) }),
              let minRow = row[2].flatMap({ Int($0) }), let maxRow = row[3].flatMap({ Int($0) }) else { return nil }
        let n = Double(1 << zoom)
        func lng(_ x: Int) -> Double { Double(x) / n * 360 - 180 }
        // MBTiles rows count up from the south (TMS).
        func lat(_ tmsRow: Int) -> Double {
            let y = n - Double(tmsRow)
            return atan(sinh(Double.pi * (1 - 2 * y / n))) * 180 / .pi
        }
        let b = GeoBounds(west: lng(minX), south: lat(minRow), east: lng(maxX + 1), north: lat(maxRow + 1))
        return b.isValid ? b : nil
    }

    /// One XYZ-addressed tile's bytes, or nil. Safe from any thread, and after `close()`.
    func readTile(z: Int, x: Int, y: Int) -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard let db else { return nil }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int64(stmt, 1, Int64(z))
        sqlite3_bind_int64(stmt, 2, Int64(x))
        sqlite3_bind_int64(stmt, 3, Int64(layersTmsRow(z: z, xyzY: y)))
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        let count = Int(sqlite3_column_bytes(stmt, 0))
        guard count > 0, let bytes = sqlite3_column_blob(stmt, 0) else { return nil }
        return Data(bytes: bytes, count: count)
    }
}

/**
 Writes a standard MBTiles file from downloaded tiles. Rows are stored TMS
 style (0 at the south). Use from one thread at a time.
 */
final class MbtilesWriter {
    private var db: OpaquePointer?

    init(url: URL) throws {
        var handle: OpaquePointer?
        if sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) != SQLITE_OK {
            if let handle { sqlite3_close(handle) }
            throw LayersError("Couldn't create the offline map file")
        }
        db = handle
        do {
            try exec("CREATE TABLE metadata (name TEXT, value TEXT)")
            try exec("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            try exec("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
        } catch {
            close()
            throw error
        }
    }

    deinit { close() }

    func close() {
        if let db { sqlite3_close_v2(db) }
        db = nil
    }

    private func exec(_ sql: String) throws {
        guard let db else { throw LayersError("The offline map file is closed") }
        if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK {
            throw LayersError("Couldn't write the offline map file: \(String(cString: sqlite3_errmsg(db)))")
        }
    }

    func writeMetadata(name: String, format: String, bounds: GeoBounds, minZoom: Int, maxZoom: Int, attribution: String) throws {
        func put(_ key: String, _ value: String) throws {
            guard let db else { return }
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_prepare_v2(db, "INSERT INTO metadata (name, value) VALUES (?, ?)", -1, &stmt, nil) == SQLITE_OK else {
                throw LayersError("Couldn't write the offline map file")
            }
            sqlite3_bind_text(stmt, 1, key, -1, layersSqliteTransient)
            sqlite3_bind_text(stmt, 2, value, -1, layersSqliteTransient)
            if sqlite3_step(stmt) != SQLITE_DONE { throw LayersError("Couldn't write the offline map file") }
        }
        try put("name", name)
        try put("format", format)
        try put("minzoom", String(minZoom))
        try put("maxzoom", String(maxZoom))
        try put("bounds", "\(bounds.west),\(bounds.south),\(bounds.east),\(bounds.north)")
        try put("type", "baselayer")
        try put("version", "1.1")
        if !attribution.trimmingCharacters(in: .whitespaces).isEmpty { try put("attribution", attribution) }
    }

    /// `x`/`y` are XYZ (y = 0 at the north); written as the TMS row.
    func writeTile(z: Int, x: Int, y: Int, data: Data) throws {
        guard let db else { return }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { throw LayersError("Couldn't write the offline map file") }
        sqlite3_bind_int64(stmt, 1, Int64(z))
        sqlite3_bind_int64(stmt, 2, Int64(x))
        sqlite3_bind_int64(stmt, 3, Int64(layersTmsRow(z: z, xyzY: y)))
        _ = data.withUnsafeBytes { raw in
            sqlite3_bind_blob(stmt, 4, raw.baseAddress, Int32(data.count), layersSqliteTransient)
        }
        if sqlite3_step(stmt) != SQLITE_DONE { throw LayersError("Couldn't write the offline map file") }
    }

    /// One transaction per batch: per-statement commits make thousands of inserts very slow.
    func inTransaction<T>(_ block: () throws -> T) throws -> T {
        try exec("BEGIN TRANSACTION")
        do {
            let result = try block()
            try exec("COMMIT")
            return result
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }
}
