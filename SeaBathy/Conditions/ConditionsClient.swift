import Foundation

/// A download or reading problem, with a message fit to show the user.
struct ConditionsError: LocalizedError {
    let message: String
    /// The server is overloaded or down for now: worth asking again.
    var busy = false

    init(_ message: String, busy: Bool = false) {
        self.message = message
        self.busy = busy
    }

    var errorDescription: String? { message }
}

/**
 Downloads sea conditions for an area (Android ConditionsClient.kt). All free, no account:

 - Open-Meteo Marine: surface currents (Météo-France SMOC, tidal flow included,
   about 8 km), waves, swell and sea temperature, hourly for up to 8 days.
   Open-Meteo forecast: wind and gusts at 10 m.
 - NOAA CoastWatch ERDDAP, worldwide, with a backup for each where one exists:
   daily satellite sea temperature (NASA JPL MUR, then NOAA Geo-polar Blended),
   chlorophyll (a 5-day blend), sea level anomaly and salinity.
 - HYCOM: sea temperature below the surface, from its ocean model (about 9 km).

 Every call blocks: run it off the main thread.
 */
final class ConditionsClient {
    static let maxDays = 8
    static let maxSatelliteSide = 250
    private static let pointsPerRequest = 48
    /// Five days of pictures come back as one row per point per day.
    private static let maxBlendSide = 120
    private static let blendDays = 5
    private static let retryPauseMs: UInt32 = 2_500
    /// HYCOM's server often fails at random for a moment, and has no backup.
    private static let hycomAttempts = 5
    /// How far a point may be from the nearest ocean-model cell and still take its values.
    static let seaReachKm = 20.0

    private let http: (String) throws -> String
    private let httpBytes: (String) throws -> Data
    private let pause: (UInt32) -> Void

    init(http: @escaping (String) throws -> String = ConditionsClient.httpGet,
         httpBytes: @escaping (String) throws -> Data = ConditionsClient.httpGetBytes,
         pause: @escaping (UInt32) -> Void = { _ = usleep($0 * 1000) }) {
        self.http = http
        self.httpBytes = httpBytes
        self.pause = pause
    }

    typealias Point = (lat: Double, lng: Double)

    /**
     The forecast for `grid`, `days` days from today; `onProgress` gets 0...1.
     Wind changes slowly across an area, so it is fetched on the coarser
     `windGrid` and blended onto `grid`.
     */
    func forecast(grid: ConditionsGeoGrid, days: Int, windGrid: ConditionsGeoGrid? = nil,
                  onProgress: (Double) -> Void = { _ in }) throws -> ConditionsForecastGrid {
        let windGrid = windGrid ?? grid
        let points = Self.pointsOf(grid)
        let windPoints = Self.pointsOf(windGrid)
        let chunks = Self.chunked(points, Self.pointsPerRequest)
        let windChunks = Self.chunked(windPoints, Self.pointsPerRequest)
        let total = Double(chunks.count + windChunks.count)
        var marine: [PointSeries] = []
        for (i, chunk) in chunks.enumerated() {
            marine += try Self.parseOpenMeteo(try http(Self.marineUrl(chunk, days: days)))
            onProgress(Double(i + 1) / total)
        }
        // Wind is a separate service; a forecast without it still has the sea.
        var weather: [PointSeries]? = []
        do {
            for (i, chunk) in windChunks.enumerated() {
                weather! += try Self.parseOpenMeteo(try http(Self.windUrl(chunk, days: days)))
                onProgress(Double(chunks.count + i + 1) / total)
            }
        } catch {
            weather = nil
        }
        return try Self.assemble(grid: grid, requested: points, marine: marine, weather: weather, windGrid: windGrid)
    }

    private static func pointsOf(_ grid: ConditionsGeoGrid) -> [Point] {
        var out: [Point] = []
        for r in 0..<grid.rows { for c in 0..<grid.cols { out.append((grid.lats[r], grid.lngs[c])) } }
        return out
    }

    private static func chunked(_ points: [Point], _ size: Int) -> [[Point]] {
        stride(from: 0, to: points.count, by: size).map { Array(points[$0..<min($0 + size, points.count)]) }
    }

