import Foundation
import Compression

// TIFF block decompression, predictor undoing and sample conversion. Android hands
// most of this to the mil.nga:tiff library; iOS has none, so it is all here.

enum TiffCodecs {
    static let compressionNone = 1
    static let compressionLzw = 5
    static let compressionDeflate = 8
    static let compressionAdobeDeflate = 32946
    static let compressionPackBits = 32773

    /// Plain-English names for compressions this app can't decode.
    static func unsupportedCompressionMessage(_ code: Int) -> String {
        let name: String
        switch code {
        case 2, 3, 4: name = "fax (CCITT) compression"
        case 6, 7: name = "JPEG compression"
        case 34712: name = "JPEG 2000 compression"
        case 34887: name = "LERC compression"
        case 34925: name = "LZMA compression"
        case 50000: name = "ZSTD compression"
        case 50001: name = "WebP compression"
        default: name = "compression type \(code)"
        }
        return "This TIFF uses \(name), which SeaBathy can't read. Re-save it with Deflate, LZW or no compression "
            + "(for example with gdal_translate -co COMPRESS=DEFLATE) and import it again."
    }

    static func isSupported(compression: Int) -> Bool {
        [compressionNone, compressionLzw, compressionDeflate, compressionAdobeDeflate, compressionPackBits].contains(compression)
    }

    /// Decompresses one strip or tile to (up to) `expected` bytes.
    static func decompress(_ input: [UInt8], compression: Int, expected: Int) throws -> [UInt8] {
        switch compression {
        case compressionNone: return input
        case compressionPackBits: return packBitsDecode(input, expected: expected)
        case compressionLzw: return try lzwDecode(input, expected: expected)
        case compressionDeflate, compressionAdobeDeflate: return try inflate(input, expected: expected)
        default: throw GeoTiffImportError(unsupportedCompressionMessage(compression))
        }
    }

    // Deflate -----------------------------------------------------------------

    /// TIFF Deflate data has a 2-byte zlib header; the Compression framework wants raw DEFLATE.
    static func inflate(_ input: [UInt8], expected: Int) throws -> [UInt8] {
        if expected <= 0 { return [] }
        var skip = 0
        if input.count >= 2 && (input[0] & 0x0F) == 8 && ((Int(input[0]) << 8) | Int(input[1])) % 31 == 0 {
            skip = 2
        }
        guard input.count > skip else {
            throw GeoTiffImportError("This TIFF's Deflate-compressed pixel data is empty or damaged.")
        }
        var out = [UInt8](repeating: 0, count: expected)
        let written: Int = input.withUnsafeBufferPointer { src -> Int in
            out.withUnsafeMutableBufferPointer { dst -> Int in
                guard let s = src.baseAddress, let d = dst.baseAddress else { return 0 }
                return compression_decode_buffer(d, expected, s + skip, src.count - skip, nil, COMPRESSION_ZLIB)
            }
        }
        if written <= 0 {
            throw GeoTiffImportError("This TIFF's Deflate-compressed pixel data is corrupt or uses an unsupported variant.")
        }
        if written < expected { out.removeSubrange(written..<expected) }
        return out
    }

    // PackBits ----------------------------------------------------------------

