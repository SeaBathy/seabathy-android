import CoreGraphics
import Foundation
import UIKit

/// What the colours on the map show (Android ColourChoice). Raw values are the Android names (saved setting).
enum ConditionsColourChoice: String, CaseIterable {
    case none = "NONE"
    case current = "CURRENT"
    case waves = "WAVES"
    case swell = "SWELL"
    case wind = "WIND"
    case seaTempSatellite = "SEA_TEMP_SATELLITE"
    case seaTempBreaks = "SEA_TEMP_BREAKS"
    case seaTempForecast = "SEA_TEMP_FORECAST"
    case tempBelow = "TEMP_BELOW"
    case chlorophyll = "CHLOROPHYLL"
    case seaLevel = "SEA_LEVEL"
    case salinity = "SALINITY"

    var label: String {
        switch self {
        case .none: return "None"
        case .current: return "Current speed"
        case .waves: return "Wave height"
        case .swell: return "Swell height"
        case .wind: return "Wind speed"
        case .seaTempSatellite: return "Sea temperature (satellite)"
        case .seaTempBreaks: return "Temperature breaks (satellite)"
        case .seaTempForecast: return "Sea temperature (forecast)"
        case .tempBelow: return "Temperature at depth (model)"
        case .chlorophyll: return "Chlorophyll (satellite, 5-day blend)"
        case .seaLevel: return "Sea level and eddies (satellite)"
        case .salinity: return "Salinity (satellite)"
        }
    }

    var forecast: Bool {
        switch self {
        case .current, .waves, .swell, .wind, .seaTempForecast: return true
        default: return false
        }
    }

    var fixedRange: Bool {
        switch self {
        case .current, .waves, .swell, .wind: return true
        default: return false
        }
    }

    var satellite: ConditionsSatelliteKind? {
        switch self {
        case .seaTempSatellite, .seaTempBreaks: return .seaTemp
        case .tempBelow: return .tempBelow
        case .chlorophyll: return .chlorophyll
        case .seaLevel: return .seaLevel
        case .salinity: return .salinity
        default: return nil
        }
    }

    /// A temperature, so it follows the chosen unit and the range set for the colours.
    var isTemperature: Bool { self == .seaTempSatellite || self == .seaTempForecast || self == .tempBelow }
}

/// Which way the flow lines (or arrows) show things moving.
enum ConditionsArrowChoice: String {
    /// Whatever the map layer is: wind for wind speed, the current for the rest.
    case same = "SAME"
    case current = "CURRENT"
    case wind = "WIND"
    case waves = "WAVES"
    case swell = "SWELL"
    case none = "NONE"

    var short: String {
        switch self {
        case .current: return "current flow"
        case .wind: return "wind flow"
        case .waves: return "wave flow"
        case .swell: return "swell flow"
        default: return ""
        }
    }

    /// What `same` stands for with `layer` on the map.
    func resolve(_ layer: ConditionsColourChoice) -> ConditionsArrowChoice {
        if self != .same { return self }
        switch layer {
        case .wind: return .wind
        case .waves: return .waves
        case .swell: return .swell
        default: return .current
        }
    }

    /// The strength that gets a full-size arrow and the fastest flow lines: m/s for current and wind, metres for waves.
    var reference: Double {
        switch self {
        case .current: return 1.5
        case .wind: return 15
        default: return 3
        }
    }

    var fields: (ConditionsForecastField, ConditionsForecastField)? {
        switch self {
        case .none, .same: return nil
        case .current: return (.currentU, .currentV)
        case .waves: return (.waveU, .waveV)
        case .swell: return (.swellU, .swellV)
        case .wind: return (.windU, .windV)
        }
    }
}

/// How the direction is drawn.
enum ConditionsDirectionStyle: String, CaseIterable {
    case flow = "FLOW", arrows = "ARROWS", both = "BOTH", none = "NONE"

    var label: String {
        switch self {
        case .flow: return "Moving lines"
        case .arrows: return "Arrows"
        case .both: return "Moving lines and arrows"
        case .none: return "Off"
        }
    }

    var arrows: Bool { self == .arrows || self == .both }
    var flow: Bool { self == .flow || self == .both }
}

/**
 The colour range for a layer. Speeds and heights use fixed ranges so a colour
 always means the same; sea temperature and chlorophyll are stretched to the area.
 */
struct ConditionsColourScale {
    let low: Double
    let high: Double
    let logarithmic: Bool
    let stops: [UInt32]
    /// Where each stop sits, 0...1 rising; evenly spaced when nil.
    let at: [Double]?

