import XCTest
@testable import SeaBathy

/// Android MapProjectionTest, GeoTiffImportRouterTest, PyramidGeometryTest,
/// DepthTileCodecTest, TiffBlockCacheCapacityTest and TiffIfdReaderBigTiffTest.
final class DepthEngineTests: XCTestCase {

    // Projections ---------------------------------------------------------------

    private func assertRoundTrip(_ p: DepthMapProjection, _ lat: Double, _ lng: Double, _ tolerance: Double) {
        let f = p.forward(lat: lat, lng: lng)
        guard let back = p.inverse(x: f.x, y: f.y) else { return XCTFail("\(p.label) could not invert") }
        XCTAssertEqual(back.lat, lat, accuracy: tolerance, p.label)
        XCTAssertEqual(back.lng, lng, accuracy: tolerance, p.label)
    }

    func testGeographicIsTheIdentity() {
        let f = DepthMapProjection.geographic.forward(lat: -18.3, lng: 147.7)
        XCTAssertEqual(f.x, 147.7); XCTAssertEqual(f.y, -18.3)
        XCTAssertNil(DepthMapProjection.geographic.inverse(x: 200, y: 0))
        XCTAssertNil(DepthMapProjection.geographic.inverse(x: 0, y: 100))
    }

    func testWebMercator() {
        let origin = DepthMapProjection.webMercator.forward(lat: 0, lng: 0)
        XCTAssertEqual(origin.x, 0, accuracy: 1e-9)
        XCTAssertEqual(origin.y, 0, accuracy: 1e-9)
        XCTAssertEqual(DepthMapProjection.webMercator.forward(lat: 0, lng: 180).x, Double.pi * DepthMapProjection.earthRadius, accuracy: 1e-3)
        assertRoundTrip(.webMercator, -18.3, 147.7, 1e-9)
        assertRoundTrip(.webMercator, 51.5, -0.12, 1e-9)
    }

    func testUtm() {
        XCTAssertEqual(DepthMapProjection.utm(zone: 13, northernHemisphere: true).forward(lat: 40, lng: -105).x, 500000, accuracy: 0.001)
        let south = DepthMapProjection.utm(zone: 55, northernHemisphere: false).forward(lat: -18.3, lng: 147.7)
        XCTAssertTrue((7_900_000.0...8_100_000.0).contains(south.y))
        XCTAssertTrue((100_000.0...900_000.0).contains(south.x))
        assertRoundTrip(.utm(zone: 55, northernHemisphere: false), -18.3, 147.7, 1e-8)
        assertRoundTrip(.utm(zone: 56, northernHemisphere: false), -33.86, 151.21, 1e-8)
        assertRoundTrip(.utm(zone: 30, northernHemisphere: true), 51.5, -0.12, 1e-8)
        assertRoundTrip(.utm(zone: 13, northernHemisphere: true), 40, -105, 1e-8)
    }

    func testCrsCodes() {
        XCTAssertEqual(DepthMapProjection.fromProjectedCode(3857), .webMercator)
        XCTAssertEqual(DepthMapProjection.fromProjectedCode(32655), .utm(zone: 55, northernHemisphere: true))
        XCTAssertEqual(DepthMapProjection.fromProjectedCode(32755), .utm(zone: 55, northernHemisphere: false))
        XCTAssertEqual(DepthMapProjection.fromProjectedCode(28356), .utm(zone: 56, northernHemisphere: false))
        XCTAssertEqual(DepthMapProjection.fromProjectedCode(7856), .utm(zone: 56, northernHemisphere: false))
        XCTAssertNil(DepthMapProjection.fromProjectedCode(3577))
        XCTAssertNil(DepthMapProjection.fromProjectedCode(0))
        XCTAssertNotNil(DepthMapProjection.fromGeographicCode(4326))
        XCTAssertNotNil(DepthMapProjection.fromGeographicCode(0))
        XCTAssertEqual(DepthMapProjection.fromGeographicCode(7844), .geographic)
        XCTAssertNil(DepthMapProjection.fromGeographicCode(4277))
        XCTAssertEqual(DepthMapProjection.utm(zone: 55, northernHemisphere: false).label, "UTM zone 55S")
    }

    // Router --------------------------------------------------------------------

