import Foundation

// Recorded tracks: points, filtering, totals and storage (Android track/Track.kt, TrackStore.kt).

/// One recorded position. Times are epoch milliseconds (0 = unknown, from a GPX file without times).
struct TrackPoint: Equatable, Codable {
    var timeMillis: Int64
    var lat: Double
    var lng: Double
}

/// A saved track as listed: the points live in their own file. Field names match Android's index.json.
struct TrackSummary: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var startMillis: Int64
    var endMillis: Int64
    var distanceM: Double
    var movingMillis: Int64
    var maxSpeedMs: Double
    var pointCount: Int

    var averageSpeedMs: Double { movingMillis > 0 ? distanceM / (Double(movingMillis) / 1000) : 0 }

    /// Never under the average: tracks saved by older builds could store a lower (even zero) top speed.
    var topSpeedMs: Double { max(maxSpeedMs, averageSpeedMs) }

    init(id: String, name: String, startMillis: Int64, endMillis: Int64, distanceM: Double,
         movingMillis: Int64, maxSpeedMs: Double, pointCount: Int) {
        self.id = id; self.name = name; self.startMillis = startMillis; self.endMillis = endMillis
        self.distanceM = distanceM; self.movingMillis = movingMillis; self.maxSpeedMs = maxSpeedMs; self.pointCount = pointCount
    }

    // Tolerant decoding, as Gson is: missing numbers are zero.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        startMillis = try c.decodeIfPresent(Int64.self, forKey: .startMillis) ?? 0
        endMillis = try c.decodeIfPresent(Int64.self, forKey: .endMillis) ?? 0
        distanceM = try c.decodeIfPresent(Double.self, forKey: .distanceM) ?? 0
        movingMillis = try c.decodeIfPresent(Int64.self, forKey: .movingMillis) ?? 0
        maxSpeedMs = try c.decodeIfPresent(Double.self, forKey: .maxSpeedMs) ?? 0
        pointCount = try c.decodeIfPresent(Int.self, forKey: .pointCount) ?? 0
    }
}

/**
 Decides which GPS fixes go into a track. A boat sitting still (or swinging at
 anchor) adds nothing, rough fixes are dropped, and a lone fix that jumps
 impossibly far is ignored as a glitch.
 */
struct TrackFilter {
    private var last: TrackPoint?

    static let maxAccuracyM = 50.0
    static let minStepM = 10.0
    /// About 60 knots.
    static let maxSpeedMs = 31.0
    static let glitchWindowS = 60.0

    init(last: TrackPoint? = nil) { self.last = last }

    /// The point to record, or nil to skip this fix.
    mutating func accept(_ timeMillis: Int64, _ lat: Double, _ lng: Double, accuracyM: Double?) -> TrackPoint? {
        if let accuracyM, accuracyM > Self.maxAccuracyM { return nil }
        let point = TrackPoint(timeMillis: timeMillis, lat: lat, lng: lng)
        if let previous = last {
            let metres = TrackMath.distanceM(previous, point)
            if metres < Self.minStepM { return nil }
            let seconds = Double(timeMillis - previous.timeMillis) / 1000
            if seconds <= 0 { return nil }
            // Faster than any small boat over a short gap: a GPS jump, not the boat.
            if seconds < Self.glitchWindowS && metres / seconds > Self.maxSpeedMs { return nil }
        }
        last = point
        return point
    }
}

enum TrackMath {
    private static let earthRadiusM = 6_371_008.8
    /// A gap this long that also moved `gapJumpM` splits the track (GPS lost, or the phone off).
    static let gapMs: Int64 = 10 * 60_000
    /// Sitting at anchor for hours is not a gap: the boat comes back to the same spot.
    private static let gapJumpM = 500.0
    /// Slower than this between points counts as stopped.
    private static let movingMs = 0.25
    /// Top speed is measured over at least this long, so one noisy fix can't set it.
    private static let maxSpeedWindowMs: Int64 = 30_000

    static func distanceM(_ a: TrackPoint, _ b: TrackPoint) -> Double { distanceM(a.lat, a.lng, b.lat, b.lng) }

    static func distanceM(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dp = p2 - p1, dl = (lng2 - lng1) * .pi / 180
        let h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusM * asin(sqrt(min(max(h, 0), 1)))
    }

    /// True when `b` follows `a` after lost GPS rather than steady recording.
    static func isGap(_ a: TrackPoint, _ b: TrackPoint) -> Bool {
        b.timeMillis - a.timeMillis > gapMs && distanceM(a, b) > gapJumpM
    }

    /// The track split wherever `isGap`.
    static func segments(_ points: [TrackPoint]) -> [[TrackPoint]] {
        if points.isEmpty { return [] }
        var result: [[TrackPoint]] = []
        var start = 0
        for i in 1..<max(points.count, 1) where i < points.count {
            if isGap(points[i - 1], points[i]) {
                result.append(Array(points[start..<i]))
                start = i
            }
        }
        result.append(Array(points[start..<points.count]))
        return result
    }

