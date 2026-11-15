import Foundation
import UIKit

// Colour ramps, relief shading, rendering and the automatic range
// (Android DepthPalette.kt, ReliefShading.kt, DepthGridRenderer.kt and
// OverlayLayerController.fitRange/autoFitRamp). The maths is kept identical
// so a survey looks the same on both platforms.

/// A named colour ramp: stops (0xAARRGGBB) from the shallow end (0) to the deep end (1).
struct DepthPalette: Identifiable, Equatable {
    let id: String
    let label: String
    let stops: [UInt32]

    init(id: String, label: String, stops: [UInt32]) {
        precondition(stops.count >= 2, "A palette needs at least two stops")
        self.id = id
        self.label = label
        self.stops = stops
    }

    /// Interpolates the ramp at `position`, clamped to 0...1, as 0xAARRGGBB.
    func colorAt(_ position: Double) -> UInt32 {
        let clamped = min(max(position, 0), 1)
        let scaled = clamped * Double(stops.count - 1)
        let lowerIndex = min(Int(scaled), stops.count - 2)
        let amount = Float(scaled - Double(lowerIndex))
        return DepthPalette.blend(stops[lowerIndex], stops[lowerIndex + 1], amount)
    }

    /// `size` precomputed entries along the ramp.
    func lookupTable(size: Int) -> [UInt32] {
        precondition(size >= 2, "Lookup table needs at least two entries")
        return (0..<size).map { colorAt(Double($0) / Double(size - 1)) }
    }

    private static func blend(_ from: UInt32, _ to: UInt32, _ amount: Float) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let a = Int((from >> shift) & 0xFF)
            let b = Int((to >> shift) & 0xFF)
            let v = Int(Float(a) + Float(b - a) * amount)
            return UInt32(min(max(v, 0), 255))
        }
        return 0xFF00_0000 | (channel(16) << 16) | (channel(8) << 8) | channel(0)
    }

    /// Every palette selectable from the Depth colours sheet.
    static let all: [DepthPalette] = [
        DepthPalette(id: "rainbow", label: "Rainbow", stops: [
            0xFFB3002D, 0xFFFF3B30, 0xFFFF9500, 0xFFFFE000, 0xFF34C759, 0xFF00C8B4, 0xFF0A84FF, 0xFF1B2A6B]),
        DepthPalette(id: "ocean", label: "Ocean blues", stops: [
            0xFFD9F5FF, 0xFF8FE0F5, 0xFF3EB8E0, 0xFF1573B8, 0xFF0B3A7A, 0xFF04143D]),
        DepthPalette(id: "chart", label: "Nautical chart", stops: [
            0xFFFFF6C9, 0xFFCFEAF7, 0xFF9CD3EF, 0xFF66B6E2, 0xFFE8F4FA, 0xFFFFFFFF]),
        DepthPalette(id: "viridis", label: "Viridis", stops: [
            0xFFFDE725, 0xFFA0DA39, 0xFF4AC16D, 0xFF1FA187, 0xFF277F8E, 0xFF365C8D, 0xFF46327E, 0xFF440154]),
        DepthPalette(id: "inferno", label: "Inferno", stops: [
            0xFFFCFFA4, 0xFFF9C932, 0xFFED6925, 0xFFBB3754, 0xFF781C6D, 0xFF320A5E, 0xFF000004]),
        DepthPalette(id: "reef", label: "Reef contrast", stops: [
            0xFFFFFFFF, 0xFFFFD166, 0xFF06D6A0, 0xFF118AB2, 0xFF073B4C]),
        DepthPalette(id: "grayscale", label: "Greyscale", stops: [
            0xFFF5F5F5, 0xFF9A9A9A, 0xFF3C3C3C, 0xFF000000]),
    ]

    static var defaultPalette: DepthPalette { all[0] }

    static func byId(_ id: String?) -> DepthPalette {
        all.first { $0.id == id } ?? defaultPalette
    }
}

/**
 Hillshade brightness for a depth grid, baked into the depth colours so reefs, ledges
 and channels read as relief rather than one flat colour band.
 */
enum ReliefShading {
    // Sun from the north-west, 45 degrees up: the cartographic convention.
    private static let lightX = -0.5
    private static let lightY = 0.5
    private static let lightZ = 0.70710678
    private static let ambient = 0.45
    private static let direct = 0.75
    private static let maxBrightness = 1.15

