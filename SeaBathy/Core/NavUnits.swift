import Foundation

// Units and formatting, ported from Android nav/NavUnits.kt and nav/NavSettings.kt.
// Enum raw values are the Android enum names, so settings keep their meaning.

enum DistanceUnit: String, CaseIterable, Codable {
    case nauticalMiles = "NAUTICAL_MILES", kilometres = "KILOMETRES", statuteMiles = "STATUTE_MILES"
    var label: String { switch self { case .nauticalMiles: return "nm"; case .kilometres: return "km"; case .statuteMiles: return "mi" } }
    var perNm: Double { switch self { case .nauticalMiles: return 1.0; case .kilometres: return 1.852; case .statuteMiles: return 1.150779 } }
}

enum SpeedUnit: String, CaseIterable, Codable {
    case knots = "KNOTS", kmh = "KMH", mph = "MPH"
    var label: String { switch self { case .knots: return "kn"; case .kmh: return "km/h"; case .mph: return "mph" } }
    var perKnot: Double { switch self { case .knots: return 1.0; case .kmh: return 1.852; case .mph: return 1.150779 } }
}

enum DepthUnit: String, CaseIterable, Codable {
    case metres = "METRES", feet = "FEET", fathoms = "FATHOMS"
    var label: String { switch self { case .metres: return "m"; case .feet: return "ft"; case .fathoms: return "fm" } }
    var perMetre: Double { switch self { case .metres: return 1.0; case .feet: return 3.280840; case .fathoms: return 0.546807 } }
}

enum FuelUnit: String, CaseIterable, Codable {
    case litres = "LITRES", usGallons = "US_GALLONS", ukGallons = "UK_GALLONS"
    var label: String { switch self { case .litres: return "L"; case .usGallons: return "US gal"; case .ukGallons: return "gal" } }
    var perLitre: Double { switch self { case .litres: return 1.0; case .usGallons: return 0.264172; case .ukGallons: return 0.219969 } }
}

/// Bearings are kept true internally; magnetic adds the local variation for display.
enum BearingReference: String, CaseIterable, Codable { case trueNorth = "TRUE", magnetic = "MAGNETIC" }

enum TemperatureUnit: String, CaseIterable, Codable {
    case celsius = "CELSIUS", fahrenheit = "FAHRENHEIT"
    var label: String { self == .celsius ? "°C" : "°F" }
    func of(_ celsius: Double) -> Double { self == .fahrenheit ? celsius * 9 / 5 + 32 : celsius }
    func toCelsius(_ value: Double) -> Double { self == .fahrenheit ? (value - 32) * 5 / 9 : value }
    func ofDifference(_ celsius: Double) -> Double { self == .fahrenheit ? celsius * 9 / 5 : celsius }
}

/// Heights at sea: tides, waves and swell.
enum HeightUnit: String, CaseIterable, Codable {
    case metres = "METRES", feet = "FEET"
    var label: String { self == .metres ? "m" : "ft" }
    var perMetre: Double { self == .metres ? 1.0 : 3.280840 }
    func of(_ metres: Double) -> Double { metres * perMetre }
    func toMetres(_ value: Double) -> Double { value / perMetre }
    static func defaultFor(depth: DepthUnit) -> HeightUnit { depth != .metres ? .feet : .metres }
}

