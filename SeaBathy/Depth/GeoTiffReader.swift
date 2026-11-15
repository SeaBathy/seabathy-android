import Foundation
import UIKit

// Decodes a GeoTIFF into something the map can draw (Android geotiff/GeoTiffReader.kt).
// Single-band rasters become a depth grid, resampled onto a north-up WGS84 grid so
// projected sources (Web Mercator, UTM) land in the right place. Pixels are read a
// block at a time, so the file is never loaded whole.

/// A user-facing import failure. `message` is plain English, ready for an alert.
struct GeoTiffImportError: LocalizedError, CustomStringConvertible {
    enum Kind {
        /// Not a readable TIFF at all (empty, damaged, not a TIFF).
        case unreadable
        /// A real TIFF the app can't handle (projection, compression, no georeferencing...).
        case unsupported
        /// A colour image (RGB or palette), not depths: `GeoTiffImport.readImage` can open it.
        case imageNotDepth
    }

    let message: String
    let kind: Kind

    init(_ message: String, kind: Kind = .unsupported) {
        self.message = message
        self.kind = kind
    }

    var errorDescription: String? { message }
    var description: String { message }
}

struct GeoTiffReadResult {
    /// Full-precision sampling (reads the file for big surveys); `previewGrid()` is what to draw.
    let source: DepthLayerSource
    let projectionLabel: String
    let sourceWidth: Int
    let sourceHeight: Int
    /// The file held positive-down soundings and was flipped to negative-down.
    let positiveDownConverted: Bool
    var bounds: GeoBounds { source.bounds }
}

/// A colour GeoTIFF (orthophoto, satellite export) resampled to north-up WGS84.
struct GeoTiffImageResult {
    let image: UIImage
    let bounds: GeoBounds
    let projectionLabel: String
    let sourceWidth: Int
    let sourceHeight: Int
}

enum GeoTiffImport {
    /// Output grid edge cap when the caller has no quality setting to hand.
    static let defaultMaxDimension = 1600

    /// Blocking: call off the main thread. Throws `GeoTiffImportError` with a user-facing message.
    static func read(url: URL, maxDimension: Int) throws -> GeoTiffReadResult {
        let source = try open(url)
        return try read(source: source, maxDimension: maxDimension, displayName: url.lastPathComponent)
    }

    /// The same from bytes in memory (tests, small downloads).
    static func read(data: Data, maxDimension: Int = defaultMaxDimension) throws -> GeoTiffReadResult {
        try read(source: TiffByteSource(data: data), maxDimension: maxDimension, displayName: "<data>")
    }

    /// A colour GeoTIFF as an image. Blocking. Depth rasters throw (use `read`).
    static func readImage(url: URL, maxDimension: Int) throws -> GeoTiffImageResult {
        let source = try open(url)
        return try wrapErrors {
            let meta = try resolveMetadata(source, displayName: url.lastPathComponent)
            let (cols, rows) = outputSize(meta, maxDimension: maxDimension)
            let dir = meta.directory
            let paletted = dir.samplesPerPixel == 1 && dir.photometric == 3 && !(dir.colorMap ?? []).isEmpty
            guard dir.samplesPerPixel >= 3 || paletted else {
                throw GeoTiffImportError("This TIFF holds depths, not a colour image.")
            }
            return try readImage(source: source, meta: meta, cols: cols, rows: rows, paletted: paletted)
        }
    }

