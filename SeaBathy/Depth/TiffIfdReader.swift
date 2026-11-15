import Foundation

// Reads a TIFF or BigTIFF header and first IFD with positioned reads, never loading
// the whole file (Android geotiff/TiffIfdReader.kt; iOS has no TIFF tag library, so
// the tag values are decoded here too). Only the tags the app uses are kept.

/// Positioned reads from a file (or bytes in memory, for tests). Thread-safe.
final class TiffByteSource {
    let length: UInt64
    private let handle: FileHandle?
    private let memory: Data?
    private let lock = NSLock()

    init(url: URL) throws {
        let handle = try FileHandle(forReadingFrom: url)
        self.handle = handle
        self.memory = nil
        self.length = try handle.seekToEnd()
    }

    init(data: Data) {
        self.handle = nil
        self.memory = data
        self.length = UInt64(data.count)
    }

    deinit {
        try? handle?.close()
    }

    /// Exactly `count` bytes from `offset`, or throws at the end of the file.
    func read(at offset: UInt64, count: Int) throws -> [UInt8] {
        if count <= 0 { return [] }
        if offset > length || UInt64(count) > length - offset {
            throw GeoTiffImportError("This TIFF is truncated or damaged: it points past the end of the file.", kind: .unreadable)
        }
        if let memory {
            let start = memory.startIndex + Int(offset)
            return [UInt8](memory[start..<(start + count)])
        }
        guard let handle else { return [] }
        lock.lock()
        defer { lock.unlock() }
        try handle.seek(toOffset: offset)
        var out = [UInt8]()
        out.reserveCapacity(count)
        while out.count < count {
            guard let chunk = try handle.read(upToCount: count - out.count), !chunk.isEmpty else {
                throw GeoTiffImportError("This TIFF is truncated or damaged: it ended unexpectedly.", kind: .unreadable)
            }
            out.append(contentsOf: chunk)
        }
        return out
    }
}

/// Endian-aware reads from a byte array.
struct TiffBytes {
    let bytes: [UInt8]
    let littleEndian: Bool

    func u8(_ at: Int) -> UInt8 { bytes[at] }

    func u16(_ at: Int) -> UInt16 {
        let a = UInt16(bytes[at]), b = UInt16(bytes[at + 1])
        return littleEndian ? (a | b << 8) : (a << 8 | b)
    }

    func u32(_ at: Int) -> UInt32 {
        var v: UInt32 = 0
        for i in 0..<4 {
            let byte = UInt32(bytes[at + (littleEndian ? 3 - i : i)])
            v = v << 8 | byte
        }
        return v
    }

    func u64(_ at: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<8 {
            let byte = UInt64(bytes[at + (littleEndian ? 7 - i : i)])
            v = v << 8 | byte
        }
        return v
    }

    func f32(_ at: Int) -> Float { Float(bitPattern: u32(at)) }
    func f64(_ at: Int) -> Double { Double(bitPattern: u64(at)) }
}

/// The first IFD's tags that the reader uses, with TIFF defaults filled in.
struct TiffDirectory {
    var littleEndian = true
    var isBigTiff = false
    var width = 0
    var height = 0
    var bitsPerSample: [Int] = []
    var compression = 1
    var photometric = 1
    var samplesPerPixel = 1
    var rowsPerStrip: Int?
    var planarConfiguration = 1
    var predictor = 1
    var sampleFormat: [Int] = []
    var tileWidth: Int?
    var tileHeight: Int?
    var stripOffsets: [UInt64]?
    var stripByteCounts: [UInt64]?
    var tileOffsets: [UInt64]?
    var tileByteCounts: [UInt64]?
    var colorMap: [Int]?
    var modelPixelScale: [Double]?
    var modelTiepoint: [Double]?
    var modelTransformation: [Double]?
    var geoKeyDirectory: [Int]?
    var gdalNoData: String?

