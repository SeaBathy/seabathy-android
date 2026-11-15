import Foundation

/**
 Saved sea-condition areas, one compressed file each, so they can be shown with
 no connection (Android ConditionsStore.kt). A few hundred KB for a week of an area.

 Reading and writing files happens off the main thread (`load`, `save`,
 `rename`, `delete`, `summaries`); the main thread only reads the list kept in
 memory (`cachedSummaries`), so it never waits on the disk.

 File layout: a small plain header (what the list shows, so it needn't
 decompress the values), then the grids and values compressed with zlib.
 */
final class ConditionsStore {
    private static let fileExtension = "sbc"
    private static let magic: UInt32 = 0x5342_4349 // "SBCI"
    private static let version: UInt32 = 1

    let dir: URL
    private let lock = NSLock()
    private var cache: [String: ConditionsArea] = [:]
    private var summaryCache: [ConditionsSummary]?

    init(dir: URL) {
        self.dir = dir
    }

    /// The list as last read, without touching the disk: nil until `summaries` has run once.
    func cachedSummaries() -> [ConditionsSummary]? {
        lock.lock()
        defer { lock.unlock() }
        return summaryCache
    }

    /// The saved areas, newest first (reads the files the first time). Off the main thread.
    func summaries() -> [ConditionsSummary] {
        if let cached = cachedSummaries() { return cached }
        return rescan()
    }

    @discardableResult
    private func rescan() -> [ConditionsSummary] {
        let list = files().compactMap { try? Self.readSummary($0) }.sorted { $0.fetchedMillis > $1.fetchedMillis }
        lock.lock()
        summaryCache = list
        lock.unlock()
        return list
    }

    /// Off the main thread.
    func load(_ id: String) -> ConditionsArea? {
        lock.lock()
        if let hit = cache[id] { lock.unlock(); return hit }
        lock.unlock()
        guard let data = try? Data(contentsOf: file(id)), let area = try? Self.read(data) else { return nil }
        lock.lock()
        cache[id] = area
        lock.unlock()
        return area
    }

