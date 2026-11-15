import Foundation

enum TideMode: String, CaseIterable { case off = "OFF", model = "MODEL", manual = "MANUAL" }

/// What the survey's depths are measured from.
enum TideDatum: String, CaseIterable { case meanSeaLevel = "MEAN_SEA_LEVEL", chartDatum = "CHART_DATUM" }

/**
 How tides are applied. Heights are shown above chart datum (the lowest
 tide, LAT) like a tide table: worked out from the tide model, unless
 `chartDatumAuto` is off and `chartDatumBelowMslM` is typed in. `placeId`
 nil means "follow me": tides for where the boat is, refreshed as it travels.
 */
struct TideSettings: Equatable {
    var mode: TideMode = .model
    var datum: TideDatum = .meanSeaLevel
    var chartDatumAuto = true
    var chartDatumBelowMslM = 0.0
    var manualHeightM = 0.0
    var showChip = true
    /// Add the tide to depth readouts (crosshair, navigation, alarms).
    var correctDepths = true
    var placeId: String? = nil
    /// Set once the user has chosen where tides come from.
    var locationChosen = false

    static let maxChartDatumM = 10.0
    static let maxTideHeightM = 17.0

    /// Chart datum below mean sea level, metres: the biggest tides on Earth put it about 8 m down.
    static func validChartDatum(_ m: Double) -> Bool { m.isFinite && m >= 0 && m <= maxChartDatumM }

    /// A tide height above chart datum, metres: a little below it in odd weather, up to the biggest tides.
    static func validTideHeight(_ m: Double) -> Bool { m.isFinite && m >= -2 && m <= maxTideHeightM }

    /// How far chart datum (LAT) is below mean sea level here: typed in, or from the tide model; nil until known.
    func chartDatumBelowMsl(_ forecast: TideForecast?) -> Double? {
        if !chartDatumAuto { return chartDatumBelowMslM }
        return forecast?.lowestTideM.map { -$0 }
    }

    /**
     The tide to add to survey depths at `timeMillis`: its height above the
     survey's datum. Nil when off or unknown, and, for a chart-datum survey,
     until chart datum is known here, so depths stay as charted.
     */
    func heightAboveDatum(_ forecast: TideForecast?, _ timeMillis: Int64) -> Double? {
        switch mode {
        case .off: return nil
        // A tide table's height is above chart datum.
        case .manual: return datum == .chartDatum ? manualHeightM : manualHeightM - (chartDatumBelowMsl(forecast) ?? 0.0)
        case .model:
            guard let msl = forecast?.curve.heightAt(timeMillis) else { return nil }
            if datum == .meanSeaLevel { return msl }
            return chartDatumBelowMsl(forecast).map { msl + $0 }
        }
    }

    /// What shown heights are measured from: true for chart datum (LAT), false for mean sea level until that is known.
    func showsChartDatum(_ forecast: TideForecast?) -> Bool { mode == .manual || chartDatumBelowMsl(forecast) != nil }

    /// Added to the model's heights (from mean sea level) to show them above chart datum.
    func shownOffset(_ forecast: TideForecast?) -> Double { chartDatumBelowMsl(forecast) ?? 0.0 }

    /// The tide as a tide table gives it at `timeMillis`: metres above chart datum (or mean sea level until known).
    func shownHeight(_ forecast: TideForecast?, _ timeMillis: Int64) -> Double? {
        switch mode {
        case .off: return nil
        case .manual: return manualHeightM
        case .model: return forecast?.curve.heightAt(timeMillis).map { $0 + shownOffset(forecast) }
        }
    }
}

/// Loads and saves TideSettings with Android's SharedPreferences keys.
enum TideSettingsRepository {
    private static let kMode = "tide_mode", kDatum = "tide_datum", kOffset = "tide_datum_offset_m"
    private static let kAuto = "tide_datum_auto", kManual = "tide_manual_m", kChip = "tide_show_chip"
    private static let kCorrect = "tide_correct_depths", kPlace = "tide_place_id", kChosen = "tide_location_chosen"

