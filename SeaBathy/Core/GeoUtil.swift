import Foundation

/// Great-circle distance, bearing and passage-time helpers (Android util/GeoMath.kt).
enum GeoMath {
    private static let earthRadiusNm = 3440.065
    static let kmPerNm = 1.852
    static let metresPerNm = 1852.0
    private static let shortDistanceNm = 0.1

    static func rad(_ d: Double) -> Double { d * .pi / 180 }
    static func deg(_ r: Double) -> Double { r * 180 / .pi }

    static func distanceNm(_ aLat: Double, _ aLng: Double, _ bLat: Double, _ bLng: Double) -> Double {
        let dLat = rad(bLat - aLat), dLng = rad(bLng - aLng)
        let h = pow(sin(dLat / 2), 2) + cos(rad(aLat)) * cos(rad(bLat)) * pow(sin(dLng / 2), 2)
        return 2 * earthRadiusNm * asin(min(1, sqrt(h)))
    }

    static func distanceNm(_ a: RoutePoint, _ b: RoutePoint) -> Double { distanceNm(a.lat, a.lng, b.lat, b.lng) }

    static func totalNm(_ points: [RoutePoint]) -> Double {
        guard points.count > 1 else { return 0 }
        return (1..<points.count).reduce(0) { $0 + distanceNm(points[$1 - 1], points[$1]) }
    }

    static func bearingDegrees(_ a: RoutePoint, _ b: RoutePoint) -> Double {
        let dLng = rad(b.lng - a.lng)
        let y = sin(dLng) * cos(rad(b.lat))
        let x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(dLng)
        return (deg(atan2(y, x)) + 360).truncatingRemainder(dividingBy: 360)
    }

    static func eta(distanceNm: Double, speedKnots: Double) -> String {
        if distanceNm <= 0 || speedKnots <= 0 { return "—" }
        let hours = distanceNm / speedKnots
        let whole = Int(hours)
        let minutes = Int((hours - Double(whole)) * 60)
        if whole > 0 { return "\(whole)h\(minutes)m" }
        return minutes == 0 ? "<1m" : "\(minutes)m"
    }

    /// Nautical miles with kilometres, or metres under a tenth of a mile.
    static func formatDistance(_ nm: Double) -> String {
        nm < shortDistanceNm ? String(format: "%.0f m", nm * metresPerNm)
            : String(format: "%.2f nm (%.1f km)", nm, nm * kmPerNm)
    }
}

/// Coordinate display and parsing (Android util/CoordinateFormatter.kt).
enum CoordinateFormatter {
    static let modeDDM = "DDM"
    static let modeDD = "DD"
    static let modeDMS = "DMS"
    static let modes = [modeDDM, modeDD, modeDMS]
    static let modeLabels = ["Marine — degrees and decimal minutes", "Decimal degrees", "Degrees, minutes and seconds"]

    static func formatDdm(_ lat: Double, _ lng: Double) -> String { "\(ddmPart(lat, "N", "S")) \(ddmPart(lng, "E", "W"))" }
    static func formatDd(_ lat: Double, _ lng: Double) -> String { String(format: "%.6f, %.6f", lat, lng) }
    static func formatDms(_ lat: Double, _ lng: Double) -> String { "\(dmsPart(lat, "N", "S")) \(dmsPart(lng, "E", "W"))" }

    static func formatPair(_ lat: Double, _ lng: Double, mode: String) -> String {
        switch mode {
        case modeDD: return formatDd(lat, lng)
        case modeDMS: return formatDms(lat, lng)
        default: return formatDdm(lat, lng)
        }
    }

