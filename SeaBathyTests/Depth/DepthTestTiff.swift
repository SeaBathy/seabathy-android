import Foundation
import Compression
@testable import SeaBathy

/**
 Hand-writes small GeoTIFFs (classic or BigTIFF, either byte order, strips or tiles,
 any of the supported compressions and predictors) so the reader can be tested end to
 end with no sample files. Predictors are genuinely encoded, as libtiff does, so a
 passing round trip really tests undoing them.
 */
enum DepthTestTiff {
    struct Options {
        var width = 4
        var height = 4
        var bitsPerSample = 32
        /// 1 unsigned, 2 signed, 3 float.
        var sampleFormat = 3
        var samplesPerPixel = 1
        var photometric = 1
        var littleEndian = true
        var bigTiff = false
        var compression = 1
        var predictor = 1
        var rowsPerStrip: Int? = nil
        var tileWidth: Int? = nil
        var tileHeight: Int? = nil
        var originX = 147.0
        var originY = -18.0
        var pixelSizeX = 0.25
        var pixelSizeY = 0.25
        var geoKeys: [Int]? = DepthTestTiff.geographicKeys(4326)
        var noData: String? = nil
    }

    static func geographicKeys(_ code: Int) -> [Int] { [1, 1, 0, 2, 1024, 0, 1, 2, 2048, 0, 1, code] }
    static func projectedKeys(_ code: Int) -> [Int] { [1, 1, 0, 2, 1024, 0, 1, 1, 3072, 0, 1, code] }

