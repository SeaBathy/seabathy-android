import Foundation

/// Small helpers shared by the tide code (Java/Kotlin semantics where they differ from Swift's).
enum TideMath {
    static func rad(_ degrees: Double) -> Double { degrees * (Double.pi / 180) }
    static func deg(_ radians: Double) -> Double { radians * (180 / Double.pi) }

    /// Math.floorDiv: rounds towards minus infinity.
    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    /// Kotlin's Double.mod: the remainder with the sign of the (positive) divisor.
    static func mod(_ x: Double, _ m: Double) -> Double {
        let r = x.truncatingRemainder(dividingBy: m)
        return (r != 0 && ((r < 0) != (m < 0))) ? r + m : r
    }

    /// Math.round / roundToLong: half rounds up.
    static func roundToInt64(_ x: Double) -> Int64 { Int64((x + 0.5).rounded(.down)) }

    static func nowMillis() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }

    static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded(.down)) }
}

/// A tide problem with a message for the user.
struct TideError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/**
 Tidal harmonic analysis: the tide as a sum of cosines at the fixed
 frequencies of the moon's and sun's cycles. Fitted (least squares) to a
 year of hourly heights, the amplitudes and phases predict the tide for any
 date offline, the same method official tide tables use. The moon's
 18.6-year nodal cycle slowly changes the lunar constituents; `nodal`
 corrects for it, so predictions a year or two out stay as good as the
 first month.
 */
struct TideHarmonics {
    /// Mean level, metres.
    let mean: Double
    /// Per constituent: cosine and sine coefficients, metres.
    let cosines: [Double]
    let sines: [Double]
    /// Time origin of the phases.
    let epochMillis: Int64
    /// Fitted with nodal corrections (tides saved by older builds were not).
    let nodal: Bool

    init(mean: Double, cosines: [Double], sines: [Double], epochMillis: Int64, nodal: Bool = true) {
        precondition(cosines.count == TideHarmonics.constituents.count && sines.count == TideHarmonics.constituents.count)
        self.mean = mean
        self.cosines = cosines
        self.sines = sines
        self.epochMillis = epochMillis
        self.nodal = nodal
    }

    static let hourMs = 3_600_000.0
    static let dayMs = 86_400_000.0
    /// A full nodal cycle and a bit, as LAT is defined over.
    static let latYears = 19
    static let latCandidates = 40

    func heightAt(_ timeMillis: Int64) -> Double { heights(start: timeMillis, count: 1)[0] }

    /**
     `count` heights from `startMillis`, one every `stepMillis`. Much faster
     than calling `heightAt` for each: the angles are stepped by rotation
     and the nodal factors worked out once a day.
     */
    func heights(start startMillis: Int64, count: Int, stepMillis: Int64 = 3_600_000) -> [Double] {
        let cons = TideHarmonics.constituents
        let n = cons.count
        var out = [Double](repeating: 0, count: max(count, 0))
        guard count > 0 else { return out }
        var c = [Double](repeating: 0, count: n)
        var s = [Double](repeating: 0, count: n)
        var stepCos = [Double](repeating: 0, count: n)
        var stepSin = [Double](repeating: 0, count: n)
        var a = [Double](repeating: 0, count: n)
        var b = [Double](repeating: 0, count: n)
        var factors = Nodal.Factors()
        for i in 0..<n {
            let step = TideMath.rad(cons[i].degreesPerHour * Double(stepMillis) / TideHarmonics.hourMs)
            stepCos[i] = cos(step)
            stepSin[i] = sin(step)
        }
        // Exact angles and nodal factors at the start of each day, rotation within it.
        let day: Int64 = 86_400_000
        var k = 0
        while k < count {
            let t = startMillis + Int64(k) * stepMillis
            let hours = Double(t - epochMillis) / TideHarmonics.hourMs
            if nodal { Nodal.at(t, into: &factors) }
            for i in 0..<n {
                let angle = TideMath.rad(cons[i].degreesPerHour * hours)
                c[i] = cos(angle)
                s[i] = sin(angle)
                // f * (A cos(θ+u) + B sin(θ+u)) rewritten as a cos θ + b sin θ.
                let kind = cons[i].nodal
                let f = nodal ? factors.f(kind) : 1.0
                let u = nodal ? TideMath.rad(factors.u(kind)) : 0.0
                let cu = cos(u)
                let su = sin(u)
                a[i] = f * (cosines[i] * cu + sines[i] * su)
                b[i] = f * (sines[i] * cu - cosines[i] * su)
            }
            let nextDay = (TideMath.floorDiv(t, day) + 1) * day
            let steps = max(Int64(1), (nextDay - t + stepMillis - 1) / stepMillis)
            let end = min(count, k + Int(steps))
            while k < end {
                var h = mean
                for i in 0..<n {
                    h += a[i] * c[i] + b[i] * s[i]
                    let nc = c[i] * stepCos[i] - s[i] * stepSin[i]
                    s[i] = s[i] * stepCos[i] + c[i] * stepSin[i]
                    c[i] = nc
                }
                out[k] = h
                k += 1
            }
        }
        return out
    }

