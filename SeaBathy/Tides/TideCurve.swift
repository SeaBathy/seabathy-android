import Foundation

/// A high or low water.
struct TideExtreme: Equatable {
    let timeMillis: Int64
    let heightM: Double
    let isHigh: Bool
}

/**
 Sea level over time from evenly or unevenly spaced samples (hourly from
 the model), smoothed with a cubic Hermite curve so the height, trend and
 the times of high and low water fall between samples. Heights are metres
 above the model's mean sea level.
 */
struct TideCurve {
    private let timesMillis: [Int64]
    private let heightsM: [Double]

    static let hourMs: Int64 = 3_600_000
    private static let rateStepMs: Int64 = 10 * 60_000
    private static let scanStepMs: Int64 = 3 * 60_000
    private static let lookaheadMs: Int64 = 7 * 24 * hourMs

    init(times: [Int64], heights: [Double]) {
        precondition(times.count == heights.count, "times and heights differ in length")
        timesMillis = times
        heightsM = heights
    }

    var startMillis: Int64? { timesMillis.first }
    var endMillis: Int64? { timesMillis.last }

    func covers(_ timeMillis: Int64) -> Bool {
        guard timesMillis.count >= 2, let first = timesMillis.first, let last = timesMillis.last else { return false }
        return timeMillis >= first && timeMillis <= last
    }

    /// Height at `timeMillis`, or nil outside the data.
    func heightAt(_ timeMillis: Int64) -> Double? {
        if !covers(timeMillis) { return nil }
        var i = binarySearch(timeMillis)
        if i >= 0 { return heightsM[i] }
        i = -i - 2 // index of the sample before timeMillis
        let t0 = Double(timesMillis[i])
        let t1 = Double(timesMillis[i + 1])
        let span = t1 - t0
        let u = (Double(timeMillis) - t0) / span
        let m0 = slope(i) * span
        let m1 = slope(i + 1) * span
        let u2 = u * u
        let u3 = u2 * u
        let p0 = (2 * u3 - 3 * u2 + 1) * heightsM[i] + (u3 - 2 * u2 + u) * m0
        let p1 = (-2 * u3 + 3 * u2) * heightsM[i + 1] + (u3 - u2) * m1
        return p0 + p1
    }

    /// Rate of change in metres per hour; positive = rising.
    func rateMPerHour(_ timeMillis: Int64) -> Double? {
        guard let a = heightAt(timeMillis - TideCurve.rateStepMs) ?? heightAt(timeMillis) else { return nil }
        guard let b = heightAt(timeMillis + TideCurve.rateStepMs) ?? heightAt(timeMillis) else { return nil }
        let hours = (2.0 * Double(TideCurve.rateStepMs)) / Double(TideCurve.hourMs)
        return (b - a) / hours
    }

    /// Highs and lows between `fromMillis` and `toMillis`, in time order.
    func extremes(from fromMillis: Int64, to toMillis: Int64) -> [TideExtreme] {
        guard let first = startMillis, let last = endMillis else { return [] }
        let step = TideCurve.scanStepMs
        let start = max(fromMillis, first)
        let end = min(toMillis, last)
        if end - start < 2 * step { return [] }
        var out: [TideExtreme] = []
        var tPrev = start
        guard var hPrev = heightAt(tPrev) else { return [] }
        var t = start + step
        guard var h = heightAt(t) else { return [] }
        while t + step <= end {
            let tNext = t + step
            guard let hNext = heightAt(tNext) else { break }
            let isHigh = h >= hPrev && h > hNext
            let isLow = h <= hPrev && h < hNext
            if isHigh || isLow {
                // The turning point lies between the scan steps: fit a parabola through
                // the three samples, so the time doesn't depend on where the scan started.
                let bend = hPrev - 2 * h + hNext
                let shift = bend == 0.0 ? 0.0 : min(max(0.5 * (hPrev - hNext) / bend, -0.5), 0.5)
                let tExact = t + Int64(shift * Double(step))
                out.append(TideExtreme(timeMillis: tExact, heightM: heightAt(tExact) ?? h, isHigh: isHigh))
            }
            tPrev = t; hPrev = h
            t = tNext; h = hNext
        }
        _ = tPrev
        return out
    }

    func nextExtremes(from fromMillis: Int64, count: Int) -> [TideExtreme] {
        Array(extremes(from: fromMillis, to: fromMillis + TideCurve.lookaheadMs).prefix(count))
    }

    /// Like Kotlin's LongArray.binarySearch: the index, or -(insertion point) - 1.
    private func binarySearch(_ key: Int64) -> Int {
        var lo = 0
        var hi = timesMillis.count - 1
        while lo <= hi {
            let mid = (lo + hi) >> 1
            let v = timesMillis[mid]
            if v < key { lo = mid + 1 } else if v > key { hi = mid - 1 } else { return mid }
        }
        return -(lo + 1)
    }

    private func slope(_ i: Int) -> Double {
        let n = timesMillis.count
        if n < 2 { return 0.0 }
        if i == 0 { return (heightsM[1] - heightsM[0]) / Double(timesMillis[1] - timesMillis[0]) }
        if i == n - 1 { return (heightsM[i] - heightsM[i - 1]) / Double(timesMillis[i] - timesMillis[i - 1]) }
        return (heightsM[i + 1] - heightsM[i - 1]) / Double(timesMillis[i + 1] - timesMillis[i - 1])
    }
}
