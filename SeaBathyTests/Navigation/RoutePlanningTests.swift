import XCTest
@testable import SeaBathy

/// Ported from Android NavMathTest (geometry and plan parts), RouteControllerTest,
/// RouteDepthCheckTest, GeoJsonTest and WaypointJsonMigrationsTest.
final class RoutePlanningTests: XCTestCase {

    // NavGeo / RoutePlan --------------------------------------------------------------

    func testCrossAndAlongTrack() {
        let a = RoutePoint(lat: 0, lng: 0)
        let b = RoutePoint(lat: 0, lng: 1)
        XCTAssertEqual(NavGeo.crossTrackM(a, b, 0.01, 0.5), -1112, accuracy: 5)
        XCTAssertEqual(NavGeo.alongTrackM(a, b, 0.01, 0.5), 55_597, accuracy: 50)
        XCTAssertEqual(NavGeo.alongTrackM(a, b, 0, -0.1), -11_119, accuracy: 20)
    }

    func testRoutePlanLegsTimesAndFuel() {
        let plan = RoutePlan(points: [RoutePoint(lat: 0, lng: 0), RoutePoint(lat: 0.1, lng: 0), RoutePoint(lat: 0.1, lng: 0.1)],
                             speedKn: 6, burnLph: 10)
        XCTAssertEqual(plan.legs.count, 2)
        XCTAssertEqual(plan.legs[0].distanceNm, 6, accuracy: 0.05)
        XCTAssertEqual(plan.legs[0].bearingTrue, 0, accuracy: 0.1)
        XCTAssertEqual(plan.legs[1].bearingTrue, 90, accuracy: 0.2)
        XCTAssertEqual(plan.totalNm, plan.legs[1].cumulativeNm, accuracy: 1e-9)
        XCTAssertEqual(plan.totalHours!, 2, accuracy: 0.02)
        XCTAssertEqual(plan.totalFuelLitres!, 20, accuracy: 0.2)
        XCTAssertEqual(Double(plan.arrivalMillis(plan.legs[0], departureMillis: 1000)!), 3_601_000, accuracy: 60_000)
    }

    func testNoSpeedMeansNoTimesOrFuel() {
        let plan = RoutePlan(points: [RoutePoint(lat: 0, lng: 0), RoutePoint(lat: 0.1, lng: 0)], speedKn: 0, burnLph: 10)
        XCTAssertNil(plan.totalHours)
        XCTAssertNil(plan.totalFuelLitres)
    }

    func testOffsetRoundTrip() {
        let p = NavGeo.offset(0, 0, bearing: 90, distanceM: 1852)
        XCTAssertEqual(NavGeo.distanceM(0, 0, p.lat, p.lng), 1852, accuracy: 0.5)
        XCTAssertEqual(NavGeo.bearing(0, 0, p.lat, p.lng), 90, accuracy: 0.01)
    }

    // RoutePlanner (Android RouteController) ----------------------------------------------

    private var settings = NavSettings()

    private func planner() -> RoutePlanner {
        RoutePlanner(defaults: nil, settings: { [unowned self] in self.settings })
    }

    private func twoPointRoute() -> RoutePlanner {
        let r = planner()
        r.add(lat: -33.86, lng: 151.20)
        r.add(lat: -33.87, lng: 151.21)
        return r
    }

    func testSecondTapOnSameSpotAddsNoLeg() {
        let r = planner()
        r.add(lat: -33.86, lng: 151.20)
        r.add(lat: -33.86, lng: 151.20, name: "WPT 8")
        r.add(lat: -33.860001, lng: 151.200001)
        r.add(lat: -33.87, lng: 151.21)
        XCTAssertEqual(r.points.count, 2)
    }

    func testPlansAtCruiseSpeedByDefault() {
        settings.cruiseSpeedKn = 10
        let summary = twoPointRoute().summary(routeToolActive: true)!
        XCTAssertTrue(summary.contains("at 10.0 kn (cruise)"), summary)
    }

    func testGpsSpeedUsedWhenChosenAndAvailable() {
        settings.cruiseSpeedKn = 10
        settings.speedSource = .gps
        let r = twoPointRoute()
        r.updateGpsSpeedKnots(4.5)
        XCTAssertTrue(r.summary(routeToolActive: true)!.contains("at 4.5 kn (GPS)"))
    }

    func testGpsChosenWithoutFixFallsBackToCruise() {
        settings.cruiseSpeedKn = 8
        settings.speedSource = .gps
        let summary = twoPointRoute().summary(routeToolActive: true)!
        XCTAssertTrue(summary.contains("at 8.0 kn (cruise, no GPS fix)"), summary)
    }

    func testThreePointRouteReportsFirstLeg() {
        let r = planner()
        r.add(lat: 0, lng: 0)
        r.add(lat: 1, lng: 0)
        r.add(lat: 1, lng: 1)
        XCTAssertTrue(r.summary(routeToolActive: true)!.contains("1st leg 000°T"))
    }

    func testFuelCheckedAgainstUsableFuel() {
        settings.cruiseSpeedKn = 6
        settings.fuelBurnLph = 12
        settings.tankLitres = 10
        settings.reservePercent = 0
        let r = planner()
        r.add(lat: 0, lng: 0)
        r.add(lat: 0.1, lng: 0)
        let summary = r.summary(routeToolActive: true)!
        XCTAssertTrue(summary.contains("Fuel 12.0 L"), summary)
        XCTAssertTrue(summary.contains("more than the usable 10.0 L"), summary)
    }