    /// Hourly heights from `fromMillis` to `toMillis`, as a TideCurve.
    func curve(from fromMillis: Int64, to toMillis: Int64) -> TideCurve {
        let hour = Int64(TideHarmonics.hourMs)
        let start = fromMillis - fromMillis % hour
        let count = Int((toMillis - start) / hour) + 2
        let times = (0..<count).map { start + Int64($0) * hour }
        return TideCurve(times: times, heights: heights(start: start, count: count))
    }

    /**
     The lowest tide predicted over the moon's 18.6-year cycle from
     `fromMillis`: Lowest Astronomical Tide, the chart datum tide tables and
     charts measure from. Metres on the same level as `heightAt`. Hourly
     first, then minute by minute around the lowest lows.
     */
    func lowestAstronomical(from fromMillis: Int64) -> Double {
        let hour = Int64(TideHarmonics.hourMs)
        let start = fromMillis - fromMillis % hour
        let count = Int(Double(TideHarmonics.latYears) * 365.25 * 24)
        let hourly = heights(start: start, count: count)
        var lows: [Int] = []
        if count > 2 {
            for i in 1..<(count - 1) where hourly[i] <= hourly[i - 1] && hourly[i] < hourly[i + 1] {
                lows.append(i)
            }
        }
        lows.sort { hourly[$0] < hourly[$1] }
        let best = Array(lows.prefix(TideHarmonics.latCandidates))
        if best.isEmpty { return hourly.min() ?? mean }
        var lowest = Double.infinity
        for i in best {
            let fine = heights(start: start + Int64(i - 1) * hour, count: 121, stepMillis: 60_000)
            if let m = fine.min(), m < lowest { lowest = m }
        }
        return lowest
    }

    /// Amplitude of a constituent by name, metres (for checks and display).
    func amplitude(_ name: String) -> Double {
        guard let i = TideHarmonics.constituents.firstIndex(where: { $0.name == name }) else { return 0 }
        return (cosines[i] * cosines[i] + sines[i] * sines[i]).squareRoot()
    }

    struct Constituent {
        let name: String
        let degreesPerHour: Double
        var nodal: Nodal.Kind = .none
    }

    /**
     Nodal corrections: the amplitude factor f and phase shift u (degrees)
     of the lunar constituents, from the longitude of the moon's ascending
     node (standard formulas, as in Schureman and Pugh's tables).
     */
    enum Nodal {
        enum Kind { case none, m2, m3, m4, m6, k2, k1, o1, j1, oo1, mf, mm }

        struct Factors {
            var fM2 = 1.0, uM2 = 0.0
            var fK2 = 1.0, uK2 = 0.0
            var fK1 = 1.0, uK1 = 0.0
            var fO1 = 1.0, uO1 = 0.0
            var fJ1 = 1.0, uJ1 = 0.0
            var fOO1 = 1.0, uOO1 = 0.0
            var fMf = 1.0, uMf = 0.0
            var fMm = 1.0

            func f(_ kind: Kind) -> Double {
                switch kind {
                case .none: return 1.0
                case .m2: return fM2
                case .m3: return pow(fM2, 1.5)
                case .m4: return fM2 * fM2
                case .m6: return fM2 * fM2 * fM2
                case .k2: return fK2
                case .k1: return fK1
                case .o1: return fO1
                case .j1: return fJ1
                case .oo1: return fOO1
                case .mf: return fMf
                case .mm: return fMm
                }
            }

