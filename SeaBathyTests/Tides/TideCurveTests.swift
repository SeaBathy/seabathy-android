import XCTest
@testable import SeaBathy

final class TideCurveTests: XCTestCase {
    private let hour = TideCurve.hourMs

    /// A 12.42 h semidiurnal tide, amplitude 1.2 m, sampled hourly for 3 days.
    private func semidiurnal() -> TideCurve {
        let period = 12.42
        let times = (0..<72).map { Int64($0) * hour }
        let heights = (0..<72).map { 1.2 * cos(2 * Double.pi * Double($0) / period) }
        return TideCurve(times: times, heights: heights)
    }

    func testHeightsBetweenSamplesFollowTheCurve() {
        let curve = semidiurnal()
        let t = Int64(2.5 * Double(hour))
        let expected = 1.2 * cos(2 * Double.pi * 2.5 / 12.42)
        XCTAssertEqual(curve.heightAt(t)!, expected, accuracy: 0.02)
        XCTAssertNil(curve.heightAt(-1))
    }

    func testHighsAndLowsLandAtTheRightTimes() {
        let curve = semidiurnal()
        let extremes = curve.extremes(from: hour, to: 30 * hour)
        // Lows at 6.21 h and 18.63 h, highs at 12.42 h and 24.84 h.
        XCTAssertEqual(extremes.count, 4)
        XCTAssertFalse(extremes[0].isHigh)
        XCTAssertEqual(Double(extremes[0].timeMillis) / Double(hour), 6.21, accuracy: 0.1)
        XCTAssertEqual(extremes[0].heightM, -1.2, accuracy: 0.03)
        XCTAssertTrue(extremes[1].isHigh)
        XCTAssertEqual(Double(extremes[1].timeMillis) / Double(hour), 12.42, accuracy: 0.1)
        XCTAssertEqual(extremes[1].heightM, 1.2, accuracy: 0.03)
    }

    func testRisingAndFalling() {
        let curve = semidiurnal()
        XCTAssertTrue(curve.rateMPerHour(3 * hour)! < 0) // falling towards the low
        XCTAssertTrue(curve.rateMPerHour(9 * hour)! > 0) // rising towards the high
    }

    func testTideAboveDatumForEachMode() {
        let curve = semidiurnal()
        let forecast = TideForecast(requestLat: 0, requestLng: 0, modelLat: 0, modelLng: 0, fetchedMillis: 0, curve: curve)
        XCTAssertEqual(TideSettings(datum: .meanSeaLevel).heightAboveDatum(forecast, 0)!, 1.2, accuracy: 1e-9)
        XCTAssertEqual(TideSettings(datum: .chartDatum, chartDatumAuto: false, chartDatumBelowMslM: 1.2).heightAboveDatum(forecast, 0)!, 2.4, accuracy: 1e-9)
        XCTAssertEqual(TideSettings(mode: .manual, datum: .chartDatum, manualHeightM: 0.7).heightAboveDatum(forecast, 0)!, 0.7, accuracy: 1e-9)
        XCTAssertNil(TideSettings(mode: .off).heightAboveDatum(forecast, 0))
        // Chart-datum survey before chart datum is known here: depths stay as charted.
        XCTAssertNil(TideSettings(datum: .chartDatum).heightAboveDatum(forecast, 0))
    }

    func testHeightsAreShownAboveTheLowestTideOnceItIsKnown() {
        let curve = semidiurnal()
        let low = curve.extremes(from: 0, to: 12 * hour).first { !$0.isHigh }!
        let unknown = TideForecast(requestLat: 0, requestLng: 0, modelLat: 0, modelLng: 0, fetchedMillis: 0, curve: curve)
        let known = TideForecast(requestLat: 0, requestLng: 0, modelLat: 0, modelLng: 0, fetchedMillis: 0, curve: curve, lowestTideM: -1.5)
        let s = TideSettings()
        // From mean sea level the low reads below zero; above LAT it doesn't.
        XCTAssertTrue(s.shownHeight(unknown, low.timeMillis)! < 0)
        XCTAssertTrue(s.shownHeight(known, low.timeMillis)! >= 0)
        XCTAssertEqual(s.shownHeight(known, 0)!, 1.2 + 1.5, accuracy: 1e-9)
        XCTAssertTrue(s.showsChartDatum(known))
        // Depth correction for a mean-sea-level survey is unchanged by how it's shown.
        XCTAssertEqual(s.heightAboveDatum(known, 0)!, 1.2, accuracy: 1e-9)
        // A chart-datum survey adds the height above LAT.
        var chart = s
        chart.datum = .chartDatum
        XCTAssertEqual(chart.heightAboveDatum(known, 0)!, 2.7, accuracy: 1e-9)
        // A tide-table height (above LAT) on a mean-sea-level survey.
        var manual = s
        manual.mode = .manual
        manual.manualHeightM = 0.5
        XCTAssertEqual(manual.heightAboveDatum(known, 0)!, 0.5 - 1.5, accuracy: 1e-9)
        // A typed chart datum wins over the worked-out one.
        var typed = s
        typed.chartDatumAuto = false
        typed.chartDatumBelowMslM = 2.0
        XCTAssertEqual(typed.shownHeight(known, 0)!, 1.2 + 2.0, accuracy: 1e-9)
    }
}