    static func read(source: TiffByteSource, maxDimension: Int, displayName: String) throws -> GeoTiffReadResult {
        try wrapErrors {
            if source.length == 0 { throw GeoTiffImportError("The selected TIFF file is empty.", kind: .unreadable) }
            let meta = try resolveMetadata(source, displayName: displayName)
            let dir = meta.directory
            let paletted = dir.samplesPerPixel == 1 && dir.photometric == 3 && !(dir.colorMap ?? []).isEmpty
            if dir.samplesPerPixel >= 3 || paletted {
                throw GeoTiffImportError("This TIFF is a colour image, not a depth survey, so it has no depths to colour.",
                                         kind: .imageNotDepth)
            }
            if dir.samplesPerPixel != 1 {
                throw GeoTiffImportError("This TIFF has \(dir.samplesPerPixel) bands per pixel, which is neither a depth raster nor an image.")
            }
            let (cols, rows) = outputSize(meta, maxDimension: maxDimension)
            let started = Date()
            let reader = try TiffBlockReader(source: source, directory: dir, bandIndexes: [0])
            let depth = try readDepth(reader: reader, meta: meta, cols: cols, rows: rows)
            DiagnosticLog.log("GeoTiffReader", "decoded \(cols)x\(rows) from \(meta.sourceWidth)x\(meta.sourceHeight) "
                + "blocks=\(reader.blocksDecoded) elapsedMs=\(Int(Date().timeIntervalSince(started) * 1000))")

            let downsampled = cols < meta.sourceWidth || rows < meta.sourceHeight
            let layerSource: DepthLayerSource
            if downsampled {
                // Keep the file open so the depth at the crosshair is read at full precision.
                let sampler = try TiffBlockReader(source: source, directory: dir, bandIndexes: [0],
                                                  cacheBudget: 16 * 1024 * 1024)
                layerSource = GeoTiffStreamedDepth(preview: depth.grid, reader: sampler, meta: meta,
                                                   positiveDown: depth.positiveDown)
            } else {
                layerSource = depth.grid
            }
            return GeoTiffReadResult(source: layerSource, projectionLabel: meta.projection.label,
                                     sourceWidth: meta.sourceWidth, sourceHeight: meta.sourceHeight,
                                     positiveDownConverted: depth.positiveDown)
        }
    }

    // Shared metadata -----------------------------------------------------------

    struct Metadata {
        let directory: TiffDirectory
        let transform: PixelTransform
        let projection: DepthMapProjection
        let fullBounds: GeoBounds
        let sourceWidth: Int
        let sourceHeight: Int
        let noData: Double?
    }

    /// Affine mapping between raster pixel indices and projected units.
    struct PixelTransform {
        let originX: Double
        let originY: Double
        let scaleX: Double
        let scaleY: Double

        func toProjected(col: Double, row: Double) -> (x: Double, y: Double) {
            (originX + col * scaleX, originY + row * scaleY)
        }

        func toPixel(x: Double, y: Double) -> (col: Double, row: Double) {
            ((x - originX) / scaleX, (y - originY) / scaleY)
        }

        static func from(_ dir: TiffDirectory) throws -> PixelTransform {
            if let m = dir.modelTransformation, m.count >= 16 {
                if abs(m[1]) > 1e-9 || abs(m[4]) > 1e-9 {
                    throw GeoTiffImportError("This TIFF is rotated relative to north, which is not supported.")
                }
                if m[0] == 0 || m[5] == 0 {
                    throw GeoTiffImportError("This TIFF reports a zero pixel size.")
                }
                return PixelTransform(originX: m[3], originY: m[7], scaleX: m[0], scaleY: m[5])
            }
            guard let scale = dir.modelPixelScale, scale.count >= 2,
                  let tie = dir.modelTiepoint, tie.count >= 6 else {
                throw GeoTiffImportError("This TIFF has no georeferencing, so it cannot be placed on the map.")
            }
            if scale[0] == 0 || scale[1] == 0 {
                throw GeoTiffImportError("This TIFF reports a zero pixel size.")
            }
            return PixelTransform(originX: tie[3] - tie[0] * scale[0], originY: tie[4] + tie[1] * scale[1],
                                  scaleX: scale[0], scaleY: -scale[1])
        }
    }