            func u(_ kind: Kind) -> Double {
                switch kind {
                case .none, .mm: return 0.0
                case .m2: return uM2
                case .m3: return 1.5 * uM2
                case .m4: return 2 * uM2
                case .m6: return 3 * uM2
                case .k2: return uK2
                case .k1: return uK1
                case .o1: return uO1
                case .j1: return uJ1
                case .oo1: return uOO1
                case .mf: return uMf
                }
            }
        }

        private static let unixEpochJD = 2440587.5
        private static let j2000JD = 2451545.0

        /// Fills `into` with the factors for the day containing `timeMillis`.
        static func at(_ timeMillis: Int64, into: inout Factors) {
            let dayMs = TideHarmonics.dayMs
            let midday = floor(Double(timeMillis) / dayMs) * dayMs + dayMs / 2
            let centuries = (midday / dayMs + unixEpochJD - j2000JD) / 36525.0
            let n = TideMath.rad(125.0445479 - 1934.1362891 * centuries)
            let c1 = cos(n), c2 = cos(2 * n), c3 = cos(3 * n)
            let s1 = sin(n), s2 = sin(2 * n), s3 = sin(3 * n)
            into.fM2 = 1.0004 - 0.0373 * c1 + 0.0002 * c2
            into.uM2 = -2.14 * s1
            into.fK2 = 1.0241 + 0.2863 * c1 + 0.0083 * c2 - 0.0015 * c3
            into.uK2 = -17.74 * s1 + 0.68 * s2 - 0.04 * s3
            into.fK1 = 1.0060 + 0.1150 * c1 - 0.0088 * c2 + 0.0006 * c3
            into.uK1 = -8.86 * s1 + 0.68 * s2 - 0.07 * s3
            into.fO1 = 1.0089 + 0.1871 * c1 - 0.0147 * c2 + 0.0014 * c3
            into.uO1 = 10.80 * s1 - 1.34 * s2 + 0.19 * s3
            into.fJ1 = 1.1029 + 0.1676 * c1 - 0.0170 * c2 + 0.0016 * c3
            into.uJ1 = -12.94 * s1 + 1.34 * s2 - 0.19 * s3
            into.fOO1 = 1.1027 + 0.6504 * c1 + 0.0317 * c2 - 0.0014 * c3
            into.uOO1 = -36.68 * s1 + 4.02 * s2 - 0.57 * s3
            into.fMf = 1.0429 + 0.4135 * c1 - 0.0040 * c2
            into.uMf = -23.74 * s1 + 2.68 * s2 - 0.38 * s3
            into.fMm = 1.0 - 0.1300 * c1 + 0.0013 * c2
        }
    }

    /// The constituents a year of hourly data can separate (order matters: saved tides list them).
    static let constituents: [Constituent] = [
        Constituent(name: "M2", degreesPerHour: 28.9841042, nodal: .m2), Constituent(name: "S2", degreesPerHour: 30.0),
        Constituent(name: "N2", degreesPerHour: 28.4397295, nodal: .m2),
        Constituent(name: "K2", degreesPerHour: 30.0821373, nodal: .k2), Constituent(name: "2N2", degreesPerHour: 27.8953548, nodal: .m2),
        Constituent(name: "MU2", degreesPerHour: 27.9682084, nodal: .m2), Constituent(name: "NU2", degreesPerHour: 28.5125831, nodal: .m2),
        Constituent(name: "L2", degreesPerHour: 29.5284789, nodal: .m2), Constituent(name: "T2", degreesPerHour: 29.9589333),
        Constituent(name: "K1", degreesPerHour: 15.0410686, nodal: .k1), Constituent(name: "O1", degreesPerHour: 13.9430356, nodal: .o1),
        Constituent(name: "P1", degreesPerHour: 14.9589314),
        Constituent(name: "Q1", degreesPerHour: 13.3986609, nodal: .o1), Constituent(name: "J1", degreesPerHour: 15.5854433, nodal: .j1),
        Constituent(name: "OO1", degreesPerHour: 16.1391017, nodal: .oo1),
        Constituent(name: "M4", degreesPerHour: 57.9682084, nodal: .m4), Constituent(name: "MS4", degreesPerHour: 58.9841042, nodal: .m2),
        Constituent(name: "MN4", degreesPerHour: 57.4238337, nodal: .m4),
        Constituent(name: "M6", degreesPerHour: 86.9523127, nodal: .m6), Constituent(name: "M3", degreesPerHour: 43.4761563, nodal: .m3),
        Constituent(name: "MF", degreesPerHour: 1.0980331, nodal: .mf), Constituent(name: "MM", degreesPerHour: 0.5443747, nodal: .mm),
        Constituent(name: "SSA", degreesPerHour: 0.0821373), Constituent(name: "SA", degreesPerHour: 0.0410686)
    ]