    static func load(_ p: UserDefaults = .standard) -> TideSettings {
        let d = TideSettings()
        func bool(_ key: String, _ fallback: Bool) -> Bool { p.object(forKey: key) == nil ? fallback : p.bool(forKey: key) }
        let offset = p.string(forKey: kOffset).flatMap { Double($0) }
        var s = TideSettings()
        s.mode = p.string(forKey: kMode).flatMap(TideMode.init(rawValue:)) ?? d.mode
        s.datum = p.string(forKey: kDatum).flatMap(TideDatum.init(rawValue:)) ?? d.datum
        s.chartDatumBelowMslM = offset.flatMap { TideSettings.validChartDatum($0) ? $0 : nil } ?? d.chartDatumBelowMslM
        // In older builds the offset was always typed in; a sensible typed one is kept.
        if p.object(forKey: kAuto) != nil {
            s.chartDatumAuto = p.bool(forKey: kAuto)
        } else {
            s.chartDatumAuto = offset.flatMap { ($0 != 0 && TideSettings.validChartDatum($0)) ? $0 : nil } == nil
        }
        s.manualHeightM = p.string(forKey: kManual).flatMap { Double($0) }.flatMap { TideSettings.validTideHeight($0) ? $0 : nil } ?? d.manualHeightM
        s.showChip = bool(kChip, d.showChip)
        s.correctDepths = bool(kCorrect, d.correctDepths)
        s.placeId = p.string(forKey: kPlace)
        s.locationChosen = bool(kChosen, d.locationChosen)
        return s
    }

    static func save(_ s: TideSettings, _ p: UserDefaults = .standard) {
        p.set(s.mode.rawValue, forKey: kMode)
        p.set(s.datum.rawValue, forKey: kDatum)
        p.set(String(s.chartDatumBelowMslM), forKey: kOffset)
        p.set(s.chartDatumAuto, forKey: kAuto)
        p.set(String(s.manualHeightM), forKey: kManual)
        p.set(s.showChip, forKey: kChip)
        p.set(s.correctDepths, forKey: kCorrect)
        if let id = s.placeId { p.set(id, forKey: kPlace) } else { p.removeObject(forKey: kPlace) }
        p.set(s.locationChosen, forKey: kChosen)
    }
}

/// Great-circle distance and bearing (Android NavGeo), for the tide code.
enum TideGeo {
    private static let earthRadiusM = 6_371_008.8

    static func distanceM(_ aLat: Double, _ aLng: Double, _ bLat: Double, _ bLng: Double) -> Double {
        let p1 = TideMath.rad(aLat)
        let p2 = TideMath.rad(bLat)
        let dp = p2 - p1
        let dl = TideMath.rad(bLng - aLng)
        let h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusM * asin(min(max(h, 0), 1).squareRoot())
    }

    /// Initial true bearing from a to b, 0..360.
    static func bearing(_ aLat: Double, _ aLng: Double, _ bLat: Double, _ bLng: Double) -> Double {
        let p1 = TideMath.rad(aLat)
        let p2 = TideMath.rad(bLat)
        let dl = TideMath.rad(bLng - aLng)
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return NavFormat.normaliseDegrees(TideMath.deg(atan2(y, x)))
    }
}

/// A downloaded tide forecast for one place.
struct TideForecast {
    let requestLat: Double
    let requestLng: Double
    let modelLat: Double
    let modelLng: Double
    let fetchedMillis: Int64
    let curve: TideCurve
    /// End of the tides saved for offline use, or nil if none are.
    var savedUntilMillis: Int64? = nil
    /// Space the place's tide files take, bytes.
    var storedBytes: Int64 = 0
    /// The lowest tide here (LAT), metres from mean sea level (negative), once worked out.
    var lowestTideM: Double? = nil

