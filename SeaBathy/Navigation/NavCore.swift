import Foundation

// Pure navigation maths and data (Android nav/NavGeo, NavigationEngine, RoutePlan,
// map/RouteDepthCheck, map/GeoJson). No UI or map here, so it is unit-tested directly.

/// Great-circle geometry for navigation, in metres and true degrees.
enum NavGeo {
    static let earthRadiusM = 6_371_008.8

    static func distanceM(_ aLat: Double, _ aLng: Double, _ bLat: Double, _ bLng: Double) -> Double {
        let p1 = GeoMath.rad(aLat), p2 = GeoMath.rad(bLat)
        let dp = p2 - p1
        let dl = GeoMath.rad(bLng - aLng)
        let h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusM * asin(sqrt(min(max(h, 0), 1)))
    }

    static func distanceM(_ a: RoutePoint, _ b: RoutePoint) -> Double { distanceM(a.lat, a.lng, b.lat, b.lng) }

    /// Initial true bearing from a to b, 0..360.
    static func bearing(_ aLat: Double, _ aLng: Double, _ bLat: Double, _ bLng: Double) -> Double {
        let p1 = GeoMath.rad(aLat), p2 = GeoMath.rad(bLat)
        let dl = GeoMath.rad(bLng - aLng)
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return NavFormat.normaliseDegrees(GeoMath.deg(atan2(y, x)))
    }

    static func bearing(_ a: RoutePoint, _ b: RoutePoint) -> Double { bearing(a.lat, a.lng, b.lat, b.lng) }

    /// Signed distance of p from the great circle from -> to: positive to starboard of the track.
    static func crossTrackM(_ from: RoutePoint, _ to: RoutePoint, _ pLat: Double, _ pLng: Double) -> Double {
        let d13 = distanceM(from.lat, from.lng, pLat, pLng) / earthRadiusM
        let t13 = GeoMath.rad(bearing(from.lat, from.lng, pLat, pLng))
        let t12 = GeoMath.rad(bearing(from, to))
        return asin(min(max(sin(d13) * sin(t13 - t12), -1), 1)) * earthRadiusM
    }

    /// How far along the track the point abeam of p is; negative when p is behind `from`.
    static func alongTrackM(_ from: RoutePoint, _ to: RoutePoint, _ pLat: Double, _ pLng: Double) -> Double {
        let d13 = distanceM(from.lat, from.lng, pLat, pLng) / earthRadiusM
        let xt = crossTrackM(from, to, pLat, pLng) / earthRadiusM
        let ratio = min(max(cos(d13) / cos(xt), -1), 1)
        let along = acos(ratio) * earthRadiusM
        let t13 = GeoMath.rad(bearing(from.lat, from.lng, pLat, pLng))
        let t12 = GeoMath.rad(bearing(from, to))
        return (cos(t13 - t12) < 0 && abs(d13) > 0) ? -along : along
    }

    /// The point `distanceM` from lat/lng on `bearing` (true degrees).
    static func offset(_ lat: Double, _ lng: Double, bearing: Double, distanceM: Double) -> (lat: Double, lng: Double) {
        let d = distanceM / earthRadiusM
        let b = GeoMath.rad(bearing)
        let p1 = GeoMath.rad(lat), l1 = GeoMath.rad(lng)
        let p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        let l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        let lngOut = (GeoMath.deg(l2) + 540).truncatingRemainder(dividingBy: 360) - 180
        return (GeoMath.deg(p2), lngOut)
    }
}

/// One GPS reading. `sogKn` and `cogTrue` are nil when the receiver gives none.
struct NavFix: Equatable {
    var lat: Double
    var lng: Double
    var sogKn: Double?
    var cogTrue: Double?
    var timeMillis: Int64
}

/// Everything the navigation display shows, for one fix.
struct NavState: Equatable {
    var targetIndex: Int
    var target: RoutePoint
    var targetLabel: String
    var pointCount: Int
    /// Start of the current leg: the previous point, or where navigation began.
    var origin: RoutePoint
    var dtwNm: Double
    var btwTrue: Double
    var legBearingTrue: Double
    /// Cross-track error in metres; positive = boat is to starboard of the track line.
    var xteM: Double
    /// Turn from the current course to the bearing to the waypoint; positive = to starboard.
    var steerDegrees: Double?
    var sogKn: Double?
    var cogTrue: Double?
    var vmgKn: Double?
    var ttgNextHours: Double?
    var etaNextMillis: Int64?
    var remainingNm: Double
    var ttgEndHours: Double?
    var etaEndMillis: Int64?
    var fuelToEndLitres: Double?
    var offCourse: Bool
    /// Set on the update that reached this point.
    var arrivedLabel: String?
    var finished: Bool
}

