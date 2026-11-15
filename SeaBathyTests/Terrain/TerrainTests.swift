import XCTest
@testable import SeaBathy

/// A depth source with no grid of its own, for the point-by-point path.
private final class TerrainTestSource: DepthSource {
    let bounds: GeoBounds
    let shallowestMetres: Double
    let deepestMetres: Double
    let hasData = true
    private let sample: (Double, Double) -> Double?

    init(bounds: GeoBounds, shallowest: Double, deepest: Double, sample: @escaping (Double, Double) -> Double?) {
        self.bounds = bounds
        self.shallowestMetres = shallowest
        self.deepestMetres = deepest
        self.sample = sample
    }

    func sampleMetres(lat: Double, lng: Double) -> Double? { sample(lat, lng) }
}

final class TerrainGridTests: XCTestCase {
    // 0.1° square off Sydney, depth getting deeper eastwards from -10 to -110 m.
    private let bounds = GeoBounds(west: 151.3, south: -33.95, east: 151.4, north: -33.85)

    private func rampCells(cols: Int = 100, rows: Int = 100) -> TerrainCells {
        let values = (0..<(cols * rows)).map { i in -10 - Float(i % cols) }
        return TerrainCells(bounds: bounds, cols: cols, rows: rows, values: values)
    }

    /// The source behind in-memory cells: nearest cell, as a grid's own sampling would do.
    private func source(for cells: TerrainCells) -> TerrainTestSource {
        let b = cells.bounds
        return TerrainTestSource(bounds: b, shallowest: -10, deepest: -109) { lat, lng in
            guard b.contains(lat: lat, lng: lng) else { return nil }
            let c = min(cells.cols - 1, Int((lng - b.west) / (b.east - b.west) * Double(cells.cols)))
            let r = min(cells.rows - 1, Int((b.north - lat) / (b.north - b.south) * Double(cells.rows)))
            let v = cells.values[r * cells.cols + c]
            return v.isNaN ? nil : Double(v)
        }
    }

    private func sampleRamp(maxDim: Int, range: ClosedRange<Double>? = nil, cells: TerrainCells? = nil) -> TerrainGrid? {
        let g = cells ?? rampCells()
        return TerrainGridSampler.sample(source: source(for: g), cells: g, bounds: bounds, maxDim: maxDim, range: range)
    }

    func testCellsAreRoughlySquareInMetres() throws {
        let grid = try XCTUnwrap(sampleRamp(maxDim: 64))
        // 0.1° of longitude at 34°S is ~9.2 km, 0.1° of latitude ~11.1 km.
        XCTAssertGreaterThan(grid.rows, grid.cols)
        XCTAssertEqual(grid.cellXMetres, grid.cellYMetres, accuracy: grid.cellYMetres * 0.05)
        XCTAssertEqual(grid.cellYMetres * Double(grid.rows), 11_057, accuracy: 50)
    }

    func testDepthsOutsideTheRangeBecomeNoDataAndSetTheMinAndMax() throws {
        let grid = try XCTUnwrap(sampleRamp(maxDim: 64, range: -60.0 ... -30.0))
        XCTAssertTrue(grid.values.contains { $0.isNaN })
        XCTAssertTrue(grid.values.filter { !$0.isNaN }.allSatisfy { $0 >= -60 && $0 <= -30 })
        XCTAssertTrue(grid.minMetres >= -60 && grid.maxMetres <= -30)
    }

    func testAnInMemorySourceIsNotSampledMuchFinerThanItsOwnData() throws {
        let coarse = rampCells(cols: 20, rows: 20)
        let grid = try XCTUnwrap(sampleRamp(maxDim: 1024, cells: coarse))
        XCTAssertLessThanOrEqual(grid.cols, 40)
    }