    /// When the newest forecast run of the models behind the sea forecast came out, or nil when none could be read.
    func latestRunMillis() -> Int64? {
        Self.runStatusUrls.compactMap { url in (try? http(url)).flatMap { Self.parseLatestRun($0) } }.max()
    }

    /**
     The latest picture of `kind` over the area, at most `maxSatelliteSide`
     points a side: from the first of its sources that has it, asking a busy
     server once more before moving on.
     */
    func satellite(_ kind: ConditionsSatelliteKind, south: Double, west: Double, north: Double, east: Double,
                   belowDepthM: Int = ConditionsArea.defaultBelowDepthM) throws -> ConditionsSatelliteGrid {
        if kind == .tempBelow {
            return try retrying(Self.hycomAttempts) {
                try Self.parseHycom(try httpBytes(Self.hycomUrl(south: south, west: west, north: north, east: east, depthM: belowDepthM)), kind: kind)
            }
        }
        var failure: Error?
        for url in Self.satelliteUrls(kind, south: south, west: west, north: north, east: east) {
            do {
                return try retrying(2) { try Self.parseErddapCsv(try http(url), kind: kind) }
            } catch {
                failure = error
            }
        }
        throw failure ?? ConditionsError("No source for \(kind.label)")
    }

    private func retrying<T>(_ attempts: Int, _ fetch: () throws -> T) throws -> T {
        for _ in 0..<max(attempts - 1, 0) {
            do {
                return try fetch()
            } catch let error as ConditionsError where error.busy {
                pause(Self.retryPauseMs)
            }
        }
        return try fetch()
    }

    // Servers ---------------------------------------------------------------------------

    private static let runStatusUrls = [
        "https://marine-api.open-meteo.com/data/meteofrance_currents/static/meta.json",
        "https://marine-api.open-meteo.com/data/meteofrance_wave/static/meta.json",
        "https://marine-api.open-meteo.com/data/ecmwf_wam025/static/meta.json",
        "https://api.open-meteo.com/data/ecmwf_ifs025/static/meta.json",
        "https://api.open-meteo.com/data/ncep_gfs025/static/meta.json"
    ]

    private static let marineVariables = "ocean_current_velocity,ocean_current_direction,wave_height,wave_direction," +
        "wave_period,swell_wave_height,swell_wave_direction,swell_wave_period,sea_surface_temperature"
    private static let windVariables = "wind_speed_10m,wind_direction_10m,wind_gusts_10m"

    private static let pfeg = "https://coastwatch.pfeg.noaa.gov/erddap"
    /// The same data as `pfeg`, on another server.
    private static let upwell = "https://upwell.pfeg.noaa.gov/erddap"
    private static let noaa = "https://coastwatch.noaa.gov/erddap"
    private static let hycom = "https://ncss.hycom.org/thredds/ncss/FMRC_ESPC-D-V02_t3z/FMRC_ESPC-D-V02_t3z_best.ncd"
    private static let hycomStep = 0.08

    /// When a model's latest run became available, from its status file.
    static func parseLatestRun(_ body: String) -> Int64? {
        guard let data = body.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let value = object["last_run_availability_time"] as? NSNumber else { return nil }
        return value.int64Value * 1000
    }

    private static func coords(_ points: [Point]) -> String {
        "latitude=" + points.map { String(format: "%.4f", $0.lat) }.joined(separator: ",") +
            "&longitude=" + points.map { String(format: "%.4f", $0.lng) }.joined(separator: ",")
    }

    static func marineUrl(_ points: [Point], days: Int) -> String {
        "https://marine-api.open-meteo.com/v1/marine?" + coords(points) +
            "&hourly=\(marineVariables)&wind_speed_unit=ms&timeformat=unixtime&cell_selection=sea" +
            "&forecast_days=\(min(max(days, 1), maxDays))"
    }

    static func windUrl(_ points: [Point], days: Int) -> String {
        "https://api.open-meteo.com/v1/forecast?" + coords(points) +
            "&hourly=\(windVariables)&wind_speed_unit=ms&timeformat=unixtime" +
            "&forecast_days=\(min(max(days, 1), maxDays))"
    }

