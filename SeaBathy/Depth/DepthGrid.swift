import Foundation

// Depth grids and how their depths are spread (Android geotiff/DepthGrid.kt,
// DepthSource.kt and DepthHistogram.kt).

/// A DepthSource that can also give a drawable grid and a depth histogram.
protocol DepthLayerSource: DepthSource {
    /// A grid to draw: the whole grid, or a smaller copy for a file too big to draw in full.
    func previewGrid() -> DepthGrid
    /// How the depths are spread, for fitting the colour range. Can take a moment: call off the main thread.
    func histogram() -> DepthHistogram?
}

/**
 North-up WGS84 grid of depths, negative-down metres, NaN = no data. Row 0 is north.
 Values are metres using the negative-down convention, so -18.4 means 18.4 metres
 below the surface.
 */
final class DepthGrid: DepthLayerSource {
    static let noData: Float = .nan

    let bounds: GeoBounds
    let cols: Int
    let rows: Int
    let values: [Float]
    let shallowestMetres: Double
    let deepestMetres: Double
    let coveredCells: Int

    private let histogramLock = NSLock()
    private var cachedHistogram: DepthHistogram??

    init(bounds: GeoBounds, cols: Int, rows: Int, values: [Float],
         shallowestMetres: Double, deepestMetres: Double, coveredCells: Int) {
        precondition(cols > 0 && rows > 0, "Depth grid must have a positive size")
        precondition(values.count == cols * rows, "Depth grid value count mismatch")
        self.bounds = bounds
        self.cols = cols
        self.rows = rows
        self.values = values
        self.shallowestMetres = shallowestMetres
        self.deepestMetres = deepestMetres
        self.coveredCells = coveredCells
    }

    var hasData: Bool { coveredCells > 0 }

    /// Nearest-neighbour depth lookup, or nil outside coverage.
    func sampleMetres(lat: Double, lng: Double) -> Double? {
        if !bounds.contains(lat: lat, lng: lng) { return nil }
        let spanLng = bounds.east - bounds.west
        let spanLat = bounds.north - bounds.south
        if spanLng <= 0 || spanLat <= 0 { return nil }

        let col = min(max(Int(((lng - bounds.west) / spanLng * Double(cols)).rounded(.down)), 0), cols - 1)
        let row = min(max(Int(((bounds.north - lat) / spanLat * Double(rows)).rounded(.down)), 0), rows - 1)
        let value = values[row * cols + col]
        return value.isNaN ? nil : Double(value)
    }

    func previewGrid() -> DepthGrid { self }

    func histogram() -> DepthHistogram? {
        histogramLock.lock()
        defer { histogramLock.unlock() }
        if let cached = cachedHistogram { return cached }
        let made = DepthHistogram.of(values)
        cachedHistogram = .some(made)
        return made
    }
}

/**
 How a layer's depths are spread: counts in equal bins from `lowest` to `highest`
 metres (negative = below sea level). Lets the colour range fit the bulk of a survey
 rather than one deep hole, and say how much of the layer a range shows.
 */
struct DepthHistogram: Sendable {
    static let bins = 1000

    let lowest: Double
    let highest: Double
    let counts: [Int64]
    private let total: Int64
    private let binWidth: Double

    init(lowest: Double, highest: Double, counts: [Int64]) {
        self.lowest = lowest
        self.highest = highest
        self.counts = counts
        self.total = counts.reduce(0, +)
        self.binWidth = counts.isEmpty ? 0 : (highest - lowest) / Double(counts.count)
    }

    var isEmpty: Bool { total == 0 || !(highest > lowest) }

    /// The depth below which `fraction` (0...1) of the layer lies.
    func percentile(_ fraction: Double) -> Double {
        if isEmpty { return .nan }
        let target = min(max(fraction, 0), 1) * Double(total)
        var seen: Int64 = 0
        for i in counts.indices {
            let next = seen + counts[i]
            if Double(next) >= target && counts[i] > 0 {
                let within = (target - Double(seen)) / Double(counts[i])
                return lowest + (Double(i) + min(max(within, 0), 1)) * binWidth
            }
            seen = next
        }
        return highest
    }

    /// The share (0...1) of the layer's depths between `a` and `b`, either order.
    func fractionWithin(_ a: Double, _ b: Double) -> Double {
        if isEmpty { return .nan }
        let from = min(a, b)
        let to = max(a, b)
        var inside = 0.0
        for i in counts.indices where counts[i] != 0 {
            let binLow = lowest + Double(i) * binWidth
            let binHigh = binLow + binWidth
            let overlap = max(min(to, binHigh) - max(from, binLow), 0)
            inside += Double(counts[i]) * (overlap / binWidth)
        }
        return min(max(inside / Double(total), 0), 1)
    }

    /// From decoded depths (NaN = no data).
    static func of(_ values: [Float]) -> DepthHistogram? {
        var lo = Double.infinity
        var hi = -Double.infinity
        for v in values where !v.isNaN {
            let d = Double(v)
            if d < lo { lo = d }
            if d > hi { hi = d }
        }
        if !(hi > lo) { return nil }
        var counts = [Int64](repeating: 0, count: bins)
        let scale = Double(bins) / (hi - lo)
        for v in values where !v.isNaN {
            let index = min(max(Int((Double(v) - lo) * scale), 0), bins - 1)
            counts[index] += 1
        }
        return DepthHistogram(lowest: lo, highest: hi, counts: counts)
    }
}
