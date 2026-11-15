import Foundation

/**
 Sizes up a TIFF from its header and first IFD (Android geotiff/GeoTiffImportRouter.kt).
 On Android these decide whether a file goes to GDAL. iOS has no GDAL: the same files
 are read by streaming blocks, and `shouldStream` says when a file is that big.
 No I/O of its own, so fully unit tested.
 */
enum GeoTiffImportRouter {
    enum TiffFormat { case classic, bigTiff, unknown }

    /// Classic TIFFs bigger than this uncompressed went to GDAL on Android.
    static let gdalSizeThresholdBytes: Int64 = 300 * 1024 * 1024
    /// The biggest strip or tile Android's Kotlin reader decodes.
    static let maxKotlinBlockBytes: Int64 = 16 * 1024 * 1024
    static let headerBytesNeeded = 4

    static func detectFormat(_ header: [UInt8]) -> TiffFormat {
        if header.count < headerBytesNeeded { return .unknown }
        let little: Bool
        if header[0] == 0x49 && header[1] == 0x49 { little = true }
        else if header[0] == 0x4D && header[1] == 0x4D { little = false }
        else { return .unknown }
        let version = little ? (Int(header[2]) | Int(header[3]) << 8) : (Int(header[2]) << 8 | Int(header[3]))
        switch version {
        case 42: return .classic
        case 43: return .bigTiff
        default: return .unknown
        }
    }

    /// Uncompressed size of the image, or nil when an input is not positive or it would overflow.
    static func estimateUncompressedBytes(width: Int64, height: Int64, samplesPerPixel: Int, maxBitsPerSample: Int) -> Int64? {
        if width <= 0 || height <= 0 || samplesPerPixel <= 0 || maxBitsPerSample <= 0 { return nil }
        let bytesPerSample = Int64((maxBitsPerSample + 7) / 8)
        let (a, o1) = width.multipliedReportingOverflow(by: height)
        if o1 { return nil }
        let (b, o2) = a.multipliedReportingOverflow(by: Int64(samplesPerPixel))
        if o2 { return nil }
        let (c, o3) = b.multipliedReportingOverflow(by: bytesPerSample)
        if o3 { return nil }
        return c
    }

    /// Uncompressed bytes of one strip or tile.
    static func estimateBlockBytes(blockWidth: Int64, blockHeight: Int64, samplesPerBlockPixel: Int, maxBitsPerSample: Int) -> Int64? {
        estimateUncompressedBytes(width: blockWidth, height: blockHeight, samplesPerPixel: samplesPerBlockPixel,
                                  maxBitsPerSample: maxBitsPerSample)
    }

    /// Android's GDAL rule: BigTIFF always, and classic TIFFs that are huge, have huge blocks or an unreadable size.
    static func shouldUseGdal(format: TiffFormat, estimatedUncompressedBytes: Int64?, estimatedBlockBytes: Int64? = 0) -> Bool {
        switch format {
        case .bigTiff: return true
        case .classic:
            guard let image = estimatedUncompressedBytes, let block = estimatedBlockBytes else { return true }
            return image > gdalSizeThresholdBytes || block > maxKotlinBlockBytes
        case .unknown: return false
        }
    }

    /// Whether iOS reads this file by streaming rather than as a small in-memory decode.
    static func shouldStream(_ directory: TiffDirectory) -> Bool {
        let format: TiffFormat = directory.isBigTiff ? .bigTiff : .classic
        let image = estimateUncompressedBytes(width: Int64(directory.width), height: Int64(directory.height),
                                              samplesPerPixel: directory.samplesPerPixel,
                                              maxBitsPerSample: directory.maxBitsPerSample)
        let blockWidth = Int64(directory.isTiled ? (directory.tileWidth ?? directory.width) : directory.width)
        let blockHeight = Int64(directory.isTiled ? (directory.tileHeight ?? directory.height)
                                                  : min(max(directory.rowsPerStrip ?? directory.height, 1), max(directory.height, 1)))
        let block = estimateBlockBytes(blockWidth: blockWidth, blockHeight: blockHeight,
                                       samplesPerBlockPixel: directory.planarConfiguration == 2 ? 1 : directory.samplesPerPixel,
                                       maxBitsPerSample: directory.maxBitsPerSample)
        return shouldUseGdal(format: format, estimatedUncompressedBytes: image, estimatedBlockBytes: block)
    }
}