    private static func f3(_ v: Double) -> String { String(format: "%.3f", v) }

    /// Where to get `kind` for the area, best first.
    static func satelliteUrls(_ kind: ConditionsSatelliteKind, south: Double, west: Double, north: Double, east: Double) -> [String] {
        /// One griddap request. Coarse data reaches a cell beyond the area, so its picture fills the area.
        func url(_ base: String, _ dataset: String, _ variable: String, _ resolution: Double, time: String = "last",
                 altitude: Bool = false, northFirst: Bool = false, maxSide: Int = maxSatelliteSide) -> String {
            let pad = resolution >= 0.04 ? resolution : 0
            let latLimit = 90 - resolution / 2
            let lngLimit = 180 - resolution / 2
            let s = min(max(south - pad, -latLimit), latLimit)
            let n = min(max(north + pad, -latLimit), latLimit)
            let w = min(max(west - pad, -lngLimit), lngLimit)
            let e = min(max(east + pad, -lngLimit), lngLimit)
            let stride = max(1, Int(ceil(max(n - s, e - w) / resolution / Double(maxSide))))
            let lat = northFirst ? "(\(f3(n))):\(stride):(\(f3(s)))" : "(\(f3(s))):\(stride):(\(f3(n)))"
            return "\(base)/griddap/\(dataset).csv?\(variable)%5B\(time)%5D" + (altitude ? "%5B(0.0)%5D" : "") +
                "%5B\(lat)%5D%5B(\(f3(w))):\(stride):(\(f3(e)))%5D"
        }
        switch kind {
        case .seaTemp:
            return [
                url(pfeg, "jplMURSST41", "analysed_sst", 0.01),
                url(upwell, "jplMURSST41", "analysed_sst", 0.01),
                url(noaa, "noaacwBLENDEDsstDNDaily", "analysed_sst", 0.05)
            ]
        case .chlorophyll:
            let days = "last-\(blendDays - 1):1:last"
            return [
                url(noaa, "noaacwN20VIIRSchlaDaily", "chlor_a", 0.0375, time: days, altitude: true, northFirst: true, maxSide: maxBlendSide),
                url(pfeg, "nesdisVHNnoaa20chlaDaily", "chlor_a", 0.0375, time: days, altitude: true, northFirst: true, maxSide: maxBlendSide),
                url(noaa, "noaacwS3AOLCIchlaDaily", "chlor_a", 0.0375, time: days, altitude: true, northFirst: true, maxSide: maxBlendSide)
            ]
        case .seaLevel:
            return [url(noaa, "noaacwBLENDEDsshDaily", "sla", 0.25)]
        case .salinity:
            return [
                url(noaa, "noaacwSMAPsssDaily", "sss", 0.25, altitude: true),
                url(noaa, "noaacwSMOSsssDaily", "sss", 0.25, altitude: true)
            ]
        case .tempBelow:
            return []
        }
    }

    /// HYCOM's temperature `depthM` metres down at the moment, over the area.
    static func hycomUrl(south: Double, west: Double, north: Double, east: Double, depthM: Int) -> String {
        let pad = hycomStep
        let stride = max(1, Int(ceil(max(north - south, east - west) / hycomStep / Double(maxSatelliteSide))))
        return "\(hycom)?var=water_temp&south=\(f3(max(south - pad, -80)))&north=\(f3(min(north + pad, 89.9)))" +
            "&west=\(f3(max(west - pad, -180)))&east=\(f3(min(east + pad, 180)))" +
            "&vertCoord=\(depthM)&time=present&horizStride=\(stride)&accept=netcdf"
    }

    // HTTP ---------------------------------------------------------------------------------

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 180
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    private final class ResultBox: @unchecked Sendable {
        var data: Data?
        var response: URLResponse?
        var error: Error?
    }

