import XCTest
@testable import SeaBathy

/// Ported from Android GraticuleTest.
final class GraticuleTests: XCTestCase {

    func testLabelsReadAsAChartsDo() {
        XCTAssertEqual(Graticule.label(-20, isLatitude: true, step: 1), "20°S")
        XCTAssertEqual(Graticule.label(149.5, isLatitude: false, step: 0.5), "149°30′E")
        XCTAssertEqual(Graticule.label(-20 - 10 / 60.0, isLatitude: true, step: 10 / 60.0), "20°10′S")
        XCTAssertEqual(Graticule.label(0, isLatitude: true, step: 5), "0°")
        XCTAssertEqual(Graticule.label(-180, isLatitude: false, step: 1), "180°")
        XCTAssertEqual(Graticule.label(-88, isLatitude: false, step: 5 / 60.0), "88°00′W")
        XCTAssertEqual(Graticule.label(-20 - 0.5 / 60, isLatitude: true, step: 0.5 / 60), "20°00.5′S")
    }

    func testAboutTheSameNumberOfLinesAtAnyZoom() {
        for span in [300.0, 100, 30, 8, 2, 0.5, 0.1, 0.02, 0.005] {
            let lines = span / Graticule.stepFor(span)
            XCTAssertTrue((3.0...12.0).contains(lines), "span \(span) gave \(lines) lines")
        }
    }

    func testLinesFallOnRoundFigures() {
        let json = Graticule.geoJSON(south: -20.3, west: 149.2, north: -19.6, east: 149.9)
        XCTAssertTrue(json.contains("\"label\":\"149°30′E\""), json)
        XCTAssertTrue(json.contains("\"label\":\"20°00′S\""), json)
    }

    func testTheDateLineOnScreenStillGivesTrueLongitudes() {
        let json = Graticule.geoJSON(south: -20, west: 175, north: -10, east: -175)
        XCTAssertTrue(json.contains("\"label\":\"178°E\""), json)
        XCTAssertTrue(json.contains("\"label\":\"178°W\""), json)
    }

    func testNothingToDrawWithNoArea() {
        XCTAssertEqual(Graticule.geoJSON(south: 10, west: 0, north: 10, east: 5), MapLayerIds.emptyGeoJSON)
    }
}

/// Ported from Android AnchorWatchTest.
final class AnchorWatchTests: XCTestCase {
    private let anchor = AnchorSpot(lat: -19, lng: 147, radiusM: 40, setMillis: 0)

    /// A point `metres` north of the anchor.
    private func north(_ metres: Double) -> Double { -19.0 + metres / 111_195.0 }

    func testSwingingInsideTheCircleIsFine() {
        let watch = AnchorWatch(anchor: anchor)
        for _ in 0..<5 { XCTAssertFalse(watch.update(lat: north(35), lng: 147, accuracyM: 5).dragging) }
        XCTAssertEqual(watch.maxDistanceM, 35, accuracy: 0.5)
    }

    func testOneStrayFixDoesntSoundTwoInARowDo() {
        let watch = AnchorWatch(anchor: anchor)
        XCTAssertFalse(watch.update(lat: north(55), lng: 147, accuracyM: 5).dragging)
        XCTAssertFalse(watch.update(lat: north(20), lng: 147, accuracyM: 5).dragging)
        XCTAssertFalse(watch.update(lat: north(55), lng: 147, accuracyM: 5).dragging)
        let status = watch.update(lat: north(60), lng: 147, accuracyM: 5)
        XCTAssertTrue(status.dragging)
        XCTAssertEqual(status.distanceM, 60, accuracy: 0.5)
        XCTAssertEqual(status.bearingFromAnchor, 0, accuracy: 1)
        // Back inside: the alarm clears.
        XCTAssertFalse(watch.update(lat: north(30), lng: 147, accuracyM: 5).dragging)
    }

    func testVeryRoughFixesAreReportedNotCounted() {
        let watch = AnchorWatch(anchor: anchor)
        let first = watch.update(lat: north(90), lng: 147, accuracyM: 120)
        let second = watch.update(lat: north(90), lng: 147, accuracyM: 120)
        XCTAssertTrue(second.gpsPoor)
        XCTAssertFalse(first.dragging || second.dragging)
        XCTAssertEqual(watch.maxDistanceM, 0)
    }

    func testRepositoryRoundTrips() {
        let defaults = UserDefaults(suiteName: "anchor-tests-\(UUID().uuidString)")!
        let repo = AnchorRepository(defaults: defaults)
        XCTAssertNil(repo.load())
        XCTAssertEqual(repo.lastRadiusM, AnchorWatch.defaultRadiusM)
        repo.save(anchor)
        XCTAssertEqual(repo.load(), anchor)
        XCTAssertEqual(defaults.string(forKey: "anchor_lat"), "-19.0")
        repo.lastRadiusM = 60
        XCTAssertEqual(repo.lastRadiusM, 60)
        repo.save(nil)
        XCTAssertNil(repo.load())
    }

    func testSirenIsAValidWav() {
        let wav = AnchorSiren.sirenWav()
        XCTAssertEqual(String(decoding: wav.prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(wav.count, 44 + 22_050 * 2)
    }
}

/// The measuring readout (Android MeasureController.summary).
final class MeasureSummaryTests: XCTestCase {

    func testSummary() {
        XCTAssertNil(MeasureController.summary(for: []))
        XCTAssertEqual(MeasureController.summary(for: [RoutePoint(lat: -19, lng: 147)]), "Tap another point…")
        let one = MeasureController.summary(for: [RoutePoint(lat: -19, lng: 147), RoutePoint(lat: -18, lng: 147)])
        XCTAssertEqual(one, "60.04 nm (111.2 km)  —  000°T")
        let short = MeasureController.summary(for: [RoutePoint(lat: 0, lng: 0), RoutePoint(lat: 0, lng: 0.0005)])
        XCTAssertEqual(short, "56 m  —  090°T")
    }

    func testGeoJSONHasALineAndDots() {
        let json = MeasureController.geoJSON([RoutePoint(lat: 1, lng: 2), RoutePoint(lat: 3, lng: 4)])
        XCTAssertTrue(json.contains("LineString"))
        XCTAssertEqual(json.components(separatedBy: "\"Point\"").count - 1, 2)
        XCTAssertFalse(MeasureController.geoJSON([RoutePoint(lat: 1, lng: 2)]).contains("LineString"))
    }
}
