import Foundation

// Sea-condition data types (Android conditions/ConditionsData.kt).

/**
 A grid of points: `lats` south to north, `lngs` west to east, each in order
 but not necessarily evenly spaced. Values are stored row by row from the south.
 */
final class ConditionsGeoGrid {
    let lats: [Double]
    let lngs: [Double]
    // Evenly spaced axes (the usual case) are looked up by arithmetic, not searched.
    private let latStep: Double
    private let lngStep: Double

    init(lats: [Double], lngs: [Double]) {
        precondition(!lats.isEmpty && !lngs.isEmpty, "empty grid")
        self.lats = lats
        self.lngs = lngs
        latStep = ConditionsGeoGrid.evenStep(lats)
        lngStep = ConditionsGeoGrid.evenStep(lngs)
    }

    var rows: Int { lats.count }
    var cols: Int { lngs.count }
    var size: Int { lats.count * lngs.count }
    var south: Double { lats[0] }
    var north: Double { lats[lats.count - 1] }
    var west: Double { lngs[0] }
    var east: Double { lngs[lngs.count - 1] }

    @inline(__always) func index(_ row: Int, _ col: Int) -> Int { row * lngs.count + col }

    func contains(lat: Double, lng: Double) -> Bool {
        let padLat = rows > 1 ? (north - south) / Double(rows - 1) / 2 : 0.05
        let padLng = cols > 1 ? (east - west) / Double(cols - 1) / 2 : 0.05
        return lat >= south - padLat && lat <= north + padLat && lng >= west - padLng && lng <= east + padLng
    }

    /**
     The value at a point, blended from the four nearest points. Land (NaN) is
     left blank where the nearest point is land, so values don't bleed onto the
     shore. With `strict` off, any sea corner gives a value: for when a finer
     coastline (the satellite's) does the masking instead.
     */
    func sample(_ values: [Float], offset: Int, lat: Double, lng: Double, strict: Bool = true) -> Float {
        if !contains(lat: lat, lng: lng) { return .nan }
        let ry = ConditionsGeoGrid.position(lats, latStep, lat)
        let cx = ConditionsGeoGrid.position(lngs, lngStep, lng)
        let r0 = min(max(Int(ry), 0), rows - 1)
        let c0 = min(max(Int(cx), 0), cols - 1)
        let fr = min(max(ry - Double(r0), 0), 1)
        let fc = min(max(cx - Double(c0), 0), 1)
        let r1 = min(r0 + 1, rows - 1)
        let c1 = min(c0 + 1, cols - 1)
        let nearest = values[offset + index(fr < 0.5 ? r0 : r1, fc < 0.5 ? c0 : c1)]
        if nearest.isNaN && strict { return .nan }
        var sum = 0.0
        var weight = 0.0
        let v00 = values[offset + index(r0, c0)]
        let w00 = (1 - fr) * (1 - fc)
        if !v00.isNaN && w00 > 0 { sum += Double(v00) * w00; weight += w00 }
        let v01 = values[offset + index(r0, c1)]
        let w01 = (1 - fr) * fc
        if !v01.isNaN && w01 > 0 { sum += Double(v01) * w01; weight += w01 }
        let v10 = values[offset + index(r1, c0)]
        let w10 = fr * (1 - fc)
        if !v10.isNaN && w10 > 0 { sum += Double(v10) * w10; weight += w10 }
        let v11 = values[offset + index(r1, c1)]
        let w11 = fr * fc
        if !v11.isNaN && w11 > 0 { sum += Double(v11) * w11; weight += w11 }
        return weight > 0 ? Float(sum / weight) : .nan
    }

    /// The spacing of an evenly spaced axis (to a millionth of a step), or 0 when it isn't even.
    static func evenStep(_ axis: [Double]) -> Double {
        if axis.count < 2 { return 0 }
        let step = (axis[axis.count - 1] - axis[0]) / Double(axis.count - 1)
        if step <= 0 { return 0 }
        for i in axis.indices where abs(axis[i] - (axis[0] + step * Double(i))) > step * 1e-6 { return 0 }
        return step
    }