/**
 Follows a route from GPS fixes: works out the values chartplotters show
 (DTW, BTW, XTE, VMG, TTG, ETA) and moves on to the next point on arrival,
 either inside the arrival circle or once the boat has passed abeam of it.
 */
final class NavigationEngine {
    let points: [RoutePoint]
    var settings: NavSettings
    private(set) var targetIndex: Int
    private(set) var finished = false
    private var startOrigin: RoutePoint?
    private var announcedIndex = -1

    /// Below this, GPS course is noise (drifting at anchor, say).
    static let minCourseSpeedKn = 0.5

    init(points: [RoutePoint], settings: NavSettings, startTargetIndex: Int? = nil) {
        precondition(!points.isEmpty, "A route needs at least one point")
        self.points = points
        self.settings = settings
        if let start = startTargetIndex {
            targetIndex = min(max(start, 0), points.count - 1)
        } else {
            targetIndex = -1
        }
    }

    /// Picks the first point to steer to: the second if already at the first, otherwise the first.
    func start(_ fix: NavFix) {
        if targetIndex >= 0 { return }
        let atStart = NavGeo.distanceM(fix.lat, fix.lng, points[0].lat, points[0].lng) <= settings.arrivalRadiusM
        targetIndex = (atStart && points.count > 1) ? 1 : 0
        if targetIndex == 0 { startOrigin = RoutePoint(lat: fix.lat, lng: fix.lng) }
        finished = false
    }

    func skip() {
        if targetIndex < points.count - 1 {
            targetIndex += 1
            finished = false
        }
    }

    func previous(_ fix: NavFix?) {
        if targetIndex > 0 {
            targetIndex -= 1
            finished = false
            if targetIndex == 0, let fix { startOrigin = RoutePoint(lat: fix.lat, lng: fix.lng) }
        }
    }

    func label(_ index: Int) -> String {
        if let name = points[index].name, !name.trimmingCharacters(in: .whitespaces).isEmpty { return name }
        return "Point \(index + 1)"
    }

    func update(_ fix: NavFix) -> NavState {
        if targetIndex < 0 { start(fix) }
        var arrived: String?
        // Announced once per point, even while waiting inside its circle with auto-advance off.
        if !finished && announcedIndex != targetIndex && hasArrived(fix) {
            arrived = label(targetIndex)
            announcedIndex = targetIndex
            if targetIndex == points.count - 1 {
                finished = true
            } else if settings.autoAdvance {
                targetIndex += 1
            }
        }
        return state(fix, arrived)
    }

    private func origin() -> RoutePoint {
        targetIndex == 0 ? (startOrigin ?? points[0]) : points[targetIndex - 1]
    }

    private func hasArrived(_ fix: NavFix) -> Bool {
        let target = points[targetIndex]
        if NavGeo.distanceM(fix.lat, fix.lng, target.lat, target.lng) <= settings.arrivalRadiusM { return true }
        // Passed abeam: further along the leg than its length, and near the line.
        let o = origin()
        let legM = NavGeo.distanceM(o, target)
        if legM < 1 { return false }
        let along = NavGeo.alongTrackM(o, target, fix.lat, fix.lng)
        let xte = abs(NavGeo.crossTrackM(o, target, fix.lat, fix.lng))
        return along >= legM && xte <= max(settings.arrivalRadiusM * 4, settings.xteLimitM * 2)
    }