    static func summarise(id: String, name: String, points: [TrackPoint]) -> TrackSummary {
        var distance = 0.0
        var moving: Int64 = 0
        for segment in segments(points) where segment.count > 1 {
            for i in 1..<segment.count {
                let metres = distanceM(segment[i - 1], segment[i])
                let dt = segment[i].timeMillis - segment[i - 1].timeMillis
                distance += metres
                if dt > 0 && metres / (Double(dt) / 1000) >= movingMs { moving += dt }
            }
        }
        // The top speed is a straight line over a stretch, so on a winding track it can
        // come out under the average; it is never really slower.
        let average = moving > 0 ? distance / (Double(moving) / 1000) : 0
        return TrackSummary(id: id, name: name,
                            startMillis: points.first?.timeMillis ?? 0,
                            endMillis: points.last?.timeMillis ?? 0,
                            distanceM: distance, movingMillis: moving,
                            maxSpeedMs: max(maxSpeedMs(points), average),
                            pointCount: points.count)
    }

    /// The fastest straight-line speed over any stretch of at least 30 s within one segment.
    /// A segment shorter than that uses its whole length.
    static func maxSpeedMs(_ points: [TrackPoint]) -> Double {
        var best = 0.0
        for segment in segments(points) {
            if segment.count < 2 { continue }
            let span = segment[segment.count - 1].timeMillis - segment[0].timeMillis
            if span < maxSpeedWindowMs {
                if span > 0 { best = max(best, distanceM(segment[0], segment[segment.count - 1]) / (Double(span) / 1000)) }
                continue
            }
            var j = 0
            for i in 0..<segment.count {
                if j < i { j = i }
                while j < segment.count && segment[j].timeMillis - segment[i].timeMillis < maxSpeedWindowMs { j += 1 }
                if j >= segment.count { break }
                let seconds = Double(segment[j].timeMillis - segment[i].timeMillis) / 1000
                best = max(best, distanceM(segment[i], segment[j]) / seconds)
            }
        }
        return best
    }

    /// Fewer points for drawing (Douglas-Peucker): drops points within `toleranceM`
    /// of the line through their neighbours.
    static func simplify(_ points: [TrackPoint], toleranceM: Double) -> [TrackPoint] {
        if points.count < 3 { return points }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        let cosLat = cos(points[0].lat * .pi / 180)
        var stack: [(Int, Int)] = [(0, points.count - 1)]
        while let range = stack.popLast() {
            let (first, last) = range
            var worst = -1
            var worstM = toleranceM
            if last - first > 1 {
                for i in (first + 1)..<last {
                    let d = offLineM(points[i], points[first], points[last], cosLat)
                    if d > worstM {
                        worst = i
                        worstM = d
                    }
                }
            }
            if worst >= 0 {
                keep[worst] = true
                stack.append((first, worst))
                stack.append((worst, last))
            }
        }
        return points.indices.filter { keep[$0] }.map { points[$0] }
    }

    /// Distance of `p` from the segment a-b, on a flat local projection.
    private static func offLineM(_ p: TrackPoint, _ a: TrackPoint, _ b: TrackPoint, _ cosLat: Double) -> Double {
        let metresPerDeg = 111_320.0
        let ax = a.lng * metresPerDeg * cosLat, ay = a.lat * metresPerDeg
        let bx = b.lng * metresPerDeg * cosLat - ax, by = b.lat * metresPerDeg - ay
        let px = p.lng * metresPerDeg * cosLat - ax, py = p.lat * metresPerDeg - ay
        let lengthSq = bx * bx + by * by
        if lengthSq == 0 { return sqrt(px * px + py * py) }
        let t = min(max((px * bx + py * by) / lengthSq, 0), 1)
        let dx = px - t * bx, dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /// "2 h 05 min", "12 min", "45 s".
    static func duration(_ millis: Int64) -> String {
        let total = abs(millis)
        let minutes = total / 60_000
        if minutes >= 60 { return String(format: "%d h %02d min", Int(minutes / 60), Int(minutes % 60)) }
        if minutes >= 1 { return "\(minutes) min" }
        return "\(total / 1000) s"
    }
}

/// A track's points as text, one "time,lat,lng" line each, so recording only ever appends.
enum TrackCodec {
    static func line(_ p: TrackPoint) -> String { "\(p.timeMillis),\(p.lat),\(p.lng)\n" }