    var isTiled: Bool { tileWidth != nil }
    var maxBitsPerSample: Int { bitsPerSample.max() ?? 8 }
    var blockOffsets: [UInt64] { (isTiled ? tileOffsets : stripOffsets) ?? [] }
    var blockByteCounts: [UInt64] { (isTiled ? tileByteCounts : stripByteCounts) ?? [] }
}

enum TiffIfdReader {
    private static let maxEntries: UInt64 = 8192
    private static let maxEntryCount: UInt64 = 50_000_000

    private static let interestingTags: Set<Int> = [
        256, 257, 258, 259, 262, 277, 278, 273, 279, 284, 317, 320,
        322, 323, 324, 325, 339, 33550, 33922, 34264, 34735, 42113,
    ]

    /// Bytes per element for each TIFF field type.
    private static func typeSize(_ type: Int) -> Int? {
        switch type {
        case 1, 2, 6, 7: return 1
        case 3, 8: return 2
        case 4, 9, 11, 13: return 4
        case 5, 10, 12, 16, 17, 18: return 8
        default: return nil
        }
    }

    static func read(_ source: TiffByteSource) throws -> TiffDirectory {
        if source.length < 8 {
            throw GeoTiffImportError("This file is too small to be a TIFF.", kind: .unreadable)
        }
        let headerBytes = try source.read(at: 0, count: Int(min(source.length, 16)))
        let little: Bool
        if headerBytes[0] == 0x49 && headerBytes[1] == 0x49 {
            little = true
        } else if headerBytes[0] == 0x4D && headerBytes[1] == 0x4D {
            little = false
        } else {
            throw GeoTiffImportError("This is not a TIFF file (unrecognised byte order marker).", kind: .unreadable)
        }
        let header = TiffBytes(bytes: headerBytes, littleEndian: little)
        let magic = header.u16(2)
        let isBig: Bool
        switch magic {
        case 42: isBig = false
        case 43: isBig = true
        default: throw GeoTiffImportError("This is not a TIFF file (unrecognised version number).", kind: .unreadable)
        }
        if isBig && headerBytes.count < 16 {
            throw GeoTiffImportError("This BigTIFF file is truncated.", kind: .unreadable)
        }
        let firstIfd: UInt64 = isBig ? header.u64(8) : UInt64(header.u32(4))

        var dir = TiffDirectory()
        dir.littleEndian = little
        dir.isBigTiff = isBig

        let entryCount: UInt64
        let tableOffset: UInt64
        let entrySize = isBig ? 20 : 12
        let inlineBytes = isBig ? 8 : 4
        if isBig {
            let c = try source.read(at: firstIfd, count: 8)
            entryCount = TiffBytes(bytes: c, littleEndian: little).u64(0)
            tableOffset = firstIfd + 8
        } else {
            let c = try source.read(at: firstIfd, count: 2)
            entryCount = UInt64(TiffBytes(bytes: c, littleEndian: little).u16(0))
            tableOffset = firstIfd + 2
        }
        if entryCount == 0 || entryCount > maxEntries {
            throw GeoTiffImportError("This TIFF has no readable image directory.", kind: .unreadable)
        }
        let tableRaw = try source.read(at: tableOffset, count: Int(entryCount) * entrySize)
        let table = TiffBytes(bytes: tableRaw, littleEndian: little)

        for index in 0..<Int(entryCount) {
            let base = index * entrySize
            let tag = Int(table.u16(base))
            if !interestingTags.contains(tag) { continue }
            let type = Int(table.u16(base + 2))
            guard let size = typeSize(type) else { continue }
            let count: UInt64 = isBig ? table.u64(base + 4) : UInt64(table.u32(base + 4))
            if count == 0 || count > maxEntryCount { continue }
            let byteSize = Int(count) * size
            let valuePos = base + entrySize - inlineBytes
            let values: TiffBytes
            let start: Int
            if byteSize <= inlineBytes {
                values = table
                start = valuePos
            } else {
                let offset: UInt64 = isBig ? table.u64(valuePos) : UInt64(table.u32(valuePos))
                guard let raw = try? source.read(at: offset, count: byteSize) else { continue }
                values = TiffBytes(bytes: raw, littleEndian: little)
                start = 0
            }
            let n = Int(count)
            if type == 2 {
                let chars = values.bytes[start..<(start + n)].prefix { $0 != 0 }
                let text = String(decoding: chars, as: UTF8.self)
                if tag == 42113 { dir.gdalNoData = text }
                continue
            }
            let numbers = decodeNumbers(values, start: start, count: n, type: type)
            apply(tag: tag, numbers: numbers, to: &dir)
        }

        if dir.bitsPerSample.isEmpty { dir.bitsPerSample = Array(repeating: 1, count: dir.samplesPerPixel) }
        if dir.sampleFormat.isEmpty { dir.sampleFormat = Array(repeating: 1, count: dir.samplesPerPixel) }
        return dir
    }