    /// Writes to a fresh temporary file.
    static func file(_ options: Options, sample: (Int, Int, Int) -> Double) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("depth-test-\(UUID().uuidString).tif")
        try make(options, sample: sample).write(to: url)
        return url
    }

    static func file(_ options: Options, sample: (Int, Int) -> Double) throws -> URL {
        try file(options) { c, r, _ in sample(c, r) }
    }

    static func make(_ options: Options, sample: (Int, Int) -> Double) -> Data {
        make(options) { c, r, _ in sample(c, r) }
    }

    /// `sample(column, row, band)`.
    static func make(_ o: Options, sample: (Int, Int, Int) -> Double) -> Data {
        let le = o.littleEndian
        let bps = o.bitsPerSample / 8
        let spp = o.samplesPerPixel
        let tiled = o.tileWidth != nil
        let blockW = o.tileWidth ?? o.width
        let blockH = tiled ? (o.tileHeight ?? o.height) : min(o.rowsPerStrip ?? o.height, o.height)
        let across = (o.width + blockW - 1) / blockW
        let down = (o.height + blockH - 1) / blockH

        var blocks: [[UInt8]] = []
        for by in 0..<down {
            for bx in 0..<across {
                let rows = tiled ? blockH : min(blockH, o.height - by * blockH)
                var raw = [UInt8]()
                for r in 0..<rows {
                    var row = [UInt8]()
                    for c in 0..<blockW {
                        let col = bx * blockW + c
                        let line = by * blockH + r
                        for band in 0..<spp {
                            let v = (col < o.width && line < o.height) ? sample(col, line, band) : 0
                            row.append(contentsOf: encodeSample(v, bits: o.bitsPerSample, format: o.sampleFormat, le: le))
                        }
                    }
                    if o.predictor == 2 { predictHorizontal(&row, spp: spp, bps: bps, le: le) }
                    if o.predictor == 3 { predictFloat(&row, spp: spp, bps: bps) }
                    raw.append(contentsOf: row)
                }
                blocks.append(compress(raw, o.compression))
            }
        }

        var w = Writer(le: le)
        let headerSize = o.bigTiff ? 16 : 8
        w.zeros(headerSize)
        var offsets: [UInt64] = []
        var counts: [UInt64] = []
        for b in blocks {
            offsets.append(UInt64(w.bytes.count))
            counts.append(UInt64(b.count))
            w.bytes.append(contentsOf: b)
        }
        if w.bytes.count % 2 == 1 { w.zeros(1) }

        var entries: [(tag: Int, type: Int, count: Int, payload: [UInt8])] = []
        func shorts(_ tag: Int, _ v: [Int]) {
            var p = Writer(le: le); v.forEach { p.u16(UInt16($0)) }; entries.append((tag, 3, v.count, p.bytes))
        }
        func longs(_ tag: Int, _ v: [UInt64]) {
            var p = Writer(le: le)
            if o.bigTiff { v.forEach { p.u64($0) }; entries.append((tag, 16, v.count, p.bytes)) }
            else { v.forEach { p.u32(UInt32($0)) }; entries.append((tag, 4, v.count, p.bytes)) }
        }
        func doubles(_ tag: Int, _ v: [Double]) {
            var p = Writer(le: le); v.forEach { p.u64($0.bitPattern) }; entries.append((tag, 12, v.count, p.bytes))
        }
        func ascii(_ tag: Int, _ s: String) {
            let b = Array(s.utf8) + [0]; entries.append((tag, 2, b.count, b))
        }
        longs(256, [UInt64(o.width)])
        longs(257, [UInt64(o.height)])
        shorts(258, Array(repeating: o.bitsPerSample, count: spp))
        shorts(259, [o.compression])
        shorts(262, [o.photometric])
        if !tiled { longs(273, offsets) }
        shorts(277, [spp])
        if !tiled { longs(278, [UInt64(blockH)]); longs(279, counts) }
        shorts(284, [1])
        if o.predictor != 1 { shorts(317, [o.predictor]) }
        if tiled {
            shorts(322, [blockW]); shorts(323, [blockH])
            longs(324, offsets); longs(325, counts)
        }
        shorts(339, Array(repeating: o.sampleFormat, count: spp))
        doubles(33550, [o.pixelSizeX, o.pixelSizeY, 0])
        doubles(33922, [0, 0, 0, o.originX, o.originY, 0])
        if let keys = o.geoKeys { shorts(34735, keys) }
        if let nd = o.noData { ascii(42113, nd) }
        entries.sort { $0.tag < $1.tag }

        let ifdOffset = UInt64(w.bytes.count)
        let entrySize = o.bigTiff ? 20 : 12
        let inline = o.bigTiff ? 8 : 4
        let ifdSize = (o.bigTiff ? 8 : 2) + entries.count * entrySize + (o.bigTiff ? 8 : 4)
        var extraOffset = ifdOffset + UInt64(ifdSize)
        var extra = [UInt8]()
        if o.bigTiff { w.u64(UInt64(entries.count)) } else { w.u16(UInt16(entries.count)) }
        for e in entries {
            w.u16(UInt16(e.tag)); w.u16(UInt16(e.type))
            if o.bigTiff { w.u64(UInt64(e.count)) } else { w.u32(UInt32(e.count)) }
            if e.payload.count <= inline {
                w.bytes.append(contentsOf: e.payload)
                w.zeros(inline - e.payload.count)
            } else {
                if o.bigTiff { w.u64(extraOffset) } else { w.u32(UInt32(extraOffset)) }
                var payload = e.payload
                if payload.count % 2 == 1 { payload.append(0) }
                extra.append(contentsOf: payload)
                extraOffset += UInt64(payload.count)
            }
        }
        if o.bigTiff { w.u64(0) } else { w.u32(0) }
        w.bytes.append(contentsOf: extra)

        // Header last, now the IFD offset is known.
        var h = Writer(le: le)
        h.bytes.append(contentsOf: le ? [0x49, 0x49] : [0x4D, 0x4D])
        if o.bigTiff { h.u16(43); h.u16(8); h.u16(0); h.u64(ifdOffset) } else { h.u16(42); h.u32(UInt32(ifdOffset)) }
        w.bytes.replaceSubrange(0..<headerSize, with: h.bytes)
        return Data(w.bytes)
    }

    // Encoding helpers ----------------------------------------------------------

    struct Writer {
        var bytes: [UInt8] = []
        let le: Bool
        mutating func zeros(_ n: Int) { bytes.append(contentsOf: repeatElement(UInt8(0), count: max(n, 0))) }
        mutating func uint(_ v: UInt64, _ size: Int) {
            for i in 0..<size {
                let shift = UInt64(8 * (le ? i : size - 1 - i))
                bytes.append(UInt8((v >> shift) & 0xFF))
            }
        }
        mutating func u16(_ v: UInt16) { uint(UInt64(v), 2) }
        mutating func u32(_ v: UInt32) { uint(UInt64(v), 4) }
        mutating func u64(_ v: UInt64) { uint(v, 8) }
    }

    static func encodeSample(_ v: Double, bits: Int, format: Int, le: Bool) -> [UInt8] {
        var w = Writer(le: le)
        switch (format, bits) {
        case (3, 32): w.u32(Float(v).bitPattern)
        case (3, 64): w.u64(v.bitPattern)
        case (2, 16): w.u16(UInt16(bitPattern: Int16(v)))
        case (2, 32): w.u32(UInt32(bitPattern: Int32(v)))
        case (2, 8): w.bytes.append(UInt8(bitPattern: Int8(v)))
        case (_, 8): w.bytes.append(UInt8(v))
        case (_, 16): w.u16(UInt16(v))
        default: w.u32(UInt32(v))
        }
        return w.bytes
    }

    static func predictHorizontal(_ row: inout [UInt8], spp: Int, bps: Int, le: Bool) {
        let n = row.count / bps
        func read(_ i: Int) -> UInt64 {
            var v: UInt64 = 0
            for k in 0..<bps { v = v << 8 | UInt64(row[i * bps + (le ? bps - 1 - k : k)]) }
            return v
        }
        func write(_ i: Int, _ value: UInt64) {
            var v = value
            for k in 0..<bps { row[i * bps + (le ? k : bps - 1 - k)] = UInt8(v & 0xFF); v >>= 8 }
        }
        if n <= spp { return }
        for i in stride(from: n - 1, through: spp, by: -1) {
            write(i, read(i) &- read(i - spp))
        }
    }

    /// The inverse of the reader's predictor-3 decode: byte planes, then byte differences.
    static func predictFloat(_ row: inout [UInt8], spp: Int, bps: Int) {
        let samples = row.count / bps
        var shuffled = [UInt8](repeating: 0, count: row.count)
        for s in 0..<samples {
            for b in 0..<bps { shuffled[(bps - 1 - b) * samples + s] = row[s * bps + b] }
        }
        if shuffled.count > spp {
            for i in stride(from: shuffled.count - 1, through: spp, by: -1) {
                shuffled[i] = shuffled[i] &- shuffled[i - spp]
            }
        }
        row = shuffled
    }

    static func compress(_ raw: [UInt8], _ compression: Int) -> [UInt8] {
        switch compression {
        case 8, 32946: return deflate(raw)
        case 32773: return packBits(raw)
        case 5: return lzw(raw)
        default: return raw
        }
    }

    static func deflate(_ raw: [UInt8]) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: raw.count + 1024)
        let n = raw.withUnsafeBufferPointer { src -> Int in
            out.withUnsafeMutableBufferPointer { dst -> Int in
                compression_encode_buffer(dst.baseAddress!, dst.count, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in raw { a = (a + UInt32(byte)) % 65521; b = (b + a) % 65521 }
        let adler = b << 16 | a
        return [0x78, 0x01] + Array(out[0..<n]) + [UInt8(adler >> 24), UInt8((adler >> 16) & 0xFF), UInt8((adler >> 8) & 0xFF), UInt8(adler & 0xFF)]
    }

    static func packBits(_ raw: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        var i = 0
        while i < raw.count {
            // A repeat run where there is one, literal bytes otherwise.
            var run = 1
            while i + run < raw.count && raw[i + run] == raw[i] && run < 128 { run += 1 }
            if run >= 3 {
                out.append(UInt8(bitPattern: Int8(1 - run)))
                out.append(raw[i])
                i += run
            } else {
                let n = min(128, raw.count - i)
                out.append(UInt8(n - 1))
                out.append(contentsOf: raw[i..<(i + n)])
                i += n
            }
        }
        return out
    }

    static func lzw(_ raw: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        var acc: UInt64 = 0
        var accBits = 0
        var width = 9
        func emit(_ code: Int) {
            acc = acc << UInt64(width) | UInt64(code)
            accBits += width
            while accBits >= 8 {
                out.append(UInt8((acc >> UInt64(accBits - 8)) & 0xFF))
                accBits -= 8
            }
        }
        var dict: [Int: Int] = [:]
        var next = 258
        emit(256)
        guard let first = raw.first else { emit(257); return out }
        var w = Int(first)
        for byte in raw.dropFirst() {
            let key = w << 8 | Int(byte)
            if let code = dict[key] {
                w = code
                continue
            }
            if next >= (1 << width) && width < 12 { width += 1 }
            emit(w)
            dict[key] = next
            next += 1
            w = Int(byte)
            if next >= 4093 {
                if next >= (1 << width) && width < 12 { width += 1 }
                emit(256)
                dict.removeAll()
                next = 258
                width = 9
            }
        }
        if next >= (1 << width) && width < 12 { width += 1 }
        emit(w)
        if next + 1 >= (1 << width) && width < 12 { width += 1 }
        emit(257)
        if accBits > 0 { out.append(UInt8((acc << UInt64(8 - accBits)) & 0xFF)) }
        return out
    }
}
