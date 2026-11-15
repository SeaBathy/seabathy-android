import XCTest
@testable import SeaBathy

// Ported from Android download/* tests: estimator, sampler, retries, bounded concurrency,
// download file names and link redaction.

final class OfflineDownloadEstimatorTests: XCTestCase {
    func testSingleZoomRangeIsThatZoomsOwnTileCount() {
        let bounds = LayersTiles.tileBounds(z: 4, x: 3, y: 5)!
        XCTAssertEqual(OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: 4...4).tileCount, 1)
    }

    func testMultiZoomRangeSumsEveryZoom() {
        let bounds = GeoBounds(west: -1, south: -1, east: 1, north: 1)
        let perZoom = (3...5).map { LayersTiles.tileRangeCovering(bounds, z: $0).tileCount }.reduce(0, +)
        XCTAssertEqual(OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: 3...5).tileCount, perZoom)
    }

    func testEstimatedBytesIsCountTimesAverage() {
        let bounds = LayersTiles.tileBounds(z: 4, x: 3, y: 5)!
        XCTAssertEqual(OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: 4...4, averageTileBytes: 30_000).estimatedBytes, 30_000)
    }

    func testFormatBytes() {
        XCTAssertEqual(OfflineDownloadEstimator.formatBytes(Int64(1.5 * 1024 * 1024 * 1024)), "1.5 GB")
        XCTAssertEqual(OfflineDownloadEstimator.formatBytes(340 * 1024 * 1024), "340 MB")
        XCTAssertEqual(OfflineDownloadEstimator.formatBytes(12 * 1024), "12 KB")
        XCTAssertEqual(OfflineDownloadEstimator.formatBytes(500), "500 bytes")
    }

    func testFormatTileCountGroupsThousands() {
        XCTAssertEqual(OfflineDownloadEstimator.formatTileCount(4096), "4,096")
        XCTAssertEqual(OfflineDownloadEstimator.formatTileCount(12), "12")
    }

    func testTilesToFetchIncludesEveryZoom() {
        let bounds = GeoBounds(west: 151.20, south: -33.87, east: 151.21, north: -33.86)
        let tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange: 10...14)
        XCTAssertEqual(Set(tiles.map { $0.z }), Set(10...14))
    }

    func testTilesToFetchMatchesRangeCountsWithoutDuplicates() {
        let bounds = GeoBounds(west: -1, south: -1, east: 1, north: 1)
        let tiles = OfflineDownloadEstimator.tilesToFetch(bounds, zoomRange: 3...7)
        for z in 3...7 {
            XCTAssertEqual(Int64(tiles.filter { $0.z == z }.count), LayersTiles.tileRangeCovering(bounds, z: z).tileCount, "zoom \(z)")
        }
        XCTAssertEqual(Int64(tiles.count), OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: 3...7).tileCount)
        XCTAssertEqual(tiles.count, Set(tiles).count)
    }
}

final class LayersTilesTests: XCTestCase {
    func testWholeWorldAtZoomZeroIsOneTile() {
        let range = LayersTiles.tileRangeCovering(LayersTiles.tileBounds(z: 0, x: 0, y: 0)!, z: 0)
        XCTAssertEqual(range.tileCount, 1)
    }

    func testTileBoundsRoundTrip() {
        let bounds = LayersTiles.tileBounds(z: 3, x: 5, y: 2)!
        let range = LayersTiles.tileRangeCovering(bounds, z: 3)
        XCTAssertEqual(range, LayersTiles.TileRange(z: 3, minX: 5, maxX: 5, minY: 2, maxY: 2))
    }

    func testOutOfRangeTileIsRejected() {
        XCTAssertNil(LayersTiles.tileBounds(z: 2, x: 4, y: 0))
        XCTAssertNil(LayersTiles.tileBounds(z: 2, x: 0, y: -1))
    }

    func testCoarseZoomIsClampedToTheGrid() {
        let range = LayersTiles.tileRangeCovering(GeoBounds(west: -180, south: -85, east: 180, north: 85), z: 1)
        XCTAssertEqual(range.minX, 0)
        XCTAssertEqual(range.maxX, 1)
        XCTAssertEqual(range.minY, 0)
        XCTAssertEqual(range.maxY, 1)
        XCTAssertEqual(range.tileCount, 4)
    }

    func testTileCountMultipliesSpans() {
        XCTAssertEqual(LayersTiles.TileRange(z: 5, minX: 10, maxX: 14, minY: 20, maxY: 22).tileCount, 15)
    }
}

final class RetryingFetcherTests: XCTestCase {
    func testSucceedsWithoutRetrying() async {
        var attempts = 0
        let fetcher = RetryingFetcher(sleep: { _ in }) { _ in attempts += 1; return .success(Data("ok".utf8)) }
        let result = await fetcher.fetch("https://example.com/tile")
        XCTAssertEqual(attempts, 1)
        XCTAssertEqual(result, Data("ok".utf8))
    }

