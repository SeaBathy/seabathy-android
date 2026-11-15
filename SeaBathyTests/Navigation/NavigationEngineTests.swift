import XCTest
@testable import SeaBathy

/// Ported from Android NavigationEngineTest.
final class NavigationEngineTests: XCTestCase {
    // Due north from 0,0 to 0.1N, then east to 0.1N 0.1E.
    private let route = [
        RoutePoint(lat: 0.0, lng: 0.0, name: "Start"),
        RoutePoint(lat: 0.1, lng: 0.0, name: "Turn"),
        RoutePoint(lat: 0.1, lng: 0.1, name: "End")
    ]

    private func settings(_ change: (inout NavSettings) -> Void = { _ in }) -> NavSettings {
        var s = NavSettings()
        s.cruiseSpeedKn = 6
        s.arrivalRadiusM = 50
        s.xteLimitM = 100
        change(&s)
        return s
    }

    private func fix(_ lat: Double, _ lng: Double, sog: Double? = 6.0, cog: Double? = 0.0) -> NavFix {
        NavFix(lat: lat, lng: lng, sogKn: sog, cogTrue: cog, timeMillis: 1_000_000)
    }

    func testStartingAtFirstPointSteersToSecond() {
        let engine = NavigationEngine(points: route, settings: settings())
        let state = engine.update(fix(0, 0))
        XCTAssertEqual(state.targetIndex, 1)
        XCTAssertEqual(state.targetLabel, "Turn")
        XCTAssertEqual(state.btwTrue, 0, accuracy: 0.5)
        XCTAssertEqual(state.dtwNm, 6.0, accuracy: 0.05)
    }

    func testStartingAwaySteersToFirstPoint() {
        let engine = NavigationEngine(points: route, settings: settings())
        XCTAssertEqual(engine.update(fix(-0.05, 0)).targetIndex, 0)
    }

    func testCrossTrackSignAndOffCourse() {
        let engine = NavigationEngine(points: route, settings: settings())
        _ = engine.update(fix(0, 0))
        let right = engine.update(fix(0.05, 0.002))
        XCTAssertTrue(right.xteM > 200 && right.xteM < 245)
        XCTAssertTrue(right.offCourse)
        let left = engine.update(fix(0.05, -0.0005))
        XCTAssertTrue(left.xteM < 0)
        XCTAssertFalse(left.offCourse)
    }

    func testSteerIsTurnToWaypoint() {
        let engine = NavigationEngine(points: route, settings: settings())
        _ = engine.update(fix(0, 0))
        let state = engine.update(fix(0.05, 0.01, cog: 0))
        XCTAssertNotNil(state.steerDegrees)
        XCTAssertTrue(state.steerDegrees! < 0)
    }

    func testVmgAndEta() {
        let engine = NavigationEngine(points: route, settings: settings { $0.speedSource = .gps })
        _ = engine.update(fix(0, 0))
        let state = engine.update(fix(0.05, 0, sog: 6, cog: 0))
        XCTAssertEqual(state.vmgKn!, 6, accuracy: 0.01)
        XCTAssertEqual(state.ttgNextHours!, 0.5, accuracy: 0.01)
        XCTAssertEqual(state.remainingNm, 9, accuracy: 0.1)
    }

    func testArrivalMovesOnAndAnnouncesOnce() {
        let engine = NavigationEngine(points: route, settings: settings())
        _ = engine.update(fix(0, 0))
        let arrived = engine.update(fix(0.0999, 0))
        XCTAssertEqual(arrived.arrivedLabel, "Turn")
        XCTAssertEqual(arrived.targetIndex, 2)
        XCTAssertNil(engine.update(fix(0.0999, 0.0001)).arrivedLabel)
    }

    func testPassingAbeamCountsAsArriving() {
        let engine = NavigationEngine(points: route, settings: settings())
        _ = engine.update(fix(0, 0))
        XCTAssertEqual(engine.update(fix(0.1005, 0.00135)).arrivedLabel, "Turn")
    }

    func testLastPointFinishes() {
        let engine = NavigationEngine(points: route, settings: settings(), startTargetIndex: 2)
        let state = engine.update(fix(0.1, 0.0999))
        XCTAssertTrue(state.finished)
        XCTAssertEqual(state.remainingNm, 0, accuracy: 1e-9)
    }

    func testWithoutAutoAdvanceWaitsUntilSkipped() {
        let engine = NavigationEngine(points: route, settings: settings { $0.autoAdvance = false })
        _ = engine.update(fix(0, 0))
        let arrived = engine.update(fix(0.0999, 0))
        XCTAssertEqual(arrived.arrivedLabel, "Turn")
        XCTAssertEqual(arrived.targetIndex, 1)
        XCTAssertNil(engine.update(fix(0.0999, 0)).arrivedLabel)
        engine.skip()
        XCTAssertEqual(engine.targetIndex, 2)
    }

    func testFuelToEndUsesCruiseBurn() {
        let engine = NavigationEngine(points: route, settings: settings { $0.fuelBurnLph = 12 })
        XCTAssertEqual(engine.update(fix(0, 0)).fuelToEndLitres!, 24, accuracy: 0.3)
    }
}