/// Wind and current. Beaufort is for wind only.
enum SeaSpeedUnit: String, CaseIterable, Codable {
    case knots = "KNOTS", kmh = "KMH", mph = "MPH", metresPerSecond = "METRES_PER_SECOND", beaufort = "BEAUFORT"
    var label: String {
        switch self { case .knots: return "kn"; case .kmh: return "km/h"; case .mph: return "mph"; case .metresPerSecond: return "m/s"; case .beaufort: return "Bft" }
    }
    var perKnot: Double {
        switch self { case .knots: return 1.0; case .kmh: return 1.852; case .mph: return 1.150779; case .metresPerSecond: return 0.514444; case .beaufort: return 0 }
    }
    func format(_ knots: Double?) -> String {
        guard let knots, knots.isFinite else { return "—" }
        if self == .beaufort { return "\(SeaSpeedUnit.beaufort(knots)) Bft" }
        return String(format: "%.1f %@", knots * perKnot, label)
    }
    private static let beaufortTops: [Double] = [1, 4, 7, 11, 17, 22, 28, 34, 41, 48, 56, 64]
    static func beaufort(_ knots: Double) -> Int { beaufortTops.firstIndex { knots < $0 } ?? 12 }
    static func like(_ speed: SpeedUnit) -> SeaSpeedUnit {
        switch speed { case .knots: return .knots; case .kmh: return .kmh; case .mph: return .mph }
    }
}

struct NavUnits: Equatable {
    var distance: DistanceUnit = .nauticalMiles
    var speed: SpeedUnit = .knots
    var depth: DepthUnit = .metres
    var fuel: FuelUnit = .litres
    var bearing: BearingReference = .trueNorth
    var temperature: TemperatureUnit = .celsius
    var tide: HeightUnit = .metres
    var waves: HeightUnit = .metres
    var wind: SeaSpeedUnit = .knots
    var current: SeaSpeedUnit = .knots
}

/// Display formatting for navigation values in the user's chosen units.
struct NavFormat {
    let units: NavUnits
    var timeZone: TimeZone = .current

    static let metresPerNm = 1852.0
    static let feetPerMetre = 3.280840

    static func normaliseDegrees(_ d: Double) -> Double { (d.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) }

    /// The signed smallest turn from `from` to `to`, in -180...180 (positive = to starboard).
    static func turnDegrees(from: Double, to: Double) -> Double {
        let diff = normaliseDegrees(to - from)
        return diff > 180 ? diff - 360 : diff
    }

    func distance(_ nm: Double) -> String {
        guard nm.isFinite else { return "—" }
        let metres = nm * Self.metresPerNm
        switch units.distance {
        case .nauticalMiles:
            return nm < 0.1 ? "\(Int(metres.rounded())) m" : String(format: "%.2f nm", nm)
        case .kilometres:
            return metres < 1000 ? "\(Int(metres.rounded())) m" : String(format: "%.2f km", metres / 1000)
        case .statuteMiles:
            let miles = nm * DistanceUnit.statuteMiles.perNm
            return miles < 0.1 ? "\(Int((metres * Self.feetPerMetre).rounded())) ft" : String(format: "%.2f mi", miles)
        }
    }

    /// A cross-track error in metres, always in a small unit, with no sign.
    func offset(_ metres: Double) -> String {
        guard metres.isFinite else { return "—" }
        let m = abs(metres)
        if units.distance == .statuteMiles { return "\(Int((m * Self.feetPerMetre).rounded())) ft" }
        if units.distance == .nauticalMiles && m >= 0.1 * Self.metresPerNm { return String(format: "%.2f nm", m / Self.metresPerNm) }
        if m >= 1000 { return String(format: "%.2f km", m / 1000) }
        return "\(Int(m.rounded())) m"
    }

    func speed(_ knots: Double?) -> String {
        guard let knots, knots.isFinite else { return "—" }
        return String(format: "%.1f %@", knots * units.speed.perKnot, units.speed.label)
    }

    /// A depth of water in metres (positive down); below zero the bottom dries.
    func depth(_ metres: Double?) -> String {
        guard let metres, metres.isFinite else { return "—" }
        if metres < 0 { return String(format: "dries %.1f %@", -metres * units.depth.perMetre, units.depth.label) }
        return String(format: "%.1f %@", metres * units.depth.perMetre, units.depth.label)
    }

    func temperature(_ celsius: Double?) -> String {
        guard let celsius, celsius.isFinite else { return "—" }
        return String(format: "%.1f %@", units.temperature.of(celsius), units.temperature.label)
    }