    /// Blocking GET. Throws a ConditionsError naming the service when it answers with an error.
    static func fetch(_ urlString: String) throws -> Data {
        guard let url = URL(string: urlString) else { throw ConditionsError("Bad address") }
        var request = URLRequest(url: url)
        request.setValue("SeaBathy (iOS sea conditions)", forHTTPHeaderField: "User-Agent")
        let box = ResultBox()
        let done = DispatchSemaphore(value: 0)
        session.dataTask(with: request) { data, response, error in
            box.data = data
            box.response = response
            box.error = error
            done.signal()
        }.resume()
        done.wait()
        if let error = box.error { throw error }
        let data = box.data ?? Data()
        let code = (box.response as? HTTPURLResponse)?.statusCode ?? 200
        if !(200...299).contains(code) {
            throw serviceError(urlString, code: code, body: String(decoding: data, as: UTF8.self))
        }
        return data
    }

    static func httpGet(_ url: String) throws -> String { String(decoding: try fetch(url), as: UTF8.self) }
    static func httpGetBytes(_ url: String) throws -> Data { try fetch(url) }

    private static func serviceError(_ url: String, code: Int, body: String) -> ConditionsError {
        let service: String
        if url.contains("open-meteo") { service = "Open-Meteo" } else if url.contains("hycom") { service = "HYCOM" } else { service = "NOAA" }
        var reason: String?
        if let data = body.data(using: .utf8),
           let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            reason = object["reason"] as? String
        }
        if reason == nil, let regex = try? NSRegularExpression(pattern: "message=\"([^\"]+)\""),
           let match = regex.firstMatch(in: body, range: NSRange(body.startIndex..., in: body)),
           let range = Range(match.range(at: 1), in: body) {
            reason = String(body[range])
        }
        if code == 429 { return ConditionsError("\(service) is busy (too many requests). Try again in a minute.") }
        if (500...599).contains(code) { return ConditionsError("\(service) isn't answering just now (\(code))", busy: true) }
        if let reason { return ConditionsError("\(service): \(reason)") }
        return ConditionsError("\(service) answered \(code)")
    }

    /// A short reason for a failed download, for the user.
    static func message(_ error: Error) -> String {
        if let e = error as? ConditionsError { return e.message }
        if let e = error as? URLError {
            switch e.code {
            case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost,
                 .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
                return "no internet connection"
            case .timedOut:
                return "the service didn't answer in time"
            default:
                return e.localizedDescription
            }
        }
        return error.localizedDescription
    }

    // Parsing ------------------------------------------------------------------------------

    /// One forecast point's hourly series, as Open-Meteo answered it.
    final class PointSeries {
        let lat: Double
        let lng: Double
        let times: [Int64]
        let values: [String: [Double]]

        init(lat: Double, lng: Double, times: [Int64], values: [String: [Double]]) {
            self.lat = lat; self.lng = lng; self.times = times; self.values = values
        }
    }

    private static func number(_ any: Any?) -> Double? {
        guard let n = any as? NSNumber else { return nil }
        return n.doubleValue
    }

    /// An Open-Meteo answer: one location is an object, several an array.
    static func parseOpenMeteo(_ body: String) throws -> [PointSeries] {
        guard let data = body.data(using: .utf8), let root = try? JSONSerialization.jsonObject(with: data) else {
            throw ConditionsError("Open-Meteo sent an unreadable answer")
        }
        let items: [Any]
        if let array = root as? [Any] { items = array } else { items = [root] }
        return try items.map { item -> PointSeries in
            guard let o = item as? [String: Any] else { throw ConditionsError("Open-Meteo sent an unreadable answer") }
            if (o["error"] as? Bool) == true {
                throw ConditionsError("Open-Meteo: \(o["reason"] as? String ?? "error")")
            }
            guard let hourly = o["hourly"] as? [String: Any] else { throw ConditionsError("Open-Meteo sent no hourly data") }
            let rawTimes = hourly["time"] as? [Any] ?? []
            let times = rawTimes.map { Int64((number($0) ?? 0) * 1000) }
            var values: [String: [Double]] = [:]
            for (key, value) in hourly where key != "time" {
                let array = value as? [Any] ?? []
                values[key] = (0..<times.count).map { i in i < array.count ? (number(array[i]) ?? .nan) : .nan }
            }
            return PointSeries(lat: number(o["latitude"]) ?? .nan, lng: number(o["longitude"]) ?? .nan, times: times, values: values)
        }
    }

    private static func finite(_ p: PointSeries, _ key: String, _ k: Int) -> Double? {
        guard let array = p.values[key], k >= 0, k < array.count, array[k].isFinite else { return nil }
        return array[k]
    }

    private static func vector(_ p: PointSeries, _ speedKey: String, _ directionKey: String, _ k: Int, towards: Bool) -> (Float, Float)? {
        guard let speed = finite(p, speedKey, k), let direction = finite(p, directionKey, k) else { return nil }
        let parts = ConditionsFlow.parts(speed: speed, towardsDegrees: towards ? direction : direction + 180)
        return (Float(parts.east), Float(parts.north))
    }

    /**
     Puts the points' series onto the grid. Open-Meteo moves a point to the
     nearest cell of its ocean model; a point moved further than `reachKm` is
     inland, and its sea values are left blank. Wind is kept everywhere.
     */
    static func assemble(grid: ConditionsGeoGrid, requested: [Point], marine: [PointSeries], weather: [PointSeries]?,
                         reachKm: Double = seaReachKm, windGrid: ConditionsGeoGrid? = nil) throws -> ConditionsForecastGrid {
        let windGrid = windGrid ?? grid
        if marine.count != requested.count { throw ConditionsError("Open-Meteo sent \(marine.count) of \(requested.count) points") }
        guard let first = marine.first else { throw ConditionsError("There's no sea forecast for this area (all land?)") }
        let times = first.times
        let hours = times.count
        if hours == 0 { throw ConditionsError("Open-Meteo sent no hourly data") }
        var fields: [ConditionsForecastField: [Float]] = [:]
        func set(_ f: ConditionsForecastField, _ at: Int, _ value: Float) {
            if fields[f] == nil { fields[f] = [Float](repeating: .nan, count: hours * grid.size) }
            fields[f]![at] = value
        }
        for i in requested.indices {
            let p = marine[i]
            let distanceM = GeoMath.distanceNm(requested[i].lat, requested[i].lng, p.lat, p.lng) * GeoMath.metresPerNm
            if !(distanceM <= reachKm * 1000) { continue }
            var byTime: [Int64: Int]?
            if p.times != times {
                var map: [Int64: Int] = [:]
                for (index, t) in p.times.enumerated() { map[t] = index }
                byTime = map
            }
            for h in 0..<hours {
                let k: Int
                if let byTime {
                    guard let found = byTime[times[h]] else { continue }
                    k = found
                } else {
                    k = h
                }
                let at = h * grid.size + i
                if let uv = vector(p, "ocean_current_velocity", "ocean_current_direction", k, towards: true) {
                    set(.currentU, at, uv.0)
                    set(.currentV, at, uv.1)
                }
                for (heightKey, directionKey, periodKey, height, u, v, period) in [
                    ("wave_height", "wave_direction", "wave_period", ConditionsForecastField.waveHeight, ConditionsForecastField.waveU, ConditionsForecastField.waveV, ConditionsForecastField.wavePeriod),
                    ("swell_wave_height", "swell_wave_direction", "swell_wave_period", ConditionsForecastField.swellHeight, ConditionsForecastField.swellU, ConditionsForecastField.swellV, ConditionsForecastField.swellPeriod)
                ] {
                    guard let hValue = finite(p, heightKey, k) else { continue }
                    set(height, at, Float(hValue))
                    if let parts = vector(p, heightKey, directionKey, k, towards: false) {
                        // A unit direction: the height is blended on its own.
                        let length = hypot(parts.0, parts.1)
                        if length > 0 {
                            set(u, at, parts.0 / length)
                            set(v, at, parts.1 / length)
                        }
                    }
                    if let periodValue = finite(p, periodKey, k) { set(period, at, Float(periodValue)) }
                }
                if let t = finite(p, "sea_surface_temperature", k) { set(.seaTemp, at, Float(t)) }
            }
        }
        if let weather, weather.count == windGrid.size {
            // Wind on its own grid first, then onto the sea points.
            var windU = [Float](repeating: .nan, count: hours * windGrid.size)
            var windV = windU
            var gust = windU
            for i in 0..<windGrid.size {
                let p = weather[i]
                var byTime: [Int64: Int] = [:]
                for (index, t) in p.times.enumerated() { byTime[t] = index }
                for h in 0..<hours {
                    guard let k = byTime[times[h]] else { continue }
                    let at = h * windGrid.size + i
                    if let uv = vector(p, "wind_speed_10m", "wind_direction_10m", k, towards: false) {
                        windU[at] = uv.0
                        windV[at] = uv.1
                    }
                    if let g = finite(p, "wind_gusts_10m", k) { gust[at] = Float(g) }
                }
            }
            for i in requested.indices {
                let lat = requested[i].lat, lng = requested[i].lng
                for h in 0..<hours {
                    let at = h * grid.size + i
                    let offset = h * windGrid.size
                    let u = windGrid.sample(windU, offset: offset, lat: lat, lng: lng, strict: false)
                    let v = windGrid.sample(windV, offset: offset, lat: lat, lng: lng, strict: false)
                    if !u.isNaN && !v.isNaN {
                        set(.windU, at, u)
                        set(.windV, at, v)
                    }
                    let g = windGrid.sample(gust, offset: offset, lat: lat, lng: lng, strict: false)
                    if !g.isNaN { set(.gust, at, g) }
                }
            }
        }
        if fields.isEmpty { throw ConditionsError("There's no sea forecast for this area (all land?)") }
        return ConditionsForecastGrid(grid: grid, times: times, fields: fields)
    }

    private static func isoMillis(_ text: String) -> Int64? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: text) { return Int64(d.timeIntervalSince1970 * 1000) }
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: text) { return Int64(d.timeIntervalSince1970 * 1000) }
        return nil
    }

    /**
     An ERDDAP griddap CSV (a names row, a units row, then
     time,[altitude,]lat,lon,value rows). Several days of a point are blended
     (chlorophyll on a log scale); the date given is the newest. Sea level comes
     in metres and is kept in cm.
     */
    static func parseErddapCsv(_ body: String, kind: ConditionsSatelliteKind) throws -> ConditionsSatelliteGrid {
        let lower = kind.label.lowercased()
        let lines = body.split(whereSeparator: { $0 == "\n" || $0 == "\r\n" || $0 == "\r" })
            .map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        if lines.count < 3 { throw ConditionsError("NOAA sent no \(lower) for this area") }
        let names = lines[0].components(separatedBy: ",")
        guard let latCol = names.firstIndex(of: "latitude"), let lngCol = names.firstIndex(of: "longitude") else {
            throw ConditionsError("NOAA sent an unexpected table")
        }
        let timeCol = names.firstIndex(of: "time")
        let valueCol = names.count - 1
        var latSet = Set<Double>()
        var lngSet = Set<Double>()
        var rows: [(Double, Double, Double)] = []
        rows.reserveCapacity(lines.count)
        var dateMillis: Int64 = 0
        var lastTime = ""
        for line in lines.dropFirst(2) {
            let cells = line.components(separatedBy: ",")
            if cells.count <= valueCol { continue }
            guard let lat = Double(cells[latCol]), let lng = Double(cells[lngCol]) else { continue }
            let parsed = Double(cells[valueCol]) ?? .nan
            if let timeCol, cells[timeCol] != lastTime {
                lastTime = cells[timeCol]
                dateMillis = max(dateMillis, isoMillis(lastTime) ?? 0)
            }
            latSet.insert(lat)
            lngSet.insert(lng)
            rows.append((lat, lng, parsed))
        }
        if latSet.isEmpty || lngSet.isEmpty { throw ConditionsError("NOAA sent no \(lower) for this area") }
        let grid = ConditionsGeoGrid(lats: latSet.sorted(), lngs: lngSet.sorted())
        var latIndex: [Double: Int] = [:]
        for (i, v) in grid.lats.enumerated() { latIndex[v] = i }
        var lngIndex: [Double: Int] = [:]
        for (i, v) in grid.lngs.enumerated() { lngIndex[v] = i }
        var sums = [Double](repeating: 0, count: grid.size)
        var counts = [Int](repeating: 0, count: grid.size)
        let blendLogs = kind == .chlorophyll
        for (lat, lng, value) in rows {
            if !value.isFinite || (blendLogs && value <= 0) { continue }
            guard let r = latIndex[lat], let c = lngIndex[lng] else { continue }
            let at = grid.index(r, c)
            sums[at] += blendLogs ? log(value) : value
            counts[at] += 1
        }
        if counts.allSatisfy({ $0 == 0 }) { throw ConditionsError("NOAA has no \(lower) here today (land, or no data)") }
        var values = [Float](repeating: .nan, count: grid.size)
        for i in 0..<grid.size where counts[i] > 0 {
            let mean = sums[i] / Double(counts[i])
            switch kind {
            case .chlorophyll: values[i] = Float(exp(mean))
            case .seaLevel: values[i] = Float(mean * 100)
            default: values[i] = Float(mean)
            }
        }
        return ConditionsSatelliteGrid(kind: kind, grid: grid, dateMillis: dateMillis, values: values)
    }

    /// HYCOM's NetCDF answer as a picture. Its longitudes may run 0 to 360.
    static func parseHycom(_ bytes: Data, kind: ConditionsSatelliteKind) throws -> ConditionsSatelliteGrid {
        let file = try ConditionsNetCdf.read(bytes)
        guard let temperature = file["water_temp"] else { throw ConditionsError("HYCOM sent no temperature") }
        guard let latAxis = file["lat"]?.values else { throw ConditionsError("HYCOM sent no latitudes") }
        guard let rawLngs = file["lon"]?.values else { throw ConditionsError("HYCOM sent no longitudes") }
        // West of the date line it counts on past 180, starting from 180 itself.
        let pastDateLine = rawLngs.contains { $0 > 180 }
        let lngAxis = rawLngs.map { ($0 > 180 || (pastDateLine && $0 == 180)) ? $0 - 360 : $0 }
        let rows = latAxis.count
        let cols = lngAxis.count
        if rows == 0 || cols == 0 || temperature.values.count != rows * cols { throw ConditionsError("HYCOM sent an unexpected table") }
        let rowOrder = latAxis.indices.sorted { latAxis[$0] < latAxis[$1] }
        let colOrder = lngAxis.indices.sorted { lngAxis[$0] < lngAxis[$1] }
        let grid = ConditionsGeoGrid(lats: rowOrder.map { latAxis[$0] }, lngs: colOrder.map { lngAxis[$0] })
        let fill = temperature.number("_FillValue") ?? temperature.number("missing_value")
        let scale = temperature.number("scale_factor") ?? 1
        let offset = temperature.number("add_offset") ?? 0
        var any = false
        var values = [Float](repeating: .nan, count: grid.size)
        for r in 0..<rows {
            for c in 0..<cols {
                let raw = temperature.values[rowOrder[r] * cols + colOrder[c]]
                if raw.isNaN || raw == fill { continue }
                values[grid.index(r, c)] = Float(raw * scale + offset)
                any = true
            }
        }
        if !any { throw ConditionsError("HYCOM has no temperature this deep here (land or shallow water)") }
        return ConditionsSatelliteGrid(kind: kind, grid: grid, dateMillis: hycomTime(file["time"]), values: values)
    }

    /// "hours since 2000-01-01 12:00:00" and a number, as a date.
    private static func hycomTime(_ time: ConditionsNetCdf.Variable?) -> Int64 {
        guard let time, let hours = time.values.first else { return 0 }
        let units = time.text("units") ?? ""
        guard let regex = try? NSRegularExpression(pattern: #"hours since (\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})"#),
              let match = regex.firstMatch(in: units, range: NSRange(units.startIndex..., in: units)),
              let day = Range(match.range(at: 1), in: units), let clock = Range(match.range(at: 2), in: units),
              let base = isoMillis("\(units[day])T\(units[clock])Z") else { return 0 }
        return base + Int64(hours * 3_600_000)
    }
}