    func testFormatDetection() {
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x49, 0x49, 0x2A, 0x00]), .classic)
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x4D, 0x4D, 0x00, 0x2A]), .classic)
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x49, 0x49, 0x2B, 0x00]), .bigTiff)
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x4D, 0x4D, 0x00, 0x2B]), .bigTiff)
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x50, 0x4B, 0x03, 0x04]), .unknown)
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x49, 0x49, 0x2C, 0x00]), .unknown)
        XCTAssertEqual(GeoTiffImportRouter.detectFormat([0x49, 0x49, 0x2A]), .unknown)
    }

    func testSizeEstimates() {
        XCTAssertEqual(GeoTiffImportRouter.estimateUncompressedBytes(width: 1000, height: 500, samplesPerPixel: 1, maxBitsPerSample: 8), 500_000)
        XCTAssertEqual(GeoTiffImportRouter.estimateUncompressedBytes(width: 100, height: 100, samplesPerPixel: 1, maxBitsPerSample: 1), 10_000)
        XCTAssertEqual(GeoTiffImportRouter.estimateUncompressedBytes(width: 10000, height: 8000, samplesPerPixel: 3, maxBitsPerSample: 32), 10000 * 8000 * 3 * 4)
        XCTAssertNil(GeoTiffImportRouter.estimateUncompressedBytes(width: 0, height: 100, samplesPerPixel: 1, maxBitsPerSample: 8))
        XCTAssertNil(GeoTiffImportRouter.estimateUncompressedBytes(width: Int64.max / 2, height: 4, samplesPerPixel: 4, maxBitsPerSample: 32))
    }

    func testRouting() {
        let t = GeoTiffImportRouter.gdalSizeThresholdBytes
        let cap = GeoTiffImportRouter.maxKotlinBlockBytes
        XCTAssertTrue(GeoTiffImportRouter.shouldUseGdal(format: .bigTiff, estimatedUncompressedBytes: 10))
        XCTAssertFalse(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: 1024))
        XCTAssertFalse(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: t))
        XCTAssertTrue(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: t + 1))
        XCTAssertTrue(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: nil))
        XCTAssertFalse(GeoTiffImportRouter.shouldUseGdal(format: .unknown, estimatedUncompressedBytes: nil))
        let image = GeoTiffImportRouter.estimateUncompressedBytes(width: 2100, height: 2100, samplesPerPixel: 1, maxBitsPerSample: 32)
        let strip = GeoTiffImportRouter.estimateBlockBytes(blockWidth: 2100, blockHeight: 2100, samplesPerBlockPixel: 1, maxBitsPerSample: 32)
        XCTAssertTrue(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: image, estimatedBlockBytes: strip))
        XCTAssertFalse(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: cap, estimatedBlockBytes: cap))
        XCTAssertTrue(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: cap, estimatedBlockBytes: cap + 1))
        XCTAssertEqual(GeoTiffImportRouter.estimateBlockBytes(blockWidth: 256, blockHeight: 256, samplesPerBlockPixel: 1, maxBitsPerSample: 32), 256 * 1024)
        XCTAssertTrue(GeoTiffImportRouter.shouldUseGdal(format: .classic, estimatedUncompressedBytes: 1000, estimatedBlockBytes: nil))
    }

    // Block cache -----------------------------------------------------------------

    func testBlockCacheCapacity() {
        let tile: Int64 = 256 * 256 * 4
        let budget = TiffBlockReader.cacheBudgetBytes
        XCTAssertEqual(TiffBlockReader.blockCacheCapacity(blocksPerRow: 32, blockBytes: tile), 33)
        XCTAssertEqual(TiffBlockReader.blockCacheCapacity(blocksPerRow: 1, blockBytes: 40_000), TiffBlockReader.minCacheBlocks)
        let wide = TiffBlockReader.blockCacheCapacity(blocksPerRow: 10_000, blockBytes: tile)
        XCTAssertEqual(wide, Int(budget / tile))
        XCTAssertEqual(TiffBlockReader.blockCacheCapacity(blocksPerRow: 1, blockBytes: GeoTiffImportRouter.maxKotlinBlockBytes), 4)
        XCTAssertEqual(TiffBlockReader.blockCacheCapacity(blocksPerRow: 1, blockBytes: budget * 2), 1)
    }

    // Pyramid -------------------------------------------------------------------

    func testPyramidGeometry() throws {
        let small = try XCTUnwrap(DepthPyramidGeometry(sourceWidth: 200, sourceHeight: 150))
        XCTAssertEqual(small.maxZoom, 0)
        XCTAssertEqual(small.levelWidth(0), 200)
        XCTAssertEqual(small.tilesAcross(0), 1)

        let big = try XCTUnwrap(DepthPyramidGeometry(sourceWidth: 15000, sourceHeight: 9000))
        XCTAssertEqual(big.levelWidth(big.maxZoom), 15000)
        XCTAssertEqual(big.levelHeight(big.maxZoom), 9000)

        let halves = try XCTUnwrap(DepthPyramidGeometry(sourceWidth: 16384, sourceHeight: 8192))
        for zoom in 1...halves.maxZoom {
            XCTAssertEqual(halves.levelWidth(zoom) / 2, halves.levelWidth(zoom - 1))
        }
        let z = halves.previewZoom(minDimension: 1600)
        XCTAssertTrue(max(halves.levelWidth(z), halves.levelHeight(z)) >= 1600)
        if z > 0 { XCTAssertTrue(max(halves.levelWidth(z - 1), halves.levelHeight(z - 1)) < 1600) }

        let g = try XCTUnwrap(DepthPyramidGeometry(sourceWidth: 1000, sourceHeight: 700))
        XCTAssertEqual(g.tilesAcross(g.maxZoom), 4)
        XCTAssertEqual(g.tilesDown(g.maxZoom), 3)
        XCTAssertEqual(g.tileFor(zoom: g.maxZoom, levelCol: 300, levelRow: 10),
                       DepthTileAddress(zoom: g.maxZoom, tileCol: 1, tileRow: 0, localCol: 44, localRow: 10))
        let edge = g.tilePixelSize(zoom: g.maxZoom, tileCol: 3, tileRow: 0)
        XCTAssertEqual(edge.width, 232)
        XCTAssertEqual(edge.height, 256)
        XCTAssertNil(DepthPyramidGeometry(sourceWidth: 0, sourceHeight: 10))
        let tiny = try XCTUnwrap(DepthPyramidGeometry(sourceWidth: 500, sourceHeight: 400))
        XCTAssertEqual(tiny.previewZoom(minDimension: 1600), tiny.maxZoom)
    }

    func testTileCodec() throws {
        let values: [Float] = [-1.5, -12.25, 0, -999.9, .nan]
        let decoded = try XCTUnwrap(DepthTileCodec.decode(DepthTileCodec.encode(values)))
        XCTAssertEqual(Array(decoded[0..<4]), Array(values[0..<4]))
        XCTAssertTrue(decoded[4].isNaN)
        XCTAssertEqual(DepthTileCodec.encode([Float](repeating: -1, count: 65536)).count, 65536 * 4)
        XCTAssertEqual(DepthTileCodec.decode(Data())?.count, 0)
        XCTAssertNil(DepthTileCodec.decode(Data(count: 6)))
    }

    func testPyramidStoreRoundTrip() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pyramid-\(UUID().uuidString).sbdp")
        defer { try? FileManager.default.removeItem(at: url) }
        let geometry = try XCTUnwrap(DepthPyramidGeometry(sourceWidth: 4, sourceHeight: 2, tileSize: 256))
        let bounds = GeoBounds(west: 147, south: -19, east: 148, north: -18)
        let store = try TiledDepthPyramidStore.create(url, geometry: geometry, bounds: bounds, shallowestMetres: -1,
                                                      deepestMetres: -8, projectionLabel: "WGS84 geographic")
        store.writeTile(zoom: 0, tileCol: 0, tileRow: 0, values: [-1, -2, -3, -4, -5, -6, -7, .nan])
        store.close()
        let pyramid = try TiledDepthPyramid.open(url)
        XCTAssertTrue(TiledDepthPyramid.isPyramidFile(url))
        XCTAssertEqual(pyramid.bounds, bounds)
        XCTAssertEqual(pyramid.sampleMetres(lat: -18.25, lng: 147.1)!, -1, accuracy: 1e-6)
        XCTAssertEqual(pyramid.sampleMetres(lat: -18.75, lng: 147.6)!, -7, accuracy: 1e-6)
        XCTAssertNil(pyramid.sampleMetres(lat: -18.75, lng: 147.9))
        XCTAssertEqual(pyramid.previewGrid().coveredCells, 7)
    }

    // TIFF directory ------------------------------------------------------------

    /// A minimal pixel-free BigTIFF with LONG8 strip tables, as in the Android test.
    private func minimalBigTiff() -> Data {
        var w = DepthTestTiff.Writer(le: true)
        let entries: [(Int, Int, UInt64)] = [(256, 4, 4), (257, 4, 3), (258, 3, 32), (273, 16, 0),
                                             (277, 3, 1), (278, 4, 3), (279, 16, 48)]
        w.bytes.append(contentsOf: [0x49, 0x49]); w.u16(43); w.u16(8); w.u16(0); w.u64(16)
        w.u64(UInt64(entries.count))
        for (tag, type, value) in entries {
            w.u16(UInt16(tag)); w.u16(UInt16(type)); w.u64(1)
            switch type {
            case 3: w.u16(UInt16(value)); w.zeros(6)
            case 4: w.u32(UInt32(value)); w.zeros(4)
            default: w.u64(value)
            }
        }
        w.u64(0)
        return Data(w.bytes)
    }

    func testBigTiffDirectoryIsParsed() throws {
        let dir = try TiffIfdReader.read(TiffByteSource(data: minimalBigTiff()))
        XCTAssertTrue(dir.isBigTiff)
        XCTAssertTrue(dir.littleEndian)
        XCTAssertEqual(dir.width, 4)
        XCTAssertEqual(dir.height, 3)
        XCTAssertEqual(dir.samplesPerPixel, 1)
        XCTAssertEqual(dir.rowsPerStrip, 3)
        XCTAssertEqual(dir.bitsPerSample, [32])
        XCTAssertEqual(dir.stripOffsets ?? [], [0])
        XCTAssertEqual(dir.stripByteCounts ?? [], [48])
        XCTAssertTrue(GeoTiffImportRouter.shouldStream(dir))
    }
}