    /// How far the model's nearest ocean point is from the spot asked for.
    var modelDistanceM: Double { TideGeo.distanceM(requestLat, requestLng, modelLat, modelLng) }
}

/// How far ahead tides are saved for offline use.
enum TideSavePeriod: CaseIterable {
    case week, twoWeeks, month, threeMonths, sixMonths, year, twoYears

    var label: String {
        switch self {
        case .week: return "1 week"
        case .twoWeeks: return "2 weeks"
        case .month: return "1 month"
        case .threeMonths: return "3 months"
        case .sixMonths: return "6 months"
        case .year: return "1 year"
        case .twoYears: return "2 years"
        }
    }

    private var days: Int {
        switch self { case .week: return 7; case .twoWeeks: return 14; default: return 0 }
    }

    private var months: Int {
        switch self {
        case .week, .twoWeeks: return 0
        case .month: return 1
        case .threeMonths: return 3
        case .sixMonths: return 6
        case .year: return 12
        case .twoYears: return 24
        }
    }

    /// The end of the period, at the end of that day.
    func endFrom(_ now: Date, timeZone: TimeZone = .current) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        var d = cal.date(byAdding: .day, value: days, to: now) ?? now
        d = cal.date(byAdding: .month, value: months, to: d) ?? d
        let start = cal.startOfDay(for: d)
        let next = cal.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        return next.addingTimeInterval(-1)
    }
}

/// A town or place found by name.
struct TidePlaceResult: Identifiable {
    let id = UUID()
    let name: String
    let detail: String
    let lat: Double
    let lng: Double
}

/// Long-range tides (harmonic fits) saved for offline use.
struct TideSavedEntry {
    let harmonics: TideHarmonics
    let untilMillis: Int64
    let requestLat: Double
    let requestLng: Double
    let modelLat: Double
    let modelLng: Double
    let savedMillis: Int64
}

/// The lowest tide (LAT) worked out for a place, metres from mean sea level.
struct TideLowestEntry {
    let metres: Double
    let requestLat: Double
    let requestLng: Double
}

/**
 Modelled sea level from the Open-Meteo marine API (free, no key), one
 forecast per place (or "follow me"), each cached to a file so it keeps
 working offline for the days it covers. The API picks the nearest ocean
 cell to the spot asked for, so a town or a point on land still gets the
 tide just offshore. This is a global model, not an official tide table.
 */
@MainActor
final class TideStore {
    /// Cache key for "follow me".
    static let followKey = "follow"

    static let refreshDistanceM = 15_000.0
    private static let refreshAgeMs: Int64 = 12 * TideCurve.hourMs
    private static let dayMs: Int64 = 24 * TideCurve.hourMs
    /// The joined curve starts two days back, so rebuild it now and then.
    private static let rebuildMs: Int64 = 6 * TideCurve.hourMs
    /// How long the forecast's last level takes to ease into the saved prediction.
    private static let seamMs: Int64 = 24 * TideCurve.hourMs

    private let cacheDir: URL
    /// Short-range model forecasts, as downloaded.
    private var forecasts: [String: TideForecast] = [:]
    private var saved: [String: TideSavedEntry] = [:]
    private var lowest: [String: TideLowestEntry] = [:]
    /// Forecast and saved tides joined into one curve, rebuilt as time moves on.
    private var merged: [String: (builtAt: Int64, value: TideForecast)] = [:]

