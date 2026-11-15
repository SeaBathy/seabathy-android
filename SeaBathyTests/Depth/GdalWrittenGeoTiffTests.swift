import XCTest
@testable import SeaBathy

/// Decodes small GeoTIFFs written by real GDAL (gdal_translate -co COMPRESS=DEFLATE
/// -co PREDICTOR=...), not by this project's own test writer, so a decoder and encoder
/// that agree with each other but not with libtiff cannot both pass. Each fixture is the
/// same 8x4 EPSG:4326 grid, origin 147.0E 18.0S, 0.01 degree cells, holding
/// -(1.5 + row * 10 + column) metres. (Android GdalWrittenGeoTiffTest.)
final class GdalWrittenGeoTiffTests: XCTestCase {

    private func read(_ base64: String) throws -> GeoTiffReadResult {
        let data = try XCTUnwrap(Data(base64Encoded: base64))
        return try GeoTiffImport.read(data: data, maxDimension: 1600)
    }

    private func at(_ r: GeoTiffReadResult, _ row: Int, _ column: Int) -> Double {
        r.source.sampleMetres(lat: -18.0 - 0.005 - Double(row) * 0.01, lng: 147.005 + Double(column) * 0.01) ?? .nan
    }

    private func assertFloatGrid(_ r: GeoTiffReadResult) {
        XCTAssertEqual(at(r, 0, 0), -1.5, accuracy: 1e-4)
        XCTAssertEqual(at(r, 0, 7), -8.5, accuracy: 1e-4)
        XCTAssertEqual(at(r, 1, 3), -14.5, accuracy: 1e-4)
        XCTAssertEqual(at(r, 3, 7), -38.5, accuracy: 1e-4)
    }

    func testLittleEndianFloat32DeflatePredictor3() throws { assertFloatGrid(try read(Self.leP3)) }
    func testBigEndianFloat32DeflatePredictor3() throws { assertFloatGrid(try read(Self.beP3)) }
    func testTiledLittleEndianFloat32DeflatePredictor3() throws { assertFloatGrid(try read(Self.leP3Tiled)) }

    func testLittleEndianInt16DeflatePredictor2() throws {
        let r = try read(Self.leP2Int16)
        // GDAL rounded the half-metre values to integers when writing Int16.
        XCTAssertEqual(at(r, 0, 0), -2.0, accuracy: 1e-6)
        XCTAssertEqual(at(r, 1, 3), -15.0, accuracy: 1e-6)
        XCTAssertEqual(at(r, 3, 7), -39.0, accuracy: 1e-6)
    }

    func testGdalTagsAreRead() throws {
        let data = try XCTUnwrap(Data(base64Encoded: Self.leP3))
        let dir = try TiffIfdReader.read(TiffByteSource(data: data))
        XCTAssertEqual(dir.gdalNoData, "-9999")
        XCTAssertEqual(dir.predictor, 3)
        XCTAssertEqual(dir.compression, 8)
        XCTAssertEqual(dir.width, 8)
        XCTAssertEqual(dir.height, 4)
    }