    /// Comma-joined constituent names, stored with saved tides to spot files from another version.
    static var constituentNames: String { constituents.map { $0.name }.joined(separator: ",") }

    /**
     Least-squares fit to hourly samples. Needs about a year for the
     long-period constituents; shorter records still fit, with those
     absorbing less well.
     */
    static func fit(times timesMillis: [Int64], heights: [Double], nodal: Bool = true) throws -> TideHarmonics {
        let cons = constituents
        guard timesMillis.count == heights.count && timesMillis.count > 2 * cons.count + 1 else {
            throw TideError("Not enough tide data to analyse")
        }
        let epoch = timesMillis[timesMillis.count / 2]
        let n = 1 + 2 * cons.count
        var ata = [Double](repeating: 0, count: n * n)
        var atb = [Double](repeating: 0, count: n)
        var row = [Double](repeating: 0, count: n)
        var factors = Nodal.Factors()
        var factorsDay = Int64.min
        let dayMs = Int64(TideHarmonics.dayMs)
        for k in 0..<timesMillis.count {
            let hours = Double(timesMillis[k] - epoch) / TideHarmonics.hourMs
            let day = TideMath.floorDiv(timesMillis[k], dayMs)
            if nodal && day != factorsDay {
                Nodal.at(timesMillis[k], into: &factors)
                factorsDay = day
            }
            row[0] = 1.0
            for i in 0..<cons.count {
                let kind = cons[i].nodal
                let f = nodal ? factors.f(kind) : 1.0
                let u = nodal ? factors.u(kind) : 0.0
                let angle = TideMath.rad(cons[i].degreesPerHour * hours + u)
                row[1 + 2 * i] = f * cos(angle)
                row[2 + 2 * i] = f * sin(angle)
            }
            let hk = heights[k]
            for a in 0..<n {
                let ra = row[a]
                atb[a] += ra * hk
                let base = a * n
                for b in a..<n { ata[base + b] += ra * row[b] }
            }
        }
        for a in 0..<n { for b in 0..<a { ata[a * n + b] = ata[b * n + a] } }
        let x = solve(ata, atb, n)
        return TideHarmonics(
            mean: x[0],
            cosines: (0..<cons.count).map { x[1 + 2 * $0] },
            sines: (0..<cons.count).map { x[2 + 2 * $0] },
            epochMillis: epoch,
            nodal: nodal
        )
    }

    /// Gaussian elimination with partial pivoting; tiny ridge for near-singular fits. `matrix` is n×n row-major.
    private static func solve(_ matrix: [Double], _ rhs: [Double], _ n: Int) -> [Double] {
        var a: [[Double]] = (0..<n).map { i in
            var r = Array(matrix[(i * n)..<(i * n + n)])
            r[i] += 1e-9
            return r
        }
        var b = rhs
        for col in 0..<n {
            var pivot = col
            if col + 1 < n {
                for r in (col + 1)..<n where abs(a[r][col]) > abs(a[pivot][col]) { pivot = r }
            }
            if pivot != col {
                a.swapAt(col, pivot)
                b.swapAt(col, pivot)
            }
            let p = a[col][col]
            if abs(p) < 1e-12 { continue }
            if col + 1 < n {
                for r in (col + 1)..<n {
                    let f = a[r][col] / p
                    if f == 0.0 { continue }
                    for c in col..<n { a[r][c] -= f * a[col][c] }
                    b[r] -= f * b[col]
                }
            }
        }
        var x = [Double](repeating: 0, count: n)
        for r in stride(from: n - 1, through: 0, by: -1) {
            var sum = b[r]
            if r + 1 < n { for c in (r + 1)..<n { sum -= a[r][c] * x[c] } }
            x[r] = abs(a[r][r]) < 1e-12 ? 0.0 : sum / a[r][r]
        }
        return x
    }
}