    static func resolveMetadata(_ source: TiffByteSource, displayName: String) throws -> Metadata {
        let dir = try TiffIfdReader.read(source)
        let width = dir.width
        let height = dir.height
        if width <= 0 || height <= 0 {
            throw GeoTiffImportError("This TIFF reports an empty image size.", kind: .unreadable)
        }
        DiagnosticLog.log("GeoTiffReader", "image params: width=\(width) height=\(height) tiled=\(dir.isTiled) "
            + "bigTiff=\(dir.isBigTiff) compression=\(dir.compression) predictor=\(dir.predictor) "
            + "samplesPerPixel=\(dir.samplesPerPixel) streamed=\(GeoTiffImportRouter.shouldStream(dir))")
        let transform = try PixelTransform.from(dir)
        let projection = try resolveProjection(dir, transform: transform, width: width, height: height)
        let bounds = try geographicBounds(transform: transform, projection: projection, width: width, height: height)
        if !bounds.isValid {
            throw GeoTiffImportError("The georeferencing in this TIFF does not resolve to a valid latitude and longitude extent.")
        }
        let noData = dir.gdalNoData.flatMap { Double($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return Metadata(directory: dir, transform: transform, projection: projection, fullBounds: bounds,
                        sourceWidth: width, sourceHeight: height, noData: noData)
    }

    private static func outputSize(_ meta: Metadata, maxDimension: Int) -> (Int, Int) {
        let scale = min(1.0, Double(max(maxDimension, 2)) / Double(max(meta.sourceWidth, meta.sourceHeight)))
        let cols = max(2, Int((Double(meta.sourceWidth) * scale).rounded()))
        let rows = max(2, Int((Double(meta.sourceHeight) * scale).rounded()))
        return (cols, rows)
    }

    private static func readGeoKeys(_ dir: TiffDirectory) -> [Int: Int] {
        guard let raw = dir.geoKeyDirectory, raw.count >= 4 else { return [:] }
        let available = (raw.count - 4) / 4
        let keyCount = min(raw[3], available)
        var keys: [Int: Int] = [:]
        if keyCount <= 0 { return keys }
        for index in 0..<keyCount {
            let offset = 4 + index * 4
            // A zero tag location means the value is stored inline, the only form the CRS keys use.
            if raw[offset + 1] == 0 { keys[raw[offset]] = raw[offset + 3] }
        }
        return keys
    }

    private static func resolveProjection(_ dir: TiffDirectory, transform: PixelTransform,
                                          width: Int, height: Int) throws -> DepthMapProjection {
        let keys = readGeoKeys(dir)
        let projectedCode = keys[3072] ?? 0
        let geographicCode = keys[2048] ?? 0
        let modelType = keys[1024] ?? 0

        if projectedCode > 0 && projectedCode != 32767 {
            if let p = DepthMapProjection.fromProjectedCode(projectedCode) { return p }
            throw GeoTiffImportError("Projected CRS EPSG:\(projectedCode) is not supported, so reproject to EPSG:4326 or EPSG:3857 first.")
        }
        if modelType == 2 || geographicCode > 0 {
            if let p = DepthMapProjection.fromGeographicCode(geographicCode) { return p }
            throw GeoTiffImportError("Geographic CRS EPSG:\(geographicCode) is not supported, so reproject to EPSG:4326 first.")
        }
        // No usable geo keys: degrees when the corners are plausible as latitude and
        // longitude, Web Mercator when they are clearly metres.
        let corner = transform.toProjected(col: Double(width), row: Double(height))
        if abs(transform.originX) <= 180 && abs(transform.originY) <= 90 && abs(corner.x) <= 180 && abs(corner.y) <= 90 {
            return .geographic
        }
        let limit = 2.0037508e7
        if abs(transform.originX) <= limit && abs(transform.originY) <= limit && abs(corner.x) <= limit && abs(corner.y) <= limit {
            return .webMercator
        }
        throw GeoTiffImportError("This TIFF does not declare a coordinate system and its extent could not be identified.")
    }

    private static func geographicBounds(transform: PixelTransform, projection: DepthMapProjection,
                                         width: Int, height: Int) throws -> GeoBounds {
        var west = Double.infinity, east = -Double.infinity
        var south = Double.infinity, north = -Double.infinity
        func consider(_ col: Double, _ row: Double) {
            let p = transform.toProjected(col: col, row: row)
            guard let g = projection.inverse(x: p.x, y: p.y) else { return }
            west = min(west, g.lng); east = max(east, g.lng)
            south = min(south, g.lat); north = max(north, g.lat)
        }
        // The whole perimeter, not just the corners: reprojected edges bow outwards.
        let samples = 64
        for step in 0...samples {
            let alongX = Double(width) * Double(step) / Double(samples)
            let alongY = Double(height) * Double(step) / Double(samples)
            consider(alongX, 0)
            consider(alongX, Double(height))
            consider(0, alongY)
            consider(Double(width), alongY)
        }
        if !west.isFinite || !east.isFinite || !south.isFinite || !north.isFinite {
            throw GeoTiffImportError("The extent of this TIFF could not be converted to latitude and longitude.")
        }
        return GeoBounds(west: min(max(west, -180), 180), south: min(max(south, -90), 90),
                         east: min(max(east, -180), 180), north: min(max(north, -90), 90))
    }

    static func isNoData(_ value: Double, _ noData: Double?) -> Bool {
        if value.isNaN || value.isInfinite { return true }
        if abs(value) >= 1.0e30 { return true }
        guard let noData, !noData.isNaN else { return false }
        return abs(value - noData) <= abs(noData) * 1.0e-6 + 1.0e-6
    }

    /// The source pixel holding a projected point, or nil outside the raster. Truncates like Android.
    static func sourcePixel(_ meta: Metadata, lat: Double, lng: Double) -> (col: Int, row: Int)? {
        let projected = meta.projection.forward(lat: lat, lng: lng)
        let pixel = meta.transform.toPixel(x: projected.x, y: projected.y)
        guard pixel.col.isFinite, pixel.row.isFinite, abs(pixel.col) < 1e9, abs(pixel.row) < 1e9 else { return nil }
        let col = Int(pixel.col), row = Int(pixel.row)
        if col < 0 || col >= meta.sourceWidth || row < 0 || row >= meta.sourceHeight { return nil }
        return (col, row)
    }

    // Resampling --------------------------------------------------------------

    /// Walks the output grid row by row, mapping each cell back to a source pixel.
    private static func forEachOutputCell(_ meta: Metadata, bounds: GeoBounds, cols: Int, rows: Int,
                                          _ onCell: (Int, Int, Int) throws -> Void) throws {
        let lngSpan = bounds.east - bounds.west
        let latSpan = bounds.north - bounds.south
        var sourceCols = [Int](repeating: -1, count: cols)
        var sourceRows = [Int](repeating: 0, count: cols)
        for row in 0..<rows {
            let lat = bounds.north - (Double(row) + 0.5) / Double(rows) * latSpan
            var any = false
            for col in 0..<cols {
                let lng = bounds.west + (Double(col) + 0.5) / Double(cols) * lngSpan
                if let p = sourcePixel(meta, lat: lat, lng: lng) {
                    sourceCols[col] = p.col
                    sourceRows[col] = p.row
                    any = true
                } else {
                    sourceCols[col] = -1
                }
            }
            if !any { continue }
            let offset = row * cols
            for col in 0..<cols where sourceCols[col] >= 0 {
                try onCell(offset + col, sourceCols[col], sourceRows[col])
            }
        }
    }

    private static func readDepth(reader: TiffBlockReader, meta: Metadata, cols: Int,
                                  rows: Int) throws -> (grid: DepthGrid, positiveDown: Bool) {
        var values = [Float](repeating: DepthGrid.noData, count: cols * rows)
        var covered = 0
        var minValue = Double.infinity
        var maxValue = -Double.infinity
        let noData = meta.noData

        try forEachOutputCell(meta, bounds: meta.fullBounds, cols: cols, rows: rows) { index, sc, sr in
            guard let raw = try reader.sample(col: sc, row: sr, band: 0) else { return }
            let value = Double(raw)
            if isNoData(value, noData) { return }
            values[index] = raw
            covered += 1
            if value < minValue { minValue = value }
            if value > maxValue { maxValue = value }
        }
        if covered == 0 {
            throw GeoTiffImportError("Every pixel in this TIFF is marked as no-data.")
        }
        // Bathymetry ships in both sign conventions. When nothing is negative the file
        // holds positive-down soundings, so flip it into the negative-down form.
        let positiveDown = minValue >= 0 && maxValue > 0
        if positiveDown {
            for i in values.indices where !values[i].isNaN { values[i] = -values[i] }
            let flippedMin = -maxValue
            maxValue = -minValue
            minValue = flippedMin
        }
        let grid = DepthGrid(bounds: meta.fullBounds, cols: cols, rows: rows, values: values,
                             shallowestMetres: maxValue, deepestMetres: minValue, coveredCells: covered)
        return (grid, positiveDown)
    }

    private static func readImage(source: TiffByteSource, meta: Metadata, cols: Int, rows: Int,
                                  paletted: Bool) throws -> GeoTiffImageResult {
        let dir = meta.directory
        let bands = paletted ? 1 : min(dir.samplesPerPixel, 4)
        let reader = try TiffBlockReader(source: source, directory: dir, bandIndexes: Array(0..<bands))
        let bits = dir.maxBitsPerSample
        let scale: Double = bits <= 8 ? 1.0 : (bits <= 16 ? 255.0 / 65535.0 : 255.0 / 4294967295.0)
        let colorMap = dir.colorMap ?? []
        let entries = colorMap.count / 3
        var pixels = [UInt8](repeating: 0, count: cols * rows * 4)
        func byte(_ v: Double) -> UInt8 { v.isNaN ? 0 : UInt8(min(max(v.rounded(), 0), 255)) }

        try forEachOutputCell(meta, bounds: meta.fullBounds, cols: cols, rows: rows) { index, sc, sr in
            guard let first = try reader.sample(col: sc, row: sr, band: 0) else { return }
            let o = index * 4
            if paletted {
                let i = first.isNaN ? -1 : Int(first)
                guard entries > 0, i >= 0, i < entries else { return }
                let cmScale = 255.0 / 65535.0
                pixels[o] = byte(Double(colorMap[i]) * cmScale)
                pixels[o + 1] = byte(Double(colorMap[entries + i]) * cmScale)
                pixels[o + 2] = byte(Double(colorMap[2 * entries + i]) * cmScale)
                pixels[o + 3] = 255
                return
            }
            let red = Double(first)
            let greenSample = try reader.sample(col: sc, row: sr, band: 1)
            let blueSample = try reader.sample(col: sc, row: sr, band: 2)
            let green = Double(greenSample ?? first)
            let blue = Double(blueSample ?? first)
            var alpha = 255.0
            if bands >= 4 {
                let alphaSample = try reader.sample(col: sc, row: sr, band: 3)
                alpha = Double(alphaSample ?? 255) * scale
            }
            let a = byte(alpha)
            // Premultiplied for Core Graphics.
            let f = Double(a) / 255
            pixels[o] = byte(red * scale * f)
            pixels[o + 1] = byte(green * scale * f)
            pixels[o + 2] = byte(blue * scale * f)
            pixels[o + 3] = a
        }
        guard let image = DepthImageRenderer.makeImage(pixels: pixels, width: cols, height: rows) else {
            throw GeoTiffImportError("This image couldn't be drawn.")
        }
        return GeoTiffImageResult(image: image, bounds: meta.fullBounds, projectionLabel: meta.projection.label,
                                  sourceWidth: meta.sourceWidth, sourceHeight: meta.sourceHeight)
    }

    // Errors ------------------------------------------------------------------

    private static func open(_ url: URL) throws -> TiffByteSource {
        do {
            let source = try TiffByteSource(url: url)
            if source.length == 0 { throw GeoTiffImportError("The selected TIFF file is empty.", kind: .unreadable) }
            return source
        } catch let error as GeoTiffImportError {
            throw error
        } catch {
            DiagnosticLog.logError("GeoTiffReader", "open failed", error)
            throw GeoTiffImportError("This file couldn't be opened. It may have been moved or deleted.", kind: .unreadable)
        }
    }

    private static func wrapErrors<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch let error as GeoTiffImportError {
            DiagnosticLog.log("GeoTiffReader", "import refused: \(error.message)")
            throw error
        } catch {
            DiagnosticLog.logError("GeoTiffReader", "import failed", error)
            throw GeoTiffImportError("This file couldn't be read as a GeoTIFF. It may be damaged, or not a GeoTIFF.",
                                     kind: .unreadable)
        }
    }
}

/**
 A survey bigger than the drawn grid: draws the downsampled preview, but reads the
 depth at a point straight from the file at full precision (Android used a GDAL tile
 source or a pyramid for this).
 */
final class GeoTiffStreamedDepth: DepthLayerSource {
    private let preview: DepthGrid
    private let reader: TiffBlockReader
    private let meta: GeoTiffImport.Metadata
    private let positiveDown: Bool
    private let lock = NSLock()

    init(preview: DepthGrid, reader: TiffBlockReader, meta: GeoTiffImport.Metadata, positiveDown: Bool) {
        self.preview = preview
        self.reader = reader
        self.meta = meta
        self.positiveDown = positiveDown
    }

    var bounds: GeoBounds { preview.bounds }
    var shallowestMetres: Double { preview.shallowestMetres }
    var deepestMetres: Double { preview.deepestMetres }
    var hasData: Bool { preview.hasData }

    func sampleMetres(lat: Double, lng: Double) -> Double? {
        if !bounds.contains(lat: lat, lng: lng) { return nil }
        guard let pixel = GeoTiffImport.sourcePixel(meta, lat: lat, lng: lng) else { return nil }
        lock.lock()
        defer { lock.unlock() }
        guard let raw = try? reader.sample(col: pixel.col, row: pixel.row, band: 0) else {
            return preview.sampleMetres(lat: lat, lng: lng)
        }
        let value = Double(raw)
        if GeoTiffImport.isNoData(value, meta.noData) { return nil }
        return positiveDown ? -value : value
    }

    func previewGrid() -> DepthGrid { preview }

    func histogram() -> DepthHistogram? { preview.histogram() }
}
