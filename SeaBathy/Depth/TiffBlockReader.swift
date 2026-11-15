import Foundation

/**
 Reads raster samples one strip or tile at a time, straight from the offsets in the
 TIFF, with a small cache of recent blocks (Android geotiff/TiffBlockReader.kt).
 Memory depends on the block size, not the file size, so files Android sends to GDAL
 (BigTIFF, or hundreds of megabytes) can be read here by streaming. An uncompressed
 file stored as huge strips is read a row at a time instead of a strip at a time.

 Not thread-safe: one reader per thread, or guard it with a lock.
 */
final class TiffBlockReader {
    /// Ceiling on decoded blocks held by one reader.
    static let cacheBudgetBytes: Int64 = 64 * 1024 * 1024
    /// The smallest number of blocks kept, for files with small blocks.
    static let minCacheBlocks = 8
    /// Uncompressed blocks bigger than this are read row by row rather than whole.
    static let rowModeThresholdBytes: Int64 = 8 * 1024 * 1024
    /// The biggest compressed block decoded whole; bigger ones would need too much memory.
    static let maxWholeBlockBytes: Int64 = 256 * 1024 * 1024

    /**
     One full row of blocks plus one, never below `minCacheBlocks` (a reprojected output
     row can straddle several strips), never more than the budget, and always at least 1.
     */
    static func blockCacheCapacity(blocksPerRow: Int, blockBytes: Int64, budget: Int64 = cacheBudgetBytes) -> Int {
        let wanted = Int64(max(minCacheBlocks, blocksPerRow + 1))
        let affordable = blockBytes <= 0 ? wanted : budget / blockBytes
        return Int(max(min(wanted, affordable), 1))
    }

    private final class DecodedBlock {
        let values: [Float]
        let width: Int
        init(values: [Float], width: Int) { self.values = values; self.width = width }
    }

    private struct Key: Hashable {
        let block: Int
        let row: Int
    }

    private let source: TiffByteSource
    private let directory: TiffDirectory
    private let bandIndexes: [Int]
    private let imageWidth: Int
    private let imageHeight: Int
    private let tiled: Bool
    private let blockWidth: Int
    private let blockHeight: Int
    private let blocksAcross: Int
    private let blocksDown: Int
    private let planarSeparate: Bool
    private let samplesPerBlockPixel: Int
    private let bits: Int
    private let format: Int
    private let bytesPerSample: Int
    private let blockRowBytes: Int
    private let rowMode: Bool
    private let offsets: [UInt64]
    private let byteCounts: [UInt64]
    private let capacity: Int

    private var cache: [Key: (block: DecodedBlock?, tick: Int)] = [:]
    private var tick = 0
    private var lastKey: Key?
    private var lastBlock: DecodedBlock?
    private(set) var blocksDecoded = 0

    init(source: TiffByteSource, directory: TiffDirectory, bandIndexes: [Int],
         cacheBudget: Int64 = TiffBlockReader.cacheBudgetBytes) throws {
        if !TiffCodecs.isSupported(compression: directory.compression) {
            throw GeoTiffImportError(TiffCodecs.unsupportedCompressionMessage(directory.compression))
        }
        let bitsList = directory.bitsPerSample
        let formats = directory.sampleFormat
        let bits = bitsList.first ?? 8
        let format = formats.first ?? 1
        if bitsList.contains(where: { $0 != bits }) || formats.contains(where: { $0 != format }) {
            throw GeoTiffImportError("This TIFF mixes different sample types between its bands, which SeaBathy can't read.")
        }
        try TiffCodecs.checkSampleLayout(bits: bits, format: format)
        let bytesPerSample = bits / 8

        let width = directory.width
        let height = directory.height
        let tiled = directory.isTiled
        let blockWidth: Int
        let blockHeight: Int
        if tiled {
            blockWidth = max(directory.tileWidth ?? width, 1)
            blockHeight = max(directory.tileHeight ?? height, 1)
        } else {
            blockWidth = max(width, 1)
            blockHeight = min(max(directory.rowsPerStrip ?? height, 1), max(height, 1))
        }
        let blocksAcross = (width + blockWidth - 1) / blockWidth
        let blocksDown = (height + blockHeight - 1) / blockHeight
        let planarSeparate = directory.planarConfiguration == 2
        let samplesPerBlockPixel = planarSeparate ? 1 : directory.samplesPerPixel
        let blockRowBytes = blockWidth * samplesPerBlockPixel * bytesPerSample
        if directory.blockOffsets.isEmpty {
            throw GeoTiffImportError("This TIFF has no pixel data (no strip or tile offsets).")
        }

        let blockBytes = Int64(blockRowBytes) * Int64(blockHeight)
        let rowMode = directory.compression == TiffCodecs.compressionNone && blockBytes > TiffBlockReader.rowModeThresholdBytes
        if !rowMode && blockBytes > TiffBlockReader.maxWholeBlockBytes {
            throw GeoTiffImportError("This TIFF keeps its pixels in very large compressed blocks (about "
                + "\(blockBytes / (1024 * 1024)) MB each), too big to decode on a phone. Re-save it tiled, for example with "
                + "gdal_translate -co TILED=YES -co COMPRESS=DEFLATE, and import it again.")
        }

        // Cached values are Floats, 4 bytes a sample.
        let floatsPerUnit = Int64(blockWidth) * Int64(samplesPerBlockPixel) * (rowMode ? 1 : Int64(blockHeight))
        let capacity: Int
        if rowMode {
            capacity = max(1, min(64, Int(cacheBudget / max(floatsPerUnit * 4, 1))))
        } else {
            capacity = TiffBlockReader.blockCacheCapacity(
                blocksPerRow: blocksAcross * (planarSeparate ? bandIndexes.count : 1),
                blockBytes: floatsPerUnit * 4, budget: cacheBudget)
        }

        self.source = source
        self.directory = directory
        self.bandIndexes = bandIndexes
        self.imageWidth = width
        self.imageHeight = height
        self.tiled = tiled
        self.blockWidth = blockWidth
        self.blockHeight = blockHeight
        self.blocksAcross = blocksAcross
        self.blocksDown = blocksDown
        self.planarSeparate = planarSeparate
        self.samplesPerBlockPixel = samplesPerBlockPixel
        self.bits = bits
        self.format = format
        self.bytesPerSample = bytesPerSample
        self.blockRowBytes = blockRowBytes
        self.rowMode = rowMode
        self.offsets = directory.blockOffsets
        self.byteCounts = directory.blockByteCounts
        self.capacity = capacity

        DiagnosticLog.log("TiffBlockReader", "init: tiled=\(tiled) block=\(blockWidth)x\(blockHeight) "
            + "blocks=\(blocksAcross)x\(blocksDown) compression=\(directory.compression) predictor=\(directory.predictor) "
            + "bits=\(bits) format=\(format) rowMode=\(rowMode) cache=\(capacity)")
    }

