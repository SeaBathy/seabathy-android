import XCTest
@testable import SeaBathy

final class ConditionsPolicyTests: XCTestCase {
    private let hour: Int64 = 3_600_000
    private var day: Int64 { 24 * hour }
    private var now: Int64 { 1000 * day }

    private func area(_ id: String, fetched: Int64, until: Int64?? = .none,
                      south: Double = -19, west: Double = 146, north: Double = -17, east: Double = 148,
                      satellites: Set<ConditionsSatelliteKind> = [.seaTemp]) -> ConditionsSummary {
        let forecastUntil: Int64?
        switch until {
        case .none: forecastUntil = fetched + 7 * day
        case .some(let value): forecastUntil = value
        }
        return ConditionsSummary(id: id, name: id, south: south, west: west, north: north, east: east, fetchedMillis: fetched,
                                 forecastUntilMillis: forecastUntil, satellites: satellites, bytes: 1000)
    }

    func testUpdatesWhenANewerRunIsOutOrTheCopyIsHalfADayOld() {
        XCTAssertFalse(ConditionsPolicy.needsUpdate(fetchedMillis: now - hour, latestRunMillis: now - 3 * hour, nowMillis: now))
        XCTAssertTrue(ConditionsPolicy.needsUpdate(fetchedMillis: now - 3 * hour, latestRunMillis: now - hour, nowMillis: now))
        XCTAssertFalse(ConditionsPolicy.needsUpdate(fetchedMillis: now - 11 * hour, latestRunMillis: nil, nowMillis: now))
        XCTAssertTrue(ConditionsPolicy.needsUpdate(fetchedMillis: now - 13 * hour, latestRunMillis: nil, nowMillis: now))
    }

    func testTheNewestAreaCoveringAPlaceIsUsed() {
        let old = area("old", fetched: now - 5 * hour)
        let new = area("new", fetched: now - hour, south: -18.5, west: 146.5, north: -17.5, east: 147.5)
        let elsewhere = area("elsewhere", fetched: now, south: -30, west: 150, north: -29, east: 151)
        let all = [old, new, elsewhere]
        XCTAssertEqual(ConditionsPolicy.newestCovering(lat: -18, lng: 147, areas: all)?.id, "new")
        XCTAssertEqual(ConditionsPolicy.newestCovering(lat: -18.9, lng: 146.1, areas: all)?.id, "old")
        XCTAssertNil(ConditionsPolicy.newestCovering(lat: -40, lng: 140, areas: all))
    }

    func testOldCopiesAndRunOutForecastsAreRemovedNeverTheSpots() {
        let spotId = ConditionsLogic.spotId
        let spot = area(spotId, fetched: now - hour)
        let sameBlock = area("sameBlock", fetched: now - 5 * hour, south: -18.9, west: 146.1, north: -17.1, east: 147.9)
        let inside = area("inside", fetched: now - 5 * hour, south: -18.5, west: 146.5, north: -17.5, east: 147.5)
        let bigger = area("bigger", fetched: now - 5 * hour, south: -20, west: 145, north: -16, east: 149)
        let expired = area("expired", fetched: now - 10 * day, until: .some(now - 3 * day), south: -30, west: 150, north: -29, east: 151)
        let recent = area("recent", fetched: now - 8 * day, until: .some(now - day), south: -35, west: 150, north: -34, east: 151)
        let moreData = area("moreData", fetched: now - 5 * hour, south: -18.9, west: 146.1, north: -17.1, east: 147.9,
                            satellites: [.seaTemp, .chlorophyll])
        let oldSpot = area(spotId, fetched: now - 20 * day, until: .some(now - 13 * day))

        var removed: [String: ConditionsPolicy.Reason] = [:]
        for (summary, reason) in ConditionsPolicy.toRemove([spot, sameBlock, inside, bigger, expired, recent, moreData], keepId: spotId, nowMillis: now) {
            removed[summary.id] = reason
        }
        XCTAssertEqual(removed, ["sameBlock": .coveredByNewer, "expired": .expired])
        XCTAssertTrue(ConditionsPolicy.toRemove([oldSpot], keepId: spotId, nowMillis: now).isEmpty)
    }

    func testSatelliteOnlyAreasGoAfterAWeek() {
        let six = area("six", fetched: now - 6 * day, until: .some(nil))
        let eight = area("eight", fetched: now - 8 * day, until: .some(nil), south: -30, north: -29)
        let removed = ConditionsPolicy.toRemove([six, eight], keepId: ConditionsLogic.spotId, nowMillis: now).map { $0.0.id }
        XCTAssertEqual(removed, ["eight"])
    }

    func testReadsWhenAModelsNewestRunCameOut() {
        let body = #"{"chunk_time_length":360,"last_run_availability_time":1793110885,"last_run_initialisation_time":1793059200,"update_interval_seconds":86400}"#
        XCTAssertEqual(ConditionsClient.parseLatestRun(body), 1_793_110_885_000)
        XCTAssertNil(ConditionsClient.parseLatestRun(#"{"chunk_time_length":360}"#))
    }

    func testTheNewestOfTheModelsRunsIsUsed() {
        var asked = 0
        let client = ConditionsClient(http: { url in
            asked += 1
            if url.contains("currents") { return #"{"last_run_availability_time":1000}"# }
            if url.contains("meteofrance_wave") { return #"{"last_run_availability_time":3000}"# }
            return "not json"
        })
        XCTAssertEqual(client.latestRunMillis(), 3_000_000)
        XCTAssertEqual(asked, 5)
    }
}
