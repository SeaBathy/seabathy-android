import XCTest
@testable import SeaBathy

/// End-to-end cover for the GeoTIFF depth path (Android GeoTiffReaderTest and
/// PredictorDecodeTest): georeferencing, resampling, no-data, sign convention,
/// strips, tiles, byte orders, BigTIFF, every supported compression and predictor.
final class GeoTiffReaderTests: XCTestCase {

    private func read(_ o: DepthTestTiff.Options, maxDimension: Int = 1600,
                      _ sample: (Int, Int) -> Double) throws -> GeoTiffReadResult {
        try GeoTiffImport.read(data: DepthTestTiff.make(o, sample: sample), maxDimension: maxDimension)
    }

    private func grid(_ r: GeoTiffReadResult) -> DepthGrid { r.source.previewGrid() }

    /// A 4 x 4 raster covering 147..148 E and 18..19 S in EPSG:4326.
    private var reef: DepthTestTiff.Options { DepthTestTiff.Options() }

    func testGeographicRasterIsPlacedAndSampledCorrectly() throws {
        let r = try read(reef) { _, row in -10.0 * Double(row + 1) }
        XCTAssertEqual(r.projectionLabel, "WGS84 geographic")
        XCTAssertEqual(r.bounds.west, 147.0, accuracy: 1e-9)
        XCTAssertEqual(r.bounds.east, 148.0, accuracy: 1e-9)
        XCTAssertEqual(r.bounds.south, -19.0, accuracy: 1e-9)
        XCTAssertEqual(r.bounds.north, -18.0, accuracy: 1e-9)
        let g = grid(r)
        XCTAssertEqual(g.cols, 4)
        XCTAssertEqual(g.rows, 4)
        XCTAssertEqual(g.coveredCells, 16)
        XCTAssertEqual(g.shallowestMetres, -10, accuracy: 1e-6)
        XCTAssertEqual(g.deepestMetres, -40, accuracy: 1e-6)
        XCTAssertFalse(r.positiveDownConverted)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.125, lng: 147.5)!, -10, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.375, lng: 147.5)!, -20, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.625, lng: 147.5)!, -30, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.875, lng: 147.5)!, -40, accuracy: 1e-6)
    }

    func testPositiveDownSoundingsAreConverted() throws {
        let r = try read(reef) { _, row in 10.0 * Double(row + 1) }
        XCTAssertTrue(r.positiveDownConverted)
        XCTAssertEqual(r.source.shallowestMetres, -10, accuracy: 1e-6)
        XCTAssertEqual(r.source.deepestMetres, -40, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.125, lng: 147.5)!, -10, accuracy: 1e-6)
    }

    func testMixedElevationKeepsItsSigns() throws {
        let r = try read(reef) { _, row in row < 2 ? 5 : -25 }
        XCTAssertFalse(r.positiveDownConverted)
        XCTAssertEqual(r.source.shallowestMetres, 5, accuracy: 1e-6)
        XCTAssertEqual(r.source.deepestMetres, -25, accuracy: 1e-6)
    }

    func testDeclaredNoDataIsExcluded() throws {
        var o = reef
        o.noData = "-9999"
        let r = try read(o) { col, _ in col == 0 ? -9999 : -15 }
        XCTAssertEqual(grid(r).coveredCells, 12)
        XCTAssertEqual(r.source.shallowestMetres, -15, accuracy: 1e-6)
        XCTAssertEqual(r.source.deepestMetres, -15, accuracy: 1e-6)
        XCTAssertNil(r.source.sampleMetres(lat: -18.5, lng: 147.1))
        XCTAssertEqual(r.source.sampleMetres(lat: -18.5, lng: 147.6)!, -15, accuracy: 1e-6)
    }

    func testFloatSentinelsAreNoDataEvenWhenUndeclared() {
        XCTAssertThrowsError(try read(reef) { col, _ in col == 0 ? Double.nan : -3.4e38 }) { error in
            XCTAssertTrue(error is GeoTiffImportError)
        }
    }

    func testWebMercatorRasterIsReprojected() throws {
        var o = reef
        o.width = 8; o.height = 8
        o.originX = 16364206; o.originY = -2035006
        o.pixelSizeX = 13915; o.pixelSizeY = 14700
        o.geoKeys = DepthTestTiff.projectedKeys(3857)
        let r = try read(o) { _, row in -5.0 * Double(row + 1) }
        XCTAssertEqual(r.projectionLabel, "Web Mercator")
        XCTAssertTrue(r.bounds.isValid)
        XCTAssertEqual(r.bounds.west, 147, accuracy: 0.05)
        XCTAssertEqual(r.bounds.east, 148, accuracy: 0.05)
        XCTAssertEqual(r.bounds.north, -18, accuracy: 0.05)
        XCTAssertEqual(r.bounds.south, -19, accuracy: 0.05)
        XCTAssertTrue(r.source.hasData)
    }

    func testUtmRasterIsReprojected() throws {
        var o = reef
        o.width = 8; o.height = 8
        o.originX = 564000; o.originY = 7977000
        o.pixelSizeX = 2500; o.pixelSizeY = 2500
        o.geoKeys = DepthTestTiff.projectedKeys(32755)
        let r = try read(o) { _, row in -8.0 * Double(row + 1) }
        XCTAssertEqual(r.projectionLabel, "UTM zone 55S")
        XCTAssertTrue(r.bounds.isValid)
        XCTAssertEqual(r.bounds.west, 147.6, accuracy: 0.2)
        XCTAssertEqual(r.bounds.north, -18.3, accuracy: 0.2)
        XCTAssertTrue(r.source.hasData)
    }

    func testUnsupportedProjectedCrsIsRejectedWithItsCode() {
        var o = reef
        o.originX = 400000; o.originY = 8000000; o.pixelSizeX = 1000; o.pixelSizeY = 1000
        o.geoKeys = DepthTestTiff.projectedKeys(3577)
        XCTAssertThrowsError(try read(o) { _, _ in -12 }) { error in
            let e = error as? GeoTiffImportError
            XCTAssertNotNil(e)
            XCTAssertTrue(e?.message.contains("3577") ?? false)
        }
    }

    func testNoGeoKeysWithDegreeLikeCornersIsTakenAsGeographic() throws {
        var o = reef
        o.originX = 0; o.originY = 0; o.pixelSizeX = 1; o.pixelSizeY = 1
        o.geoKeys = nil
        let r = try read(o) { _, _ in -12 }
        XCTAssertEqual(r.projectionLabel, "WGS84 geographic")
    }

    func testOversizedRastersAreDownsampledAndSampledAtFullPrecision() throws {
        var o = reef
        o.width = 200; o.height = 100; o.pixelSizeX = 0.005; o.pixelSizeY = 0.005
        let r = try read(o, maxDimension: 50) { col, _ in -1.0 * Double(col + 1) }
        let g = grid(r)
        XCTAssertEqual(g.cols, 50)
        XCTAssertEqual(g.rows, 25)
        XCTAssertTrue(g.coveredCells > 0)
        XCTAssertEqual(r.sourceWidth, 200)
        XCTAssertEqual(r.sourceHeight, 100)
        XCTAssertTrue(g.sampleMetres(lat: -18.1, lng: 147.1)! > g.sampleMetres(lat: -18.1, lng: 147.9)!)
        // The streamed source reads the file itself: column 20 (147.1025 E) is exactly -21.
        XCTAssertTrue(r.source is GeoTiffStreamedDepth)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.1, lng: 147.1025)!, -21, accuracy: 1e-6)
        XCTAssertNotNil(r.source.histogram())
    }

    func testEmptyDataIsRejected() {
        XCTAssertThrowsError(try GeoTiffImport.read(data: Data(), maxDimension: 100))
    }

    func testNotATiffIsRejected() {
        XCTAssertThrowsError(try GeoTiffImport.read(data: Data("hello, this is not a tiff".utf8), maxDimension: 100)) { error in
            XCTAssertEqual((error as? GeoTiffImportError)?.kind, .unreadable)
        }
    }

    func testManyStripsReadEndToEnd() throws {
        var o = reef
        o.width = 20; o.height = 40; o.pixelSizeX = 0.01; o.pixelSizeY = 0.01; o.rowsPerStrip = 4
        let r = try read(o) { _, row in -1.0 * Double(row + 1) }
        XCTAssertEqual(grid(r).coveredCells, 800)
        XCTAssertEqual(r.source.shallowestMetres, -1, accuracy: 1e-6)
        XCTAssertEqual(r.source.deepestMetres, -40, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.005, lng: 147.005)!, -1, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.395, lng: 147.005)!, -40, accuracy: 1e-6)
    }

    /// Every compression, in strips (with a short last strip) and in padded tiles.
    func testEveryCompressionDecodes() throws {
        for compression in [1, 5, 8, 32946, 32773] {
            for tiled in [false, true] {
                var o = reef
                o.width = 10; o.height = 10; o.pixelSizeX = 0.01; o.pixelSizeY = 0.01
                o.compression = compression
                if tiled { o.tileWidth = 4; o.tileHeight = 4 } else { o.rowsPerStrip = 3 }
                let r = try read(o) { col, row in -1.0 * Double(row * 10 + col + 1) }
                let label = "compression \(compression) tiled \(tiled)"
                XCTAssertEqual(grid(r).coveredCells, 100, label)
                XCTAssertEqual(r.source.shallowestMetres, -1, accuracy: 1e-6, label)
                XCTAssertEqual(r.source.deepestMetres, -100, accuracy: 1e-6, label)
                XCTAssertEqual(r.source.sampleMetres(lat: -18.095, lng: 147.095)!, -100, accuracy: 1e-6, label)
                XCTAssertEqual(r.source.sampleMetres(lat: -18.045, lng: 147.065)!, -47, accuracy: 1e-6, label)
            }
        }
    }

    func testBigEndianTiledRaster() throws {
        var o = reef
        o.width = 10; o.height = 10; o.pixelSizeX = 0.01; o.pixelSizeY = 0.01
        o.tileWidth = 4; o.tileHeight = 4; o.littleEndian = false; o.compression = 8; o.predictor = 3
        let r = try read(o) { col, row in -1.0 * Double(row * 10 + col + 1) }
        XCTAssertEqual(r.source.shallowestMetres, -1, accuracy: 1e-6)
        XCTAssertEqual(r.source.deepestMetres, -100, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.005, lng: 147.005)!, -1, accuracy: 1e-6)
    }

    func testBigTiffReads() throws {
        for le in [true, false] {
            var o = reef
            o.bigTiff = true; o.littleEndian = le; o.width = 12; o.height = 9
            o.pixelSizeX = 0.01; o.pixelSizeY = 0.01; o.tileWidth = 8; o.tileHeight = 8; o.compression = 8
            let r = try read(o) { col, row in -0.5 * Double(row * 12 + col + 1) }
            XCTAssertEqual(grid(r).coveredCells, 108)
            XCTAssertEqual(r.source.sampleMetres(lat: -18.085, lng: 147.115)!, -54, accuracy: 1e-6)
        }
    }

    func testPredictor3Float32AcrossStripsWithFractionalValues() throws {
        let values: [Double] = [
            -12.34, -0.5, -100.125, -3.75, -45.0, -0.001, -9999.9, -1.0,
            -2.5, -8.125, -0.0625, -300.3, -17.75, -0.0, -6.6, -60.06,
            -1.1, -22.22, -333.3, -4.0, -55.5, -0.9, -700.7, -8.8,
        ]
        for compression in [1, 5, 8] {
            var o = reef
            o.width = 8; o.height = 3; o.pixelSizeX = 0.01; o.pixelSizeY = 0.01
            o.compression = compression; o.predictor = 3; o.rowsPerStrip = 2
            let r = try read(o) { col, row in values[row * 8 + col] }
            XCTAssertEqual(grid(r).coveredCells, 24)
            for row in 0..<3 {
                for col in 0..<8 {
                    let lat = -18.0 - (Double(row) + 0.5) * 0.01
                    let lng = 147.0 + (Double(col) + 0.5) * 0.01
                    XCTAssertEqual(r.source.sampleMetres(lat: lat, lng: lng)!, Double(Float(values[row * 8 + col])),
                                   accuracy: 1e-4, "compression \(compression) row \(row) col \(col)")
                }
            }
        }
    }

    func testPredictor3Float64() throws {
        var o = reef
        o.bitsPerSample = 64; o.compression = 8; o.predictor = 3
        let r = try read(o) { col, row in -1.25 * Double(row * 4 + col + 1) }
        XCTAssertEqual(r.source.deepestMetres, -20, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.125, lng: 147.375)!, -2.5, accuracy: 1e-6)
    }

    func testPredictor2UInt16FlipsPositiveDown() throws {
        var o = reef
        o.width = 6; o.height = 4; o.pixelSizeX = 0.01; o.pixelSizeY = 0.01
        o.bitsPerSample = 16; o.sampleFormat = 1; o.compression = 8; o.predictor = 2
        let r = try read(o) { col, row in Double(row * 6 + col + 1) }
        XCTAssertTrue(r.positiveDownConverted)
        XCTAssertEqual(grid(r).coveredCells, 24)
        XCTAssertEqual(r.source.shallowestMetres, -1, accuracy: 1e-6)
        XCTAssertEqual(r.source.deepestMetres, -24, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.005, lng: 147.005)!, -1, accuracy: 1e-6)
    }

    func testPredictor2SignedSamplesWithLzw() throws {
        for (bits, le) in [(16, true), (16, false), (32, true), (32, false)] {
            var o = reef
            o.bitsPerSample = bits; o.sampleFormat = 2; o.compression = 5; o.predictor = 2; o.littleEndian = le
            let r = try read(o) { col, row in col % 2 == 0 ? -Double(row * 4 + col + 1) : Double(col) }
            XCTAssertEqual(r.source.sampleMetres(lat: -18.875, lng: 147.125)!, -13, accuracy: 1e-6, "bits \(bits)")
            XCTAssertEqual(r.source.sampleMetres(lat: -18.875, lng: 147.875)!, 3, accuracy: 1e-6, "bits \(bits)")
        }
    }

    func testUInt8Samples() throws {
        var o = reef
        o.bitsPerSample = 8; o.sampleFormat = 1; o.compression = 32773
        let r = try read(o) { col, _ in Double(col * 10 + 5) }
        XCTAssertTrue(r.positiveDownConverted)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.5, lng: 147.875)!, -35, accuracy: 1e-6)
    }

    func testUnsupportedCompressionFailsWithAClearMessage() throws {
        var data = DepthTestTiff.make(reef) { _, _ in -1 }
        // Rewrite the Compression tag's value (tag 259) to 50000 (ZSTD).
        let bytes = [UInt8](data)
        let ifd = Int(UInt32(bytes[4]) | UInt32(bytes[5]) << 8 | UInt32(bytes[6]) << 16 | UInt32(bytes[7]) << 24)
        let count = Int(bytes[ifd]) | Int(bytes[ifd + 1]) << 8
        for i in 0..<count {
            let base = ifd + 2 + i * 12
            if Int(bytes[base]) | Int(bytes[base + 1]) << 8 == 259 {
                data[base + 8] = 0x50
                data[base + 9] = 0xC3
            }
        }
        XCTAssertThrowsError(try GeoTiffImport.read(data: data, maxDimension: 100)) { error in
            let e = error as? GeoTiffImportError
            XCTAssertEqual(e?.kind, .unsupported)
            XCTAssertTrue(e?.message.contains("ZSTD") ?? false)
        }
    }

    func testColourImagesAreReportedAsImages() throws {
        var o = reef
        o.samplesPerPixel = 3; o.bitsPerSample = 8; o.sampleFormat = 1; o.photometric = 2
        let url = try DepthTestTiff.file(o) { col, _, band in Double(band == 0 ? col * 60 : 10) }
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertThrowsError(try GeoTiffImport.read(url: url, maxDimension: 100)) { error in
            XCTAssertEqual((error as? GeoTiffImportError)?.kind, .imageNotDepth)
        }
        let image = try GeoTiffImport.readImage(url: url, maxDimension: 100)
        XCTAssertEqual(Int(image.image.size.width), 4)
        XCTAssertEqual(image.bounds.west, 147, accuracy: 1e-9)
    }

    func testReadingFromAFile() throws {
        var o = reef
        o.tileWidth = 16; o.tileHeight = 16; o.compression = 8; o.predictor = 3
        let url = try DepthTestTiff.file(o) { _, row in -10.0 * Double(row + 1) }
        defer { try? FileManager.default.removeItem(at: url) }
        let r = try GeoTiffImport.read(url: url, maxDimension: 1600)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.875, lng: 147.5)!, -40, accuracy: 1e-6)
    }

    func testUncompressedHugeStripIsReadRowByRow() throws {
        // One 3000 x 800 Float32 strip (9.6 MB) is over the row-mode threshold.
        var o = reef
        o.width = 3000; o.height = 800; o.pixelSizeX = 0.001; o.pixelSizeY = 0.001
        let r = try read(o, maxDimension: 300) { col, row in -Double(row) - Double(col) / 10000 }
        XCTAssertEqual(grid(r).cols, 300)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.0005, lng: 147.0005)!, 0, accuracy: 1e-6)
        XCTAssertEqual(r.source.sampleMetres(lat: -18.7995, lng: 147.0005)!, -799, accuracy: 1e-4)
    }
}