    func wind(_ knots: Double?) -> String { units.wind.format(knots) }
    func current(_ knots: Double?) -> String { units.current.format(knots) }

    func waveHeight(_ metres: Double?) -> String {
        guard let metres, metres.isFinite else { return "—" }
        return String(format: "%.1f %@", units.waves.of(metres), units.waves.label)
    }

    func tideHeight(_ metres: Double?) -> String {
        guard let metres, metres.isFinite else { return "—" }
        return String(format: units.tide == .feet ? "%.1f %@" : "%.2f %@", units.tide.of(metres), units.tide.label)
    }

    func tideChartStep(_ rangeMetres: Double) -> Double {
        let range = units.tide.of(rangeMetres)
        if units.tide == .metres { return range > 3 ? 1.0 : 0.5 }
        if range > 12 { return 5 }
        if range > 6 { return 2 }
        return 1
    }

    func tideUnitsPerMetre() -> Double { units.tide.perMetre }

    func tideMark(_ metres: Double) -> String { String(format: "%.1f %@", units.tide.of(metres), units.tide.label) }

    func tideRate(_ metresPerHour: Double) -> String {
        String(format: units.tide == .feet ? "%.1f %@/h" : "%.2f %@/h", units.tide.of(metresPerHour), units.tide.label)
    }

    func seaLevel(_ cm: Double) -> String {
        units.tide == .feet ? String(format: "%+.1f in", cm / 2.54) : String(format: "%+.0f cm", cm)
    }

    func fuel(_ litres: Double?) -> String {
        guard let litres, litres.isFinite else { return "—" }
        return String(format: "%.1f %@", litres * units.fuel.perLitre, units.fuel.label)
    }

    /// A true bearing shown in the chosen reference; `variation` is east-positive degrees.
    func bearing(_ trueDegrees: Double?, variation: Double) -> String {
        guard let trueDegrees, trueDegrees.isFinite else { return "—" }
        let shown = units.bearing == .trueNorth ? trueDegrees : trueDegrees - variation
        let suffix = units.bearing == .trueNorth ? "T" : "M"
        return String(format: "%03d°%@", Int(Self.normaliseDegrees(shown).rounded()) % 360, suffix)
    }

    /// "2h 05m", "45m", "<1m", or "—".
    func duration(_ hours: Double?) -> String {
        guard let hours, hours.isFinite, hours >= 0 else { return "—" }
        let total = Int((hours * 60).rounded())
        if hours * 60 < 1 { return "<1m" }
        if total >= 60 * 48 { return "\(total / (60 * 24))d \((total / 60) % 24)h" }
        if total >= 60 { return String(format: "%dh %02dm", total / 60, total % 60) }
        return "\(total)m"
    }

    /// A clock time, with the weekday when it is not today. Times are epoch milliseconds, as on Android.
    func clock(_ epochMillis: Int64?, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        guard let epochMillis else { return "—" }
        let date = Date(timeIntervalSince1970: Double(epochMillis) / 1000)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let sameDay = cal.isDate(date, inSameDayAs: Date(timeIntervalSince1970: Double(now) / 1000))
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = sameDay ? "HH:mm" : "EEE HH:mm"
        return f.string(from: date)
    }
}

/// Where the ETA and fuel figures take their speed from.
enum PlanSpeedSource: String, Codable { case cruise = "CRUISE", gps = "GPS" }

/// The boat and how navigation behaves (Android NavSettings).
struct NavSettings: Equatable {
    var units = NavUnits()
    var cruiseSpeedKn = 6.0
    var speedSource: PlanSpeedSource = .cruise
    var fuelBurnLph = 0.0
    var tankLitres = 0.0
    var fuelOnBoardLitres = 0.0
    var reservePercent = 20.0
    var draftM = 1.0
    var safetyMarginM = 1.0
    var arrivalRadiusM = 50.0
    var xteLimitM = 100.0
    var autoAdvance = true
    var alarmSound = true
    var alarmVibrate = true
    var offCourseAlarm = true
    var arrivalAlarm = true
    var shallowAlarm = true
    var keepScreenOn = true
    var courseUp = false
    var nightFilter = false