    static func packBitsDecode(_ input: [UInt8], expected: Int) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(expected)
        var i = 0
        while i < input.count && out.count < expected {
            let n = Int(Int8(bitPattern: input[i]))
            i += 1
            if n >= 0 {
                let count = min(n + 1, input.count - i)
                if count <= 0 { break }
                out.append(contentsOf: input[i..<(i + count)])
                i += count
            } else if n != -128 {
                guard i < input.count else { break }
                out.append(contentsOf: repeatElement(input[i], count: 1 - n))
                i += 1
            }
        }
        return out
    }

    // LZW ---------------------------------------------------------------------

    /// TIFF LZW: MSB-first codes of 9 to 12 bits, Clear 256, End 257, "early change" widths.
    static func lzwDecode(_ input: [UInt8], expected: Int) throws -> [UInt8] {
        if input.count >= 2 && input[0] == 0 && (input[1] & 0x01) != 0 {
            throw GeoTiffImportError("This TIFF uses the old, pre-1992 form of LZW compression, which SeaBathy can't read. "
                + "Re-save it with Deflate compression and import it again.")
        }
        var out = [UInt8]()
        out.reserveCapacity(max(expected, 0))
        var prefix = [Int](repeating: -1, count: 4096)
        var suffix = [UInt8](repeating: 0, count: 4096)
        var firstByte = [UInt8](repeating: 0, count: 4096)
        var length = [Int](repeating: 0, count: 4096)
        for i in 0..<256 {
            suffix[i] = UInt8(i); firstByte[i] = UInt8(i); length[i] = 1
        }
        var next = 258
        var width = 9
        var old = -1
        var acc: UInt32 = 0
        var accBits = 0
        var index = 0

        func emit(_ code: Int) {
            let len = length[code]
            let start = out.count
            out.append(contentsOf: repeatElement(UInt8(0), count: len))
            var c = code
            var p = start + len - 1
            while c >= 0 && p >= start {
                out[p] = suffix[c]
                p -= 1
                c = prefix[c]
            }
        }

        while out.count < expected {
            while accBits < width {
                if index >= input.count { return out }
                acc = (acc << 8) | UInt32(input[index])
                index += 1
                accBits += 8
            }
            let code = Int((acc >> UInt32(accBits - width)) & UInt32((1 << width) - 1))
            accBits -= width

            if code == 257 { break }
            if code == 256 {
                next = 258; width = 9; old = -1
                continue
            }
            if old == -1 {
                guard code < 256 else { throw lzwError() }
                out.append(UInt8(code))
                old = code
                continue
            }
            let fb: UInt8
            if code < next {
                emit(code)
                fb = firstByte[code]
            } else if code == next {
                fb = firstByte[old]
                emit(old)
                out.append(fb)
            } else {
                throw lzwError()
            }
            if next < 4096 {
                prefix[next] = old
                suffix[next] = fb
                firstByte[next] = firstByte[old]
                length[next] = length[old] + 1
                next += 1
            }
            old = code
            if next + 1 >= (1 << width) && width < 12 { width += 1 }
        }
        return out
    }

    private static func lzwError() -> GeoTiffImportError {
        GeoTiffImportError("This TIFF's LZW-compressed pixel data is corrupt.")
    }

    // Predictors --------------------------------------------------------------

    /**
     Undoes TIFF Predictor 2 (horizontal) or 3 (floating point) in place, row by row:
     each row starts afresh, so a block can be done on its own (as libtiff's tif_predict.c).
     */
    static func unpredict(_ bytes: inout [UInt8], predictor: Int, width: Int, height: Int,
                          samplesPerPixel: Int, bytesPerSample: Int, littleEndian: Bool) throws {
        if predictor == 1 { return }
        if bytesPerSample <= 0 || width <= 0 || height <= 0 { return }
        let rowBytes = width * samplesPerPixel * bytesPerSample
        let rows = min(height, bytes.count / max(rowBytes, 1))
        switch predictor {
        case 2:
            guard [1, 2, 4, 8].contains(bytesPerSample) else {
                throw GeoTiffImportError("This TIFF uses a horizontal predictor with \(bytesPerSample)-byte samples, which SeaBathy can't decode.")
            }
            for row in 0..<rows {
                unpredictHorizontal(&bytes, rowStart: row * rowBytes, width: width, samplesPerPixel: samplesPerPixel,
                                    bytesPerSample: bytesPerSample, littleEndian: littleEndian)
            }
        case 3:
            for row in 0..<rows {
                unpredictFloatingPoint(&bytes, rowStart: row * rowBytes, width: width, samplesPerPixel: samplesPerPixel,
                                       bytesPerSample: bytesPerSample)
            }
        default:
            throw GeoTiffImportError("This TIFF declares predictor \(predictor), which is not a value the TIFF specification defines (only 2 and 3 are).")
        }
    }

    private static func readUInt(_ b: [UInt8], _ at: Int, _ size: Int, _ little: Bool) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<size {
            v = v << 8 | UInt64(b[at + (little ? size - 1 - i : i)])
        }
        return v
    }

    private static func writeUInt(_ b: inout [UInt8], _ at: Int, _ size: Int, _ little: Bool, _ value: UInt64) {
        var v = value
        for i in 0..<size {
            b[at + (little ? i : size - 1 - i)] = UInt8(v & 0xFF)
            v >>= 8
        }
    }

    private static func unpredictHorizontal(_ bytes: inout [UInt8], rowStart: Int, width: Int, samplesPerPixel: Int,
                                            bytesPerSample: Int, littleEndian: Bool) {
        let elements = width * samplesPerPixel
        if elements <= samplesPerPixel { return }
        if bytesPerSample == 1 {
            for i in samplesPerPixel..<elements {
                let pos = rowStart + i
                bytes[pos] = bytes[pos] &+ bytes[pos - samplesPerPixel]
            }
            return
        }
        for i in samplesPerPixel..<elements {
            let pos = rowStart + i * bytesPerSample
            let prev = pos - samplesPerPixel * bytesPerSample
            let sum = readUInt(bytes, pos, bytesPerSample, littleEndian) &+ readUInt(bytes, prev, bytesPerSample, littleEndian)
            writeUInt(&bytes, pos, bytesPerSample, littleEndian, sum)
        }
    }

    /**
     Predictor 3: each row's bytes were split into byte planes and then differenced as
     bytes. Undone in reverse: add the bytes back, then regroup the planes. libtiff
     writes the planes in the reverse of the file's byte order (checked against
     GDAL-written files), so taking them in reverse gives each sample in file order.
     */
    private static func unpredictFloatingPoint(_ bytes: inout [UInt8], rowStart: Int, width: Int,
                                               samplesPerPixel: Int, bytesPerSample: Int) {
        let rowSamples = width * samplesPerPixel
        let rowLength = rowSamples * bytesPerSample
        if rowLength <= 0 { return }
        if rowLength > samplesPerPixel {
            for i in samplesPerPixel..<rowLength {
                let pos = rowStart + i
                bytes[pos] = bytes[pos] &+ bytes[pos - samplesPerPixel]
            }
        }
        let shuffled = Array(bytes[rowStart..<(rowStart + rowLength)])
        for sample in 0..<rowSamples {
            for b in 0..<bytesPerSample {
                bytes[rowStart + sample * bytesPerSample + b] = shuffled[(bytesPerSample - 1 - b) * rowSamples + sample]
            }
        }
    }

    // Samples -----------------------------------------------------------------

    /// Throws for sample layouts this app can't read.
    static func checkSampleLayout(bits: Int, format: Int) throws {
        switch (format, bits) {
        case (1, 8), (1, 16), (1, 32), (1, 64), (2, 8), (2, 16), (2, 32), (2, 64), (3, 16), (3, 32), (3, 64):
            return
        case (3, _):
            throw GeoTiffImportError("This TIFF stores \(bits)-bit floating-point samples, which SeaBathy can't read.")
        case (5, _), (6, _):
            throw GeoTiffImportError("This TIFF stores complex-number samples, which aren't depths.")
        default:
            if format == 4 && [8, 16, 32, 64].contains(bits) { return } // "undefined": read as unsigned
            throw GeoTiffImportError("This TIFF uses \(bits)-bit samples, which SeaBathy can't read. Re-save it as 16- or 32-bit data.")
        }
    }

    /// Converts `count` samples of the given layout to Float (NaN where the data ran out).
    static func samples(_ bytes: [UInt8], count: Int, bits: Int, format: Int, littleEndian: Bool) -> [Float] {
        var out = [Float](repeating: .nan, count: count)
        let size = bits / 8
        if size <= 0 { return out }
        let available = min(count, bytes.count / size)
        let b = TiffBytes(bytes: bytes, littleEndian: littleEndian)
        switch (format, bits) {
        case (2, 8):
            for i in 0..<available { out[i] = Float(Int8(bitPattern: bytes[i])) }
        case (2, 16):
            for i in 0..<available { out[i] = Float(Int16(bitPattern: b.u16(i * 2))) }
        case (2, 32):
            for i in 0..<available { out[i] = Float(Int32(bitPattern: b.u32(i * 4))) }
        case (2, 64):
            for i in 0..<available { out[i] = Float(Int64(bitPattern: b.u64(i * 8))) }
        case (3, 16):
            for i in 0..<available { out[i] = halfToFloat(b.u16(i * 2)) }
        case (3, 32):
            for i in 0..<available { out[i] = b.f32(i * 4) }
        case (3, 64):
            for i in 0..<available { out[i] = Float(b.f64(i * 8)) }
        case (_, 8):
            for i in 0..<available { out[i] = Float(bytes[i]) }
        case (_, 16):
            for i in 0..<available { out[i] = Float(b.u16(i * 2)) }
        case (_, 32):
            for i in 0..<available { out[i] = Float(b.u32(i * 4)) }
        case (_, 64):
            for i in 0..<available { out[i] = Float(b.u64(i * 8)) }
        default:
            break
        }
        return out
    }

    /// IEEE half precision to Float (Float16 isn't available on every build target).
    static func halfToFloat(_ h: UInt16) -> Float {
        let sign: UInt32 = UInt32(h & 0x8000) << 16
        let exponent = Int((h >> 10) & 0x1F)
        let mantissa = UInt32(h & 0x3FF)
        if exponent == 0 {
            if mantissa == 0 { return Float(bitPattern: sign) }
            let value = Float(mantissa) * 5.9604645e-8 // mantissa x 2^-24
            return sign != 0 ? -value : value
        }
        if exponent == 31 {
            return Float(bitPattern: sign | 0x7F80_0000 | (mantissa << 13))
        }
        return Float(bitPattern: sign | (UInt32(exponent - 15 + 127) << 23) | (mantissa << 13))
    }
}