    func testBilinearSamplingInterpolatesBetweenSourceCells() throws {
        let two = TerrainCells(bounds: bounds, cols: 2, rows: 1, values: [-10, -20])
        let grid = try XCTUnwrap(sampleRamp(maxDim: 4, cells: two))
        let row = grid.values[0..<grid.cols]
        // Strictly between the two source values somewhere in the middle, not a hard step.
        XCTAssertTrue(row.contains { $0 < -10 && $0 > -20 })
    }

    func testIsolateBoundsCoverTheInRangeCellsAndStayInsideTheGrid() throws {
        let grid = try XCTUnwrap(sampleRamp(maxDim: 100))
        let area = try XCTUnwrap(TerrainGridSampler.boundsWithin(grid, range: -40.0 ... -20.0))
        XCTAssertTrue(area.west >= bounds.west && area.east <= bounds.east)
        XCTAssertLessThan(area.east - area.west, (bounds.east - bounds.west) * 0.5)
        XCTAssertEqual(area.north, bounds.north, accuracy: 1e-9)
        XCTAssertEqual(area.south, bounds.south, accuracy: 1e-9)
    }

    func testNothingInRangeGivesNil() throws {
        let grid = try XCTUnwrap(sampleRamp(maxDim: 32))
        XCTAssertNil(TerrainGridSampler.boundsWithin(grid, range: -500.0 ... -400.0))
        XCTAssertNil(sampleRamp(maxDim: 32, range: -500.0 ... -400.0))
    }

    func testOtherSourcesAreSampledThroughSampleMetres() throws {
        let flat = TerrainTestSource(bounds: bounds, shallowest: -5, deepest: -5) { _, _ in -5 }
        let grid = try XCTUnwrap(TerrainGridSampler.sample(source: flat, bounds: bounds, maxDim: 16))
        XCTAssertTrue(grid.values.allSatisfy { $0 == -5 })
    }
}

final class TerrainBudgetTests: XCTestCase {
    func testMorePhoneMemoryHoldsMore() {
        let sizes = [2.0, 3.6, 5.5, 7.3, 11.2].map { TerrainBudget.cells(totalGib: $0, lowMemory: false, safety: 0) }
        XCTAssertEqual(sizes.sorted(), sizes)
        XCTAssertEqual(Set(sizes).count, sizes.count)
    }

    func testAn8GbPhoneGetsThe8GbSizeEvenThoughItReportsLess() {
        XCTAssertEqual(TerrainBudget.cells(totalGib: 7.2, lowMemory: false, safety: 0), 1_500_000)
    }

    func testABigPhoneReachesMaximumOnAWideSurvey() {
        let most = TerrainBudget.cells(totalGib: 11.2, lowMemory: false, safety: 0)
        XCTAssertGreaterThanOrEqual(TerrainBudget.longestSide(cells: most, aspect: 1.67), 2048)
    }

    func testASmallPhoneStaysLight() {
        XCTAssertEqual(TerrainBudget.longestSide(cells: TerrainBudget.cells(totalGib: 2.0, lowMemory: false, safety: 0), aspect: 1.67), 707)
    }

    func testGraphicsTroubleAndLowMemoryEachHoldLess() {
        let full = TerrainBudget.cells(totalGib: 11.2, lowMemory: false, safety: 0)
        XCTAssertEqual(TerrainBudget.cells(totalGib: 11.2, lowMemory: false, safety: 1), full)
        XCTAssertEqual(TerrainBudget.cells(totalGib: 11.2, lowMemory: false, safety: 2), full / 2)
        XCTAssertEqual(TerrainBudget.cells(totalGib: 11.2, lowMemory: false, safety: 3), full / 4)
        XCTAssertEqual(TerrainBudget.cells(totalGib: 11.2, lowMemory: true, safety: 0), full / 2)
    }
}