    static let maxCruiseKn = 100.0

    var minimumDepthM: Double { draftM + safetyMarginM }
    var fuelSet: Bool { fuelBurnLph > 0 }

    var usableFuelLitres: Double {
        let onBoard = fuelOnBoardLitres > 0 ? fuelOnBoardLitres : tankLitres
        let reserve = max(tankLitres, onBoard) * reservePercent / 100
        return max(onBoard - reserve, 0)
    }

    var litresPerNm: Double? { fuelSet && cruiseSpeedKn > 0 ? fuelBurnLph / cruiseSpeedKn : nil }

    var rangeNm: Double? {
        guard let perNm = litresPerNm, usableFuelLitres > 0 else { return nil }
        return usableFuelLitres / perNm
    }

    var format: NavFormat { NavFormat(units: units) }
}

/// Saves NavSettings one key per field (same keys as Android), so a missing key keeps its default.
@MainActor
final class NavSettingsStore: ObservableObject {
    @Published var settings: NavSettings { didSet { save(settings) } }
    private let d: UserDefaults

    init(defaults: UserDefaults = .standard) {
        d = defaults
        settings = NavSettingsStore.load(defaults)
    }

    private static func load(_ p: UserDefaults) -> NavSettings {
        let def = NavSettings()
        func e<E: RawRepresentable>(_ key: String, _ fallback: E) -> E where E.RawValue == String {
            p.string(forKey: key).flatMap(E.init(rawValue:)) ?? fallback
        }
        func dbl(_ key: String, _ fallback: Double) -> Double {
            guard let s = p.string(forKey: key), let v = Double(s), v.isFinite, v >= 0 else { return fallback }
            return v
        }
        func bool(_ key: String, _ fallback: Bool) -> Bool { p.object(forKey: key) == nil ? fallback : p.bool(forKey: key) }
        let depth: DepthUnit = e(K.depth, def.units.depth)
        let speed: SpeedUnit = e(K.speed, def.units.speed)
        var s = NavSettings()
        s.units = NavUnits(
            distance: e(K.distance, def.units.distance), speed: speed, depth: depth,
            fuel: e(K.fuel, def.units.fuel), bearing: e(K.bearing, def.units.bearing),
            temperature: e(K.temperature, TemperatureUnit.celsius),
            tide: e(K.tide, HeightUnit.defaultFor(depth: depth)), waves: e(K.waves, HeightUnit.defaultFor(depth: depth)),
            wind: e(K.wind, SeaSpeedUnit.like(speed)), current: e(K.current, SeaSpeedUnit.like(speed)))
        let cruise = dbl(K.cruise, def.cruiseSpeedKn)
        s.cruiseSpeedKn = (cruise > 0 && cruise <= NavSettings.maxCruiseKn) ? cruise : def.cruiseSpeedKn
        s.speedSource = e(K.speedSource, def.speedSource)
        s.fuelBurnLph = dbl(K.burn, def.fuelBurnLph)
        s.tankLitres = dbl(K.tank, def.tankLitres)
        s.fuelOnBoardLitres = dbl(K.onBoard, def.fuelOnBoardLitres)
        s.reservePercent = dbl(K.reserve, def.reservePercent)
        s.draftM = dbl(K.draft, def.draftM)
        s.safetyMarginM = dbl(K.safety, def.safetyMarginM)
        s.arrivalRadiusM = dbl(K.arrival, def.arrivalRadiusM)
        s.xteLimitM = dbl(K.xte, def.xteLimitM)
        s.autoAdvance = bool(K.autoAdvance, def.autoAdvance)
        s.alarmSound = bool(K.sound, def.alarmSound)
        s.alarmVibrate = bool(K.vibrate, def.alarmVibrate)
        s.offCourseAlarm = bool(K.offCourse, def.offCourseAlarm)
        s.arrivalAlarm = bool(K.arrivalAlarm, def.arrivalAlarm)
        s.shallowAlarm = bool(K.shallow, def.shallowAlarm)
        s.keepScreenOn = bool(K.screenOn, def.keepScreenOn)
        s.courseUp = bool(K.courseUp, def.courseUp)
        s.nightFilter = bool(K.night, def.nightFilter)
        return s
    }