    /// Where `value` falls along `axis` as a fractional index.
    static func position(_ axis: [Double], _ step: Double, _ value: Double) -> Double {
        if axis.count == 1 || value <= axis[0] { return 0 }
        if value >= axis[axis.count - 1] { return Double(axis.count - 1) }
        if step > 0 { return (value - axis[0]) / step }
        var lo = 0
        var hi = axis.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) >> 1
            if axis[mid] <= value { lo = mid } else { hi = mid }
        }
        let span = axis[hi] - axis[lo]
        return Double(lo) + (span > 0 ? (value - axis[lo]) / span : 0)
    }

    /// Points every `step` degrees covering the box, at most `maxPerSide` a side.
    static func covering(south: Double, west: Double, north: Double, east: Double, step: Double, maxPerSide: Int) -> ConditionsGeoGrid {
        func axis(_ from: Double, _ to: Double) -> [Double] {
            let count = min(max(Int(floor((to - from) / step + 1e-9)) + 1, 2), maxPerSide)
            return (0..<count).map { from + (to - from) * Double($0) / Double(count - 1) }
        }
        return ConditionsGeoGrid(lats: axis(south, north), lngs: axis(west, east))
    }
}

/// What is kept for each forecast point and hour. Directions are stored as east/north parts so they blend.
/// Raw values are the Android names (used in saved files).
enum ConditionsForecastField: String, CaseIterable {
    /// Surface current towards east / north, m/s (tidal flow included).
    case currentU = "CURRENT_U", currentV = "CURRENT_V"
    case waveHeight = "WAVE_HEIGHT"
    /// Unit vector of where the waves are going.
    case waveU = "WAVE_U", waveV = "WAVE_V"
    case wavePeriod = "WAVE_PERIOD"
    case swellHeight = "SWELL_HEIGHT", swellU = "SWELL_U", swellV = "SWELL_V", swellPeriod = "SWELL_PERIOD"
    /// The forecast model's sea temperature, °C.
    case seaTemp = "SEA_TEMP"
    /// Wind blowing towards east / north, m/s, at 10 m.
    case windU = "WIND_U", windV = "WIND_V"
    case gust = "GUST"
}

/// A movement: `speed` (in the stored unit) towards compass bearing `towardsDegrees`.
struct ConditionsFlow: Equatable {
    let speed: Double
    let towardsDegrees: Double

    /// Where it comes from, as winds and waves are named.
    var fromDegrees: Double { (towardsDegrees + 180).truncatingRemainder(dividingBy: 360) }

    static func of(east: Double, north: Double) -> ConditionsFlow {
        let bearing = atan2(east, north) * 180 / .pi
        return ConditionsFlow(speed: hypot(east, north), towardsDegrees: (bearing + 360).truncatingRemainder(dividingBy: 360))
    }

    /// East and north parts of `speed` heading towards `towardsDegrees`.
    static func parts(speed: Double, towardsDegrees: Double) -> (east: Double, north: Double) {
        let r = towardsDegrees * .pi / 180
        return (speed * sin(r), speed * cos(r))
    }
}

/// Hourly forecast values on a grid. Each field holds times.count × grid.size values, hour by hour.
final class ConditionsForecastGrid {
    static let hourMs: Int64 = 3_600_000

    let grid: ConditionsGeoGrid
    let times: [Int64]
    let fields: [ConditionsForecastField: [Float]]
    private let timeStep: Int64

    init(grid: ConditionsGeoGrid, times: [Int64], fields: [ConditionsForecastField: [Float]]) {
        precondition(!times.isEmpty, "no hours")
        for v in fields.values { precondition(v.count == times.count * grid.size, "field size") }
        self.grid = grid
        self.times = times
        self.fields = fields
        if times.count > 1 {
            let first = times[1] - times[0]
            var even = true
            for i in 1..<times.count where times[i] - times[i - 1] != first { even = false; break }
            timeStep = even ? first : 0
        } else {
            timeStep = 0
        }
    }

    var startMillis: Int64 { times[0] }
    var endMillis: Int64 { times[times.count - 1] }

    func has(_ field: ConditionsForecastField) -> Bool { fields[field] != nil }

    /// The value at a place and time, blended between the hours either side; NaN off the grid or outside the hours.
    func sample(_ field: ConditionsForecastField, lat: Double, lng: Double, time: Int64, coastal: Bool = false) -> Float {
        guard let values = fields[field] else { return .nan }
        return sample(values: values, lat: lat, lng: lng, time: time, coastal: coastal)
    }

    /// As `sample`, with the field's values already looked up (for loops over many points).
    func sample(values: [Float], lat: Double, lng: Double, time: Int64, coastal: Bool = false) -> Float {
        if time < startMillis - Self.hourMs / 2 || time > endMillis + Self.hourMs / 2 { return .nan }
        let hour = hourPosition(time)
        let h0 = min(max(Int(hour), 0), times.count - 1)
        let fh = hour - Double(h0)
        let a = grid.sample(values, offset: h0 * grid.size, lat: lat, lng: lng, strict: !coastal)
        if fh <= 0 || h0 + 1 >= times.count { return a }
        let b = grid.sample(values, offset: (h0 + 1) * grid.size, lat: lat, lng: lng, strict: !coastal)
        if a.isNaN { return b }
        if b.isNaN { return a }
        return Float(Double(a) + Double(b - a) * fh)
    }