    private func state(_ fix: NavFix, _ arrivedLabel: String?) -> NavState {
        let target = points[targetIndex]
        let o = origin()
        let dtwNm = NavGeo.distanceM(fix.lat, fix.lng, target.lat, target.lng) / NavFormat.metresPerNm
        let btw = NavGeo.bearing(fix.lat, fix.lng, target.lat, target.lng)
        let legLong = NavGeo.distanceM(o, target) > 1
        let legBearing = legLong ? NavGeo.bearing(o, target) : btw
        let xte = legLong ? NavGeo.crossTrackM(o, target, fix.lat, fix.lng) : 0

        var sog: Double?
        if let s = fix.sogKn, s.isFinite, s >= 0 { sog = s }
        var cog: Double?
        if let c = fix.cogTrue, c.isFinite, let s = sog, s >= Self.minCourseSpeedKn { cog = c }
        var vmg: Double?
        if let s = sog, let c = cog { vmg = s * cos(GeoMath.rad(btw - c)) }
        let steer = cog.map { NavFormat.turnDegrees(from: $0, to: btw) }

        var laterNm = 0.0
        if targetIndex < points.count - 1 {
            for i in targetIndex..<(points.count - 1) {
                laterNm += NavGeo.distanceM(points[i], points[i + 1]) / NavFormat.metresPerNm
            }
        }
        let remainingNm = finished ? 0 : dtwNm + laterNm

        // ETA speed: live GPS speed when chosen and moving, else the planned cruise speed.
        var liveSpeed: Double?
        if let s = sog, settings.speedSource == .gps, s >= Self.minCourseSpeedKn { liveSpeed = s }
        let nextSpeed: Double
        if let live = liveSpeed {
            if let v = vmg, v >= Self.minCourseSpeedKn { nextSpeed = v } else { nextSpeed = live }
        } else {
            nextSpeed = settings.cruiseSpeedKn
        }
        let endSpeed = liveSpeed ?? settings.cruiseSpeedKn
        let ttgNext: Double? = nextSpeed > 0 ? dtwNm / nextSpeed : nil
        let ttgEnd: Double? = endSpeed > 0 ? remainingNm / endSpeed : nil
        let fuelToEnd: Double? = (settings.fuelSet && settings.cruiseSpeedKn > 0)
            ? remainingNm / settings.cruiseSpeedKn * settings.fuelBurnLph : nil

        return NavState(
            targetIndex: targetIndex, target: target, targetLabel: label(targetIndex), pointCount: points.count,
            origin: o, dtwNm: dtwNm, btwTrue: btw, legBearingTrue: legBearing, xteM: xte,
            steerDegrees: steer, sogKn: sog, cogTrue: cog, vmgKn: vmg,
            ttgNextHours: ttgNext, etaNextMillis: ttgNext.map { fix.timeMillis + Int64($0 * RoutePlan.hourMs) },
            remainingNm: remainingNm,
            ttgEndHours: ttgEnd, etaEndMillis: ttgEnd.map { fix.timeMillis + Int64($0 * RoutePlan.hourMs) },
            fuelToEndLitres: fuelToEnd,
            offCourse: !finished && abs(xte) > settings.xteLimitM,
            arrivedLabel: arrivedLabel, finished: finished)
    }
}

/// One leg of a route plan. Times are hours from departure.
struct PlanLeg: Equatable {
    /// 1-based leg number.
    var number: Int
    var from: RoutePoint
    var to: RoutePoint
    var distanceNm: Double
    var bearingTrue: Double
    var cumulativeNm: Double
    var hours: Double?
    var cumulativeHours: Double?
    var fuelLitres: Double?
}

/// A route worked out leg by leg at a speed, with fuel at a burn when set.
struct RoutePlan {
    static let hourMs = 3_600_000.0

    let points: [RoutePoint]
    let legs: [PlanLeg]
    let totalNm: Double
    let totalHours: Double?
    let totalFuelLitres: Double?

    init(points: [RoutePoint], speedKn: Double, burnLph: Double) {
        self.points = points
        let speed: Double? = speedKn > 0 ? speedKn : nil
        var cumulative = 0.0
        var cumulativeHours: Double? = speed != nil ? 0 : nil
        var out: [PlanLeg] = []
        if points.count > 1 {
            for i in 0..<(points.count - 1) {
                let from = points[i], to = points[i + 1]
                let nm = NavGeo.distanceM(from, to) / NavFormat.metresPerNm
                cumulative += nm
                let hours = speed.map { nm / $0 }
                if let h = hours, let c = cumulativeHours { cumulativeHours = c + h } else { cumulativeHours = nil }
                out.append(PlanLeg(number: i + 1, from: from, to: to, distanceNm: nm,
                                   bearingTrue: NavGeo.bearing(from, to), cumulativeNm: cumulative,
                                   hours: hours, cumulativeHours: cumulativeHours,
                                   fuelLitres: (hours != nil && burnLph > 0) ? hours! * burnLph : nil))
            }
        }
        legs = out
        totalNm = cumulative
        let th = speed.map { cumulative / $0 }
        totalHours = th
        totalFuelLitres = (th != nil && burnLph > 0) ? th! * burnLph : nil
    }

