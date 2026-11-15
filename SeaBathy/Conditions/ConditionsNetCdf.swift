import Foundation

/**
 Reads the variables of a NetCDF-3 "classic" file, the only table format
 HYCOM's server sends for a grid of ocean temperatures (Android NetCdf3.kt).
 Just what that needs: names, sizes, attributes and numbers.
 */
enum ConditionsNetCdf {

    struct ReadError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    final class Variable {
        let shape: [Int]
        /// Text attributes as String, numeric ones as [Double].
        let attributes: [String: Any]
        let values: [Double]

        init(shape: [Int], attributes: [String: Any], values: [Double]) {
            self.shape = shape
            self.attributes = attributes
            self.values = values
        }

        func number(_ attribute: String) -> Double? { (attributes[attribute] as? [Double])?.first }
        func text(_ attribute: String) -> String? { attributes[attribute] as? String }
    }

    private static let typeByte = 1, typeChar = 2, typeShort = 3, typeInt = 4, typeFloat = 5, typeDouble = 6

    static func read(_ data: Data) throws -> [String: Variable] {
        let bytes = [UInt8](data)
        guard bytes.count >= 8, bytes[0] == 0x43, bytes[1] == 0x44, bytes[2] == 0x46 else {
            throw ReadError(message: "not a NetCDF-3 file")
        }
        let wideOffsets = bytes[3] == 2
        var reader = Reader(bytes: bytes, position: 4)
        let records = try reader.int()

        func name() throws -> String {
            let length = try reader.int()
            let text = try reader.text(at: reader.position, length: length)
            reader.position += padded(length)
            return text
        }

        func attributes() throws -> [String: Any] {
            _ = try reader.int() // the tag, or 0 for none
            let count = try reader.int()
            var out: [String: Any] = [:]
            for _ in 0..<max(count, 0) {
                let key = try name()
                let type = try reader.int()
                let size = try reader.int()
                let start = reader.position
                if type == typeChar {
                    out[key] = try reader.text(at: start, length: size)
                } else {
                    var numbers: [Double] = []
                    numbers.reserveCapacity(size)
                    for i in 0..<max(size, 0) { numbers.append(try reader.value(start: start, type: type, index: i)) }
                    out[key] = numbers
                }
                let width = try sizeOf(type)
                reader.position = start + padded(size * width)
            }
            return out
        }

        _ = try reader.int() // the dimensions' tag
        let dimensionCount = try reader.int()
        var dimensions: [Int] = []
        for _ in 0..<max(dimensionCount, 0) {
            _ = try name()
            let length = try reader.int()
            dimensions.append(length == 0 ? records : length)
        }
        _ = try attributes()
        _ = try reader.int() // the variables' tag
        let variableCount = try reader.int()
        var variables: [String: Variable] = [:]
        for _ in 0..<max(variableCount, 0) {
            let varName = try name()
            let rank = try reader.int()
            var shape: [Int] = []
            for _ in 0..<max(rank, 0) {
                let d = try reader.int()
                guard d >= 0 && d < dimensions.count else { throw ReadError(message: "NetCDF dimension \(d)") }
                shape.append(dimensions[d])
            }
            let attrs = try attributes()
            let type = try reader.int()
            _ = try reader.int() // size
            let begin: Int
            if wideOffsets {
                begin = Int(try reader.long())
            } else {
                begin = try reader.int()
            }
            let count = shape.reduce(1, *)
            let width = try sizeOf(type)
            if begin < 0 || Int64(begin) + Int64(count) * Int64(width) > Int64(bytes.count) {
                throw ReadError(message: "NetCDF file is cut short")
            }
            var values = [Double](repeating: 0, count: count)
            for i in 0..<count { values[i] = try reader.value(start: begin, type: type, index: i) }
            variables[varName] = Variable(shape: shape, attributes: attrs, values: values)
        }
        return variables
    }

    private static func padded(_ length: Int) -> Int { (length + 3) / 4 * 4 }

    private static func sizeOf(_ type: Int) throws -> Int {
        switch type {
        case typeByte, typeChar: return 1
        case typeShort: return 2
        case typeInt, typeFloat: return 4
        case typeDouble: return 8
        default: throw ReadError(message: "NetCDF type \(type)")
        }
    }

    /// Big-endian reading from a byte array.
    private struct Reader {
        let bytes: [UInt8]
        var position: Int

        private func check(_ at: Int, _ count: Int) throws {
            if at < 0 || at + count > bytes.count { throw ReadError(message: "NetCDF file is cut short") }
        }

        func u32(at: Int) throws -> UInt32 {
            try check(at, 4)
            return UInt32(bytes[at]) << 24 | UInt32(bytes[at + 1]) << 16 | UInt32(bytes[at + 2]) << 8 | UInt32(bytes[at + 3])
        }

        func u64(at: Int) throws -> UInt64 {
            let high = try u32(at: at)
            let low = try u32(at: at + 4)
            return UInt64(high) << 32 | UInt64(low)
        }

        mutating func int() throws -> Int {
            let v = Int32(bitPattern: try u32(at: position))
            position += 4
            return Int(v)
        }

        mutating func long() throws -> Int64 {
            let v = Int64(bitPattern: try u64(at: position))
            position += 8
            return v
        }

        func text(at: Int, length: Int) throws -> String {
            try check(at, max(length, 0))
            let slice = bytes[at..<(at + max(length, 0))]
            // Text attributes may end in a zero byte.
            let trimmed = slice.prefix { $0 != 0 }
            return String(decoding: trimmed, as: UTF8.self)
        }

        func value(start: Int, type: Int, index: Int) throws -> Double {
            switch type {
            case ConditionsNetCdf.typeByte, ConditionsNetCdf.typeChar:
                try check(start + index, 1)
                return Double(Int8(bitPattern: bytes[start + index]))
            case ConditionsNetCdf.typeShort:
                let at = start + index * 2
                try check(at, 2)
                return Double(Int16(bitPattern: UInt16(bytes[at]) << 8 | UInt16(bytes[at + 1])))
            case ConditionsNetCdf.typeInt:
                return Double(Int32(bitPattern: try u32(at: start + index * 4)))
            case ConditionsNetCdf.typeFloat:
                return Double(Float(bitPattern: try u32(at: start + index * 4)))
            case ConditionsNetCdf.typeDouble:
                return Double(bitPattern: try u64(at: start + index * 8))
            default:
                throw ReadError(message: "NetCDF type \(type)")
            }
        }
    }
}
