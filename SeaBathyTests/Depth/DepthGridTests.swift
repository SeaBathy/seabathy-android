import XCTest
@testable import SeaBathy

/// Android DepthGridTest, InMemoryDepthGridTest, DepthHistogramTest and DepthRampColourTest.
final class DepthGridTests: XCTestCase {

    /// A two-by-two grid spanning one degree each way:  -10 -20 / -30 no-data.
    private let grid = DepthGrid(bounds: GeoBounds(west: 147, south: -19, east: 148, north: -18), cols: 2, rows: 2,
                                 values: [-10, -20, -30, DepthGrid.noData], shallowestMetres: -10, deepestMetres: -30,
                                 coveredCells: 3)

    func testEachQuadrantReadsItsOwnCell() {
        XCTAssertEqual(grid.sampleMetres(lat: -18.25, lng: 147.25)!, -10, accuracy: 1e-6)
        XCTAssertEqual(grid.sampleMetres(lat: -18.25, lng: 147.75)!, -20, accuracy: 1e-6)
        XCTAssertEqual(grid.sampleMetres(lat: -18.75, lng: 147.25)!, -30, accuracy: 1e-6)
    }

    func testNoDataAndOutsideReadAsUnknown() {
        XCTAssertNil(grid.sampleMetres(lat: -18.75, lng: 147.75))
        XCTAssertNil(grid.sampleMetres(lat: -17, lng: 147.5))
        XCTAssertNil(grid.sampleMetres(lat: -18.5, lng: 150))
    }

    func testFarEdgesClampInsideTheGrid() {
        XCTAssertEqual(grid.sampleMetres(lat: -19, lng: 147)!, -30, accuracy: 1e-6)
        XCTAssertEqual(grid.sampleMetres(lat: -18, lng: 147)!, -10, accuracy: 1e-6)
    }

    func testCoverageAndSourceMembers() {
        XCTAssertTrue(grid.hasData)
        XCTAssertTrue(grid.previewGrid() === grid)
        let empty = DepthGrid(bounds: grid.bounds, cols: 1, rows: 1, values: [DepthGrid.noData],
                              shallowestMetres: .nan, deepestMetres: .nan, coveredCells: 0)
        XCTAssertFalse(empty.hasData)
        XCTAssertNil(empty.histogram())
    }

    // Histogram -----------------------------------------------------------------

    /// A shelf from -1 to -70 m, plus one deep hole at -890 m and an island at +154 m.
    private func shelfWithOutliers() -> [Float] {
        var values = (0..<10_000).map { i in -1 - 69 * Float(i) / 9_999 }
        values.append(contentsOf: [-890, 154, .nan])
        return values
    }

    func testPercentilesAndSharesFollowTheBulk() throws {
        let h = try XCTUnwrap(DepthHistogram.of(shelfWithOutliers()))
        XCTAssertEqual(h.lowest, -890, accuracy: 1e-6)
        XCTAssertEqual(h.highest, 154, accuracy: 1e-6)
        XCTAssertEqual(h.percentile(0.5), -35.5, accuracy: 1.5)
        XCTAssertEqual(h.fractionWithin(-1, -35.5), 0.5, accuracy: 0.03)
        XCTAssertEqual(h.fractionWithin(200, -1000), 1, accuracy: 1e-9)
        XCTAssertEqual(h.fractionWithin(-100, -800), 0, accuracy: 0.01)
    }

    func testFitIgnoresALoneDeepHoleAndIsland() throws {
        let h = try XCTUnwrap(DepthHistogram.of(shelfWithOutliers()))
        let fit = DepthRampFit.fitRange(shallowestMetres: 154, deepestMetres: -890, histogram: h)
        XCTAssertTrue((-2.0...0.0).contains(fit.shallow), "shallow \(fit.shallow)")
        XCTAssertTrue((-71.0...(-67.0)).contains(fit.deep), "deep \(fit.deep)")
        let plain = DepthRampFit.fitRange(shallowestMetres: 154, deepestMetres: -890)
        XCTAssertEqual(plain.shallow, 0)
        XCTAssertEqual(plain.deep, -890)
    }

    func testNoDataGivesNoHistogram() {
        XCTAssertNil(DepthHistogram.of([.nan, .nan]))
        XCTAssertNil(DepthHistogram.of([-5, -5]))
    }

