import Foundation

/**
 Sunrise, sunset and the moon's phase, worked out offline (NOAA's
 simplified solar equations; a mean lunar month for the phase). Good to a
 minute or two for the sun, and plenty for "springs or neaps".
 (Android tides/SunMoon.kt.)
 */
enum TideSunMoon {
    private static let dayMs: Int64 = 86_400_000
    private static let j2000Ms: Int64 = 946_728_000_000 // 2000-01-01 12:00 UTC
    private static let newMoonMs: Int64 = 947_182_440_000 // 2000-01-06 18:14 UTC
    private static let synodicDays = 29.530588853
    private static let tideLagDays = 1.5

    /// Sunrise and sunset (ms) on the UTC day containing `dayMillis`; nil when the sun doesn't rise or set.
    static func sunTimes(_ dayMillis: Int64, lat: Double, lng: Double) -> (rise: Int64, set: Int64)? {
        let dayStart = TideMath.floorDiv(dayMillis, dayMs) * dayMs
        // Noon at this longitude, then the sun's declination and the equation of time there.
        let n = Double(dayStart - j2000Ms) / Double(dayMs) + 0.5 - lng / 360.0
        let meanAnomaly = TideMath.rad(TideMath.mod(357.5291 + 0.98560028 * n, 360.0))
        let centre = 1.9148 * sin(meanAnomaly) + 0.02 * sin(2 * meanAnomaly) + 0.0003 * sin(3 * meanAnomaly)
        let eclipticLng = TideMath.rad(TideMath.mod(TideMath.deg(meanAnomaly) + centre + 180 + 102.9372, 360.0))
        let transit = Double(j2000Ms) + (n + 0.0053 * sin(meanAnomaly) - 0.0069 * sin(2 * eclipticLng)) * Double(dayMs)
        let declination = asin(sin(eclipticLng) * sin(TideMath.rad(23.44)))
        let phi = TideMath.rad(lat)
        let cosHour = (sin(TideMath.rad(-0.833)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
        if cosHour < -1 || cosHour > 1 { return nil }
        let halfDay = TideMath.deg(acos(cosHour)) / 360.0 * Double(dayMs)
        return (TideMath.roundToInt64(transit - halfDay), TideMath.roundToInt64(transit + halfDay))
    }

    /// Days since the last new moon, 0 until about 29.5.
    static func moonAge(_ timeMillis: Int64) -> Double {
        let days = Double(timeMillis - newMoonMs) / Double(dayMs)
        return days - floor(days / synodicDays) * synodicDays
    }

    /// "New moon", "Waxing crescent", ...
    static func phaseName(_ timeMillis: Int64) -> String {
        let eighth = Int((moonAge(timeMillis) / synodicDays * 8) + 0.5) % 8
        return [
            "New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
            "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"
        ][eighth]
    }

    /// Springs (biggest range) come a day or two after new and full moon,
    /// neaps a day or two after the quarters; nil in between.
    static func springOrNeap(_ timeMillis: Int64) -> String? {
        // Days past the nearest new or full moon, allowing for the usual lag.
        let half = synodicDays / 2
        let sinceSyzygy = TideMath.mod(moonAge(timeMillis) - tideLagDays, half)
        if sinceSyzygy < 2.5 || sinceSyzygy > half - 2.5 { return "spring tides" }
        if abs(sinceSyzygy - half / 2) < 2.5 { return "neap tides" }
        return nil
    }
}