    /// The sample at (`col`, `row`) for output band `band` (NaN possible), or nil if unavailable.
    func sample(col: Int, row: Int, band: Int) throws -> Float? {
        if col < 0 || col >= imageWidth || row < 0 || row >= imageHeight { return nil }
        if band < 0 || band >= bandIndexes.count { return nil }
        let realBand = bandIndexes[band]
        if realBand >= directory.samplesPerPixel { return nil }

        let tileX = col / blockWidth
        let tileY = row / blockHeight
        let localCol = col - tileX * blockWidth
        let localRow = row - tileY * blockHeight
        let blockIndex = planarSeparate
            ? realBand * blocksAcross * blocksDown + tileY * blocksAcross + tileX
            : tileY * blocksAcross + tileX

        let key = Key(block: blockIndex, row: rowMode ? localRow : -1)
        let block: DecodedBlock?
        if let lastKey, lastKey == key {
            block = lastBlock
        } else {
            block = try lookup(key: key, tileY: tileY)
            self.lastKey = key
            self.lastBlock = block
        }
        guard let block else { return nil }

        let sampleBand = planarSeparate ? 0 : realBand
        let r = rowMode ? 0 : localRow
        let index = (r * block.width + localCol) * samplesPerBlockPixel + sampleBand
        if index < 0 || index >= block.values.count { return nil }
        return block.values[index]
    }

    private func lookup(key: Key, tileY: Int) throws -> DecodedBlock? {
        tick += 1
        if let hit = cache[key] {
            cache[key] = (hit.block, tick)
            return hit.block
        }
        let decoded = try decode(key: key, tileY: tileY)
        if cache.count >= capacity {
            // Evict the least recently used entry.
            if let oldest = cache.min(by: { $0.value.tick < $1.value.tick })?.key {
                cache.removeValue(forKey: oldest)
            }
        }
        cache[key] = (decoded, tick)
        return decoded
    }

    private func decode(key: Key, tileY: Int) throws -> DecodedBlock? {
        let index = key.block
        if index < 0 || index >= offsets.count || index >= byteCounts.count { return nil }
        let offset = offsets[index]
        let byteCount = byteCounts[index]
        if offset == 0 || byteCount == 0 { return nil }
        blocksDecoded += 1

        if rowMode {
            let start = offset + UInt64(key.row) * UInt64(blockRowBytes)
            let end = min(offset + byteCount, source.length)
            if start >= end { return nil }
            let count = Int(min(UInt64(blockRowBytes), end - start))
            var bytes = try source.read(at: start, count: count)
            try TiffCodecs.unpredict(&bytes, predictor: directory.predictor, width: blockWidth, height: 1,
                                     samplesPerPixel: samplesPerBlockPixel, bytesPerSample: bytesPerSample,
                                     littleEndian: directory.littleEndian)
            let values = TiffCodecs.samples(bytes, count: blockWidth * samplesPerBlockPixel, bits: bits, format: format,
                                            littleEndian: directory.littleEndian)
            return DecodedBlock(values: values, width: blockWidth)
        }

        // Tiles are stored at their full size, padding the edge tiles; strips are not
        // padded, so the last one may hold fewer rows.
        let pixelHeight = tiled ? blockHeight : min(blockHeight, imageHeight - tileY * blockHeight)
        if pixelHeight <= 0 { return nil }
        let expected = blockRowBytes * pixelHeight
        let available = source.length > offset ? source.length - offset : 0
        let readCount = Int(min(byteCount, available, UInt64(Int32.max)))
        if readCount <= 0 { return nil }
        let raw = try source.read(at: offset, count: readCount)
        var bytes = try TiffCodecs.decompress(raw, compression: directory.compression, expected: expected)
        try TiffCodecs.unpredict(&bytes, predictor: directory.predictor, width: blockWidth, height: pixelHeight,
                                 samplesPerPixel: samplesPerBlockPixel, bytesPerSample: bytesPerSample,
                                 littleEndian: directory.littleEndian)
        let values = TiffCodecs.samples(bytes, count: blockWidth * pixelHeight * samplesPerBlockPixel, bits: bits,
                                        format: format, littleEndian: directory.littleEndian)
        return DecodedBlock(values: values, width: blockWidth)
    }
}