    private static func decodeNumbers(_ b: TiffBytes, start: Int, count: Int, type: Int) -> [Double] {
        var out = [Double]()
        out.reserveCapacity(count)
        for i in 0..<count {
            let v: Double
            switch type {
            case 1, 7: v = Double(b.u8(start + i))
            case 6: v = Double(Int8(bitPattern: b.u8(start + i)))
            case 3: v = Double(b.u16(start + i * 2))
            case 8: v = Double(Int16(bitPattern: b.u16(start + i * 2)))
            case 4, 13: v = Double(b.u32(start + i * 4))
            case 9: v = Double(Int32(bitPattern: b.u32(start + i * 4)))
            case 11: v = Double(b.f32(start + i * 4))
            case 12: v = b.f64(start + i * 8)
            case 16, 18: v = Double(b.u64(start + i * 8))
            case 17: v = Double(Int64(bitPattern: b.u64(start + i * 8)))
            case 5:
                let d = b.u32(start + i * 8 + 4)
                v = d == 0 ? 0 : Double(b.u32(start + i * 8)) / Double(d)
            case 10:
                let d = Int32(bitPattern: b.u32(start + i * 8 + 4))
                v = d == 0 ? 0 : Double(Int32(bitPattern: b.u32(start + i * 8))) / Double(d)
            default: v = 0
            }
            out.append(v)
        }
        return out
    }

    private static func apply(tag: Int, numbers: [Double], to dir: inout TiffDirectory) {
        guard let firstValue = numbers.first else { return }
        // Conversions that can't trap on a damaged file's NaN or huge values.
        func int(_ v: Double) -> Int { v.isFinite && abs(v) < 9e15 ? Int(v) : 0 }
        func offset(_ v: Double) -> UInt64 { v.isFinite && v > 0 && v < 9e18 ? UInt64(v) : 0 }
        var ints: [Int] { numbers.map(int) }
        var offsets: [UInt64] { numbers.map(offset) }
        let first = int(firstValue)
        switch tag {
        case 256: dir.width = first
        case 257: dir.height = first
        case 258: dir.bitsPerSample = ints
        case 259: dir.compression = first
        case 262: dir.photometric = first
        case 277: dir.samplesPerPixel = max(1, first)
        case 278: dir.rowsPerStrip = firstValue > Double(Int32.max) ? nil : first
        case 273: dir.stripOffsets = offsets
        case 279: dir.stripByteCounts = offsets
        case 284: dir.planarConfiguration = first
        case 317: dir.predictor = first
        case 320: dir.colorMap = ints
        case 322: dir.tileWidth = first
        case 323: dir.tileHeight = first
        case 324: dir.tileOffsets = offsets
        case 325: dir.tileByteCounts = offsets
        case 339: dir.sampleFormat = ints
        case 33550: dir.modelPixelScale = numbers
        case 33922: dir.modelTiepoint = numbers
        case 34264: dir.modelTransformation = numbers
        case 34735: dir.geoKeyDirectory = ints
        default: break
        }
    }
}