    /// A direction-and-strength pair, from its east and north parts.
    func vector(_ u: ConditionsForecastField, _ v: ConditionsForecastField, lat: Double, lng: Double, time: Int64, coastal: Bool = false) -> ConditionsFlow? {
        let east = sample(u, lat: lat, lng: lng, time: time, coastal: coastal)
        let north = sample(v, lat: lat, lng: lng, time: time, coastal: coastal)
        if east.isNaN || north.isNaN { return nil }
        return ConditionsFlow.of(east: Double(east), north: Double(north))
    }

    private func hourPosition(_ time: Int64) -> Double {
        if time <= startMillis { return 0 }
        if time >= endMillis { return Double(times.count - 1) }
        if timeStep > 0 { return Double(time - startMillis) / Double(timeStep) }
        var lo = 0
        var hi = times.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) >> 1
            if times[mid] <= time { lo = mid } else { hi = mid }
        }
        return Double(lo) + Double(time - times[lo]) / Double(times[hi] - times[lo])
    }
}

/// A day's picture of the sea: from satellites, or for `tempBelow` the HYCOM ocean model.
/// Raw values are the Android names (used in saved files).
enum ConditionsSatelliteKind: String, CaseIterable {
    case seaTemp = "SEA_TEMP"
    case chlorophyll = "CHLOROPHYLL"
    /// Height of the sea against its long-term average: eddies stand out. Stored in cm.
    case seaLevel = "SEA_LEVEL"
    case salinity = "SALINITY"
    case tempBelow = "TEMP_BELOW"

    var label: String {
        switch self {
        case .seaTemp: return "Sea temperature"
        case .chlorophyll: return "Chlorophyll"
        case .seaLevel: return "Sea level"
        case .salinity: return "Salinity"
        case .tempBelow: return "Temperature at depth"
        }
    }

    var unit: String {
        switch self {
        case .seaTemp, .tempBelow: return "°C"
        case .chlorophyll: return "mg/m³"
        case .seaLevel: return "cm"
        case .salinity: return "PSU"
        }
    }

    /// A failure to get it is reported (the others often have nothing near a coast).
    var required: Bool { self == .seaTemp || self == .chlorophyll }
}

final class ConditionsSatelliteGrid {
    let kind: ConditionsSatelliteKind
    let grid: ConditionsGeoGrid
    let dateMillis: Int64
    let values: [Float]

    init(kind: ConditionsSatelliteKind, grid: ConditionsGeoGrid, dateMillis: Int64, values: [Float]) {
        precondition(values.count == grid.size, "satellite size")
        self.kind = kind
        self.grid = grid
        self.dateMillis = dateMillis
        self.values = values
    }

    func sample(lat: Double, lng: Double) -> Float { grid.sample(values, offset: 0, lat: lat, lng: lng) }

    /// As `sample`, but any sea corner gives a value: for coarse data, where a finer coastline hides the land.
    func sampleToShore(lat: Double, lng: Double) -> Float { grid.sample(values, offset: 0, lat: lat, lng: lng, strict: false) }

    static let kmPerDegree = 111.195

    /// How fast sea temperature changes across each point, in °C per km: the breaks fish gather on.
    static func temperatureBreaks(_ sst: ConditionsSatelliteGrid) -> ConditionsSatelliteGrid {
        let grid = sst.grid
        var out = [Float](repeating: .nan, count: grid.size)
        func at(_ r: Int, _ c: Int) -> Float {
            (r < 0 || c < 0 || r >= grid.rows || c >= grid.cols) ? .nan : sst.values[grid.index(r, c)]
        }
        /// Change per degree along an axis, from whichever neighbours are sea; 0 with none.
        func slope(_ here: Float, _ lo: Float, _ hi: Float, _ loAt: Double, _ hereAt: Double, _ hiAt: Double) -> Double {
            if !lo.isNaN && !hi.isNaN && hiAt > loAt { return Double(hi - lo) / (hiAt - loAt) }
            if !hi.isNaN && hiAt > hereAt { return Double(hi - here) / (hiAt - hereAt) }
            if !lo.isNaN && hereAt > loAt { return Double(here - lo) / (hereAt - loAt) }
            return 0
        }
        for r in 0..<grid.rows {
            let kmPerLng = kmPerDegree * max(cos(grid.lats[r] * .pi / 180), 0.01)
            for c in 0..<grid.cols {
                let here = at(r, c)
                if here.isNaN { continue }
                let north = slope(here, at(r - 1, c), at(r + 1, c),
                                  grid.lats[max(r - 1, 0)], grid.lats[r], grid.lats[min(r + 1, grid.rows - 1)]) / kmPerDegree
                let east = slope(here, at(r, c - 1), at(r, c + 1),
                                 grid.lngs[max(c - 1, 0)], grid.lngs[c], grid.lngs[min(c + 1, grid.cols - 1)]) / kmPerLng
                out[grid.index(r, c)] = Float(hypot(north, east))
            }
        }
        return ConditionsSatelliteGrid(kind: sst.kind, grid: grid, dateMillis: sst.dateMillis, values: out)
    }
}