    /// The strength setting (0...5) times this is the vertical exaggeration used for shading.
    static let exaggerationPerStrength = 5.0

    /// A brightness multiplier per cell (around 1.0), or nil when `strength` is zero.
    /// `cellX`/`cellY` are cell sizes in metres; values are metres, NaN for no data.
    static func shade(values: [Float], cols: Int, rows: Int, cellX: Double, cellY: Double, strength: Double) -> [Float]? {
        if strength <= 0 || cols < 2 || rows < 2 || cellX <= 0 || cellY <= 0 { return nil }
        let z = strength * exaggerationPerStrength
        var out = [Float](repeating: 1, count: cols * rows)
        for r in 0..<rows {
            let up = max(0, r - 1) * cols
            let down = min(rows - 1, r + 1) * cols
            let here = r * cols
            for c in 0..<cols {
                let v = values[here + c]
                if v.isNaN { continue }
                // Missing neighbours fall back to this cell, so no-data edges stay flat-lit.
                let left = orElse(values[here + max(0, c - 1)], v)
                let right = orElse(values[here + min(cols - 1, c + 1)], v)
                let north = orElse(values[up + c], v)
                let south = orElse(values[down + c], v)
                let dzdx = Double(right - left) / (2 * cellX) * z
                let dzdy = Double(north - south) / (2 * cellY) * z
                let lambert = (-dzdx * lightX - dzdy * lightY + lightZ) / (dzdx * dzdx + dzdy * dzdy + 1.0).squareRoot()
                out[here + c] = Float(min(maxBrightness, ambient + direct * max(0.0, lambert)))
            }
        }
        return out
    }

    private static func orElse(_ value: Float, _ fallback: Float) -> Float { value.isNaN ? fallback : value }
}

enum DepthImageRenderer {
    private static let lookupSize = 512
    static let minSpanMetres = 0.1
    private static let metresPerDegree = 111_320.0

    /// Colour image of the grid with relief shading; transparent where there's no data
    /// (or outside the range unless ramp.showOutside). Blocking.
    static func image(for grid: DepthGrid, ramp: DepthRampSettings) -> UIImage? {
        let pixels = rgba(for: grid, ramp: ramp)
        return makeImage(pixels: pixels, width: grid.cols, height: grid.rows)
    }

    /// RGBA bytes (row 0 north), not premultiplied but every pixel is either opaque or clear.
    static func rgba(for grid: DepthGrid, ramp settings: DepthRampSettings) -> [UInt8] {
        let ramp = settings.normalised()
        let lookup = DepthPalette.byId(ramp.paletteId).lookupTable(size: lookupSize)
        let span = max(ramp.spanMetres, minSpanMetres)
        let shade = shadeFor(grid: grid, ramp: ramp)
        let count = grid.cols * grid.rows
        var out = [UInt8](repeating: 0, count: count * 4)
        grid.values.withUnsafeBufferPointer { values in
            for index in 0..<count {
                let value = values[index]
                if value.isNaN { continue }
                guard let position = rampPosition(Double(value), ramp: ramp, span: span) else { continue }
                let ramped = ramp.invert ? 1.0 - position : position
                let colour = lookup[min(max(Int(ramped * Double(lookupSize - 1)), 0), lookupSize - 1)]
                var r = Int((colour >> 16) & 0xFF), g = Int((colour >> 8) & 0xFF), b = Int(colour & 0xFF)
                if let shade {
                    let brightness = shade[index]
                    r = min(Int(Float(r) * brightness), 255)
                    g = min(Int(Float(g) * brightness), 255)
                    b = min(Int(Float(b) * brightness), 255)
                }
                let o = index * 4
                out[o] = UInt8(r); out[o + 1] = UInt8(g); out[o + 2] = UInt8(b); out[o + 3] = 255
            }
        }
        return out
    }

    /**
     Where `value` falls on the ramp, 0 (shallow end) to 1 (deep end), or nil to leave
     the cell clear. Depths outside the range are hidden, unless `showOutside` is on:
     then they take the nearest end's colour, and only dry land is left clear (when the
     range is all water), so the chart's coastline shows through.
     */
    static func rampPosition(_ value: Double, ramp: DepthRampSettings, span: Double? = nil) -> Double? {
        let s = span ?? max(ramp.spanMetres, minSpanMetres)
        let position = (ramp.shallowMetres - value) / s
        if !ramp.showOutside { return (position >= 0 && position <= 1) ? position : nil }
        if value > 0 && ramp.shallowMetres <= 0 { return nil }
        return min(max(position, 0), 1)
    }