    func testRecoversFromTransientFailure() async {
        var attempts = 0
        let fetcher = RetryingFetcher(sleep: { _ in }) { _ in
            attempts += 1
            return attempts < 3 ? .retryable : .success(Data("recovered".utf8))
        }
        let result = await fetcher.fetch("https://example.com/tile")
        XCTAssertEqual(attempts, 3)
        XCTAssertEqual(result, Data("recovered".utf8))
    }

    func testGivesUpAfterMaxAttempts() async {
        var attempts = 0
        let fetcher = RetryingFetcher(maxAttempts: 3, sleep: { _ in }) { _ in attempts += 1; return .retryable }
        let result = await fetcher.fetch("https://example.com/tile")
        XCTAssertEqual(attempts, 3)
        XCTAssertNil(result)
    }

    func testDoesNotRetryPermanentFailure() async {
        var attempts = 0
        let fetcher = RetryingFetcher(sleep: { _ in }) { _ in attempts += 1; return .permanent }
        let result = await fetcher.fetch("https://example.com/tile")
        XCTAssertEqual(attempts, 1)
        XCTAssertNil(result)
    }

    func testBacksOffBetweenAttemptsButNotAfterTheLast() async {
        var sleeps: [UInt64] = []
        let fetcher = RetryingFetcher(maxAttempts: 3, backoffMillis: { UInt64($0) * 100 },
                                      sleep: { sleeps.append($0) }) { _ in .retryable }
        _ = await fetcher.fetch("https://example.com/tile")
        XCTAssertEqual(sleeps, [100, 200])
    }

    func testStopsAsSoonAsToldToStop() async {
        var attempts = 0, sleeps = 0
        var stop = false
        let fetcher = RetryingFetcher(maxAttempts: 5, sleep: { _ in sleeps += 1 }, shouldStop: { stop }) { _ in
            attempts += 1
            stop = true
            return .retryable
        }
        let result = await fetcher.fetch("https://example.com/tile")
        XCTAssertNil(result)
        XCTAssertEqual(attempts, 1)
        XCTAssertEqual(sleeps, 0)
    }

    func testMakesNoAttemptOnceStopped() async {
        var attempts = 0
        let fetcher = RetryingFetcher(sleep: { _ in }, shouldStop: { true }) { _ in attempts += 1; return .success(Data([1])) }
        let result = await fetcher.fetch("https://example.com/tile")
        XCTAssertNil(result)
        XCTAssertEqual(attempts, 0)
    }
}

final class TileSamplerTests: XCTestCase {
    private let bounds = GeoBounds(west: 151.0, south: -34.0, east: 151.5, north: -33.5)
    private let template = "https://example.com/{z}/{x}/{y}.jpg"

    func testAveragesRealFetchedSizes() async {
        let sampler = TileSampler { _ in Data(count: 30_000) }
        let result = await sampler.sample(urlTemplate: template, bounds: bounds, zoomRange: 8...12)
        XCTAssertEqual(result.averageBytes, 30_000)
        XCTAssertFalse(result.sampledSizes.isEmpty)
        XCTAssertTrue(result.sampledSizes.allSatisfy { $0 == 30_000 })
    }

    func testSkipsFailedSamples() async {
        var call = 0
        let sampler = TileSampler { _ in
            call += 1
            return call % 2 == 0 ? nil : Data(count: 10_000)
        }
        let result = await sampler.sample(urlTemplate: template, bounds: bounds, zoomRange: 8...12)
        XCTAssertFalse(result.sampledSizes.isEmpty)
        XCTAssertEqual(result.averageBytes, 10_000)
    }

    func testNilAverageWhenEverySampleFails() async {
        let sampler = TileSampler { _ in nil }
        let result = await sampler.sample(urlTemplate: template, bounds: bounds, zoomRange: 8...12)
        XCTAssertTrue(result.sampledSizes.isEmpty)
        XCTAssertNil(result.averageBytes)
    }

    func testSamplesSpanTheWholeZoomRange() async {
        var zooms = Set<Int>()
        let sampler = TileSampler { url in
            zooms.insert(Int(url.components(separatedBy: "/")[3])!)
            return Data([1])
        }
        _ = await sampler.sample(urlTemplate: template, bounds: bounds, zoomRange: 8...12)
        XCTAssertEqual(zooms, Set(8...12))
    }

    func testSpreadPicks() {
        XCTAssertEqual(TileSampler.spreadPicks(Array(0..<100), count: 5), [0, 20, 40, 60, 80])
        XCTAssertEqual(TileSampler.spreadPicks(["a", "b", "c"], count: 8).count, 3)
    }

