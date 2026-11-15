import XCTest
@testable import SeaBathy

final class ConditionsTests: XCTestCase {
    private let hour = ConditionsForecastGrid.hourMs

    private func iso(_ text: String) -> Int64 {
        let f = ISO8601DateFormatter()
        return Int64(f.date(from: text)!.timeIntervalSince1970 * 1000)
    }

    func testGridBlendsBetweenPointsAndLeavesLandBlank() {
        let grid = ConditionsGeoGrid(lats: [-20, -19], lngs: [149, 150])
        let values: [Float] = [10, 20, 30, .nan]
        XCTAssertEqual(grid.sample(values, offset: 0, lat: -20, lng: 149), 10, accuracy: 1e-4)
        XCTAssertEqual(grid.sample(values, offset: 0, lat: -20, lng: 149.5), 15, accuracy: 1e-4)
        // Nearest point is land: blank, not smeared onto the shore.
        XCTAssertTrue(grid.sample(values, offset: 0, lat: -19.1, lng: 149.9).isNaN)
        // When a finer coastline does the masking, the sea corners reach the shore.
        XCTAssertEqual(grid.sample(values, offset: 0, lat: -19.1, lng: 149.9, strict: false), 20, accuracy: 6)
        XCTAssertTrue(grid.sample(values, offset: 0, lat: -10, lng: 149.5).isNaN)
    }

    func testEvenAndUnevenGridsBlendTheSame() {
        let even = ConditionsGeoGrid(lats: [0, 1, 2], lngs: [10, 11, 12])
        let uneven = ConditionsGeoGrid(lats: [0, 1.0000001, 2], lngs: [10, 11, 12])
        let values = (0..<9).map { Float($0) }
        for (lat, lng) in [(0.0, 10.0), (0.5, 10.5), (1.25, 11.75), (2.0, 12.0), (1.9, 10.1)] {
            XCTAssertEqual(even.sample(values, offset: 0, lat: lat, lng: lng), uneven.sample(values, offset: 0, lat: lat, lng: lng), accuracy: 1e-4)
        }
        XCTAssertEqual(even.sample(values, offset: 0, lat: 1, lng: 11), 4, accuracy: 1e-6)
    }

    func testFlowDirectionsAreCompassBearings() {
        let parts = ConditionsFlow.parts(speed: 2, towardsDegrees: 90)
        XCTAssertEqual(parts.east, 2, accuracy: 1e-9)
        XCTAssertEqual(parts.north, 0, accuracy: 1e-9)
        let flow = ConditionsFlow.of(east: -1, north: -1)
        XCTAssertEqual(flow.towardsDegrees, 225, accuracy: 1e-9)
        XCTAssertEqual(flow.fromDegrees, 45, accuracy: 1e-9)
        XCTAssertEqual(ConditionsLogic.compass(flow.towardsDegrees), "SW")
        XCTAssertEqual(ConditionsLogic.compass(359), "N")
    }

    private func marineJson(_ points: [(Double, Double, Double)], speeds: [Double]) -> String {
        let items: [String] = points.map { p -> String in
            let parts: [String] = [
                #"{"latitude":\#(p.0),"longitude":\#(p.1),"hourly":{"time":[0,3600],"#,
                #""ocean_current_velocity":[\#(speeds[0]),\#(speeds[1])],"ocean_current_direction":[\#(p.2),\#(p.2)],"#,
                #""wave_height":[1.0,1.2],"wave_direction":[90,90],"wave_period":[7,7],"#,
                #""swell_wave_height":[0.5,null],"swell_wave_direction":[90,90],"swell_wave_period":[10,10],"#,
                #""sea_surface_temperature":[26.5,26.7]}}"#
            ]
            return parts.joined()
        }
        return "[" + items.joined(separator: ",") + "]"
    }

