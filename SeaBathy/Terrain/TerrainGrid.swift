import Foundation

/**
 A north-up grid of depths (metres, negative down, NaN = no data) for the 3D
 view, sampled from a layer's depth source over `bounds`. Cell sizes are in
 metres so the viewer can build true-scale geometry (Android TerrainGrid).
 */
struct TerrainGrid {
    let bounds: GeoBounds
    let cols: Int
    let rows: Int
    let values: [Float]
    let minMetres: Double
    let maxMetres: Double

    static let metresPerDegreeLat = 110_574.0
    static let metresPerDegreeLatEquator = 111_320.0

    private var midLatRadians: Double { (bounds.north + bounds.south) / 2 * .pi / 180 }

    var cellXMetres: Double {
        (bounds.east - bounds.west) / Double(cols) * TerrainGrid.metresPerDegreeLatEquator * cos(midLatRadians)
    }

    var cellYMetres: Double {
        (bounds.north - bounds.south) / Double(rows) * TerrainGrid.metresPerDegreeLat
    }
}

/// The raw cells of an in-memory depth grid, for smooth (bilinear) sampling.
struct TerrainCells {
    let bounds: GeoBounds
    let cols: Int
    let rows: Int
    let values: [Float]
}

enum TerrainGridSampler {

    /**
     Samples `source` over `bounds` at up to `maxDim` cells along the longer
     side, keeping square-ish cells in metres. Depths outside `range` (negative
     down) become no-data. Returns nil when nothing in `bounds` has data.

     An in-memory grid is sampled bilinearly from its own cells, so zooming into
     a small isolated area gives smooth slopes instead of stair-step terraces;
     other sources fall back to `sampleMetres`. Blocking: call off the main thread.
     */
    static func sample(source: DepthSource, bounds: GeoBounds, maxDim: Int,
                       range: ClosedRange<Double>? = nil) -> TerrainGrid? {
        var cells: TerrainCells? = nil
        if let grid = source as? DepthGrid {
            cells = TerrainCells(bounds: grid.bounds, cols: grid.cols, rows: grid.rows, values: grid.values)
        }
        return sample(source: source, cells: cells, bounds: bounds, maxDim: maxDim, range: range)
    }

    /// As above, with the source's own cells given directly (nil: sample point by point).
    static func sample(source: DepthSource, cells: TerrainCells?, bounds: GeoBounds, maxDim: Int,
                       range: ClosedRange<Double>? = nil) -> TerrainGrid? {
        let midLat = (bounds.north + bounds.south) / 2 * .pi / 180
        let widthMetres = (bounds.east - bounds.west) * TerrainGrid.metresPerDegreeLatEquator * cos(midLat)
        let heightMetres = (bounds.north - bounds.south) * TerrainGrid.metresPerDegreeLat
        if widthMetres <= 0 || heightMetres <= 0 || maxDim < 2 { return nil }

        var cols: Int
        var rows: Int
        if widthMetres >= heightMetres {
            cols = maxDim
            rows = max(2, Int((Double(maxDim) * heightMetres / widthMetres).rounded()))
        } else {
            rows = maxDim
            cols = max(2, Int((Double(maxDim) * widthMetres / heightMetres).rounded()))
        }

        if let g = cells, g.cols > 0, g.rows > 0, g.values.count == g.cols * g.rows {
            // No point sampling much finer than the data itself.
            let sourceCols = Double(g.cols) * (bounds.east - bounds.west) / (g.bounds.east - g.bounds.west)
            let sourceRows = Double(g.rows) * (bounds.north - bounds.south) / (g.bounds.north - g.bounds.south)
            let scale = min(1.0, max(sourceCols * 2 / Double(cols), sourceRows * 2 / Double(rows)))
            cols = max(2, Int((Double(cols) * scale).rounded()))
            rows = max(2, Int((Double(rows) * scale).rounded()))
        }
        let inMemory: TerrainCells? = {
            guard let g = cells, g.cols > 0, g.rows > 0, g.values.count == g.cols * g.rows else { return nil }
            return g
        }()

        var values = [Float](repeating: .nan, count: cols * rows)
        var lo = Double.infinity
        var hi = -Double.infinity
        for r in 0..<rows {
            let lat = bounds.north - (Double(r) + 0.5) / Double(rows) * (bounds.north - bounds.south)
            for c in 0..<cols {
                let lng = bounds.west + (Double(c) + 0.5) / Double(cols) * (bounds.east - bounds.west)
                let depth: Double?
                if let g = inMemory {
                    depth = bilinear(g, source: source, lat: lat, lng: lng)
                } else {
                    depth = source.sampleMetres(lat: lat, lng: lng)
                }
                guard let d = depth, d.isFinite else { continue }
                if let range, !range.contains(d) { continue }
                values[r * cols + c] = Float(d)
                if d < lo { lo = d }
                if d > hi { hi = d }
            }
        }
        if lo == Double.infinity { return nil }
        return TerrainGrid(bounds: bounds, cols: cols, rows: rows, values: values, minMetres: lo, maxMetres: hi)
    }