    init(directory: URL? = nil) {
        cacheDir = directory ?? AppPaths.supportFolder("tides")
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    /// Tides for a place: the model forecast while it lasts, then the saved
    /// long-range prediction. Nil when neither has been downloaded.
    func forecast(_ key: String) -> TideForecast? {
        let now = TideMath.nowMillis()
        if let m = merged[key], now - m.builtAt < TideStore.rebuildMs { return m.value }
        let raw = forecasts[key]
        var result: TideForecast?
        // Saved tides that have run out are left for the user to renew or delete.
        if let long = saved[key], long.untilMillis >= now {
            let hour = TideCurve.hourMs
            let from = min(raw?.curve.startMillis ?? now, now - 2 * TideStore.dayMs)
            let to = max(long.untilMillis, raw?.curve.endMillis ?? 0)
            let start = from - from % hour
            let count = Int((to - start) / hour) + 1
            let times = (0..<count).map { start + Int64($0) * hour }
            let predicted = long.harmonics.heights(start: start, count: count)
            // Where the forecast ends, ease from its last level into the prediction
            // instead of jumping (the two differ by the day's weather, often 5-15 cm).
            let rawEnd = raw?.curve.endMillis
            var step = 0.0
            if let raw, let rawEnd {
                step = (raw.curve.heightAt(rawEnd) ?? 0.0) - long.harmonics.heightAt(rawEnd)
            }
            var heights = [Double](repeating: 0, count: count)
            for i in 0..<count {
                if let h = raw?.curve.heightAt(times[i]) {
                    heights[i] = h
                } else {
                    var fade = 0.0
                    if let rawEnd {
                        fade = min(max(1.0 - Double(times[i] - rawEnd) / Double(TideStore.seamMs), 0.0), 1.0)
                    }
                    heights[i] = predicted[i] + step * fade
                }
            }
            result = TideForecast(
                requestLat: raw?.requestLat ?? long.requestLat,
                requestLng: raw?.requestLng ?? long.requestLng,
                modelLat: raw?.modelLat ?? long.modelLat,
                modelLng: raw?.modelLng ?? long.modelLng,
                fetchedMillis: raw?.fetchedMillis ?? long.savedMillis,
                curve: TideCurve(times: times, heights: heights),
                savedUntilMillis: long.untilMillis
            )
        } else {
            result = raw
        }
        if var r = result {
            r.storedBytes = storedBytes(key)
            r.lowestTideM = lowest[key]?.metres
            result = r
        }
        if let result { merged[key] = (now, result) } else { merged[key] = nil }
        return result
    }

    /// Loads a place's downloaded and saved tides from disk.
    @discardableResult
    func load(_ key: String) async -> TideForecast? {
        let loaded = await TideStoreIO.readAll(
            forecast: forecasts[key] == nil ? file(key) : nil,
            saved: saved[key] == nil ? savedFile(key) : nil,
            lowest: lowest[key] == nil ? lowestFile(key) : nil
        )
        if forecasts[key] == nil, let f = loaded.forecast { forecasts[key] = f }
        if saved[key] == nil, let s = loaded.saved { saved[key] = s }
        if lowest[key] == nil, let l = loaded.lowest { lowest[key] = l }
        merged[key] = nil
        return forecast(key)
    }

    /**
     Downloads the past year of tides for `lat`/`lng`, fits the tidal
     harmonics and keeps them, so the tides are known offline for `period`.
     Takes a few seconds; the saved file is a few KB whatever the period.
     */
    func saveOffline(_ key: String, lat: Double, lng: Double, period: TideSavePeriod) async throws -> TideForecast {
        let fitted = try await TideStoreIO.fitYear(lat: lat, lng: lng)
        let low = await TideStoreIO.computeLowest(fitted.harmonics, lat: lat, lng: lng, file: lowestFile(key))
        let now = TideMath.nowMillis()
        let until = TideMath.millis(period.endFrom(Date()))
        let entry = TideSavedEntry(
            harmonics: fitted.harmonics, untilMillis: until, requestLat: lat, requestLng: lng,
            modelLat: fitted.modelLat ?? lat, modelLng: fitted.modelLng ?? lng, savedMillis: now
        )
        try TideStoreIO.savedJson(entry).write(to: savedFile(key), options: .atomic)
        saved[key] = entry
        lowest[key] = low
        merged[key] = nil
        guard let result = forecast(key) else { throw TideError("Couldn't save tides") }
        return result
    }

    /// True when the place's lowest tide (chart datum) isn't known yet, or was worked out somewhere else.
    func needsLowest(_ key: String, lat: Double, lng: Double) -> Bool {
        guard let l = lowest[key] else { return true }
        return TideGeo.distanceM(lat, lng, l.requestLat, l.requestLng) > TideStore.refreshDistanceM
    }

    /**
     Works out the lowest tide (LAT) for a place, so heights can be shown
     above chart datum like a tide table: from its saved tides if it has
     them, otherwise from a year of the model downloaded for it (once).
     */
    func findLowest(_ key: String, lat: Double, lng: Double) async throws -> Double {
        var harmonics: TideHarmonics
        if let s = saved[key], s.harmonics.nodal,
           TideGeo.distanceM(lat, lng, s.requestLat, s.requestLng) <= TideStore.refreshDistanceM {
            harmonics = s.harmonics
        } else {
            harmonics = try await TideStoreIO.fitYear(lat: lat, lng: lng).harmonics
        }
        let entry = await TideStoreIO.computeLowest(harmonics, lat: lat, lng: lng, file: lowestFile(key))
        lowest[key] = entry
        merged[key] = nil
        return entry.metres
    }

    /// Removes the place's downloaded and saved tides.
    func deleteSaved(_ key: String) {
        forecasts[key] = nil
        saved[key] = nil
        merged[key] = nil
        try? FileManager.default.removeItem(at: file(key))
        try? FileManager.default.removeItem(at: savedFile(key))
    }

    func storedBytes(_ key: String) -> Int64 { TideStoreIO.size(file(key)) + TideStoreIO.size(savedFile(key)) }

    /// When a place's saved tides run out, without loading them all; nil if none are saved.
    func savedUntil(_ key: String) -> Int64? {
        if let s = saved[key] { return s.untilMillis }
        guard let data = try? Data(contentsOf: savedFile(key)),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        return (o["until"] as? NSNumber)?.int64Value
    }

    /// The nearest town or suburb to a spot (OpenStreetMap Nominatim, free), to suggest a name.
    func nearbyName(lat: Double, lng: Double) async -> String? {
        await TideStoreIO.nearbyName(lat: lat, lng: lng)
    }

    /// True when the forecast is missing, far from `lat`/`lng`, old, or runs out within a day.
    func needsRefresh(_ key: String, lat: Double, lng: Double, nowMillis: Int64 = TideMath.nowMillis()) -> Bool {
        guard let f = forecasts[key] else { return true }
        let far = TideGeo.distanceM(lat, lng, f.requestLat, f.requestLng) > TideStore.refreshDistanceM
        let old = nowMillis - f.fetchedMillis > TideStore.refreshAgeMs
        let runningOut = !f.curve.covers(nowMillis + TideCurve.hourMs * 24)
        return far || old || runningOut
    }

    /// Downloads a fresh forecast for `lat`/`lng` and caches it under `key`.
    func refresh(_ key: String, lat: Double, lng: Double) async throws -> TideForecast {
        let fresh = try await TideStoreIO.downloadForecast(lat: lat, lng: lng, file: file(key))
        forecasts[key] = fresh
        merged[key] = nil
        guard let result = forecast(key) else { throw TideError("Couldn't download tides") }
        return result
    }

    /// Removes everything kept for a place, its chart datum too.
    func forget(_ key: String) {
        deleteSaved(key)
        lowest[key] = nil
        try? FileManager.default.removeItem(at: lowestFile(key))
    }

    /// Towns and places matching `query` (Open-Meteo geocoding, free).
    func search(_ query: String) async throws -> [TidePlaceResult] {
        try await TideStoreIO.search(query)
    }

    private func safe(_ key: String) -> String {
        String(key.filter { $0.isLetter || $0.isNumber || $0 == "-" })
    }
    private func file(_ key: String) -> URL { cacheDir.appendingPathComponent(safe(key) + ".json") }
    private func savedFile(_ key: String) -> URL { cacheDir.appendingPathComponent(safe(key) + ".saved.json") }
    private func lowestFile(_ key: String) -> URL { cacheDir.appendingPathComponent(safe(key) + ".lat.json") }
}

/// Network and file work for TideStore. Not actor-bound, so its async functions run off the main thread.
enum TideStoreIO {
    static let requestLatKey = "seabathy_request_lat"
    static let requestLngKey = "seabathy_request_lng"
    private static let tag = "TideStore"