    // Ramp colour ----------------------------------------------------------------

    private let inshore = DepthRampSettings(shallowMetres: -10, deepMetres: -30)
    private var inshoreAll: DepthRampSettings { var r = inshore; r.showOutside = true; return r }

    func testDepthsInsideTheRangeSpreadAlongTheRamp() {
        XCTAssertEqual(DepthImageRenderer.rampPosition(-10, ramp: inshore)!, 0, accuracy: 1e-9)
        XCTAssertEqual(DepthImageRenderer.rampPosition(-20, ramp: inshore)!, 0.5, accuracy: 1e-9)
        XCTAssertEqual(DepthImageRenderer.rampPosition(-30, ramp: inshore)!, 1, accuracy: 1e-9)
    }

    func testDepthsOutsideTheRangeAreHiddenByDefault() {
        XCTAssertNil(DepthImageRenderer.rampPosition(-2, ramp: inshore))
        XCTAssertNil(DepthImageRenderer.rampPosition(-800, ramp: inshore))
        XCTAssertNil(DepthImageRenderer.rampPosition(12, ramp: inshore))
    }

    func testOrTakeTheEndColoursWithLandLeftClear() {
        XCTAssertEqual(DepthImageRenderer.rampPosition(-2, ramp: inshoreAll)!, 0, accuracy: 1e-9)
        XCTAssertEqual(DepthImageRenderer.rampPosition(-800, ramp: inshoreAll)!, 1, accuracy: 1e-9)
        XCTAssertNil(DepthImageRenderer.rampPosition(12, ramp: inshoreAll))
        let withLand = DepthRampSettings(shallowMetres: 20, deepMetres: -30, showOutside: true)
        XCTAssertEqual(DepthImageRenderer.rampPosition(12, ramp: withLand)!, 0.16, accuracy: 1e-9)
    }

    func testFittingASurveyWithLandCoversJustTheWater() {
        var fit = DepthRampFit.fitRange(shallowestMetres: 154, deepestMetres: -889.5)
        XCTAssertEqual(fit.shallow, 0); XCTAssertEqual(fit.deep, -890)
        fit = DepthRampFit.fitRange(shallowestMetres: -3.2, deepestMetres: -40.1)
        XCTAssertEqual(fit.shallow, -3); XCTAssertEqual(fit.deep, -41)
        // A land-only model keeps its own range.
        fit = DepthRampFit.fitRange(shallowestMetres: 119.4, deepestMetres: 4.6)
        XCTAssertEqual(fit.shallow, 120); XCTAssertEqual(fit.deep, 4)
    }

    func testAutoFitUsesTheDataRangeOrTheDefault() {
        let fitted = DepthRampFit.autoFit(grid)
        XCTAssertEqual(fitted.shallowMetres, -10)
        XCTAssertEqual(fitted.deepMetres, -30)
        XCTAssertEqual(fitted.paletteId, DepthRampSettings.defaultPaletteId)
        // A flat layer has no usable span: the default ramp.
        XCTAssertEqual(DepthRampFit.autoFit(shallowestMetres: -5, deepestMetres: -5), DepthRampSettings())
    }

    func testCoverageWording() throws {
        let h = try XCTUnwrap(DepthHistogram.of(shelfWithOutliers()))
        let missing = DepthRampFormat.coverage(h, ramp: DepthRampSettings(shallowMetres: -100, deepMetres: -800))
        XCTAssertTrue(missing.warning)
        XCTAssertTrue(missing.text.hasPrefix("Only "))
        let most = DepthRampFormat.coverage(h, ramp: DepthRampSettings(shallowMetres: 0, deepMetres: -80))
        XCTAssertFalse(most.warning)
        XCTAssertTrue(most.text.hasPrefix("Shows "))
        XCTAssertEqual(DepthRampFormat.depth(-12.5), "-12.5 m")
        XCTAssertEqual(DepthRampFormat.depth(3), "+3.0 m")
        XCTAssertEqual(DepthRampFormat.depth(0), "0.0 m")
        XCTAssertEqual(DepthRampFormat.plain(-15), "-15")
        XCTAssertEqual(DepthRampFormat.plain(-2.5), "-2.5")
        XCTAssertEqual(DepthRampFormat.parse("-7,5"), -7.5)
    }
}