    /// Off the main thread. Written to a temporary file first, then swapped in, so a reader never sees half of it.
    func save(_ area: ConditionsArea) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let data = try Self.write(area)
        try data.write(to: file(area.id), options: .atomic)
        lock.lock()
        cache[area.id] = area
        lock.unlock()
        rescan()
    }

    /// Off the main thread.
    func rename(_ id: String, name: String) throws -> ConditionsArea? {
        guard let area = load(id) else { return nil }
        let renamed = area.renamed(name)
        try save(renamed)
        return renamed
    }

    /// Off the main thread.
    func delete(_ id: String) {
        lock.lock()
        cache[id] = nil
        lock.unlock()
        try? FileManager.default.removeItem(at: file(id))
        rescan()
    }

    private func file(_ id: String) -> URL {
        // Ids are UUIDs or "spot"; anything odd is kept out of the path.
        let safe = id.replacingOccurrences(of: "/", with: "_")
        return dir.appendingPathComponent("\(safe).\(Self.fileExtension)")
    }

    private func files() -> [URL] {
        let list = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return list.filter { $0.pathExtension == Self.fileExtension }
    }

    // Format ----------------------------------------------------------------------------

    private static func readSummary(_ url: URL) throws -> ConditionsSummary {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        // The header is small; 64 KB covers any name.
        let head = handle.readData(ofLength: 64 * 1024)
        var r = ConditionsByteReader(Data(head))
        let header = try readHeader(&r)
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        return ConditionsSummary(id: header.id, name: header.name, south: header.bounds[0], west: header.bounds[1],
                                 north: header.bounds[2], east: header.bounds[3], fetchedMillis: header.fetched,
                                 forecastUntilMillis: header.until, satellites: Set(header.kinds), bytes: size)
    }

    private struct Header {
        let id: String
        let name: String
        let bounds: [Double]
        let fetched: Int64
        let until: Int64?
        let kinds: [ConditionsSatelliteKind]
        let belowDepth: Int
        let bodyOffset: Int
    }

    private static func readHeader(_ r: inout ConditionsByteReader) throws -> Header {
        if try r.u32() != magic { throw ConditionsError("not a sea conditions file") }
        let v = try r.u32()
        if v != version { throw ConditionsError("sea conditions file version \(v)") }
        let length = Int(try r.u32())
        let start = r.position
        let id = try r.string()
        let name = try r.string()
        var bounds: [Double] = []
        for _ in 0..<4 { bounds.append(try r.double()) }
        let fetched = try r.i64()
        let hasUntil = try r.bool()
        let untilValue = try r.i64()
        var kinds: [ConditionsSatelliteKind] = []
        let kindCount = Int(try r.u32())
        for _ in 0..<kindCount {
            let raw = try r.string()
            if let kind = ConditionsSatelliteKind(rawValue: raw) { kinds.append(kind) }
        }
        let belowDepth = Int(try r.i32())
        return Header(id: id, name: name, bounds: bounds, fetched: fetched, until: hasUntil ? untilValue : nil,
                      kinds: kinds, belowDepth: belowDepth, bodyOffset: start + length)
    }

    static func write(_ area: ConditionsArea) throws -> Data {
        var header = ConditionsByteWriter()
        header.string(area.id)
        header.string(area.name)
        header.double(area.south); header.double(area.west); header.double(area.north); header.double(area.east)
        header.i64(area.fetchedMillis)
        header.bool(area.forecast != nil)
        header.i64(area.forecast?.endMillis ?? 0)
        let kinds = ConditionsSatelliteKind.allCases.filter { area.satellites[$0] != nil }
        header.u32(UInt32(kinds.count))
        kinds.forEach { header.string($0.rawValue) }
        header.i32(Int32(area.belowDepthM))

        var body = ConditionsByteWriter()
        if let forecast = area.forecast {
            body.bool(true)
            writeGrid(&body, forecast.grid)
            body.u32(UInt32(forecast.times.count))
            forecast.times.forEach { body.i64($0) }
            let fields = ConditionsForecastField.allCases.filter { forecast.fields[$0] != nil }
            body.u32(UInt32(fields.count))
            for field in fields {
                body.string(field.rawValue)
                body.floats(forecast.fields[field]!)
            }
        } else {
            body.bool(false)
        }
        body.u32(UInt32(kinds.count))
        for kind in kinds {
            let sat = area.satellites[kind]!
            body.string(kind.rawValue)
            writeGrid(&body, sat.grid)
            body.i64(sat.dateMillis)
            body.floats(sat.values)
        }
        let compressed = try (body.data as NSData).compressed(using: .zlib) as Data

        var out = ConditionsByteWriter()
        out.u32(magic)
        out.u32(version)
        out.u32(UInt32(header.data.count))
        out.data.append(header.data)
        out.data.append(compressed)
        return out.data
    }

    static func read(_ data: Data) throws -> ConditionsArea {
        var r = ConditionsByteReader(data)
        let header = try readHeader(&r)
        guard header.bodyOffset <= data.count else { throw ConditionsError("sea conditions file is cut short") }
        let compressed = data.subdata(in: (data.startIndex + header.bodyOffset)..<data.endIndex)
        let body = try (compressed as NSData).decompressed(using: .zlib) as Data
        var b = ConditionsByteReader(body)
        var forecast: ConditionsForecastGrid?
        if try b.bool() {
            let grid = try readGrid(&b)
            let hourCount = Int(try b.u32())
            var times: [Int64] = []
            times.reserveCapacity(hourCount)
            for _ in 0..<hourCount { times.append(try b.i64()) }
            var fields: [ConditionsForecastField: [Float]] = [:]
            let fieldCount = Int(try b.u32())
            for _ in 0..<fieldCount {
                let name = try b.string()
                let values = try b.floats()
                // A field from a newer version is skipped rather than failing the file.
                if let field = ConditionsForecastField(rawValue: name), values.count == times.count * grid.size {
                    fields[field] = values
                }
            }
            if !times.isEmpty { forecast = ConditionsForecastGrid(grid: grid, times: times, fields: fields) }
        }
        var satellites: [ConditionsSatelliteKind: ConditionsSatelliteGrid] = [:]
        let kindCount = Int(try b.u32())
        for _ in 0..<kindCount {
            let raw = try b.string()
            let grid = try readGrid(&b)
            let date = try b.i64()
            let values = try b.floats()
            if let kind = ConditionsSatelliteKind(rawValue: raw), values.count == grid.size {
                satellites[kind] = ConditionsSatelliteGrid(kind: kind, grid: grid, dateMillis: date, values: values)
            }
        }
        let bounds = header.bounds
        return ConditionsArea(id: header.id, name: header.name, south: bounds[0], west: bounds[1], north: bounds[2], east: bounds[3],
                              fetchedMillis: header.fetched, forecast: forecast, satellites: satellites, belowDepthM: header.belowDepth)
    }

    private static func writeGrid(_ w: inout ConditionsByteWriter, _ grid: ConditionsGeoGrid) {
        w.u32(UInt32(grid.rows))
        grid.lats.forEach { w.double($0) }
        w.u32(UInt32(grid.cols))
        grid.lngs.forEach { w.double($0) }
    }

    private static func readGrid(_ r: inout ConditionsByteReader) throws -> ConditionsGeoGrid {
        let rowCount = Int(try r.u32())
        var lats: [Double] = []
        for _ in 0..<rowCount { lats.append(try r.double()) }
        let colCount = Int(try r.u32())
        var lngs: [Double] = []
        for _ in 0..<colCount { lngs.append(try r.double()) }
        if lats.isEmpty || lngs.isEmpty { throw ConditionsError("sea conditions file has an empty grid") }
        return ConditionsGeoGrid(lats: lats, lngs: lngs)
    }
}