    func testNoSummaryForEmptyRouteWithToolOff() {
        XCTAssertNil(planner().summary(routeToolActive: false))
    }

    func testReverseSwapsEnds() {
        let r = twoPointRoute()
        r.reverse()
        XCTAssertEqual(r.points.first!.lat, -33.87, accuracy: 1e-9)
    }

    // RouteDepthCheck ------------------------------------------------------------------------

    private let depthRoute = [RoutePoint(lat: -33.90, lng: 151.30), RoutePoint(lat: -33.90, lng: 151.40), RoutePoint(lat: -33.85, lng: 151.40)]

    func testFindsShallowestPoint() {
        let check = RouteDepthCheck.compute(depthRoute) { lat, lng in
            (lat < -33.89 && lng >= 151.349 && lng <= 151.351) ? -4.2 : -30
        }!
        XCTAssertEqual(check.shallowestMetres, -4.2, accuracy: 1e-9)
        XCTAssertEqual(check.longitude, 151.35, accuracy: 0.002)
        XCTAssertEqual(check.coveredFraction, 1, accuracy: 1e-9)
        XCTAssertEqual(check.label(), "shallowest 4.2 m")
    }

    func testDryingHeightReportedAsDrying() {
        let check = RouteDepthCheck.compute(depthRoute) { _, lng in lng > 151.399 ? 0.6 : -12 }!
        XCTAssertEqual(check.label(), "dries +0.6 m")
    }

    func testPartialCoverageIsStated() {
        let check = RouteDepthCheck.compute(depthRoute) { _, lng in lng < 151.35 ? -8 : nil }!
        XCTAssertTrue(check.coveredFraction >= 0.2 && check.coveredFraction <= 0.4)
        XCTAssertTrue(check.label().contains("% of route charted"), check.label())
    }

    func testNoDataOrNoRouteGivesNil() {
        XCTAssertNil(RouteDepthCheck.compute(depthRoute) { _, _ in nil })
        XCTAssertNil(RouteDepthCheck.compute(Array(depthRoute.prefix(1))) { _, _ in -5 })
    }

    func testLongRoutesAreCapped() {
        var calls = 0
        _ = RouteDepthCheck.compute([RoutePoint(lat: -34, lng: 150), RoutePoint(lat: -30, lng: 155)], maxSamples: 500) { _, _ in
            calls += 1
            return -50
        }
        XCTAssertTrue(calls >= 450 && calls <= 560, "calls=\(calls)")
    }

    // GeoJSON and stored data -------------------------------------------------------------------

    func testJsonEscape() {
        XCTAssertEqual(NavGeoJson.jsonEscape("Reef marker"), "Reef marker")
        XCTAssertEqual(NavGeoJson.jsonEscape("6\" hole"), "6\\\" hole")
        XCTAssertEqual(NavGeoJson.jsonEscape("a\\b"), "a\\\\b")
        XCTAssertEqual(NavGeoJson.jsonEscape("a\nb\tc"), "a\\nb\\tc")
    }

    func testEscapedNameRoundTripsThroughAParser() throws {
        let name = "Wreck \"Endeavour\" \\ north"
        let feature = NavGeoJson.point(lat: 1, lng: 2, properties: "{\"name\":\"\(NavGeoJson.jsonEscape(name))\"}")
        let object = try JSONSerialization.jsonObject(with: Data(feature.utf8)) as? [String: Any]
        let properties = object?["properties"] as? [String: Any]
        XCTAssertEqual(properties?["name"] as? String, name)
    }

    func testLineWithVerticesIsValidJson() throws {
        let json = NavGeoJson.lineWithVertices([RoutePoint(lat: -33.5, lng: 151.25), RoutePoint(lat: -33.6, lng: 151.3)])
        let object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        XCTAssertEqual((object?["features"] as? [Any])?.count, 3)
    }

    func testWaypointWithoutIconGetsDefault() throws {
        let raw = #"[{"id":"a","name":"Reef","lat":1.0,"lng":2.0,"color":"#EF4444"},{"id":"b","name":"Two","lat":1,"lng":2,"icon":"fish"}]"#
        let list = try JSONDecoder().decode([Waypoint].self, from: Data(raw.utf8))
        XCTAssertEqual(list[0].icon, "marker")
        XCTAssertEqual(list[1].icon, "fish")
    }

    func testSavedRouteDecodesAndroidShape() throws {
        let raw = #"[{"id":"r1","name":"Home","points":[{"lat":1.0,"lng":2.0},{"lat":1.1,"lng":2.1,"name":"WPT 1"}],"updatedMillis":1700000000000}]"#
        let list = try JSONDecoder().decode([SavedRoute].self, from: Data(raw.utf8))
        XCTAssertEqual(list[0].points.count, 2)
        XCTAssertEqual(list[0].points[1].name, "WPT 1")
        XCTAssertEqual(list[0].updatedMillis, 1_700_000_000_000)
    }

    @MainActor
    func testRouteLibraryFreeNamesAndRename() {
        let library = RouteLibrary(persist: false)
        let a = library.save(id: nil, name: "Route", points: [RoutePoint(lat: 0, lng: 0)])
        XCTAssertEqual(library.freeName("route"), "route (2)")
        library.rename(id: a.id, name: "Home")
        XCTAssertTrue(library.isNameTaken("HOME"))
        XCTAssertFalse(library.isNameTaken("Home", exceptId: a.id))
        library.delete(id: a.id)
        XCTAssertTrue(library.all.isEmpty)
    }
}