    private func save(_ s: NavSettings) {
        let p = d
        p.set(s.units.distance.rawValue, forKey: K.distance)
        p.set(s.units.speed.rawValue, forKey: K.speed)
        p.set(s.units.depth.rawValue, forKey: K.depth)
        p.set(s.units.fuel.rawValue, forKey: K.fuel)
        p.set(s.units.bearing.rawValue, forKey: K.bearing)
        p.set(s.units.temperature.rawValue, forKey: K.temperature)
        p.set(s.units.tide.rawValue, forKey: K.tide)
        p.set(s.units.waves.rawValue, forKey: K.waves)
        p.set(s.units.wind.rawValue, forKey: K.wind)
        p.set(s.units.current.rawValue, forKey: K.current)
        p.set(String(s.cruiseSpeedKn), forKey: K.cruise)
        p.set(s.speedSource.rawValue, forKey: K.speedSource)
        p.set(String(s.fuelBurnLph), forKey: K.burn)
        p.set(String(s.tankLitres), forKey: K.tank)
        p.set(String(s.fuelOnBoardLitres), forKey: K.onBoard)
        p.set(String(s.reservePercent), forKey: K.reserve)
        p.set(String(s.draftM), forKey: K.draft)
        p.set(String(s.safetyMarginM), forKey: K.safety)
        p.set(String(s.arrivalRadiusM), forKey: K.arrival)
        p.set(String(s.xteLimitM), forKey: K.xte)
        p.set(s.autoAdvance, forKey: K.autoAdvance)
        p.set(s.alarmSound, forKey: K.sound)
        p.set(s.alarmVibrate, forKey: K.vibrate)
        p.set(s.offCourseAlarm, forKey: K.offCourse)
        p.set(s.arrivalAlarm, forKey: K.arrivalAlarm)
        p.set(s.shallowAlarm, forKey: K.shallow)
        p.set(s.keepScreenOn, forKey: K.screenOn)
        p.set(s.courseUp, forKey: K.courseUp)
        p.set(s.nightFilter, forKey: K.night)
    }

    private enum K {
        static let distance = "nav_unit_distance", speed = "nav_unit_speed", depth = "nav_unit_depth", fuel = "nav_unit_fuel"
        static let bearing = "nav_bearing_ref", temperature = "nav_unit_temperature", tide = "nav_unit_tide", waves = "nav_unit_waves"
        static let wind = "nav_unit_wind", current = "nav_unit_current", cruise = "nav_cruise_kn", speedSource = "nav_speed_source"
        static let burn = "nav_burn_lph", tank = "nav_tank_l", onBoard = "nav_on_board_l", reserve = "nav_reserve_pct"
        static let draft = "nav_draft_m", safety = "nav_safety_m", arrival = "nav_arrival_m", xte = "nav_xte_m"
        static let autoAdvance = "nav_auto_advance", sound = "nav_alarm_sound", vibrate = "nav_alarm_vibrate"
        static let offCourse = "nav_alarm_off_course", arrivalAlarm = "nav_alarm_arrival", shallow = "nav_alarm_shallow"
        static let screenOn = "nav_keep_screen_on", courseUp = "nav_course_up", night = "nav_night_filter"
    }
}
