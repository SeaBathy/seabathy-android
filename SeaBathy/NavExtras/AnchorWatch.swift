import Foundation

/// Where the anchor is and how far the boat may swing before the alarm (Android nav/Anchor).
struct AnchorSpot: Equatable {
    var lat: Double
    var lng: Double
    var radiusM: Double
    var setMillis: Int64
}

/// What the anchor watch makes of the latest fix.
struct AnchorStatus: Equatable {
    /// Boat to anchor, metres.
    var distanceM: Double
    var bearingFromAnchor: Double
    /// Outside the circle on `AnchorWatch.fixesToAlarm` fixes in a row.
    var dragging: Bool
    /// The fix is too rough to tell (accuracy worse than the radius).
    var gpsPoor: Bool
}

/**
 Anchor alarm logic (Android nav/AnchorWatch.kt): the boat may swing anywhere
 inside the circle; leaving it on two fixes in a row means the anchor is
 dragging. Fixes much rougher than the circle are reported as poor GPS.
 */
final class AnchorWatch {
    let anchor: AnchorSpot
    private var outsideCount = 0
    private(set) var maxDistanceM = 0.0

    static let fixesToAlarm = 2
    /// Accuracy up to this is always usable, even with a small circle.
    static let poorAccuracyFloorM = 25.0
    /// Radii offered, metres.
    static let radiiM: [Double] = [15, 25, 40, 60, 100, 150]
    static let defaultRadiusM = 40.0

    init(anchor: AnchorSpot) {
        self.anchor = anchor
    }

    /// Keeps the furthest swing when only the circle size changes.
    func carryOver(_ previous: AnchorWatch) {
        maxDistanceM = previous.maxDistanceM
    }

    func update(lat: Double, lng: Double, accuracyM: Double?) -> AnchorStatus {
        let distance = TrackMath.distanceM(anchor.lat, anchor.lng, lat, lng)
        let bearing = Self.bearing(anchor.lat, anchor.lng, lat, lng)
        let poor = accuracyM.map { $0 > max(anchor.radiusM, Self.poorAccuracyFloorM) } ?? false
        if !poor {
            maxDistanceM = max(maxDistanceM, distance)
            outsideCount = distance > anchor.radiusM ? outsideCount + 1 : 0
        }
        return AnchorStatus(distanceM: distance, bearingFromAnchor: bearing,
                            dragging: outsideCount >= Self.fixesToAlarm, gpsPoor: poor)
    }

    /// Initial true bearing from a to b, 0..<360.
    static func bearing(_ aLat: Double, _ aLng: Double, _ bLat: Double, _ bLng: Double) -> Double {
        let p1 = aLat * .pi / 180, p2 = bLat * .pi / 180
        let dl = (bLng - aLng) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return NavFormat.normaliseDegrees(atan2(y, x) * 180 / .pi)
    }
}

/// The set anchor, kept so the watch carries on after a restart. Same keys and string values as Android.
struct AnchorRepository {
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private static let kLat = "anchor_lat", kLng = "anchor_lng", kRadius = "anchor_radius_m"
    private static let kSet = "anchor_set_millis", kLastRadius = "anchor_last_radius_m"

    func load() -> AnchorSpot? {
        guard let lat = defaults.string(forKey: Self.kLat).flatMap({ Double($0) }),
              let lng = defaults.string(forKey: Self.kLng).flatMap({ Double($0) }) else { return nil }
        let radius = defaults.string(forKey: Self.kRadius).flatMap({ Double($0) }) ?? AnchorWatch.defaultRadiusM
        let set = (defaults.object(forKey: Self.kSet) as? NSNumber)?.int64Value ?? 0
        return AnchorSpot(lat: lat, lng: lng, radiusM: radius, setMillis: set)
    }

    func save(_ anchor: AnchorSpot?) {
        guard let anchor else {
            [Self.kLat, Self.kLng, Self.kRadius, Self.kSet].forEach { defaults.removeObject(forKey: $0) }
            return
        }
        defaults.set(String(anchor.lat), forKey: Self.kLat)
        defaults.set(String(anchor.lng), forKey: Self.kLng)
        defaults.set(String(anchor.radiusM), forKey: Self.kRadius)
        defaults.set(NSNumber(value: anchor.setMillis), forKey: Self.kSet)
    }

    /// The radius last used, offered next time.
    var lastRadiusM: Double {
        get { defaults.string(forKey: Self.kLastRadius).flatMap({ Double($0) }) ?? AnchorWatch.defaultRadiusM }
        nonmutating set { defaults.set(String(newValue), forKey: Self.kLastRadius) }
    }
}
