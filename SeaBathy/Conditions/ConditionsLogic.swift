import Foundation

/**
 The sea-conditions rules and wording that need no screen or map (Android
 ConditionsFeature's companion object and its pure helpers). Kept outside the
 @MainActor feature so background work and tests can use them.
 */
enum ConditionsLogic {
    /// The area kept round the spot has this id, so each update replaces it.
    static let spotId = "spot"
    static let spotName = "Round my spot"
    static let spotRadiusKm = 200.0
    /// Largest area: about 500 km a side, so the forecast points stay close enough to be useful.
    static let maxSpanDeg = 4.5
    static let minSpanDeg = 0.3
    /// The current model is about 0.08° apart; closer points add nothing.
    static let minStepDeg = 0.08
    /// 16 a side is 256 sea points and the wind's 10 a side 100: about 360 of Open-Meteo's 600 requests a minute.
    static let maxPointsPerSide = 16
    static let maxWindPointsPerSide = 10
    /// Between downloads, so two never land in the same minute of Open-Meteo's limit.
    static let minDownloadGapMs: Int64 = 75_000
    static let maxArrowsPerSide = 12
    static let forecastPictureSide = 320
    static let maxPictureSide = 512
    /// The forecast picture is redrawn as the time moves on by this much.
    static let pictureStepMs: Int64 = 10 * 60_000
    static let knotsPerMs = 1.943844
    /// Depths the ocean model reports exactly, in metres.
    static let belowDepths = [10, 20, 30, 50, 100, 150, 200, 300]
    static let dayChoices = [1, 2, 3, 5, 7, 8]
    static let rowOrder = ["Wind", "Current", "Waves", "Swell", "Sea", "Depth", "Water"]
    /// What "Show on the map" offers, in order (the forecast's own sea temperature is left out: the satellite's is better).
    static let mapChoices: [ConditionsColourChoice] = [
        .wind, .current, .waves, .swell, .seaTempSatellite, .seaTempBreaks, .tempBelow, .chlorophyll, .seaLevel, .salinity
    ]
    /// Layers whose own direction the "same as the map layer" lines show.
    static let ownFlow: Set<ConditionsColourChoice> = [.wind, .waves, .swell, .current]

    static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // Wording ------------------------------------------------------------------------------

    private static func formatted(_ millis: Int64, _ pattern: String) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = pattern
        return f.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// In the phone's language at the time.
    static func clock(_ millis: Int64) -> String { formatted(millis, "EEE d MMM HH:mm") }
    static func day(_ millis: Int64) -> String { formatted(millis, "EEE d MMM") }

    static func dayText(_ days: Int) -> String { days == 1 ? "1 day" : "\(days) days" }

    static func autoLabel(_ hours: Int) -> String { hours > 0 ? "When there's a new forecast" : "Off (only when you tap Update now)" }

    /// How long ago, said simply.
    static func ago(_ millis: Int64, _ nowMillis: Int64) -> String {
        let minutes = (nowMillis - millis) / 60_000
        if minutes < 1 { return "just now" }
        if minutes < 90 { return "\(minutes) min ago" }
        if minutes < 48 * 60 { return "\(minutes / 60) h ago" }
        return "\(minutes / (24 * 60)) days ago"
    }

    /// South, west, north and east of a square `radiusKm` each way, stopping at the date line (the data services do too).
    static func boxAround(lat: Double, lng: Double, radiusKm: Double) -> [Double] {
        let dLat = radiusKm / 111.0
        let latRadians: Double = lat * Double.pi / 180.0
        let squeeze: Double = max(cos(latRadians), 0.2)
        let dLng: Double = radiusKm / (111.0 * squeeze)
        return [max(lat - dLat, -ConditionsArea.maxLat), max(lng - dLng, -ConditionsArea.maxLng),
                min(lat + dLat, ConditionsArea.maxLat), min(lng + dLng, ConditionsArea.maxLng)]
    }

