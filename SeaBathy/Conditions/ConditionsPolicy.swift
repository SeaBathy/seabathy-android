import Foundation

/// What the saved-areas list shows without reading every file (Android ConditionsStore.Summary).
struct ConditionsSummary: Equatable {
    let id: String
    let name: String
    let south: Double
    let west: Double
    let north: Double
    let east: Double
    let fetchedMillis: Int64
    let forecastUntilMillis: Int64?
    let satellites: Set<ConditionsSatelliteKind>
    let bytes: Int64

    func sameBoundsAs(_ s: Double, _ w: Double, _ n: Double, _ e: Double) -> Bool {
        abs(south - s) < 0.01 && abs(west - w) < 0.01 && abs(north - n) < 0.01 && abs(east - e) < 0.01
    }
}

/**
 When saved sea conditions are updated, which copy is used for a place, and
 which are removed (Android ConditionsPolicy.kt).
 */
enum ConditionsPolicy {

    /// Updated at least this often when online, even with no word of a new forecast (the satellites are daily).
    static let maxAgeMs: Int64 = 12 * 3_600_000

    /// A saved area whose forecast ran out this long ago is no use, and is removed.
    static let expiredKeepMs: Int64 = 2 * 24 * 3_600_000

    /// A saved area with satellite pictures only is removed this long after it was downloaded.
    static let satelliteOnlyKeepMs: Int64 = 7 * 24 * 3_600_000

    /// How much bigger a newer area may be and still count as a copy of the same block.
    private static let sameBlock = 1.25

    /// True when a forecast run came out after the copy was saved, or it's older than `maxAgeMs`.
    static func needsUpdate(fetchedMillis: Int64, latestRunMillis: Int64?, nowMillis: Int64) -> Bool {
        if let run = latestRunMillis, run > fetchedMillis { return true }
        return nowMillis - fetchedMillis > maxAgeMs
    }

    /// The newest saved area covering a point: the one to use there.
    static func newestCovering(lat: Double, lng: Double, areas: [ConditionsSummary]) -> ConditionsSummary? {
        areas.filter { lat >= $0.south && lat <= $0.north && lng >= $0.west && lng <= $0.east }
            .max { $0.fetchedMillis < $1.fetchedMillis }
    }

    enum Reason { case expired, coveredByNewer }

    /**
     Saved areas to remove, never `keepId` (the spot's, which each update
     replaces): a forecast that ran out long ago, old satellite-only pictures,
     or an older copy of the same block of water. A small area inside a much
     bigger one is kept: its points are closer together.
     */
    static func toRemove(_ areas: [ConditionsSummary], keepId: String, nowMillis: Int64) -> [(ConditionsSummary, Reason)] {
        areas.filter { $0.id != keepId }.compactMap { area -> (ConditionsSummary, Reason)? in
            let expired: Bool
            if let until = area.forecastUntilMillis {
                expired = nowMillis - until > expiredKeepMs
            } else {
                expired = nowMillis - area.fetchedMillis > satelliteOnlyKeepMs
            }
            if expired { return (area, .expired) }
            if areas.contains(where: { $0.id != area.id && covers($0, area) }) { return (area, .coveredByNewer) }
            return nil
        }
    }

    private static func covers(_ newer: ConditionsSummary, _ older: ConditionsSummary) -> Bool {
        if newer.fetchedMillis <= older.fetchedMillis { return false }
        if span(newer) > span(older) * sameBlock { return false }
        let slack = 1e-6
        let inside = older.south >= newer.south - slack && older.north <= newer.north + slack &&
            older.west >= newer.west - slack && older.east <= newer.east + slack
        let hasForecast = older.forecastUntilMillis == nil || newer.forecastUntilMillis != nil
        return inside && hasForecast && older.satellites.isSubset(of: newer.satellites)
    }

    private static func span(_ a: ConditionsSummary) -> Double { max(a.north - a.south, a.east - a.west) }
}