    func testOpenMeteoPointsGoOnTheGridAndInlandPointsHaveWindButNoSeaValues() throws {
        let grid = ConditionsGeoGrid(lats: [-20.0, -19.9], lngs: [149.0, 149.1])
        let requested: [ConditionsClient.Point] = [(-20.0, 149.0), (-20.0, 149.1), (-19.9, 149.0), (-19.9, 149.1)]
        // The last point was on land: Open-Meteo moved it 30 km out to sea.
        let marine = try ConditionsClient.parseOpenMeteo(marineJson(
            [(-20.0, 149.0, 90.0), (-20.0, 149.1, 90.0), (-19.9, 149.0, 90.0), (-20.1, 149.35, 90.0)], speeds: [1.0, 2.0]))
        let wind = try ConditionsClient.parseOpenMeteo("[" + requested.map { p in
            #"{"latitude":\#(p.lat),"longitude":\#(p.lng),"hourly":{"time":[0,3600],"wind_speed_10m":[5,5],"wind_direction_10m":[180,180],"wind_gusts_10m":[8,8]}}"#
        }.joined(separator: ",") + "]")
        let f = try ConditionsClient.assemble(grid: grid, requested: requested, marine: marine, weather: wind)
        let current = try XCTUnwrap(f.vector(.currentU, .currentV, lat: -20, lng: 149, time: 0))
        XCTAssertEqual(current.speed, 1.0, accuracy: 1e-5)
        XCTAssertEqual(current.towardsDegrees, 90, accuracy: 1e-3)
        XCTAssertEqual(try XCTUnwrap(f.vector(.currentU, .currentV, lat: -20, lng: 149, time: hour / 2)).speed, 1.5, accuracy: 1e-5)
        // Waves "from 90" go west.
        XCTAssertEqual(try XCTUnwrap(f.vector(.waveU, .waveV, lat: -20, lng: 149, time: 0)).towardsDegrees, 270, accuracy: 1e-3)
        // Wind from the south blows north.
        let w = try XCTUnwrap(f.vector(.windU, .windV, lat: -20, lng: 149, time: 0))
        XCTAssertEqual(w.towardsDegrees, 0, accuracy: 1e-3)
        XCTAssertEqual(w.fromDegrees, 180, accuracy: 1e-3)
        XCTAssertTrue(f.sample(.currentU, lat: -19.9, lng: 149.1, time: 0).isNaN)
        XCTAssertEqual(try XCTUnwrap(f.vector(.windU, .windV, lat: -19.9, lng: 149.1, time: 0)).speed, 5, accuracy: 1e-4)
        // A missing hour is blank, not zero.
        XCTAssertEqual(f.sample(.swellHeight, lat: -20, lng: 149, time: 0), 0.5, accuracy: 1e-5)
        XCTAssertTrue(f.sample(.swellHeight, lat: -20, lng: 149, time: hour).isNaN)
        XCTAssertEqual(f.sample(.swellHeight, lat: -20, lng: 149, time: hour / 2), 0.5, accuracy: 1e-5)
        XCTAssertTrue(f.sample(.seaTemp, lat: -20, lng: 149, time: 10 * hour).isNaN)
    }