    /// Says how old saved data is when offline (after half an hour) or when it hasn't updated for half a day.
    static func staleNote(fetchedMillis: Int64, nowMillis: Int64, online: Bool) -> String? {
        let age = nowMillis - fetchedMillis
        let agoText = ago(fetchedMillis, nowMillis)
        if !online && age > 30 * 60_000 { return "Offline: saved data from \(agoText) (\(clock(fetchedMillis)))" }
        if age > ConditionsPolicy.maxAgeMs { return "Old data: downloaded \(agoText)" }
        return nil
    }

    static func defaultName(south: Double, west: Double, north: Double, east: Double) -> String {
        let lat = (south + north) / 2
        let lng = (west + east) / 2
        return String(format: "%.2f°%@ %.2f°%@", abs(lat), lat < 0 ? "S" : "N", abs(lng), lng < 0 ? "W" : "E")
    }

    private static let points = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]

    static func compass(_ degrees: Double) -> String {
        let d = (degrees.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return points[Int(d / 45 + 0.5) % 8]
    }

    /// A legend number: whole above 10, one decimal below, plain 0.
    static func legendNumber(_ value: Double, _ unit: String) -> String {
        if abs(value) < 0.05 { return "0 \(unit)" }
        if abs(value) >= 10 { return "\(Int(value.rounded())) \(unit)" }
        return String(format: "%.1f %@", value, unit)
    }

    static func legendSpeed(_ knots: Double, _ unit: SeaSpeedUnit) -> String {
        unit == .beaufort ? unit.format(knots) : legendNumber(knots * unit.perKnot, unit.label)
    }

    static func legendValue(_ value: Double, colour: ConditionsColourChoice, units: NavUnits) -> String {
        let format = NavFormat(units: units)
        switch colour {
        case .current: return legendSpeed(value, units.current)
        case .wind: return legendSpeed(value, units.wind)
        case .waves, .swell: return legendNumber(units.waves.of(value), units.waves.label)
        case .seaTempSatellite, .seaTempForecast, .tempBelow: return format.temperature(value)
        case .seaTempBreaks: return String(format: "%.2f %@/km", units.temperature.ofDifference(value), units.temperature.label)
        case .chlorophyll: return String(format: value < 1 ? "%.2f" : "%.1f", value) + " mg/m³"
        case .seaLevel: return format.seaLevel(value)
        case .salinity: return String(format: "%.1f PSU", value)
        case .none: return ""
        }
    }

    // Values box ---------------------------------------------------------------------------

    /// One line of the values box: an arrow pointing `towardsDegrees` (none for nil), its text, and which row it is.
    struct Value: Equatable {
        let towardsDegrees: Double?
        let text: String
        let row: String

        init(_ towardsDegrees: Double?, _ text: String, row: String? = nil) {
            self.towardsDegrees = towardsDegrees
            self.text = text
            self.row = row ?? String(text.prefix { $0 != " " })
        }
    }

    /**
     The values at a point and time, in the user's units. With satellite
     temperature saved, its coastline says where the sea is.
     */
    static func values(_ a: ConditionsArea, lat: Double, lng: Double, time: Int64, format: NavFormat) -> [Value] {
        var out: [Value] = []
        let masked = a.satellites[.seaTemp] != nil
        let f = (!masked || a.isSea(lat: lat, lng: lng) != false) ? a.forecast : nil
        if let f {
            if let current = f.vector(.currentU, .currentV, lat: lat, lng: lng, time: time, coastal: masked) {
                out.append(Value(current.towardsDegrees, "Current \(format.current(current.speed * knotsPerMs)) to \(compass(current.towardsDegrees))"))
            }
            if let wind = f.vector(.windU, .windV, lat: lat, lng: lng, time: time, coastal: masked) {
                let gust = f.sample(.gust, lat: lat, lng: lng, time: time, coastal: masked)
                var text = "Wind \(format.wind(wind.speed * knotsPerMs)) from \(compass(wind.fromDegrees))"
                if gust.isFinite && Double(gust) > wind.speed * 1.15 { text += ", gusts \(format.wind(Double(gust) * knotsPerMs))" }
                out.append(Value(wind.towardsDegrees, text))
            }
            if let v = wave(f, "Waves", .waveHeight, .wavePeriod, .waveU, .waveV, lat, lng, time, format, masked) { out.append(v) }
            if let v = wave(f, "Swell", .swellHeight, .swellPeriod, .swellU, .swellV, lat, lng, time, format, masked) { out.append(v) }
        }
        var water: [String] = []
        let satTemp = a.satellites[.seaTemp]?.sample(lat: lat, lng: lng)
        let modelTemp = f?.sample(.seaTemp, lat: lat, lng: lng, time: time, coastal: masked)
        if let satTemp, satTemp.isFinite {
            water.append("Surface \(format.temperature(Double(satTemp)))")
        } else if let modelTemp, modelTemp.isFinite {
            water.append("Surface \(format.temperature(Double(modelTemp)))")
        }
        if let chl = a.satellites[.chlorophyll]?.sample(lat: lat, lng: lng), chl.isFinite, a.isSea(lat: lat, lng: lng) != false {
            water.append(String(format: chl < 1 ? "chlorophyll %.2f mg/m³" : "chlorophyll %.1f mg/m³", Double(chl)))
        }
        if !water.isEmpty { out.append(Value(nil, water.joined(separator: " · "), row: "Sea")) }

        // The coarser layers, which change slowly: the temperature at depth, then salinity and sea level.
        if a.isSea(lat: lat, lng: lng) != false {
            func coarse(_ kind: ConditionsSatelliteKind) -> Float? {
                guard let sat = a.satellites[kind] else { return nil }
                let v = masked ? sat.sampleToShore(lat: lat, lng: lng) : sat.sample(lat: lat, lng: lng)
                return v.isFinite ? v : nil
            }
            if let below = coarse(.tempBelow) {
                out.append(Value(nil, "\(format.depth(Double(a.belowDepthM))) down \(format.temperature(Double(below)))", row: "Depth"))
            }
            var surface: [String] = []
            if let s = coarse(.salinity) { surface.append(String(format: "Salinity %.1f", Double(s))) }
            if let l = coarse(.seaLevel) { surface.append("sea level \(format.seaLevel(Double(l)))") }
            if !surface.isEmpty { out.append(Value(nil, surface.joined(separator: " · "), row: "Water")) }
        }
        return out
    }

    private static func wave(_ f: ConditionsForecastGrid, _ name: String, _ height: ConditionsForecastField, _ period: ConditionsForecastField,
                             _ u: ConditionsForecastField, _ v: ConditionsForecastField, _ lat: Double, _ lng: Double,
                             _ time: Int64, _ format: NavFormat, _ coastal: Bool) -> Value? {
        let h = f.sample(height, lat: lat, lng: lng, time: time, coastal: coastal)
        if !h.isFinite { return nil }
        let p = f.sample(period, lat: lat, lng: lng, time: time, coastal: coastal)
        let flow = f.vector(u, v, lat: lat, lng: lng, time: time, coastal: coastal)
        var text = "\(name) \(format.waveHeight(Double(h)))"
        if p.isFinite { text += String(format: ", %.0f s", Double(p)) }
        if let flow { text += " from \(compass(flow.fromDegrees))" }
        return Value(flow?.towardsDegrees, text)
    }

    /// The box line that shows `layer`.
    static func rowNameOf(_ layer: ConditionsColourChoice) -> String? {
        switch layer {
        case .wind: return "Wind"
        case .current: return "Current"
        case .waves: return "Waves"
        case .swell: return "Swell"
        case .seaTempSatellite, .seaTempBreaks, .seaTempForecast, .chlorophyll: return "Sea"
        case .tempBelow: return "Depth"
        case .seaLevel, .salinity: return "Water"
        case .none: return nil
        }
    }

    /// What tapping a box line shows on the map, with `colour` the layer showing now.
    static func layerOfRow(_ name: String, area a: ConditionsArea, colour: ConditionsColourChoice) -> ConditionsColourChoice? {
        switch name {
        case "Wind": return .wind
        case "Current": return .current
        case "Waves": return .waves
        case "Swell": return .swell
        case "Sea": return a.satellites[.seaTemp] != nil ? .seaTempSatellite : .seaTempForecast
        case "Depth": return .tempBelow
        case "Water":
            // Keeps the layer showing when it's one of this line's.
            if rowNameOf(colour) == "Water", let kind = colour.satellite, a.satellites[kind] != nil { return colour }
            return a.satellites[.seaLevel] != nil ? .seaLevel : .salinity
        default: return nil
        }
    }

    // Drawing ---------------------------------------------------------------------------

    typealias Sampler = (Double, Double) -> Float

    /**
     What the colours show at a point. With satellite temperature saved, its
     1 km coastline masks the land, so the coarse forecast reaches the shore.
     */
    static func sampler(_ a: ConditionsArea, _ choice: ConditionsColourChoice, _ time: Int64) -> Sampler? {
        let hasSst = a.satellites[.seaTemp] != nil
        guard let raw = rawSampler(a, choice, time, coastal: hasSst) else { return nil }
        if choice == .seaTempSatellite || choice == .seaTempBreaks || !hasSst { return raw }
        return { lat, lng in a.isSea(lat: lat, lng: lng) == false ? Float.nan : raw(lat, lng) }
    }

    private static func rawSampler(_ a: ConditionsArea, _ choice: ConditionsColourChoice, _ time: Int64, coastal: Bool) -> Sampler? {
        let f = a.forecast
        switch choice {
        case .none:
            return nil
        case .seaTempSatellite, .chlorophyll:
            guard let kind = choice.satellite, let sat = a.satellites[kind] else { return nil }
            return { lat, lng in sat.sample(lat: lat, lng: lng) }
        case .seaTempBreaks:
            guard let breaks = a.breaks else { return nil }
            return { lat, lng in breaks.sample(lat: lat, lng: lng) }
        // Coarse: with a finer coastline to hide the land, they reach the shore.
        case .tempBelow, .seaLevel, .salinity:
            guard let kind = choice.satellite, let sat = a.satellites[kind] else { return nil }
            return { lat, lng in coastal ? sat.sampleToShore(lat: lat, lng: lng) : sat.sample(lat: lat, lng: lng) }
        case .current:
            guard let f else { return nil }
            return magnitude(f, .currentU, .currentV, time, knotsPerMs, coastal)
        case .wind:
            guard let f else { return nil }
            return magnitude(f, .windU, .windV, time, knotsPerMs, coastal)
        case .waves, .swell, .seaTempForecast:
            guard let f else { return nil }
            let field: ConditionsForecastField = choice == .waves ? .waveHeight : (choice == .swell ? .swellHeight : .seaTemp)
            guard let values = f.fields[field] else { return nil }
            return { lat, lng in f.sample(values: values, lat: lat, lng: lng, time: time, coastal: coastal) }
        }
    }

    private static func magnitude(_ f: ConditionsForecastGrid, _ u: ConditionsForecastField, _ v: ConditionsForecastField,
                                  _ time: Int64, _ factor: Double, _ coastal: Bool) -> Sampler? {
        guard let us = f.fields[u], let vs = f.fields[v] else { return nil }
        return { lat, lng in
            let east = f.sample(values: us, lat: lat, lng: lng, time: time, coastal: coastal)
            let north = f.sample(values: vs, lat: lat, lng: lng, time: time, coastal: coastal)
            if east.isNaN || north.isNaN { return Float.nan }
            return Float(hypot(Double(east), Double(north)) * factor)
        }
    }

    /// The colour range; the stretched ones come from all of the area's values, so they hold still as the time changes.
    static func scale(_ a: ConditionsArea, _ choice: ConditionsColourChoice, range: (Double, Double)?) -> ConditionsColourScale {
        if choice.isTemperature, let range { return ConditionsColourScale.temperatureRange(low: range.0, high: range.1) }
        let values: [Float]?
        switch choice {
        case .seaTempBreaks: values = a.breaks?.values
        case .seaTempForecast: values = a.forecast?.fields[.seaTemp]
        default: values = choice.satellite.flatMap { a.satellites[$0]?.values }
        }
        return ConditionsColourScale.forChoice(choice, values: values)
    }

    /// The moving lines' field for `choice` at `time`, or nil when there's nothing to show.
    static func flowField(_ a: ConditionsArea, _ choice: ConditionsArrowChoice, _ time: Int64) -> ConditionsFlowLinesView.Field? {
        guard let f = a.forecast, let fields = choice.fields, let us = f.fields[fields.0], let vs = f.fields[fields.1] else { return nil }
        let masked = a.satellites[.seaTemp] != nil
        var heights: [Float]?
        switch choice {
        case .waves: heights = f.fields[.waveHeight]
        case .swell: heights = f.fields[.swellHeight]
        default: heights = nil
        }
        if (choice == .waves || choice == .swell) && heights == nil { return nil }
        let heightValues = heights
        return { lat, lng in
            if masked && a.isSea(lat: lat, lng: lng) == false { return nil }
            let east = f.sample(values: us, lat: lat, lng: lng, time: time, coastal: masked)
            let north = f.sample(values: vs, lat: lat, lng: lng, time: time, coastal: masked)
            if east.isNaN || north.isNaN { return nil }
            // Waves and swell store a direction only: their height sets the speed.
            var scale: Float = 1
            if let heightValues {
                scale = f.sample(values: heightValues, lat: lat, lng: lng, time: time, coastal: masked)
                if scale.isNaN { return nil }
            }
            return (Double(east * scale), Double(north * scale))
        }
    }

    static func arrowGeoJson(_ a: ConditionsArea, _ choice: ConditionsArrowChoice, _ time: Int64) -> String {
        guard let f = a.forecast, let fields = choice.fields else { return MapLayerIds.emptyGeoJSON }
        let reference = choice.reference
        var parts: [String] = []
        // No more than about a dozen arrows across: the flow lines show the detail.
        let stride = max(1, (max(f.grid.rows, f.grid.cols) + maxArrowsPerSide - 1) / maxArrowsPerSide)
        for r in Swift.stride(from: 0, to: f.grid.rows, by: stride) {
            for c in Swift.stride(from: 0, to: f.grid.cols, by: stride) {
                let lat = f.grid.lats[r]
                let lng = f.grid.lngs[c]
                if a.isSea(lat: lat, lng: lng) == false { continue }
                guard let flow = f.vector(fields.0, fields.1, lat: lat, lng: lng, time: time) else { continue }
                let strength: Double
                switch choice {
                case .waves: strength = Double(f.sample(.waveHeight, lat: lat, lng: lng, time: time))
                case .swell: strength = Double(f.sample(.swellHeight, lat: lat, lng: lng, time: time))
                default: strength = flow.speed
                }
                if !strength.isFinite || strength < 0.01 { continue }
                let size = 0.3 + 0.7 * min(1, strength / reference)
                parts.append(String(format: #"{"type":"Feature","geometry":{"type":"Point","coordinates":[%.5f,%.5f]},"properties":{"rot":%.1f,"size":%.2f}}"#,
                                    lng, lat, flow.towardsDegrees, size))
            }
        }
        return #"{"type":"FeatureCollection","features":["# + parts.joined(separator: ",") + "]}"
    }

    /// Data credits for an area on show.
    static func credits(_ a: ConditionsArea) -> [String] {
        var sources: [String] = []
        if a.forecast != nil { sources.append("Open-Meteo (CC BY 4.0): Copernicus Marine, Météo-France, ECMWF, DWD, NOAA") }
        if a.satellites.keys.contains(where: { $0 != .tempBelow }) { sources.append("NOAA CoastWatch") }
        if a.satellites[.seaTemp] != nil { sources.append("NASA JPL MUR") }
        if a.satellites[.seaLevel] != nil || a.satellites[.chlorophyll] != nil { sources.append("contains modified Copernicus Sentinel data") }
        if a.satellites[.seaLevel] != nil { sources.append("AVISO+") }
        if a.satellites[.tempBelow] != nil { sources.append("HYCOM") }
        return sources.isEmpty ? [] : ["Sea: " + sources.joined(separator: ", ")]
    }

    /// The forecast hour shown at `time` (the first not more than half an hour before it).
    static func hourIndex(_ f: ConditionsForecastGrid, _ time: Int64) -> Int {
        f.times.firstIndex { $0 >= time - ConditionsForecastGrid.hourMs / 2 } ?? (f.times.count - 1)
    }
}
