import XCTest
@testable import SeaBathy

final class TideHarmonicsTests: XCTestCase {
    private let hour: Int64 = 3_600_000

    /// 2024-01-01 UTC: a real date, so the nodal cycle is where it really is.
    private let start: Int64 = 1_704_067_200_000

    /// Longitude of the moon's node, radians (Meeus).
    private func node(_ t: Int64) -> Double {
        let centuries = (Double(t) / 86_400_000.0 + 2440587.5 - 2451545.0) / 36525.0
        return (125.0445479 - 1934.1362891 * centuries) * Double.pi / 180
    }

    private func c(_ h: Double, _ speed: Double, _ amp: Double, _ phaseDeg: Double, _ f: Double = 1.0, _ uDeg: Double = 0.0) -> Double {
        f * amp * cos((speed * h - phaseDeg + uDeg) * Double.pi / 180)
    }

    /// A made-up tide: M2 1.2 m, S2 0.4 m, K1 0.35 m, O1 0.2 m, mean 0.05 m,
    /// with the lunar ones changing over the nodal cycle as real tides do.
    private func truth(_ t: Int64) -> Double {
        let h = Double(t - start) / 3_600_000.0
        let n = node(t)
        return 0.05 +
            c(h, 28.9841042, 1.2, 40.0, 1.0004 - 0.0373 * cos(n), -2.14 * sin(n)) +
            c(h, 30.0, 0.4, 75.0) +
            c(h, 15.0410686, 0.35, 200.0, 1.0060 + 0.1150 * cos(n), -8.86 * sin(n)) +
            c(h, 13.9430356, 0.2, 10.0, 1.0089 + 0.1871 * cos(n), 10.80 * sin(n))
    }

    func testAYearOfDataPredictsTwoYearsAhead() throws {
        let times = (0..<(24 * 365)).map { start + Int64($0) * hour }
        // A little noise, like a real model has.
        let heights = (0..<times.count).map { truth(times[$0]) + 0.02 * sin(Double($0) * 1.7) }
        let fit = try TideHarmonics.fit(times: times, heights: heights)
        XCTAssertEqual(fit.amplitude("S2"), 0.4, accuracy: 0.01)
        XCTAssertEqual(fit.mean, 0.05, accuracy: 0.01)

        // Two years beyond the data.
        let from = start + 24 * 365 * 3 * hour
        let predicted = fit.heights(start: from, count: 24 * 30)
        var worst = 0.0
        for k in 0..<predicted.count { worst = max(worst, abs(predicted[k] - truth(from + Int64(k) * hour))) }
        XCTAssertTrue(worst < 0.03, "worst error \(worst)")
    }

    func testLowestAstronomicalTideIsTheSpringsLowOverTheNodalCycle() {
        // M2 1 m, S2 0.4 m, K1 0.3 m, O1 0.2 m around a 0.1 m mean.
        let n = TideHarmonics.constituents.count
        var cosines = [Double](repeating: 0, count: n)
        func set(_ name: String, _ amp: Double) {
            cosines[TideHarmonics.constituents.firstIndex { $0.name == name }!] = amp
        }
        set("M2", 1.0); set("S2", 0.4); set("K1", 0.3); set("O1", 0.2)
        let h = TideHarmonics(mean: 0.1, cosines: cosines, sines: [Double](repeating: 0, count: n), epochMillis: 0)
        let lat = h.lowestAstronomical(from: 1_700_000_000_000)
        // Below an ordinary springs low, above all four at their lowest together.
        XCTAssertTrue(lat < 0.1 - 1.4 && lat > 0.1 - 2.05, "LAT \(lat)")
        // The same as a brute-force search of all 19 years every 5 minutes.
        let all = h.heights(start: 1_700_000_000_000, count: Int(19 * 365.25 * 24 * 12), stepMillis: 5 * 60_000)
        let minimum = all.min()!
        XCTAssertEqual(minimum, lat, accuracy: 0.005)
        XCTAssertTrue(minimum >= lat - 1e-6)
    }

    func testHeightsInABatchMatchOneAtATime() throws {
        let times = (0..<(24 * 60)).map { start + Int64($0) * hour }
        let fit = try TideHarmonics.fit(times: times, heights: times.map { truth($0) })
        let from = start + 1000 * hour + 1234
        let batch = fit.heights(start: from, count: 24 * 400)
        for k in stride(from: 0, to: batch.count, by: 97) {
            XCTAssertEqual(fit.heightAt(from + Int64(k) * hour), batch[k], accuracy: 1e-6)
        }
    }

    func testTidesSavedBeforeNodalCorrectionsStillPredictAsTheyDid() throws {
        let times = (0..<(24 * 120)).map { start + Int64($0) * hour }
        let plain = try TideHarmonics.fit(times: times, heights: times.map { truth($0) }, nodal: false)
        let reloaded = TideHarmonics(mean: plain.mean, cosines: plain.cosines, sines: plain.sines, epochMillis: plain.epochMillis, nodal: false)
        XCTAssertEqual(plain.heightAt(start + 50 * hour), reloaded.heightAt(start + 50 * hour), accuracy: 1e-12)
    }

    func testCurveGivesHourlyHeightsOverARange() throws {
        let times = (0..<(24 * 120)).map { start + Int64($0) * hour }
        let fit = try TideHarmonics.fit(times: times, heights: times.map { truth($0) })
        let curve = fit.curve(from: start + 500 * hour + 1234, to: start + 600 * hour)
        XCTAssertTrue(curve.covers(start + 550 * hour))
        XCTAssertEqual(truth(start + 550 * hour), curve.heightAt(start + 550 * hour)!, accuracy: 0.05)
    }

    func testSavedTidesRoundTripThroughJson() throws {
        let times = (0..<(24 * 120)).map { start + Int64($0) * hour }
        let fit = try TideHarmonics.fit(times: times, heights: times.map { truth($0) })
        let entry = TideSavedEntry(harmonics: fit, untilMillis: start + 1000 * hour, requestLat: -16.9, requestLng: 145.8,
                                   modelLat: -16.85, modelLng: 145.9, savedMillis: start)
        let back = try TideStoreIO.parseSaved(TideStoreIO.savedJson(entry))
        XCTAssertEqual(back.untilMillis, entry.untilMillis)
        XCTAssertTrue(back.harmonics.nodal)
        XCTAssertEqual(back.harmonics.heightAt(start + 77 * hour), fit.heightAt(start + 77 * hour), accuracy: 1e-9)
    }
}