final class TerrainDrapeTests: XCTestCase {
    func testViewOnlyProvidersAreNotOfferedForOfflineSaving() {
        XCTAssertFalse(TerrainDrape.allowsOfflineCopies("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"))
        XCTAssertFalse(TerrainDrape.allowsOfflineCopies("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        XCTAssertFalse(TerrainDrape.allowsOfflineCopies("https://a.tile.opentopomap.org/{z}/{x}/{y}.png"))
    }

    func testProvidersThatAllowOfflineCopiesAreOffered() {
        XCTAssertTrue(TerrainDrape.allowsOfflineCopies("https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg"))
        XCTAssertTrue(TerrainDrape.allowsOfflineCopies("https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"))
    }

    func testAnEsriDrapeIsNotSavableButASentinel2OneIs() {
        XCTAssertFalse(TerrainDrape(id: "a", label: "Esri", minZoom: 0, maxZoom: 19, urlTemplate: "https://server.arcgisonline.com/x/{z}/{y}/{x}").isSavable)
        XCTAssertTrue(TerrainDrape(id: "b", label: "S2", minZoom: 0, maxZoom: 14, urlTemplate: "https://tiles.maps.eox.at/x/{z}/{y}/{x}.jpg").isSavable)
        XCTAssertFalse(TerrainDrape(id: "c", label: "Pack", minZoom: 0, maxZoom: 14).isSavable)
    }
}

final class TerrainHelpersTests: XCTestCase {
    func testMapLinksAreCheckedWithTheLayerDialogWording() {
        XCTAssertEqual(TerrainLinkCheck.validationError(" "), "Enter an XYZ tile URL")
        XCTAssertEqual(TerrainLinkCheck.validationError("ftp://x/{z}/{x}/{y}"), "URL must start with http:// or https://")
        XCTAssertEqual(TerrainLinkCheck.validationError("https://x/{z}/{x}"), "URL must contain {z}, {x} and {y}")
        XCTAssertNil(TerrainLinkCheck.validationError("https://tiles.example.com/{z}/{x}/{y}.png"))
        XCTAssertEqual(TerrainLinkCheck.host("https://tiles.example.com/{z}/{x}/{y}.png"), "tiles.example.com")
    }

    func testThePageGetsTheChartColoursDeepToShallow() {
        let ramp = DepthRampSettings(paletteId: "reef", shallowMetres: -2, deepMetres: -40)
        let chart = TerrainChartPalette.pageChart(ramp)
        XCTAssertEqual(chart["stops"] as? [String], ["#073B4C", "#118AB2", "#06D6A0", "#FFD166", "#FFFFFF"])
        XCTAssertEqual(chart["label"] as? String, "Reef contrast")
        XCTAssertEqual(chart["hide"] as? Bool, true)
        let inverted = TerrainChartPalette.pageChart(DepthRampSettings(paletteId: "reef", invert: true, showOutside: true))
        XCTAssertEqual((inverted["stops"] as? [String])?.first, "#FFFFFF")
        XCTAssertEqual(inverted["hide"] as? Bool, false)
    }

    func testAnUnknownPaletteFallsBackToRainbow() {
        XCTAssertEqual(TerrainChartPalette.byId("nope").id, "rainbow")
    }

    func testCustomDrapesDecodeWithMissingFields() throws {
        let data = Data(#"[{"id":"custom-1","url":"https://a/{z}/{x}/{y}"}]"#.utf8)
        let list = try JSONDecoder().decode([TerrainCustomDrape].self, from: data)
        XCTAssertEqual(list, [TerrainCustomDrape(id: "custom-1", name: "", url: "https://a/{z}/{x}/{y}")])
    }

    func testImageTypesAreSniffed() {
        XCTAssertEqual(TerrainWebHost.imageMime(Data([0xFF, 0xD8, 0xFF, 0xE0])), "image/jpeg")
        XCTAssertEqual(TerrainWebHost.imageMime(Data([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50])), "image/webp")
        XCTAssertEqual(TerrainWebHost.imageMime(Data([0x89, 0x50, 0x4E, 0x47])), "image/png")
    }
}