    func testWaterNearTheCoastKeepsTheNearestOceanCellsValues() throws {
        let grid = ConditionsGeoGrid(lats: [-21.10], lngs: [149.85])
        let marine = try ConditionsClient.parseOpenMeteo(
            #"{"latitude":-21.201668,"longitude":149.85002,"hourly":{"time":[0],"ocean_current_velocity":[0.8],"ocean_current_direction":[0]}}"#)
        let f = try ConditionsClient.assemble(grid: grid, requested: [(-21.10, 149.85)], marine: marine, weather: nil)
        XCTAssertEqual(try XCTUnwrap(f.vector(.currentU, .currentV, lat: -21.10, lng: 149.85, time: 0)).speed, 0.8, accuracy: 1e-4)
    }

    func testTheSatelliteCoastlineReachesTheShore() {
        let grid = ConditionsGeoGrid(lats: [-20.0, -19.99], lngs: [149.0, 149.01])
        let sst = ConditionsSatelliteGrid(kind: .seaTemp, grid: grid, dateMillis: 0, values: [.nan, 27, .nan, 27])
        let area = ConditionsArea(id: "a", name: "a", south: -20, west: 149, north: -19.99, east: 149.01, fetchedMillis: 0,
                                  forecast: nil, satellites: [.seaTemp: sst])
        XCTAssertEqual(area.isSea(lat: -19.995, lng: 149.003), true)
        XCTAssertEqual(area.isSea(lat: -20.0, lng: 149.0), false)
    }

    func testWindFromACoarserGridIsBlendedOntoTheSeaPoints() throws {
        let grid = ConditionsGeoGrid(lats: [-20.0, -19.95, -19.9], lngs: [149.0, 149.05, 149.1])
        var requested: [ConditionsClient.Point] = []
        for r in 0..<3 { for c in 0..<3 { requested.append((grid.lats[r], grid.lngs[c])) } }
        let marine = try ConditionsClient.parseOpenMeteo("[" + requested.map { p in
            #"{"latitude":\#(p.lat),"longitude":\#(p.lng),"hourly":{"time":[0],"ocean_current_velocity":[0.5],"ocean_current_direction":[0]}}"#
        }.joined(separator: ",") + "]")
        let windGrid = ConditionsGeoGrid(lats: [-20.0, -19.9], lngs: [149.0, 149.1])
        let wind = try ConditionsClient.parseOpenMeteo("[" + [4, 8, 4, 8].map { speed in
            #"{"latitude":0,"longitude":0,"hourly":{"time":[0],"wind_speed_10m":[\#(speed)],"wind_direction_10m":[270],"wind_gusts_10m":[\#(speed + 2)]}}"#
        }.joined(separator: ",") + "]")
        let f = try ConditionsClient.assemble(grid: grid, requested: requested, marine: marine, weather: wind, windGrid: windGrid)
        let middle = try XCTUnwrap(f.vector(.windU, .windV, lat: -19.95, lng: 149.05, time: 0))
        XCTAssertEqual(middle.speed, 6, accuracy: 1e-4)
        XCTAssertEqual(middle.towardsDegrees, 90, accuracy: 1e-3)
        XCTAssertEqual(f.sample(.gust, lat: -19.95, lng: 149.05, time: 0), 8, accuracy: 1e-4)
    }

    func testOldDataIsFlaggedOfflineSooner() {
        let now: Int64 = 100 * 24 * 3_600_000
        XCTAssertNil(ConditionsLogic.staleNote(fetchedMillis: now - 10 * 60_000, nowMillis: now, online: false))
        XCTAssertTrue(ConditionsLogic.staleNote(fetchedMillis: now - 3 * 3_600_000, nowMillis: now, online: false)!.hasPrefix("Offline: saved data from 3 h ago"))
        XCTAssertNil(ConditionsLogic.staleNote(fetchedMillis: now - 3 * 3_600_000, nowMillis: now, online: true))
        XCTAssertEqual(ConditionsLogic.staleNote(fetchedMillis: now - 13 * 3_600_000, nowMillis: now, online: true), "Old data: downloaded 13 h ago")
        XCTAssertTrue(ConditionsLogic.staleNote(fetchedMillis: now - 3 * 24 * 3_600_000, nowMillis: now, online: false)!.contains("3 days ago"))
    }

    func testTheSpotsAreaReaches200KmEachWay() {
        let b = ConditionsLogic.boxAround(lat: -20, lng: 149, radiusKm: 200)
        XCTAssertEqual(b[0], -20 - 200 / 111.0, accuracy: 1e-9)
        XCTAssertEqual(b[2], -20 + 200 / 111.0, accuracy: 1e-9)
        XCTAssertTrue(b[3] - b[1] > b[2] - b[0])
        let area = ConditionsArea(id: "spot", name: "x", south: b[0], west: b[1], north: b[2], east: b[3], fetchedMillis: 0, forecast: nil, satellites: [:])
        XCTAssertTrue(area.middleContains(lat: -20, lng: 149))
        XCTAssertTrue(area.contains(lat: -20 + 150 / 111.0, lng: 149))
        XCTAssertFalse(area.middleContains(lat: -20 + 150 / 111.0, lng: 149))
    }

    func testFlowLinesFollowTheMapLayerUnlessSet() {
        XCTAssertEqual(ConditionsArrowChoice.same.resolve(.wind), .wind)
        XCTAssertEqual(ConditionsArrowChoice.same.resolve(.seaTempSatellite), .current)
        XCTAssertEqual(ConditionsArrowChoice.same.resolve(.swell), .swell)
        XCTAssertEqual(ConditionsArrowChoice.wind.resolve(.current), .wind)
        XCTAssertNil(ConditionsArrowChoice.same.fields)
    }

    func testASingleOpenMeteoLocationIsAnObject() throws {
        let one = try ConditionsClient.parseOpenMeteo(#"{"latitude":-20.0,"longitude":149.0,"hourly":{"time":[0],"wave_height":[null]}}"#)
        XCTAssertEqual(one.count, 1)
        XCTAssertTrue(one[0].values["wave_height"]![0].isNaN)
    }

    func testErddapTablesBecomeAGridEitherWayUp() throws {
        let north = """
        time,altitude,latitude,longitude,chlor_a
        UTC,m,degrees_north,degrees_east,mg m^-3
        2026-10-27T12:00:00Z,0.0,-19.4583,148.9583,NaN
        2026-10-27T12:00:00Z,0.0,-19.4583,149.0417,1.48
        2026-10-27T12:00:00Z,0.0,-19.5417,148.9583,0.42
        2026-10-27T12:00:00Z,0.0,-19.5417,149.0417,0.30

        """
        let chl = try ConditionsClient.parseErddapCsv(north, kind: .chlorophyll)
        XCTAssertEqual(chl.grid.rows, 2)
        XCTAssertEqual(chl.grid.south, -19.5417, accuracy: 1e-9)
        XCTAssertEqual(chl.sample(lat: -19.5417, lng: 148.9583), 0.42, accuracy: 1e-5)
        XCTAssertEqual(chl.sample(lat: -19.4583, lng: 149.0417), 1.48, accuracy: 1e-5)
        XCTAssertEqual(chl.dateMillis, iso("2026-10-27T12:00:00Z"))

        let sst = try ConditionsClient.parseErddapCsv(
            "time,latitude,longitude,analysed_sst\nUTC,degrees_north,degrees_east,degree_C\n" +
                "2026-10-28T09:00:00Z,-20.5,149.0,NaN\n2026-10-28T09:00:00Z,-20.5,149.01,27.4\n", kind: .seaTemp)
        XCTAssertEqual(sst.sample(lat: -20.5, lng: 149.01), 27.4, accuracy: 1e-5)
    }

    func testAnAllLandErddapAnswerIsAnError() {
        XCTAssertThrowsError(try ConditionsClient.parseErddapCsv(
            "time,latitude,longitude,analysed_sst\nUTC,degrees_north,degrees_east,degree_C\n2026-10-28T09:00:00Z,-20.5,149.0,NaN\n", kind: .seaTemp))
    }

    func testAreasSaveAndLoadIntact() throws {
        let grid = ConditionsGeoGrid(lats: [-20.0, -19.9], lngs: [149.0, 149.1])
        let forecast = ConditionsForecastGrid(grid: grid, times: [0, hour], fields: [.waveHeight: [1, 2, .nan, 4, 5, 6, 7, 8]])
        let sat = ConditionsSatelliteGrid(kind: .seaTemp, grid: grid, dateMillis: 123, values: [25, 26, .nan, 27])
        let area = ConditionsArea(id: "id1", name: "Inshore reef", south: -20, west: 149, north: -19.9, east: 149.1, fetchedMillis: 99,
                                  forecast: forecast, satellites: [.seaTemp: sat], belowDepthM: 200)
        let back = try ConditionsStore.read(try ConditionsStore.write(area))
        XCTAssertEqual(back.name, "Inshore reef")
        XCTAssertEqual(back.fetchedMillis, 99)
        XCTAssertEqual(back.belowDepthM, 200)
        XCTAssertEqual(back.forecast?.endMillis, hour)
        XCTAssertEqual(back.forecast?.fields[.waveHeight]?[4], 5)
        XCTAssertTrue(back.forecast!.fields[.waveHeight]![2].isNaN)
        XCTAssertEqual(back.satellites[.seaTemp]?.dateMillis, 123)
        XCTAssertEqual(back.satellites[.seaTemp]?.values[3], 27)
    }

    func testANonConditionsFileIsRefused() {
        XCTAssertThrowsError(try ConditionsStore.read(Data([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])))
    }

    func testStoreListsRenamesAndDeletes() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("conditions-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ConditionsStore(dir: dir)
        let grid = ConditionsGeoGrid(lats: [-20.0, -19.9], lngs: [149.0, 149.1])
        let sat = ConditionsSatelliteGrid(kind: .chlorophyll, grid: grid, dateMillis: 5, values: [0.1, 0.2, 0.3, 0.4])
        try store.save(ConditionsArea(id: "a", name: "Reef", south: -20, west: 149, north: -19.9, east: 149.1, fetchedMillis: 10,
                                      forecast: nil, satellites: [.chlorophyll: sat]))
        // Nothing read yet: the main thread gets nothing rather than waiting on the disk.
        XCTAssertNil(ConditionsStore(dir: dir).cachedSummaries())
        let listed = try XCTUnwrap(store.summaries().first)
        XCTAssertEqual(store.summaries().count, 1)
        XCTAssertEqual(listed, store.cachedSummaries()?.first)
        XCTAssertTrue(listed.sameBoundsAs(-20, 149, -19.9, 149.1))
        XCTAssertEqual(listed.name, "Reef")
        XCTAssertNil(listed.forecastUntilMillis)
        XCTAssertEqual(listed.satellites, [.chlorophyll])
        _ = try store.rename("a", name: "Outer reef")
        XCTAssertEqual(store.summaries().first?.name, "Outer reef")
        XCTAssertEqual(ConditionsStore(dir: dir).load("a")?.name, "Outer reef")
        store.delete("a")
        XCTAssertTrue(store.summaries().isEmpty)
        XCTAssertNil(store.load("a"))
    }

    func testSeaTemperatureIsStretchedToTheAreaSoBreaksShow() {
        let temps = (0..<1000).map { 26 + Float($0) / 1000 * 1.6 }
        let scale = ConditionsColourScale.forChoice(.seaTempSatellite, values: temps)
        XCTAssertTrue(scale.low > 25.9 && scale.low < 26.2)
        XCTAssertTrue(scale.high > 27.4 && scale.high < 27.7)
        let flat = ConditionsColourScale.forChoice(.seaTempSatellite, values: [Float](repeating: 20, count: 10))
        XCTAssertEqual(flat.high - flat.low, 1, accuracy: 1e-9)
        XCTAssertEqual(ConditionsColourScale.forChoice(.current).low, 0)
        XCTAssertEqual(ConditionsColourScale.forChoice(.waves).colour(.nan), 0)
        let chl = ConditionsColourScale.forChoice(.chlorophyll, values: [0.1, 0.2, 0.5, 1, 2])
        XCTAssertEqual(chl.position((chl.low * chl.high).squareRoot()), 0.5, accuracy: 1e-6)
    }

    func testWindIsColouredLikeWindy() {
        let wind = ConditionsColourScale.forChoice(.wind)
        XCTAssertEqual(wind.colour(0), 0xFF6271B7)
        XCTAssertEqual(wind.colour(9 * 1.943844), 0xFF359F35)
        XCTAssertEqual(wind.colour(60), 0xFF754A93)
        XCTAssertEqual(wind.high, 21 * 1.943844, accuracy: 1e-9)
    }

    func testPictureRowsFollowTheMapProjection() {
        var rowLats = Set<Double>()
        let scale = ConditionsColourScale(low: 0, high: 100, logarithmic: false, stops: ConditionsColourScale.spectrum)
        let pixels = ConditionsPicture.render(south: -60, west: 0, north: 60, east: 10, maxSide: 100, scale: scale,
                                              sampler: { lat, _ in rowLats.insert(lat); return Float(lat) + 60 })
        var asked = 0
        _ = ConditionsPicture.render(south: -60, west: 0, north: 60, east: 10, maxSide: 100, scale: scale,
                                     sampler: { _, _ in asked += 1; return 0 }, cancelled: { true })
        XCTAssertEqual(asked, 0)
        XCTAssertTrue(pixels.height > pixels.width)
        let lats = rowLats.sorted()
        let gaps = zip(lats, lats.dropFirst()).map { $1 - $0 }
        XCTAssertTrue(gaps[lats.count / 2] > gaps.last! * 1.5)
        XCTAssertTrue(lats.first! > -60 && lats.last! < 60)
        XCTAssertEqual(ConditionsPicture.latitudeOf(ConditionsPicture.mercatorY(0)), 0, accuracy: 1e-9)
        XCTAssertEqual(ConditionsPicture.latitudeOf(ConditionsPicture.mercatorY(-33.5)), -33.5, accuracy: 1e-9)
    }

    func testTheValuesBoxSaysWhatIsThere() {
        let grid = ConditionsGeoGrid(lats: [-20.0, -19.9], lngs: [149.0, 149.1])
        func field(_ v: Float) -> [Float] { [Float](repeating: v, count: grid.size) }
        let c = ConditionsFlow.parts(speed: 0.5, towardsDegrees: 45)
        let w = ConditionsFlow.parts(speed: 5, towardsDegrees: 0)
        let forecast = ConditionsForecastGrid(grid: grid, times: [0], fields: [
            .currentU: field(Float(c.east)), .currentV: field(Float(c.north)),
            .windU: field(Float(w.east)), .windV: field(Float(w.north)), .gust: field(8),
            .waveHeight: field(1.2), .wavePeriod: field(7), .waveU: field(-1), .waveV: field(0),
            .seaTemp: field(26.0)
        ])
        let sat = ConditionsSatelliteGrid(kind: .seaTemp, grid: grid, dateMillis: 0, values: field(26.4))
        let area = ConditionsArea(id: "a", name: "x", south: -20, west: 149, north: -19.9, east: 149.1, fetchedMillis: 0,
                                  forecast: forecast, satellites: [.seaTemp: sat])
        let values = ConditionsLogic.values(area, lat: -19.95, lng: 149.05, time: 0,
                                            format: NavFormat(units: NavUnits(), timeZone: TimeZone(identifier: "UTC")!))
        let text = values.map { $0.text }.joined(separator: "\n")
        XCTAssertEqual(values[0].text, "Current 1.0 kn to NE")
        XCTAssertEqual(values[0].towardsDegrees ?? -1, 45, accuracy: 1e-3)
        XCTAssertEqual(values[1].text, "Wind 9.7 kn from S, gusts 15.6 kn")
        XCTAssertEqual(values[1].towardsDegrees ?? -1, 0, accuracy: 1e-3)
        XCTAssertEqual(values[2].text, "Waves 1.2 m, 7 s from E")
        XCTAssertTrue(text.contains("Surface 26.4 °C"), text)
        XCTAssertEqual(values.prefix(3).map { $0.row }, ["Current", "Wind", "Waves"])
        XCTAssertEqual(values.first { $0.text.hasPrefix("Surface") }?.row, "Sea")
        XCTAssertNil(values.last?.towardsDegrees)
    }

    func testAnAreaStopsAtTheDateLineAndStillCountsAsCentredThere() {
        let b = ConditionsLogic.boxAround(lat: -19, lng: 179.5, radiusKm: 200)
        XCTAssertEqual(b[3], 180)
        let area = ConditionsArea(id: "a", name: "Fiji", south: b[0], west: b[1], north: b[2], east: b[3], fetchedMillis: 0, forecast: nil, satellites: [:])
        XCTAssertTrue(area.contains(lat: -19, lng: 179.5))
        XCTAssertTrue(area.middleContains(lat: -19, lng: 179.5))
        let west = ConditionsLogic.boxAround(lat: 52, lng: -179.8, radiusKm: 200)
        XCTAssertEqual(west[1], -180)
        XCTAssertTrue(ConditionsArea(id: "b", name: "Aleutians", south: west[0], west: west[1], north: west[2], east: west[3],
                                     fetchedMillis: 0, forecast: nil, satellites: [:]).middleContains(lat: 52, lng: -179.8))
    }

    func testRequestsStayInsideEachDataSetAtTheEdgeOfTheWorld() {
        for kind in [ConditionsSatelliteKind.seaTemp, .chlorophyll, .seaLevel, .salinity] {
            let urls = ConditionsClient.satelliteUrls(kind, south: 79, west: 178, north: 80, east: 180)
            XCTAssertFalse(urls.isEmpty)
            for url in urls { XCTAssertFalse(url.contains("(180.000)"), url) }
        }
        XCTAssertTrue(ConditionsClient.satelliteUrls(.seaTemp, south: 0, west: 0, north: 1, east: 1).count >= 2)
    }

    func testAServerThatIsDownMovesOnToTheBackup() throws {
        var asked: [String] = []
        let client = ConditionsClient(http: { url in
            asked.append(url)
            if url.contains("jplMURSST41") { throw ConditionsError("down", busy: true) }
            return "time,latitude,longitude,analysed_sst\nUTC,degrees_north,degrees_east,degree_C\n" +
                "2026-11-02T12:00:00Z,-20.0,149.0,27.5\n2026-11-02T12:00:00Z,-20.0,149.05,27.6\n"
        }, pause: { _ in })
        let sst = try client.satellite(.seaTemp, south: -20, west: 149, north: -19.9, east: 149.1)
        XCTAssertEqual(sst.values[0], 27.5, accuracy: 0.001)
        // Each MUR server asked twice, then the blended data.
        XCTAssertEqual(asked.count, 5)
        XCTAssertTrue(asked.last!.contains("noaacwBLENDEDsstDNDaily"))
    }

    func testSalinityFallsThroughToTheSecondSatelliteWhenTheFirstHasNone() throws {
        let client = ConditionsClient(http: { url in
            let value = url.contains("SMAP") ? "NaN" : "35.5"
            return "time,altitude,latitude,longitude,sss\nUTC,m,degrees_north,degrees_east,PSU\n" +
                "2026-11-01T12:00:00Z,0.0,-19.0,179.0,\(value)\n2026-11-01T12:00:00Z,0.0,-19.0,179.25,\(value)\n"
        }, pause: { _ in })
        XCTAssertEqual(try client.satellite(.salinity, south: -19, west: 179, north: -18.5, east: 179.5).values[0], 35.5, accuracy: 0.001)
    }

    func testChlorophyllDaysBlendOnALogScaleAndKeepTheNewestDate() throws {
        let csv = "time,altitude,latitude,longitude,chlor_a\nUTC,m,degrees_north,degrees_east,mg\n" +
            "2026-10-29T12:00:00Z,0.0,-20.0,149.0,0.1\n2026-10-29T12:00:00Z,0.0,-20.0,149.04,NaN\n" +
            "2026-10-30T12:00:00Z,0.0,-20.0,149.0,1.0\n2026-10-30T12:00:00Z,0.0,-20.0,149.04,0.5\n"
        let chl = try ConditionsClient.parseErddapCsv(csv, kind: .chlorophyll)
        XCTAssertEqual(chl.values[0], 0.316, accuracy: 0.001)
        XCTAssertEqual(chl.values[1], 0.5, accuracy: 0.001)
        XCTAssertEqual(chl.dateMillis, iso("2026-10-30T12:00:00Z"))
    }

    func testSeaLevelComesInMetresAndIsKeptInCentimetres() throws {
        let csv = "time,latitude,longitude,sla\nUTC,degrees_north,degrees_east,m\n2026-11-02T00:00:00Z,-20.0,149.0,0.12\n2026-11-02T00:00:00Z,-20.0,149.25,-0.03\n"
        let level = try ConditionsClient.parseErddapCsv(csv, kind: .seaLevel)
        XCTAssertEqual(level.values[0], 12, accuracy: 0.001)
        XCTAssertEqual(level.values[1], -3, accuracy: 0.001)
    }

    func testTemperatureBreaksAreStrongestWhereTheTemperatureChanges() {
        let grid = ConditionsGeoGrid(lats: [0, 0.1, 0.2], lngs: [0, 0.1, 0.2])
        let sst = ConditionsSatelliteGrid(kind: .seaTemp, grid: grid, dateMillis: 0, values: [20, 21, 21, 20, 21, 21, .nan, 21, 21])
        let breaks = ConditionsSatelliteGrid.temperatureBreaks(sst)
        XCTAssertTrue(breaks.values[3] > breaks.values[5])
        XCTAssertEqual(breaks.values[5], 0, accuracy: 0.001)
        XCTAssertTrue(breaks.values[6].isNaN)
        XCTAssertEqual(breaks.values[3], 1 / 11.12, accuracy: 0.002)
    }

    func testSeaLevelColoursAreCentredOnTheAverage() {
        let scale = ConditionsColourScale.forChoice(.seaLevel, values: [-3, 2, 18, 4])
        XCTAssertEqual(-scale.low, scale.high, accuracy: 0.001)
        XCTAssertEqual(scale.position(0), 0.5, accuracy: 0.001)
    }

    // NetCDF / HYCOM --------------------------------------------------------------------

    /// A small NetCDF-3 file laid out the way HYCOM's server does: time, depth, lat, lon.
    private func netCdf(lats: [Double], lngs: [Double], temps: [Int16]) -> Data {
        var d = Data()
        func int(_ v: Int32) { var b = v.bigEndian; withUnsafeBytes(of: &b) { d.append(contentsOf: $0) } }
        func short(_ v: Int16) { var b = v.bigEndian; withUnsafeBytes(of: &b) { d.append(contentsOf: $0) } }
        func float(_ v: Float) { var b = v.bitPattern.bigEndian; withUnsafeBytes(of: &b) { d.append(contentsOf: $0) } }
        func double(_ v: Double) { var b = v.bitPattern.bigEndian; withUnsafeBytes(of: &b) { d.append(contentsOf: $0) } }
        func bytes(_ s: String) { d.append(contentsOf: Array(s.utf8)) }
        func name(_ s: String) {
            int(Int32(s.utf8.count)); bytes(s)
            for _ in 0..<((4 - s.utf8.count % 4) % 4) { d.append(0) }
        }
        bytes("CDF"); d.append(1); int(0)
        int(10); int(4)
        name("time"); int(1); name("depth"); int(1); name("lat"); int(Int32(lats.count)); name("lon"); int(Int32(lngs.count))
        int(0); int(0)
        int(11); int(4)
        let headerSize = 512
        var at = headerSize
        func variable(_ n: String, _ dims: [Int32], _ attrs: () -> Void, _ type: Int32, _ size: Int) {
            name(n); int(Int32(dims.count)); dims.forEach { int($0) }
            attrs()
            int(type); int(Int32(size)); int(Int32(at)); at += (size + 3) / 4 * 4
        }
        func noAttrs() { int(0); int(0) }
        variable("time", [0], {
            int(12); int(1)
            name("units"); int(2); int(31); bytes("hours since 2000-01-01 12:00:00"); d.append(0)
        }, 6, 8)
        variable("lat", [2], noAttrs, 6, lats.count * 8)
        variable("lon", [3], noAttrs, 6, lngs.count * 8)
        variable("water_temp", [0, 1, 2, 3], {
            int(12); int(3)
            name("scale_factor"); int(5); int(1); float(0.001)
            name("add_offset"); int(5); int(1); float(20)
            name("_FillValue"); int(3); int(1); short(-30000); short(0)
        }, 3, temps.count * 2)
        precondition(d.count <= headerSize)
        while d.count < headerSize { d.append(0) }
        double(24)
        lats.forEach { double($0) }
        lngs.forEach { double($0) }
        temps.forEach { short($0) }
        return d
    }

    func testHycomsNetCdfAnswerBecomesAPictureSouthUpAndWestFirst() throws {
        let nc = netCdf(lats: [-20.0, -19.9], lngs: [359.8, 359.9], temps: [5000, -30000, 4000, 3000])
        let grid = try ConditionsClient.parseHycom(nc, kind: .tempBelow)
        XCTAssertEqual(grid.grid.lngs[0], -0.2, accuracy: 1e-6)
        XCTAssertEqual(grid.values[0], 25, accuracy: 0.001)
        XCTAssertTrue(grid.values[1].isNaN)
        XCTAssertEqual(grid.values[2], 24, accuracy: 0.001)
        XCTAssertEqual(grid.dateMillis, iso("2000-01-02T12:00:00Z"))
    }

    func testHycomsRandomFailuresAreAskedThrough() throws {
        var asked = 0
        let good = netCdf(lats: [-20.0, -19.9], lngs: [149.0, 149.08], temps: [5000, 5000, 5000, 5000])
        let client = ConditionsClient(httpBytes: { _ in
            asked += 1
            if asked < 5 { throw ConditionsError("No space left on device", busy: true) }
            return good
        }, pause: { _ in })
        XCTAssertEqual(try client.satellite(.tempBelow, south: -20, west: 149, north: -19.9, east: 149.1).values[0], 25, accuracy: 0.001)
        XCTAssertEqual(asked, 5)
    }

    func testLongitudesPastTheDateLineStayInOrder() throws {
        let nc = netCdf(lats: [-20.0, -19.9], lngs: [180.0, 180.08], temps: [1000, 2000, 3000, 4000])
        let grid = try ConditionsClient.parseHycom(nc, kind: .tempBelow)
        XCTAssertEqual(grid.grid.lngs[0], -180, accuracy: 1e-6)
        XCTAssertEqual(grid.values[0], 21, accuracy: 0.001)
    }

    func testNetCdfRefusesOtherFiles() {
        XCTAssertThrowsError(try ConditionsNetCdf.read(Data("not a netcdf file".utf8)))
    }
}