    init(low: Double, high: Double, logarithmic: Bool, stops: [UInt32], at: [Double]? = nil) {
        self.low = low; self.high = high; self.logarithmic = logarithmic; self.stops = stops; self.at = at
    }

    /// 0...1 along the scale.
    func position(_ value: Double) -> Double {
        if !value.isFinite { return .nan }
        let t: Double
        if logarithmic {
            t = value <= 0 ? 0 : (log10(value) - log10(low)) / (log10(high) - log10(low))
        } else {
            t = (value - low) / (high - low)
        }
        return min(max(t, 0), 1)
    }

    /// ARGB for `value`; transparent for no value.
    func colour(_ value: Double) -> UInt32 {
        let t = position(value)
        if t.isNaN { return 0 }
        return colourAt(t)
    }

    func colourAt(_ t: Double) -> UInt32 {
        let u = min(max(t, 0), 1)
        let i: Int
        let f: Double
        if let at {
            let last = at.lastIndex { $0 <= u } ?? 0
            i = min(max(last, 0), stops.count - 2)
            f = min(max((u - at[i]) / (at[i + 1] - at[i]), 0), 1)
        } else {
            let scaled = u * Double(stops.count - 1)
            i = min(Int(scaled), stops.count - 2)
            f = scaled - Double(i)
        }
        let a = stops[i]
        let b = stops[i + 1]
        func mix(_ shift: UInt32) -> UInt32 {
            let x = Double((a >> shift) & 0xFF), y = Double((b >> shift) & 0xFF)
            return UInt32((x * (1 - f) + y * f).rounded())
        }
        return 0xFF00_0000 | mix(16) << 16 | mix(8) << 8 | mix(0)
    }

    /// The value at `t` (0...1) along the scale, for legend labels.
    func valueAt(_ t: Double) -> Double {
        logarithmic ? pow(10, log10(low) + (log10(high) - log10(low)) * t) : low + (high - low) * t
    }

    static func uiColour(_ argb: UInt32) -> UIColor {
        UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }

    /// Dark blue through cyan, green and yellow to red: easy to read in sunlight.
    static let spectrum: [UInt32] = [
        0xFF30123B, 0xFF4662D7, 0xFF36AAF9, 0xFF1AE4B6, 0xFF72FE5E, 0xFFC8EF34, 0xFFFABA39, 0xFFF66B19, 0xFFCA2A04
    ]

    /// Wind, coloured the way windy.com does, stops at 0, 1, 3 ... 21 m/s.
    static let windColours: [UInt32] = [
        0xFF6271B7, 0xFF39619F, 0xFF4A94A9, 0xFF4D8D7B, 0xFF53A553, 0xFF359F35,
        0xFFA79D51, 0xFF9F7F3A, 0xFFA16C5C, 0xFF813A4E, 0xFFAF5088, 0xFF754A93
    ]
    private static let windAt: [Double] = [0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21].map { $0 / 21 }
    static let knotsPerMs = 1.943844

    /// Clear blue water to green and yellow plankton-rich water.
    static let plankton: [UInt32] = [0xFF1B2A6B, 0xFF1F5FA8, 0xFF1F9AA6, 0xFF2DB36B, 0xFF8CCB3A, 0xFFE5D93A, 0xFFB5651D]
    /// Blue below the average, white on it, red above.
    static let diverging: [UInt32] = [0xFF1B3A8C, 0xFF3B82C4, 0xFF9CCBE6, 0xFFF4F6F8, 0xFFF5B7A0, 0xFFD9543C, 0xFF8E1616]
    /// Calm water dark, sharp temperature breaks bright.
    static let breaks: [UInt32] = [0xFF0B1622, 0xFF1B3A6B, 0xFF2A7F9E, 0xFF45C4A0, 0xFFE5D93A, 0xFFF4F6F8]

    /// °C per km: flat water shouldn't fill the whole colour range.
    private static let minBreakRange = 0.05
    private static let minSeaLevelReachCm = 5.0

