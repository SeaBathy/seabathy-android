import XCTest
@testable import SeaBathy

/// Ported from Android TrackTest.
final class TrackTests: XCTestCase {
    private let lat0 = -19.0
    private let lng0 = 147.0
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("tracktests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    /// `metres` north of the start.
    private func north(_ metres: Double) -> Double { lat0 + metres / 111_195.0 }

    /// A boat heading north at `knots`, one point every `stepS` seconds.
    private func run(knots: Double, count: Int, stepS: Int64 = 10, startMillis: Int64 = 1_700_000_000_000) -> [TrackPoint] {
        let ms = knots / 1.943844
        return (0..<count).map { i in
            TrackPoint(timeMillis: startMillis + Int64(i) * stepS * 1000, lat: north(ms * Double(stepS) * Double(i)), lng: lng0)
        }
    }

    func testFilterSkipsSmallMovesRoughFixesAndGlitches() {
        var filter = TrackFilter()
        let t: Int64 = 1_000_000
        XCTAssertNotNil(filter.accept(t, lat0, lng0, accuracyM: 5))
        // Swinging at anchor: under 10 m adds nothing.
        XCTAssertNil(filter.accept(t + 10_000, north(6), lng0, accuracyM: 5))
        // Too rough to trust.
        XCTAssertNil(filter.accept(t + 20_000, north(200), lng0, accuracyM: 80))
        // 1 km in 5 s is a GPS jump, not the boat.
        XCTAssertNil(filter.accept(t + 25_000, north(1000), lng0, accuracyM: 5))
        XCTAssertNotNil(filter.accept(t + 30_000, north(30), lng0, accuracyM: 5))
        // After a long gap, a big move is real (the phone was off).
        XCTAssertNotNil(filter.accept(t + 30_000 + 600_000, north(50_000), lng0, accuracyM: 5))
    }

    func testSummaryHasDistanceMovingTimeAndSpeeds() {
        let points = run(knots: 5, count: 61)
        let s = TrackMath.summarise(id: "id", name: "Trip", points: points)
        XCTAssertEqual(s.distanceM, 5 / 1.943844 * 600, accuracy: 2)
        XCTAssertEqual(s.endMillis - s.startMillis, 600_000)
        XCTAssertEqual(s.movingMillis, 600_000)
        XCTAssertEqual(s.averageSpeedMs * 1.943844, 5, accuracy: 0.05)
        XCTAssertEqual(s.maxSpeedMs * 1.943844, 5, accuracy: 0.05)
    }

    func testAStopAndAGapDontCountAsMovingOrDistance() {
        let first = run(knots: 6, count: 31)
        let last = first[first.count - 1]
        let parked = TrackPoint(timeMillis: last.timeMillis + 3_600_000, lat: last.lat + 12 / 111_195.0, lng: lng0)
        let second = run(knots: 6, count: 31, startMillis: parked.timeMillis + 30 * 60_000)
            .map { TrackPoint(timeMillis: $0.timeMillis, lat: $0.lat + 0.1, lng: $0.lng) }
        let all = first + [parked] + second
        let s = TrackMath.summarise(id: "id", name: "Trip", points: all)
        XCTAssertEqual(TrackMath.segments(all).map { $0.count }, [32, 31])
        let legM = 6 / 1.943844 * 300
        XCTAssertEqual(s.distanceM, 2 * legM + 12, accuracy: 3)
        XCTAssertEqual(s.movingMillis, 600_000)
        XCTAssertEqual(s.maxSpeedMs * 1.943844, 6, accuracy: 0.05)
    }

    func testASingleNoisyJumpDoesntSetTheTopSpeed() {
        var points = run(knots: 4, count: 30)
        points[15].lng = lng0 + 60 / (111_195.0 * cos(lat0 * .pi / 180))
        let top = TrackMath.maxSpeedMs(points) * 1.943844
        XCTAssertLessThan(top, 8)
    }

    func testAShortTrackStillHasATopSpeed() {
        let s = TrackMath.summarise(id: "id", name: "Short", points: run(knots: 5, count: 3))
        XCTAssertEqual(s.maxSpeedMs * 1.943844, 5, accuracy: 0.05)
        XCTAssertGreaterThanOrEqual(s.maxSpeedMs, s.averageSpeedMs)
    }

    func testSimplifyKeepsTheShapeAndDropsStraightRuns() {
        let straight = run(knots: 5, count: 200)
        let end = straight[straight.count - 1]
        let turn = (1...200).map { i in
            TrackPoint(timeMillis: end.timeMillis + Int64(i) * 10_000, lat: end.lat, lng: lng0 + Double(i) * 25 / 105_000.0)
        }
        let simple = TrackMath.simplify(straight + turn, toleranceM: 2)
        XCTAssertEqual(simple.count, 3)
        XCTAssertEqual(simple[1], end)
    }

    func testCodecRoundTripsAndSkipsADamagedLastLine() {
        let points = run(knots: 3, count: 3)
        let text = points.map { TrackCodec.line($0) }.joined() + "17000,-19.0"
        XCTAssertEqual(TrackCodec.parse(text), points)
    }

    func testStoreAppendsFinishesRenamesAndDeletes() {
        let store = TrackStore(dir: tempDir)
        let points = run(knots: 5, count: 20)
        points.forEach { store.append("a", $0) }
        let saved = store.finish("a", name: "Morning")
        XCTAssertEqual(saved?.pointCount, 20)
        XCTAssertEqual(store.points("a"), points)

        // A recording that never moved is thrown away.
        store.append("b", points[0])
        XCTAssertNil(store.finish("b", name: "Nothing"))
        XCTAssertTrue(store.points("b").isEmpty)

        // A fresh store reads the index back.
        let reopened = TrackStore(dir: tempDir)
        XCTAssertEqual(reopened.summaries().map { $0.name }, ["Morning"])
        reopened.rename("a", name: "Reef run")
        XCTAssertTrue(reopened.isNameTaken("reef run"))
        XCTAssertEqual(TrackStore(dir: tempDir).summary("a")?.name, "Reef run")
        reopened.delete("a")
        XCTAssertTrue(TrackStore(dir: tempDir).summaries().isEmpty)
    }

    func testGpxExportReadsBackThroughTheImporter() throws {
        let first = run(knots: 5, count: 5)
        // GPS back an hour later, 11 km on: a new segment.
        let second = run(knots: 5, count: 5, startMillis: first[first.count - 1].timeMillis + 3_600_000)
            .map { TrackPoint(timeMillis: $0.timeMillis, lat: $0.lat + 0.1, lng: $0.lng) }
        let gpx = GpxCodec.exportTrack(name: "Reef & back", points: first + second)
        XCTAssertTrue(gpx.contains("<name>Reef &amp; back</name>"))
        XCTAssertEqual(gpx.components(separatedBy: "<trkseg>").count - 1, 2)
        XCTAssertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
        let parsed = try GpxCodec.parse(Data(gpx.utf8))
        XCTAssertEqual(parsed.tracks.count, 1)
        let track = parsed.tracks[0]
        XCTAssertEqual(track.name, "Reef & back")
        let expected = first + second
        XCTAssertEqual(track.points.map { $0.timeMillis }, expected.map { $0.timeMillis })
        for (e, a) in zip(expected, track.points) {
            XCTAssertEqual(e.lat, a.lat, accuracy: 1e-7)
            XCTAssertEqual(e.lng, a.lng, accuracy: 1e-7)
        }
    }

    func testTrackFileName() {
        XCTAssertEqual(GpxCodec.trackFileName("14 Nov, 09:30"), "seabathy_track_14_Nov_09_30.gpx")
        XCTAssertEqual(GpxCodec.trackFileName("///"), "seabathy_track_track.gpx")
    }
}
