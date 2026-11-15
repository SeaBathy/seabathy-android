import XCTest
@testable import SeaBathy

/// Android ReliefShadingTest plus palette and renderer checks.
final class DepthRenderingTests: XCTestCase {

    private func grid(_ cols: Int, _ rows: Int, _ depth: (Int, Int) -> Float) -> [Float] {
        (0..<(cols * rows)).map { depth($0 % cols, $0 / cols) }
    }

    func testFlatSeabedIsEvenlyLit() throws {
        let shade = try XCTUnwrap(ReliefShading.shade(values: grid(5, 5) { _, _ in -20 }, cols: 5, rows: 5,
                                                      cellX: 10, cellY: 10, strength: 0.6))
        XCTAssertTrue(shade.allSatisfy { abs($0 - shade[0]) < 1e-6 })
    }

    func testSlopesFacingTheLightAreBrighter() throws {
        // A north-south ridge: its west face looks towards the light, its east face away.
        let values = grid(9, 5) { c, _ in -20 + 6 - Float(abs(c - 4)) * 1.5 }
        let shade = try XCTUnwrap(ReliefShading.shade(values: values, cols: 9, rows: 5, cellX: 10, cellY: 10, strength: 0.6))
        XCTAssertGreaterThan(shade[2 * 9 + 2], shade[2 * 9 + 6])
    }

    func testStrongerSettingGivesMoreContrast() throws {
        let values = grid(9, 5) { c, _ in -20 - Float(c) * 0.5 }
        let weak = try XCTUnwrap(ReliefShading.shade(values: values, cols: 9, rows: 5, cellX: 10, cellY: 10, strength: 0.2))[2 * 9 + 4]
        let strong = try XCTUnwrap(ReliefShading.shade(values: values, cols: 9, rows: 5, cellX: 10, cellY: 10, strength: 2))[2 * 9 + 4]
        let flat = try XCTUnwrap(ReliefShading.shade(values: grid(9, 5) { _, _ in -20 }, cols: 9, rows: 5,
                                                     cellX: 10, cellY: 10, strength: 2))[0]
        XCTAssertGreaterThan(abs(strong - flat), abs(weak - flat))
    }

    func testNoDataStaysUnshadedAndZeroStrengthTurnsShadingOff() throws {
        let values = grid(4, 4) { c, r in (c == 1 && r == 1) ? .nan : -10 - Float(c) }
        let shade = try XCTUnwrap(ReliefShading.shade(values: values, cols: 4, rows: 4, cellX: 10, cellY: 10, strength: 1))
        XCTAssertEqual(shade[5], 1)
        XCTAssertNil(ReliefShading.shade(values: values, cols: 4, rows: 4, cellX: 10, cellY: 10, strength: 0))
    }

    func testPaletteEndsAndLookup() {
        let rainbow = DepthPalette.byId("rainbow")
        XCTAssertEqual(rainbow.colorAt(0), 0xFFB3002D)
        XCTAssertEqual(rainbow.colorAt(1), 0xFF1B2A6B)
        XCTAssertEqual(rainbow.colorAt(-3), 0xFFB3002D)
        XCTAssertEqual(DepthPalette.byId("nope").id, "rainbow")
        XCTAssertEqual(DepthPalette.all.map(\.id), ["rainbow", "ocean", "chart", "viridis", "inferno", "reef", "grayscale"])
        let table = DepthPalette.byId("grayscale").lookupTable(size: 512)
        XCTAssertEqual(table.count, 512)
        XCTAssertEqual(table.first, 0xFFF5F5F5)
        XCTAssertEqual(table.last, 0xFF000000)
        // Halfway between two stops: channel truncation as on Android.
        XCTAssertEqual(DepthPalette(id: "t", label: "t", stops: [0xFF000000, 0xFFFFFFFF]).colorAt(0.5), 0xFF7F7F7F)
    }

    func testRendererLeavesNoDataAndOutOfRangeClear() throws {
        let g = DepthGrid(bounds: GeoBounds(west: 147, south: -19, east: 148, north: -18), cols: 2, rows: 2,
                          values: [-10, -20, -500, .nan], shallowestMetres: -10, deepestMetres: -500, coveredCells: 3)
        var ramp = DepthRampSettings(paletteId: "grayscale", shallowMetres: -10, deepMetres: -30)
        ramp.hillshadeExaggeration = 0
        let px = DepthImageRenderer.rgba(for: g, ramp: ramp)
        XCTAssertEqual(px[3], 255)                 // -10: shallow end
        XCTAssertEqual(Array(px[0..<3]), [0xF5, 0xF5, 0xF5])
        XCTAssertEqual(px[7], 255)                 // -20: inside
        XCTAssertEqual(px[11], 0)                  // -500: outside the range
        XCTAssertEqual(px[15], 0)                  // no data
        ramp.showOutside = true
        let all = DepthImageRenderer.rgba(for: g, ramp: ramp)
        XCTAssertEqual(all[11], 255)
        XCTAssertEqual(Array(all[8..<11]), [0, 0, 0])
        let image = try XCTUnwrap(DepthImageRenderer.image(for: g, ramp: ramp))
        XCTAssertEqual(image.size, CGSize(width: 2, height: 2))
        XCTAssertNotNil(DepthImageRenderer.legendImage(ramp: ramp))
    }
}