/// Big-endian numbers into a byte buffer (float arrays in one block, the phone's own order).
struct ConditionsByteWriter {
    var data = Data()

    mutating func u32(_ v: UInt32) {
        var big = v.bigEndian
        withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
    }
    mutating func i32(_ v: Int32) { u32(UInt32(bitPattern: v)) }
    mutating func i64(_ v: Int64) {
        var big = v.bigEndian
        withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
    }
    mutating func double(_ v: Double) { i64(Int64(bitPattern: v.bitPattern)) }
    mutating func bool(_ v: Bool) { data.append(v ? 1 : 0) }
    mutating func string(_ s: String) {
        let bytes = Array(s.utf8)
        u32(UInt32(bytes.count))
        data.append(contentsOf: bytes)
    }
    // In one block: a week's forecast is about 700,000 numbers.
    mutating func floats(_ values: [Float]) {
        u32(UInt32(values.count))
        values.withUnsafeBytes { data.append(contentsOf: $0) }
    }
}

struct ConditionsByteReader {
    private let data: Data
    private(set) var position = 0

    init(_ data: Data) {
        // A fresh copy so indices start at 0.
        self.data = Data(data)
    }

    private func need(_ count: Int) throws {
        if count < 0 || position + count > data.count { throw ConditionsError("sea conditions file is cut short") }
    }

    mutating func u32() throws -> UInt32 {
        try need(4)
        var v: UInt32 = 0
        for i in 0..<4 { v = v << 8 | UInt32(data[position + i]) }
        position += 4
        return v
    }
    mutating func i32() throws -> Int32 { Int32(bitPattern: try u32()) }
    mutating func i64() throws -> Int64 {
        try need(8)
        var v: UInt64 = 0
        for i in 0..<8 { v = v << 8 | UInt64(data[position + i]) }
        position += 8
        return Int64(bitPattern: v)
    }
    mutating func double() throws -> Double { Double(bitPattern: UInt64(bitPattern: try i64())) }
    mutating func bool() throws -> Bool {
        try need(1)
        let v = data[position] != 0
        position += 1
        return v
    }
    mutating func string() throws -> String {
        let count = Int(try u32())
        try need(count)
        let s = String(decoding: data[position..<(position + count)], as: UTF8.self)
        position += count
        return s
    }
    mutating func floats() throws -> [Float] {
        let count = Int(try u32())
        try need(count * 4)
        var values = [Float](repeating: 0, count: count)
        let start = position
        values.withUnsafeMutableBytes { dst in
            _ = data.copyBytes(to: dst, from: start..<(start + count * 4))
        }
        position += count * 4
        return values
    }
}