    /// Arrival time at the end of a leg for a departure at `departureMillis`.
    func arrivalMillis(_ leg: PlanLeg, departureMillis: Int64) -> Int64? {
        leg.cumulativeHours.map { departureMillis + Int64($0 * RoutePlan.hourMs) }
    }
}

/**
 The shallowest sounding along a planned route, sampled from the loaded depth
 layers. Positions with no depth data are skipped, and `coveredFraction` says
 how much of the route the answer actually covers.
 */
struct RouteDepthCheck: Equatable {
    /// Shallowest depth found, metres, negative down (a positive value dries).
    var shallowestMetres: Double
    var latitude: Double
    var longitude: Double
    var coveredFraction: Double

    private static let fullCoverage = 0.98

    func label() -> String {
        let metres = abs(shallowestMetres)
        let base = shallowestMetres > 0 ? String(format: "dries +%.1f m", metres) : String(format: "shallowest %.1f m", metres)
        if coveredFraction < Self.fullCoverage {
            return String(format: "%@ (%d%% of route charted)", base, Int(coveredFraction * 100))
        }
        return base
    }

    /// Samples every `spacingMetres` along each leg, capped at `maxSamples` in total.
    static func compute(_ points: [RoutePoint], spacingMetres: Double = 25, maxSamples: Int = 4000,
                        depthAt: (Double, Double) -> Double?) -> RouteDepthCheck? {
        guard points.count >= 2 else { return nil }
        var legMetres: [Double] = []
        for i in 0..<(points.count - 1) {
            legMetres.append(GeoMath.distanceNm(points[i], points[i + 1]) * GeoMath.kmPerNm * 1000)
        }
        let total = legMetres.reduce(0, +)
        guard total > 0 else { return nil }
        let spacing = max(spacingMetres, total / Double(maxSamples))
        var best: RouteDepthCheck?
        var samples = 0
        var covered = 0
        for index in 0..<(points.count - 1) {
            let a = points[index], b = points[index + 1]
            let steps = max(1, Int(ceil(legMetres[index] / spacing)))
            // The last leg also samples its end point; the others leave it to the next leg.
            let last = index == points.count - 2 ? steps : steps - 1
            if last < 0 { continue }
            for step in 0...last {
                let t = Double(step) / Double(steps)
                let lat = a.lat + (b.lat - a.lat) * t
                let lng = a.lng + (b.lng - a.lng) * t
                samples += 1
                guard let depth = depthAt(lat, lng), depth.isFinite else { continue }
                covered += 1
                if best == nil || depth > best!.shallowestMetres {
                    best = RouteDepthCheck(shallowestMetres: depth, latitude: lat, longitude: lng, coveredFraction: 0)
                }
            }
        }
        guard var result = best else { return nil }
        result.coveredFraction = min(1, Double(covered) / Double(samples))
        return result
    }
}

/// Minimal GeoJSON writers for the app's own geometry (Android map/GeoJson).
enum NavGeoJson {
    static func featureCollection(_ features: [String]) -> String {
        "{\"type\":\"FeatureCollection\",\"features\":[\(features.joined(separator: ","))]}"
    }

    static func point(lat: Double, lng: Double, properties: String = "{}") -> String {
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[\(num(lng)),\(num(lat))]},\"properties\":\(properties)}"
    }

    static func lineString(_ points: [RoutePoint], properties: String = "{}") -> String {
        let coords = points.map { "[\(num($0.lng)),\(num($0.lat))]" }.joined(separator: ",")
        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[\(coords)]},\"properties\":\(properties)}"
    }

    /// A line through the points plus a point marker at each vertex.
    static func lineWithVertices(_ points: [RoutePoint]) -> String {
        var features: [String] = []
        if points.count >= 2 { features.append(lineString(points)) }
        for p in points { features.append(point(lat: p.lat, lng: p.lng)) }
        return featureCollection(features)
    }

    /// Escapes a value for a JSON string body (names the user typed).
    static func jsonEscape(_ value: String) -> String {
        var out = ""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out
    }

    /// Locale-independent number text that is always valid JSON.
    private static func num(_ v: Double) -> String {
        v.isFinite ? String(format: "%.8f", v) : "0"
    }
}
