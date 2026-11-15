import XCTest
@testable import SeaBathy

final class TideSunMoonTests: XCTestCase {
    private func utc(_ text: String) -> Int64 {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return TideMath.millis(f.date(from: text)!)
    }

    func testSydneySunriseAndSunsetAtMidwinter() {
        // 21 June 2024: sunrise 07:00, sunset 16:54 AEST (published times).
        let t = TideSunMoon.sunTimes(utc("2024-06-21T02:00:00Z"), lat: -33.87, lng: 151.21)!
        XCTAssertEqual(Double(t.rise), Double(utc("2024-06-20T21:00:00Z")), accuracy: 3 * 60_000.0)
        XCTAssertEqual(Double(t.set), Double(utc("2024-06-21T06:54:00Z")), accuracy: 3 * 60_000.0)
    }

    func testNoSunriseInPolarNight() {
        XCTAssertNil(TideSunMoon.sunTimes(utc("2024-12-21T12:00:00Z"), lat: 78.2, lng: 15.6))
    }

    func testMoonPhasesMatchKnownDates() {
        XCTAssertEqual(TideSunMoon.phaseName(utc("2024-06-22T01:08:00Z")), "Full moon")
        XCTAssertEqual(TideSunMoon.phaseName(utc("2024-07-05T22:57:00Z")), "New moon")
        XCTAssertEqual(TideSunMoon.phaseName(utc("2024-07-13T22:49:00Z")), "First quarter")
        XCTAssertEqual(TideSunMoon.springOrNeap(utc("2024-06-23T12:00:00Z")), "spring tides")
        XCTAssertEqual(TideSunMoon.springOrNeap(utc("2024-07-15T12:00:00Z")), "neap tides")
    }

    func testSavePeriodsEndAtTheEndOfTheDay() {
        let zone = TimeZone(identifier: "Australia/Brisbane")!
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 27, hour: 15, minute: 30))!
        XCTAssertEqual(TideSavePeriod.week.endFrom(now, timeZone: zone),
                       cal.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: 23, minute: 59, second: 59))!)
        XCTAssertEqual(TideSavePeriod.twoYears.endFrom(now, timeZone: zone),
                       cal.date(from: DateComponents(year: 2028, month: 10, day: 27, hour: 23, minute: 59, second: 59))!)
    }
}