    /// Relief shading for the grid at the ramp's strength (nil when off).
    static func shadeFor(grid: DepthGrid, ramp: DepthRampSettings) -> [Float]? {
        let midLat = (grid.bounds.north + grid.bounds.south) / 2 * .pi / 180
        return ReliefShading.shade(
            values: grid.values, cols: grid.cols, rows: grid.rows,
            cellX: (grid.bounds.east - grid.bounds.west) / Double(grid.cols) * metresPerDegree * cos(midLat),
            cellY: (grid.bounds.north - grid.bounds.south) / Double(grid.rows) * metresPerDegree,
            strength: ramp.hillshadeExaggeration)
    }

    /// A horizontal preview strip of the ramp for the settings UI.
    static func legendImage(ramp settings: DepthRampSettings, width: Int = 512, height: Int = 24) -> UIImage? {
        let ramp = settings.normalised()
        let palette = DepthPalette.byId(ramp.paletteId)
        let w = max(width, 2), h = max(height, 1)
        var row = [UInt8](repeating: 255, count: w * 4)
        for column in 0..<w {
            var position = Double(column) / Double(w - 1)
            if ramp.invert { position = 1.0 - position }
            let c = palette.colorAt(position)
            row[column * 4] = UInt8((c >> 16) & 0xFF)
            row[column * 4 + 1] = UInt8((c >> 8) & 0xFF)
            row[column * 4 + 2] = UInt8(c & 0xFF)
        }
        var pixels = [UInt8]()
        pixels.reserveCapacity(w * h * 4)
        for _ in 0..<h { pixels.append(contentsOf: row) }
        return makeImage(pixels: pixels, width: w, height: h)
    }

    /// Wraps RGBA bytes in a UIImage at scale 1 (one pixel per cell).
    static func makeImage(pixels: [UInt8], width: Int, height: Int) -> UIImage? {
        guard width > 0, height > 0, pixels.count >= width * height * 4 else { return nil }
        let data = Data(pixels) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
        guard let cg = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info,
                               provider: provider, decode: nil, shouldInterpolate: false,
                               intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg, scale: 1, orientation: .up)
    }
}

enum DepthRampFit {
    /// Left out at each end when fitting colours to a layer's depths.
    static let fitTail = 0.01

    /**
     The colour range that fits a layer: its depths, from the surface down. Land in a
     survey would otherwise take the shallow colours and squash the water into a few.
     With a histogram, the bulk of the survey: one deep hole or a spike shouldn't
     stretch the colours so the rest is all one shade.
     */
    static func fitRange(shallowestMetres: Double, deepestMetres: Double,
                         histogram: DepthHistogram? = nil) -> (shallow: Double, deep: Double) {
        if let histogram, !histogram.isEmpty {
            let deepest = histogram.percentile(fitTail)
            let shallowest = histogram.percentile(1 - fitTail)
            if deepest.isFinite && shallowest.isFinite && shallowest - deepest >= 1.0 {
                return fitRange(shallowestMetres: shallowest, deepestMetres: deepest)
            }
        }
        let deep = deepestMetres.rounded(.down)
        var shallow = shallowestMetres.rounded(.up)
        if deep < 0 { shallow = min(shallow, 0) }
        return (shallow, deep)
    }

    /// The automatic colour range for a new layer (Android OverlayLayerController.autoFitRamp/fitRange).
    static func autoFit(_ source: DepthLayerSource) -> DepthRampSettings {
        autoFit(shallowestMetres: source.shallowestMetres, deepestMetres: source.deepestMetres)
    }

    /// A ramp spanning the data, rounded to whole metres, so a new layer has usable contrast.
    static func autoFit(shallowestMetres: Double, deepestMetres: Double) -> DepthRampSettings {
        let range = fitRange(shallowestMetres: shallowestMetres, deepestMetres: deepestMetres)
        if !range.shallow.isFinite || !range.deep.isFinite || range.shallow - range.deep < 1.0 {
            return DepthRampSettings()
        }
        return DepthRampSettings(paletteId: DepthRampSettings.defaultPaletteId,
                                 shallowMetres: range.shallow, deepMetres: range.deep)
    }
}