    /**
     The area of `grid` whose depths fall inside `range`, padded a little so the
     isolated features are not cut off at the edges; nil when nothing is in range.
     */
    static func boundsWithin(_ grid: TerrainGrid, range: ClosedRange<Double>, padFraction: Double = 0.03) -> GeoBounds? {
        var c0 = Int.max, r0 = Int.max, c1 = -1, r1 = -1
        for r in 0..<grid.rows {
            for c in 0..<grid.cols {
                let v = grid.values[r * grid.cols + c]
                if !v.isNaN && range.contains(Double(v)) {
                    if c < c0 { c0 = c }
                    if c > c1 { c1 = c }
                    if r < r0 { r0 = r }
                    if r > r1 { r1 = r }
                }
            }
        }
        if c1 < 0 { return nil }
        let pad = max(2, Int((Double(max(c1 - c0, r1 - r0)) * padFraction).rounded()))
        let b = grid.bounds
        let lngPerCol = (b.east - b.west) / Double(grid.cols)
        let latPerRow = (b.north - b.south) / Double(grid.rows)
        return GeoBounds(
            west: max(b.west, b.west + Double(c0 - pad) * lngPerCol),
            south: max(b.south, b.north - Double(r1 + 1 + pad) * latPerRow),
            east: min(b.east, b.west + Double(c1 + 1 + pad) * lngPerCol),
            north: min(b.north, b.north - Double(r0 - pad) * latPerRow))
    }

    /// Bilinear depth from in-memory cells, falling back to the source where a neighbour has no data.
    private static func bilinear(_ g: TerrainCells, source: DepthSource, lat: Double, lng: Double) -> Double? {
        let b = g.bounds
        if !b.contains(lat: lat, lng: lng) { return nil }
        let x = (lng - b.west) / (b.east - b.west) * Double(g.cols) - 0.5
        let y = (b.north - lat) / (b.north - b.south) * Double(g.rows) - 0.5
        let c0 = min(max(Int(floor(x)), 0), g.cols - 1)
        let r0 = min(max(Int(floor(y)), 0), g.rows - 1)
        let c1 = min(g.cols - 1, c0 + 1)
        let r1 = min(g.rows - 1, r0 + 1)
        let fx = min(max(x - Double(c0), 0), 1)
        let fy = min(max(y - Double(r0), 0), 1)
        let a = g.values[r0 * g.cols + c0]
        let bb = g.values[r0 * g.cols + c1]
        let c = g.values[r1 * g.cols + c0]
        let d = g.values[r1 * g.cols + c1]
        if a.isNaN || bb.isNaN || c.isNaN || d.isNaN { return source.sampleMetres(lat: lat, lng: lng) }
        let top = Double(a) * (1 - fx) + Double(bb) * fx
        let bottom = Double(c) * (1 - fx) + Double(d) * fx
        return top * (1 - fy) + bottom * fy
    }
}

/**
 How much 3D terrain a phone is asked to hold, so that any Detail choice is
 safe: Maximum gets the most this phone can take (Android TerrainBudget).
 */