    /// Reads the lines back, skipping damaged ones (a half-written last line, say).
    static func parse(_ text: String) -> [TrackPoint] {
        var out: [TrackPoint] = []
        text.enumerateLines { line, _ in
            let parts = line.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count >= 3,
                  let time = Int64(parts[0].trimmingCharacters(in: .whitespaces)),
                  let lat = Double(parts[1].trimmingCharacters(in: .whitespaces)),
                  let lng = Double(parts[2].trimmingCharacters(in: .whitespaces)),
                  lat >= -90, lat <= 90, lng >= -180, lng <= 180 else { return }
            out.append(TrackPoint(timeMillis: time, lat: lat, lng: lng))
        }
        return out
    }
}

/**
 Saved tracks on disk: each track's points in "<id>.csv" (appended to while
 recording) and an index of names and totals in "index.json", as on Android.
 */
final class TrackStore {
    let dir: URL
    private var index: [TrackSummary]?
    private let lock = NSLock()

    init(dir: URL) {
        self.dir = dir
    }

    func summaries() -> [TrackSummary] {
        locked { loadIndex().sorted { $0.startMillis > $1.startMillis } }
    }

    func summary(_ id: String) -> TrackSummary? {
        locked { loadIndex().first { $0.id == id } }
    }

    func points(_ id: String) -> [TrackPoint] {
        locked { readPoints(id) }
    }

    func append(_ id: String, _ point: TrackPoint) {
        locked { () -> Void in
            ensureDir()
            let url = pointsFile(id)
            let data = Data(TrackCodec.line(point).utf8)
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            } else {
                try? data.write(to: url)
            }
        }
    }

    /// Files a finished recording. A track under two points (the boat never moved) is thrown away.
    func finish(_ id: String, name: String) -> TrackSummary? {
        locked { () -> TrackSummary? in
            let pts = readPoints(id)
            if pts.count < 2 {
                try? FileManager.default.removeItem(at: pointsFile(id))
                return nil
            }
            return put(TrackMath.summarise(id: id, name: name, points: pts))
        }
    }

    /// Saves points as a new track (from a GPX file).
    func add(_ id: String, name: String, points pts: [TrackPoint]) -> TrackSummary? {
        locked { () -> TrackSummary? in
            if pts.count < 2 { return nil }
            ensureDir()
            let text = pts.map { TrackCodec.line($0) }.joined()
            do {
                try Data(text.utf8).write(to: pointsFile(id), options: .atomic)
            } catch {
                DiagnosticLog.logError("TrackStore", "couldn't save track points", error)
                return nil
            }
            return put(TrackMath.summarise(id: id, name: name, points: pts))
        }
    }

    func rename(_ id: String, name: String) {
        locked { () -> Void in
            var list = loadIndex()
            guard let i = list.firstIndex(where: { $0.id == id }) else { return }
            list[i].name = name
            index = list
            saveIndex()
        }
    }

    func delete(_ id: String) {
        locked { () -> Void in
            try? FileManager.default.removeItem(at: pointsFile(id))
            var list = loadIndex()
            let before = list.count
            list.removeAll { $0.id == id }
            index = list
            if list.count != before { saveIndex() }
        }
    }

    func isNameTaken(_ name: String, exceptId: String? = nil) -> Bool {
        locked { loadIndex().contains { $0.id != exceptId && $0.name.caseInsensitiveCompare(name) == .orderedSame } }
    }

    /// `base`, or "base (2)", "base (3)"... whichever is free.
    func uniqueName(_ base: String) -> String {
        if !isNameTaken(base) { return base }
        var n = 2
        while isNameTaken("\(base) (\(n))") { n += 1 }
        return "\(base) (\(n))"
    }

    // Private (call with the lock held) ---------------------------------------------

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    private func ensureDir() {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func pointsFile(_ id: String) -> URL { dir.appendingPathComponent("\(id).csv") }

    private func readPoints(_ id: String) -> [TrackPoint] {
        guard let data = try? Data(contentsOf: pointsFile(id)) else { return [] }
        return TrackCodec.parse(String(decoding: data, as: UTF8.self))
    }

    private func put(_ summary: TrackSummary) -> TrackSummary {
        var list = loadIndex()
        list.removeAll { $0.id == summary.id }
        list.append(summary)
        index = list
        saveIndex()
        return summary
    }

    private func loadIndex() -> [TrackSummary] {
        if let index { return index }
        let url = dir.appendingPathComponent("index.json")
        var loaded: [TrackSummary] = []
        if let data = try? Data(contentsOf: url),
           let list = try? JSONDecoder().decode([TrackSummary].self, from: data) {
            // A damaged file can leave entries without an id or name.
            loaded = list.filter { !$0.id.trimmingCharacters(in: .whitespaces).isEmpty && !$0.name.isEmpty }
        }
        index = loaded
        return loaded
    }

    private func saveIndex() {
        ensureDir()
        do {
            // Written beside and moved over, so a crash mid-write can't lose every track's name.
            let data = try JSONEncoder().encode(index ?? [])
            try data.write(to: dir.appendingPathComponent("index.json"), options: .atomic)
        } catch {
            DiagnosticLog.logError("TrackStore", "couldn't save the track index", error)
        }
    }
}