    struct Loaded {
        var forecast: TideForecast?
        var saved: TideSavedEntry?
        var lowest: TideLowestEntry?
    }

    struct Fitted {
        let harmonics: TideHarmonics
        let modelLat: Double?
        let modelLng: Double?
    }

    static func readAll(forecast: URL?, saved: URL?, lowest: URL?) async -> Loaded {
        var out = Loaded()
        let fm = FileManager.default
        if let url = forecast, fm.fileExists(atPath: url.path) {
            do {
                let data = try Data(contentsOf: url)
                let modified = (try? fm.attributesOfItem(atPath: url.path)[.modificationDate] as? Date) ?? Date()
                out.forecast = try parseForecast(data, fetched: TideMath.millis(modified))
            } catch {
                DiagnosticLog.logError(tag, "tide cache unreadable; ignoring it", error)
            }
        }
        if let url = saved, fm.fileExists(atPath: url.path) {
            do {
                out.saved = try parseSaved(try Data(contentsOf: url))
            } catch {
                DiagnosticLog.logError(tag, "saved tides unreadable; ignoring them", error)
            }
        }
        if let url = lowest, let data = try? Data(contentsOf: url),
           let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           let m = number(o["lowest"]), let la = number(o["requestLat"]), let ln = number(o["requestLng"]) {
            out.lowest = TideLowestEntry(metres: m.doubleValue, requestLat: la.doubleValue, requestLng: ln.doubleValue)
        }
        return out
    }