enum TerrainBudget {
    /**
     Grid cells for a phone with `totalGib` of memory. Short of memory right now
     (`lowMemory`) or after graphics trouble (`safety` above 1), it holds less.
     */
    static func cells(totalGib: Double, lowMemory: Bool, safety: Int) -> Double {
        let base: Double
        if totalGib < 3.5 { base = 300_000 }
        else if totalGib < 5.0 { base = 600_000 }
        else if totalGib < 7.0 { base = 1_000_000 }
        else if totalGib < 10.5 { base = 1_500_000 }
        else { base = 2_600_000 }
        let shift = max(safety - 1, 0)
        return base / Double(1 << shift) / (lowMemory ? 2 : 1)
    }

    /// The longest side that fits `cells`, for an area `aspect` times as long as it is wide.
    static func longestSide(cells: Double, aspect: Double) -> Int {
        Int((cells * aspect).squareRoot())
    }
}

/// The chart's colour ramps (same stops as the depth layer palettes), so 3D starts out looking the same.
enum TerrainChartPalette {
    struct Entry { let id: String; let label: String; let stops: [String] }

    /// Stops run from the shallow end to the deep end.
    static let all: [Entry] = [
        Entry(id: "rainbow", label: "Rainbow",
              stops: ["#B3002D", "#FF3B30", "#FF9500", "#FFE000", "#34C759", "#00C8B4", "#0A84FF", "#1B2A6B"]),
        Entry(id: "ocean", label: "Ocean blues",
              stops: ["#D9F5FF", "#8FE0F5", "#3EB8E0", "#1573B8", "#0B3A7A", "#04143D"]),
        Entry(id: "chart", label: "Nautical chart",
              stops: ["#FFF6C9", "#CFEAF7", "#9CD3EF", "#66B6E2", "#E8F4FA", "#FFFFFF"]),
        Entry(id: "viridis", label: "Viridis",
              stops: ["#FDE725", "#A0DA39", "#4AC16D", "#1FA187", "#277F8E", "#365C8D", "#46327E", "#440154"]),
        Entry(id: "inferno", label: "Inferno",
              stops: ["#FCFFA4", "#F9C932", "#ED6925", "#BB3754", "#781C6D", "#320A5E", "#000004"]),
        Entry(id: "reef", label: "Reef contrast",
              stops: ["#FFFFFF", "#FFD166", "#06D6A0", "#118AB2", "#073B4C"]),
        Entry(id: "grayscale", label: "Greyscale",
              stops: ["#F5F5F5", "#9A9A9A", "#3C3C3C", "#000000"]),
    ]

    static func byId(_ id: String?) -> Entry { all.first { $0.id == id } ?? all[0] }

    /// The page's "chart" entry: its stops run deep to shallow, the chart's shallow to deep.
    static func pageChart(_ ramp: DepthRampSettings) -> [String: Any] {
        let r = ramp.normalised()
        let p = byId(r.paletteId)
        return [
            "label": p.label,
            "stops": r.invert ? p.stops : Array(p.stops.reversed()),
            "shallow": r.shallowMetres,
            "deep": r.deepMetres,
            // The chart hides depths outside the range: so does 3D, to start with.
            "hide": !r.showOutside,
        ]
    }
}

/// Checks a map link the user adds in 3D (same rules and wording as the XYZ layer dialog).
enum TerrainLinkCheck {
    static func validationError(_ urlTemplate: String) -> String? {
        let url = urlTemplate.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = url.lowercased()
        if url.isEmpty { return "Enter an XYZ tile URL" }
        if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") { return "URL must start with http:// or https://" }
        if !url.contains("{z}") || !url.contains("{x}") || !url.contains("{y}") { return "URL must contain {z}, {x} and {y}" }
        return nil
    }

    /// The host of a tile link, ignoring the `{z}`-style placeholders.
    static func host(_ urlTemplate: String) -> String? {
        let plain = urlTemplate.replacingOccurrences(of: "{", with: "").replacingOccurrences(of: "}", with: "")
        guard let host = URL(string: plain)?.host, !host.isEmpty else { return nil }
        return host
    }
}