    func testUrlForSubstitutesByToken() {
        XCTAssertEqual(TileSampler.urlFor("https://tiles.example/{z}/{y}/{x}.jpg", z: 10, x: 5, y: 7),
                       "https://tiles.example/10/7/5.jpg")
    }
}

private actor ConcurrencyCounter {
    var current = 0
    var peak = 0
    func enter() { current += 1; peak = max(peak, current) }
    func leave() { current -= 1 }
}

final class BoundedConcurrencyTests: XCTestCase {
    func testNeverRunsMoreThanTheLimit() async {
        let counter = ConcurrencyCounter()
        let results = await layersBoundedConcurrentMap(Array(1...40), concurrency: 4) { item -> Int in
            await counter.enter()
            try? await Task.sleep(nanoseconds: 20_000_000)
            await counter.leave()
            return item * 2
        }
        XCTAssertEqual(results, (1...40).map { $0 * 2 })
        let peak = await counter.peak
        XCTAssertLessThanOrEqual(peak, 4)
    }

    func testOneAtATimeKeepsOrder() async {
        let results = await layersBoundedConcurrentMap(Array(1...10), concurrency: 1) { $0 }
        XCTAssertEqual(results, Array(1...10))
    }

    func testLargeLimitKeepsOrder() async {
        let results = await layersBoundedConcurrentMap(Array(1...20), concurrency: 100) { item -> Int in
            try? await Task.sleep(nanoseconds: UInt64(20 - item) * 1_000_000)
            return item
        }
        XCTAssertEqual(results, Array(1...20))
    }
}

final class DownloadFileNameTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("layers-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func touch(_ name: String) {
        FileManager.default.createFile(atPath: dir.appendingPathComponent(name).path, contents: Data())
    }

    func testUsesThePlainNameWhenFree() {
        XCTAssertEqual(LayersFileNames.freeMbtilesFile(in: dir, baseName: "area").lastPathComponent, "area.mbtiles")
    }

    func testNeverReturnsAnExistingDownload() {
        touch("area.mbtiles")
        touch("area_2.mbtiles")
        XCTAssertEqual(LayersFileNames.freeMbtilesFile(in: dir, baseName: "area").lastPathComponent, "area_3.mbtiles")
    }

    func testSkipsNamesWithJournalOrPartFile() {
        touch("area.mbtiles-journal")
        XCTAssertEqual(LayersFileNames.freeMbtilesFile(in: dir, baseName: "area").lastPathComponent, "area_2.mbtiles")
        touch("area_2.mbtiles.part")
        XCTAssertEqual(LayersFileNames.freeMbtilesFile(in: dir, baseName: "area").lastPathComponent, "area_3.mbtiles")
    }

    func testPartFileSitsNextToItsDestination() {
        let destination = dir.appendingPathComponent("area_2.mbtiles")
        XCTAssertEqual(LayersFileNames.partFile(for: destination).lastPathComponent, "area_2.mbtiles.part")
    }

    func testStalePartsAreRemovedFinishedDownloadsAreNot() throws {
        ["done.mbtiles", "done.mbtiles-journal", "killed.mbtiles.part", "killed.mbtiles.part-journal", "notes.part"].forEach(touch)
        let removed = LayersFileNames.deleteStaleDownloadParts(in: dir).map { $0.lastPathComponent }.sorted()
        XCTAssertEqual(removed, ["killed.mbtiles.part", "killed.mbtiles.part-journal"])
        let left = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        XCTAssertEqual(left, ["done.mbtiles", "done.mbtiles-journal", "notes.part"])
    }

    func testMissingFolderHasNothingToSweep() {
        XCTAssertTrue(LayersFileNames.deleteStaleDownloadParts(in: dir.appendingPathComponent("absent")).isEmpty)
    }

    func testSafeBaseName() {
        XCTAssertEqual(LayersFileNames.safeBaseName("My survey/2024"), "My_survey_2024")
        XCTAssertEqual(LayersFileNames.safeBaseName("..", fallback: "x"), "x")
        XCTAssertEqual(LayersFileNames.safeBaseName("", fallback: "x"), "x")
    }
}

final class RedactUrlTests: XCTestCase {
    func testQueryStringsAreDropped() {
        XCTAssertEqual(layersRedactUrl("https://tiles.example.com/3/2/1.png?key=SECRET&style=x"), "https://tiles.example.com/3/2/1.png?…")
    }

    func testUserInfoIsDropped() {
        XCTAssertEqual(layersRedactUrl("http://user:pass@host.example/a/1.png"), "http://host.example/a/1.png")
    }

    func testPlainUrlsAreUnchanged() {
        XCTAssertEqual(layersRedactUrl("https://tile.openstreetmap.org/1/2/3.png"), "https://tile.openstreetmap.org/1/2/3.png")
    }
}