    static func downloadForecast(lat: Double, lng: Double, file: URL) async throws -> TideForecast {
        let text = String(
            format: "https://marine-api.open-meteo.com/v1/marine?latitude=%.4f&longitude=%.4f" +
                "&hourly=sea_level_height_msl&timeformat=unixtime&past_days=1&forecast_days=16&cell_selection=sea",
            lat, lng)
        guard let url = URL(string: text) else { throw TideError("Couldn't download tides") }
        let body = try await get(url, what: "Tide service")
        guard var root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw TideError("No tide data in the reply")
        }
        root[requestLatKey] = lat
        root[requestLngKey] = lng
        let json = try JSONSerialization.data(withJSONObject: root)
        let forecast = try parseForecast(json, fetched: TideMath.nowMillis())
        try json.write(to: file, options: .atomic)
        return forecast
    }

    /// Downloads the past year of modelled tides here and fits the harmonics to it.
    static func fitYear(lat: Double, lng: Double) async throws -> Fitted {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC") ?? TimeZone(secondsFromGMT: 0)!
        let today = utc.startOfDay(for: Date())
        let end = utc.date(byAdding: .day, value: -2, to: today) ?? today
        let start = utc.date(byAdding: .day, value: -365, to: end) ?? end
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = utc.timeZone
        f.dateFormat = "yyyy-MM-dd"
        let text = String(
            format: "https://marine-api.open-meteo.com/v1/marine?latitude=%.4f&longitude=%.4f" +
                "&hourly=sea_level_height_msl&timeformat=unixtime&cell_selection=sea&start_date=%@&end_date=%@",
            lat, lng, f.string(from: start), f.string(from: end))
        guard let url = URL(string: text) else { throw TideError("Couldn't save tides") }
        let body = try await get(url, what: "Tide service")
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw TideError("No tide data in the reply")
        }
        let year = try parseHourly(root)
        if year.times.count < 24 * 60 { throw TideError("Not enough tide history here to save tides offline.") }
        let harmonics = try TideHarmonics.fit(times: year.times, heights: year.heights)
        return Fitted(harmonics: harmonics, modelLat: number(root["latitude"])?.doubleValue,
                      modelLng: number(root["longitude"])?.doubleValue)
    }

    /// Computes and saves the lowest tide from a place's harmonics; slow-ish, so off the main thread.
    static func computeLowest(_ harmonics: TideHarmonics, lat: Double, lng: Double, file: URL) async -> TideLowestEntry {
        let entry = TideLowestEntry(metres: harmonics.lowestAstronomical(from: TideMath.nowMillis()), requestLat: lat, requestLng: lng)
        let o: [String: Any] = ["lowest": entry.metres, "requestLat": lat, "requestLng": lng]
        if let data = try? JSONSerialization.data(withJSONObject: o) { try? data.write(to: file, options: .atomic) }
        return entry
    }

    static func nearbyName(lat: Double, lng: Double) async -> String? {
        let text = String(format: "https://nominatim.openstreetmap.org/reverse?lat=%.4f&lon=%.4f&format=json&zoom=14", lat, lng)
        guard let url = URL(string: text) else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue(userAgent("iOS tide places"), forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        // A country or state means the spot is out at sea: no useful name.
        if let type = root["addresstype"] as? String, ["country", "state", "region"].contains(type) { return nil }
        let address = root["address"] as? [String: Any]
        for key in ["suburb", "town", "village", "city_district", "city", "hamlet", "locality", "bay", "island"] {
            if let v = address?[key] as? String { return v }
        }
        if let name = root["name"] as? String, !name.trimmingCharacters(in: .whitespaces).isEmpty { return name }
        return nil
    }

    static func search(_ query: String) async throws -> [TidePlaceResult] {
        var c = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        c.queryItems = [
            URLQueryItem(name: "name", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "count", value: "10"),
            URLQueryItem(name: "language", value: "en"),
            URLQueryItem(name: "format", value: "json")
        ]
        guard let url = c.url else { return [] }
        let body = try await get(url, what: "Place search")
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let results = root["results"] as? [[String: Any]] else { return [] }
        return results.compactMap { o in
            guard let lat = number(o["latitude"])?.doubleValue, let lng = number(o["longitude"])?.doubleValue else { return nil }
            let detail = [o["admin1"] as? String, o["country"] as? String].compactMap { $0 }.joined(separator: ", ")
            return TidePlaceResult(name: (o["name"] as? String) ?? "Place", detail: detail, lat: lat, lng: lng)
        }
    }

    static func size(_ url: URL) -> Int64 {
        guard let n = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber else { return 0 }
        return n.int64Value
    }

    private static func userAgent(_ what: String) -> String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        return "SeaBathy/\(version) (\(what))"
    }

    private static func get(_ url: URL, what: String) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue(userAgent("iOS tides"), forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw TideError("\(what) returned an error (HTTP \(http.statusCode))")
            }
            if data.isEmpty { throw TideError("\(what) sent an empty reply") }
            return data
        } catch let error as URLError {
            switch error.code {
            case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
                throw TideError("No internet connection. Tides already downloaded keep working offline.")
            case .timedOut, .networkConnectionLost, .cannotConnectToHost:
                throw TideError("\(what) didn't answer — check the connection and try again.")
            default:
                throw error
            }
        }
    }

    static func number(_ any: Any?) -> NSNumber? {
        if any is NSNull { return nil }
        return any as? NSNumber
    }

    /// Hourly times (ms) and heights from a marine API reply, skipping gaps.
    static func parseHourly(_ root: [String: Any]) throws -> (times: [Int64], heights: [Double]) {
        guard let hourly = root["hourly"] as? [String: Any] else { throw TideError("No tide data in the reply") }
        let times = (hourly["time"] as? [Any]) ?? []
        let heights = (hourly["sea_level_height_msl"] as? [Any]) ?? []
        var t: [Int64] = []
        var h: [Double] = []
        t.reserveCapacity(times.count)
        h.reserveCapacity(times.count)
        for i in 0..<min(times.count, heights.count) {
            guard let value = number(heights[i]), let time = number(times[i]) else { continue }
            t.append(time.int64Value * 1000)
            h.append(value.doubleValue)
        }
        if t.count < 6 { throw TideError("No tides here — too far from the sea. Pick a spot on the water.") }
        return (t, h)
    }

    static func parseForecast(_ json: Data, fetched: Int64) throws -> TideForecast {
        guard let root = (try? JSONSerialization.jsonObject(with: json)) as? [String: Any] else {
            throw TideError("No tide data in the reply")
        }
        let (t, h) = try parseHourly(root)
        let requestLat = number(root[requestLatKey])?.doubleValue ?? 0.0
        let requestLng = number(root[requestLngKey])?.doubleValue ?? 0.0
        return TideForecast(
            requestLat: requestLat,
            requestLng: requestLng,
            modelLat: number(root["latitude"])?.doubleValue ?? requestLat,
            modelLng: number(root["longitude"])?.doubleValue ?? requestLng,
            fetchedMillis: fetched,
            curve: TideCurve(times: t, heights: h)
        )
    }

    static func savedJson(_ e: TideSavedEntry) throws -> Data {
        let o: [String: Any] = [
            "mean": e.harmonics.mean,
            "cos": e.harmonics.cosines,
            "sin": e.harmonics.sines,
            "epoch": NSNumber(value: e.harmonics.epochMillis),
            "until": NSNumber(value: e.untilMillis),
            "requestLat": e.requestLat,
            "requestLng": e.requestLng,
            "modelLat": e.modelLat,
            "modelLng": e.modelLng,
            "saved": NSNumber(value: e.savedMillis),
            "constituents": TideHarmonics.constituentNames,
            "nodal": e.harmonics.nodal
        ]
        return try JSONSerialization.data(withJSONObject: o)
    }

    static func parseSaved(_ json: Data) throws -> TideSavedEntry {
        guard let o = (try? JSONSerialization.jsonObject(with: json)) as? [String: Any] else {
            throw TideError("saved tides unreadable")
        }
        // Saved with a different constituent list (an older version): can't be used.
        guard (o["constituents"] as? String) == TideHarmonics.constituentNames else {
            throw TideError("saved tides from another version")
        }
        let n = TideHarmonics.constituents.count
        guard let mean = number(o["mean"])?.doubleValue,
              let cosArr = (o["cos"] as? [Any])?.compactMap({ number($0)?.doubleValue }), cosArr.count == n,
              let sinArr = (o["sin"] as? [Any])?.compactMap({ number($0)?.doubleValue }), sinArr.count == n,
              let epoch = number(o["epoch"])?.int64Value,
              let until = number(o["until"])?.int64Value,
              let rLat = number(o["requestLat"])?.doubleValue, let rLng = number(o["requestLng"])?.doubleValue,
              let mLat = number(o["modelLat"])?.doubleValue, let mLng = number(o["modelLng"])?.doubleValue,
              let savedAt = number(o["saved"])?.int64Value else {
            throw TideError("saved tides incomplete")
        }
        let nodal = number(o["nodal"])?.boolValue ?? false
        return TideSavedEntry(
            harmonics: TideHarmonics(mean: mean, cosines: cosArr, sines: sinArr, epochMillis: epoch, nodal: nodal),
            untilMillis: until, requestLat: rLat, requestLng: rLng, modelLat: mLat, modelLng: mLng, savedMillis: savedAt
        )
    }
}