    /// For `choice` over `values` (the area's numbers, for the stretched layers); values in display units.
    static func forChoice(_ choice: ConditionsColourChoice, values: [Float]? = nil) -> ConditionsColourScale {
        switch choice {
        case .current:
            return ConditionsColourScale(low: 0, high: 3, logarithmic: false, stops: spectrum)
        case .waves, .swell:
            return ConditionsColourScale(low: 0, high: 4, logarithmic: false, stops: spectrum)
        case .wind:
            return ConditionsColourScale(low: 0, high: 21 * knotsPerMs, logarithmic: false, stops: windColours, at: windAt)
        case .seaTempSatellite, .seaTempForecast, .tempBelow:
            let r = stretch(values, minSpan: 1) ?? (10, 30)
            return ConditionsColourScale(low: r.0, high: r.1, logarithmic: false, stops: spectrum)
        case .seaTempBreaks:
            let r = stretch(values, minSpan: 0) ?? (0, 0.1)
            return ConditionsColourScale(low: 0, high: max(r.1, minBreakRange), logarithmic: false, stops: breaks)
        case .chlorophyll:
            let logs = values.map { v in v.map { $0 > 0 ? Float(log10(Double($0))) : Float.nan } }
            let r = stretch(logs, minSpan: 0.5) ?? (-1.5, 1)
            return ConditionsColourScale(low: pow(10, r.0), high: pow(10, r.1), logarithmic: true, stops: plankton)
        case .seaLevel:
            // Even about the average, so white always means average.
            let r = stretch(values, minSpan: 0) ?? (-20, 20)
            let reach = max(abs(r.0), abs(r.1), minSeaLevelReachCm)
            return ConditionsColourScale(low: -reach, high: reach, logarithmic: false, stops: diverging)
        case .salinity:
            let r = stretch(values, minSpan: 0.5) ?? (33, 37)
            return ConditionsColourScale(low: r.0, high: r.1, logarithmic: false, stops: spectrum)
        case .none:
            return ConditionsColourScale(low: 0, high: 1, logarithmic: false, stops: spectrum)
        }
    }

    /// A range set by hand for the temperatures, in °C.
    static func temperatureRange(low: Double, high: Double) -> ConditionsColourScale {
        ConditionsColourScale(low: low, high: high, logarithmic: false, stops: spectrum)
    }

    /// The 2nd to 98th percentile of `values`, widened to at least `minSpan`.
    static func stretch(_ values: [Float]?, minSpan: Double) -> (Double, Double)? {
        guard let values else { return nil }
        let finite = values.filter { $0.isFinite }.sorted()
        if finite.isEmpty { return nil }
        var lo = Double(finite[min(Int(Double(finite.count) * 0.02), finite.count - 1)])
        var hi = Double(finite[min(Int(Double(finite.count) * 0.98), finite.count - 1)])
        if hi - lo < minSpan {
            let mid = (hi + lo) / 2
            lo = mid - minSpan / 2
            hi = mid + minSpan / 2
        }
        return (lo, hi)
    }
}

/**
 Builds the colour picture of a layer for an area, as pixels laid out on the
 web-Mercator map (rows evenly spaced in map height, not latitude), so it lines
 up when MapLibre stretches it over the area's corners.
 */
enum ConditionsPicture {

    struct Pixels {
        let width: Int
        let height: Int
        /// ARGB, row by row from the top.
        let argb: [UInt32]
    }

    static func render(south: Double, west: Double, north: Double, east: Double, maxSide: Int,
                       scale: ConditionsColourScale, sampler: (Double, Double) -> Float,
                       cancelled: () -> Bool = { false }) -> Pixels {
        let yTop = mercatorY(north)
        let yBottom = mercatorY(south)
        let widthDeg = east - west
        // Width and height in the same map units (radians of longitude).
        let mapWidth = widthDeg * .pi / 180
        let mapHeight = yTop - yBottom
        let width: Int
        let height: Int
        if mapWidth >= mapHeight {
            width = maxSide
            height = max(Int((Double(maxSide) * mapHeight / mapWidth).rounded()), 2)
        } else {
            height = maxSide
            width = max(Int((Double(maxSide) * mapWidth / mapHeight).rounded()), 2)
        }
        var argb = [UInt32](repeating: 0, count: width * height)
        for y in 0..<height {
            if cancelled() { break }
            let lat = latitudeOf(yTop - (Double(y) + 0.5) / Double(height) * mapHeight)
            for x in 0..<width {
                let lng = west + (Double(x) + 0.5) / Double(width) * widthDeg
                argb[y * width + x] = scale.colour(Double(sampler(lat, lng)))
            }
        }
        return Pixels(width: width, height: height, argb: argb)
    }

    static func mercatorY(_ lat: Double) -> Double {
        log(tan(.pi / 4 + min(max(lat, -85), 85) * .pi / 180 / 2))
    }

    static func latitudeOf(_ y: Double) -> Double { (2 * atan(exp(y)) - .pi / 2) * 180 / .pi }

    /// The pixels as an image (opaque colours or fully clear, so premultiplying changes nothing).
    static func image(_ pixels: Pixels) -> UIImage? {
        guard pixels.width > 0, pixels.height > 0 else { return nil }
        let data = pixels.argb.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        guard let cg = CGImage(width: pixels.width, height: pixels.height, bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: pixels.width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info,
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