/// A saved area: its bounds, and whatever of the forecast and satellite data was downloaded for it.
final class ConditionsArea {
    static let defaultBelowDepthM = 10
    /// Sea conditions stop here: the forecast models don't go nearer the poles.
    static let maxLat = 80.0
    static let maxLng = 180.0

    let id: String
    let name: String
    let south: Double
    let west: Double
    let north: Double
    let east: Double
    let fetchedMillis: Int64
    let forecast: ConditionsForecastGrid?
    let satellites: [ConditionsSatelliteKind: ConditionsSatelliteGrid]
    /// The depth, in metres, `tempBelow` was downloaded for.
    let belowDepthM: Int

    private let breaksLock = NSLock()
    private var breaksValue: ConditionsSatelliteGrid??

    init(id: String, name: String, south: Double, west: Double, north: Double, east: Double, fetchedMillis: Int64,
         forecast: ConditionsForecastGrid?, satellites: [ConditionsSatelliteKind: ConditionsSatelliteGrid],
         belowDepthM: Int = ConditionsArea.defaultBelowDepthM) {
        self.id = id; self.name = name
        self.south = south; self.west = west; self.north = north; self.east = east
        self.fetchedMillis = fetchedMillis
        self.forecast = forecast
        self.satellites = satellites
        self.belowDepthM = belowDepthM
    }

    /// The temperature breaks, worked out once from the saved temperature.
    var breaks: ConditionsSatelliteGrid? {
        breaksLock.lock()
        defer { breaksLock.unlock() }
        if let cached = breaksValue { return cached }
        let value = satellites[.seaTemp].map { ConditionsSatelliteGrid.temperatureBreaks($0) }
        breaksValue = .some(value)
        return value
    }

    func contains(lat: Double, lng: Double) -> Bool { lat >= south && lat <= north && lng >= west && lng <= east }

    /**
     Whether a point is sea by the satellite temperature's coastline (1–2 km
     cells); nil when there's no satellite temperature to tell. Any sea cell
     round the point counts, so the colours reach the beach.
     */
    func isSea(lat: Double, lng: Double) -> Bool? {
        guard let sst = satellites[.seaTemp], sst.grid.contains(lat: lat, lng: lng) else { return nil }
        return !sst.grid.sample(sst.values, offset: 0, lat: lat, lng: lng, strict: false).isNaN
    }

    var isEmpty: Bool { forecast == nil && satellites.isEmpty }

    /**
     In the middle half of the area, well away from its edges. An edge at the
     date line or the polar limit doesn't count: nothing lies beyond it to download.
     */
    func middleContains(lat: Double, lng: Double) -> Bool {
        let dLat = (north - south) / 4
        let dLng = (east - west) / 4
        return (south <= -Self.maxLat || lat >= south + dLat) && (north >= Self.maxLat || lat <= north - dLat) &&
            (west <= -Self.maxLng || lng >= west + dLng) && (east >= Self.maxLng || lng <= east - dLng)
    }

    /// True when the given bounds cover about the same water (a re-download of this area).
    func sameBoundsAs(_ s: Double, _ w: Double, _ n: Double, _ e: Double) -> Bool {
        abs(south - s) < 0.01 && abs(west - w) < 0.01 && abs(north - n) < 0.01 && abs(east - e) < 0.01
    }

    func renamed(_ newName: String) -> ConditionsArea {
        ConditionsArea(id: id, name: newName, south: south, west: west, north: north, east: east, fetchedMillis: fetchedMillis,
                       forecast: forecast, satellites: satellites, belowDepthM: belowDepthM)
    }
}