    private static let leP3 = "SUkqAAgAAAASAAABAwABAAAACAAAAAEBAwABAAAABAAAAAIBAwABAAAAIAAAAAMBAwABAAAACAAAAAYBAwABAAAAAQAAABEBBAABAAAAjAEAABUBAwABAAAAAQAAABYBAwABAAAABAAAABcBBAABAAAANgAAABwBAwABAAAAAQAAAD0BAwABAAAAAwAAAFMBAwABAAAAAwAAAA6DDAADAAAA7AAAAIKEDAAGAAAABAEAAK+HAwAgAAAANAEAALCHDAACAAAAdAEAALGHAgAIAAAAhAEAAIGkAgAGAAAA5gAAAAAAAAAtOTk5OQB7FK5H4XqEP3sUrkfheoQ/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGBiQAAAAAAAADLAAAAAAAAAAAABAAEAAAAHAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAEIsYcHAAAABggAAAEAjiMJCLCHAQABAAsIsIcBAAAAiG10lh2kckAAAABAplRYQVdHUyA4NHwAeJzbz8gAAoz/ExwMFBQUJH4woIKDULpcAAh4ODhycMi/5oAAGXR5RghtxcYCBs/Q5AEvDgoB"
    private static let beP3 = "TU0AKgAAAAgAEgEAAAMAAAABAAgAAAEBAAMAAAABAAQAAAECAAMAAAABACAAAAEDAAMAAAABAAgAAAEGAAMAAAABAAEAAAERAAQAAAABAAABjAEVAAMAAAABAAEAAAEWAAMAAAABAAQAAAEXAAQAAAABAAAAMgEcAAMAAAABAAEAAAE9AAMAAAABAAMAAAFTAAMAAAABAAMAAIMOAAwAAAADAAAA7ISCAAwAAAAGAAABBIevAAMAAAAgAAABNIewAAwAAAACAAABdIexAAIAAAAIAAABhKSBAAIAAAAGAAAA5gAAAAAtOTk5OQA/hHrhR64Uez+EeuFHrhR7AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAYmAAAAAAAMAyAAAAAAAAAAAAAAAAAAAAAQABAAAABwQAAAAAAQACBAEAAAABAAEIAAAAAAEQ5ggBh7EABwAACAYAAAABI44ICYewAAEAAQgLh7AAAQAAQHKkHZZ0bYhBWFSmQAAAAFdHUyA4NHwAeJxjQAMHEhwMFBQUJLYzgrmM6PIWAkDAw8Ghy4AdrOGAgLs45P+wsYDBcqjJAMewBwY="
    private static let leP3Tiled = "SUkqAAgAAAATAAABAwABAAAACAAAAAEBAwABAAAABAAAAAIBAwABAAAAIAAAAAMBAwABAAAACAAAAAYBAwABAAAAAQAAABUBAwABAAAAAQAAABwBAwABAAAAAQAAAD0BAwABAAAAAwAAAEIBAwABAAAAEAAAAEMBAwABAAAAEAAAAEQBBAABAAAAmAEAAEUBBAABAAAASQAAAFMBAwABAAAAAwAAAA6DDAADAAAA+AAAAIKEDAAGAAAAEAEAAK+HAwAgAAAAQAEAALCHDAACAAAAgAEAALGHAgAIAAAAkAEAAIGkAgAGAAAA8gAAAAAAAAAtOTk5OQB7FK5H4XqEP3sUrkfheoQ/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGBiQAAAAAAAADLAAAAAAAAAAAABAAEAAAAHAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAEIsYcHAAAABggAAAEAjiMJCLCHAQABAAsIsIcBAAAAiG10lh2kckAAAABAplRYQVdHUyA4NHwAeJzbz8jAwMDAwGjPAAEHEhwMFBQUJH4wEAcOQmmYfgsBAQEBHg6OHDL1r+GAABli9TNCaDso/w8bCxg8YxgFo2AUEAIAtKoLAQ=="
    private static let leP2Int16 = "SUkqAAgAAAASAAABAwABAAAACAAAAAEBAwABAAAABAAAAAIBAwABAAAAEAAAAAMBAwABAAAACAAAAAYBAwABAAAAAQAAABEBBAABAAAAjAEAABUBAwABAAAAAQAAABYBAwABAAAABAAAABcBBAABAAAAFQAAABwBAwABAAAAAQAAAD0BAwABAAAAAgAAAFMBAwABAAAAAgAAAA6DDAADAAAA7AAAAIKEDAAGAAAABAEAAK+HAwAgAAAANAEAALCHDAACAAAAdAEAALGHAgAIAAAAhAEAAIGkAgAGAAAA5gAAAAAAAAAtOTk5OQB7FK5H4XqEP3sUrkfheoQ/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGBiQAAAAAAAADLAAAAAAAAAAAABAAEAAAAHAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAEIsYcHAAAABggAAAEAjiMJCLCHAQABAAsIsIcBAAAAiG10lh2kckAAAABAplRYQVdHUyA4NHwAeJz79x8VfEHjv0LjP0DjAwARuD+B"
}