    private static func numbers(in text: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "[-+]?\\d+(?:\\.\\d+)?")
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }

    static func parseSingle(_ raw: String, isLat: Bool) -> Double? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let hemisphere = text.first { "NSEW".contains($0) }
        let nums = numbers(in: text).compactMap(Double.init)
        guard !nums.isEmpty else { return nil }
        if nums.dropFirst().contains(where: { $0 < 0 || $0 >= 60 }) { return nil }
        var value: Double
        switch nums.count {
        case 1: value = abs(nums[0])
        case 2: value = abs(nums[0]) + nums[1] / 60
        default: value = abs(nums[0]) + nums[1] / 60 + nums[2] / 3600
        }
        if text.hasPrefix("-") || hemisphere == "S" || hemisphere == "W" { value = -value }
        if hemisphere == "N" || hemisphere == "E" { value = abs(value) }
        let limit = isLat ? 90.0 : 180.0
        return (value >= -limit && value <= limit) ? value : nil
    }

    static func parsePair(_ raw: String) -> (Double, Double)? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\n", with: " ")
        guard !text.isEmpty else { return nil }
        let comma = text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if comma.count == 2, let lat = parseSingle(comma[0], isLat: true), let lng = parseSingle(comma[1], isLat: false) {
            return (lat, lng)
        }
        let ns = text as NSString
        let latRegex = try! NSRegularExpression(pattern: "(.+?[NS])", options: .caseInsensitive)
        if let latMatch = latRegex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) {
            let latText = ns.substring(with: latMatch.range)
            let rest = ns.substring(from: latMatch.range.location + latMatch.range.length)
            let restNs = rest as NSString
            let lngRegex = try! NSRegularExpression(pattern: "(.+?[EW])", options: .caseInsensitive)
            if let lngMatch = lngRegex.firstMatch(in: rest, range: NSRange(location: 0, length: restNs.length)),
               let lat = parseSingle(latText, isLat: true),
               let lng = parseSingle(restNs.substring(with: lngMatch.range), isLat: false) {
                return (lat, lng)
            }
        }
        let nums = numbers(in: text)
        if nums.count == 2, let lat = Double(nums[0]), let lng = Double(nums[1]),
           lat >= -90, lat <= 90, lng >= -180, lng <= 180 {
            return (lat, lng)
        }
        return nil
    }

    private static func ddmPart(_ value: Double, _ pos: String, _ neg: String) -> String {
        let thousandths = Int64((abs(value) * 60_000).rounded())
        let degrees = thousandths / 60_000
        let minutes = Double(thousandths % 60_000) / 1000
        return String(format: "%d°%06.3f'%@", degrees, minutes, value >= 0 ? pos : neg)
    }

    private static func dmsPart(_ value: Double, _ pos: String, _ neg: String) -> String {
        let hundredths = Int64((abs(value) * 360_000).rounded())
        let degrees = hundredths / 360_000
        let minutes = (hundredths % 360_000) / 6_000
        let seconds = Double(hundredths % 6_000) / 100
        return String(format: "%d°%02d'%05.2f\"%@", degrees, minutes, seconds, value >= 0 ? pos : neg)
    }
}

/// XML escaping for GPX output (Android util/XmlEscape.kt).
enum XmlEscape {
    static func text(_ s: String) -> String {
        var out = ""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            default:
                // Drop characters XML 1.0 does not allow.
                let v = scalar.value
                if v == 0x9 || v == 0xA || v == 0xD || (v >= 0x20 && v <= 0xD7FF) || (v >= 0xE000 && v <= 0xFFFD) || v >= 0x10000 {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out
    }
}

/// Web Mercator tile maths (Android tiles/WebMercatorTiles.kt subset).
enum WebMercator {
    static func lngToTileX(_ lng: Double, _ z: Int) -> Double { (lng + 180) / 360 * Double(1 << z) }
    static func latToTileY(_ lat: Double, _ z: Int) -> Double {
        let r = GeoMath.rad(max(min(lat, 85.05112878), -85.05112878))
        return (1 - log(tan(r) + 1 / cos(r)) / .pi) / 2 * Double(1 << z)
    }
    static func tileXToLng(_ x: Double, _ z: Int) -> Double { x / Double(1 << z) * 360 - 180 }
    static func tileYToLat(_ y: Double, _ z: Int) -> Double {
        let n = Double.pi - 2 * .pi * y / Double(1 << z)
        return GeoMath.deg(atan(0.5 * (exp(n) - exp(-n))))
    }
}
