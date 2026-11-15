import XCTest
@testable import SeaBathy

/// Ported from Android GpxImporterTest and GpxExporterTest.
final class GpxCodecTests: XCTestCase {

    private func parse(_ xml: String) throws -> GpxCodec.Parsed {
        try GpxCodec.parse(Data(xml.utf8))
    }

    private func waypoint(_ name: String, desc: String? = nil) -> Waypoint {
        Waypoint(id: "id-\(name)", name: name, lat: -18.25, lng: 147.5, desc: desc)
    }

    /// Counts elements and collects the text of each <name> and <desc>, failing on bad XML.
    private final class Reader: NSObject, XMLParserDelegate {
        var counts: [String: Int] = [:]
        var names: [String] = []
        var descs: [String] = []
        var root: String?
        private var current = ""
        private var element = ""

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            if root == nil { root = elementName }
            counts[elementName, default: 0] += 1
            element = elementName
            current = ""
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) { current += string }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            if elementName == "name" { names.append(current) }
            if elementName == "desc" { descs.append(current) }
        }
    }

    private func read(_ gpx: String) -> Reader {
        let reader = Reader()
        let parser = XMLParser(data: Data(gpx.utf8))
        parser.delegate = reader
        XCTAssertTrue(parser.parse(), "not well formed: \(parser.parserError?.localizedDescription ?? "")")
        return reader
    }

    // Import ------------------------------------------------------------------------

    func testReadsWaypointsRoutesAndTracks() throws {
        let result = try parse("""
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <wpt lat="-18.65" lon="147.17"><name>Reef</name><desc>Good spot</desc></wpt>
          <wpt lat="-18.70" lon="147.20"/>
          <rte><name>Home run</name>
            <rtept lat="-18.6" lon="147.1"><name>A</name></rtept>
            <rtept lat="-18.7" lon="147.2"/>
          </rte>
          <rte><rtept lat="1" lon="1"/></rte>
          <trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk>
          <trk><name>Sunday sail</name>
            <trkseg><trkpt lat="-18.6" lon="147.1"><time>2024-06-21T03:15:00Z</time></trkpt>
              <trkpt lat="-18.61" lon="147.1"><time>2024-06-21T03:20:00.500+00:00</time></trkpt></trkseg>
            <trkseg><trkpt lat="-18.62" lon="147.1"/></trkseg>
          </trk>
        </gpx>
        """)
        XCTAssertEqual(result.waypoints.count, 2)
        XCTAssertEqual(result.waypoints[0].name, "Reef")
        XCTAssertEqual(result.waypoints[0].desc, "Good spot")
        XCTAssertEqual(result.waypoints[1].name, "")
        // The one-point route is dropped.
        XCTAssertEqual(result.routes.count, 1)
        XCTAssertEqual(result.routes[0].name, "Home run")
        XCTAssertEqual(result.routes[0].points[0].name, "A")
        XCTAssertEqual(result.routes[0].points[1].lng, 147.2, accuracy: 1e-9)
        // The one-point track is dropped; segments are joined, times read when present.
        XCTAssertEqual(result.tracks.count, 1)
        let track = result.tracks[0]
        XCTAssertEqual(track.name, "Sunday sail")
        XCTAssertEqual(track.points.count, 3)
        XCTAssertEqual(track.points[0].timeMillis, 1_718_939_700_000)
        XCTAssertEqual(track.points[1].timeMillis, 1_718_940_000_500)
        XCTAssertEqual(track.points[2].timeMillis, 0)
    }

    func testGpx10WithoutANamespaceWorks() throws {
        let result = try parse(#"<gpx version="1.0"><wpt lat="10" lon="20"><name>X</name></wpt></gpx>"#)
        XCTAssertEqual(result.waypoints.map { $0.name }, ["X"])
    }

    func testOutOfRangeCoordinatesAreSkipped() throws {
        let result = try parse(#"<gpx><wpt lat="95" lon="20"/><wpt lat="10" lon="200"/><wpt lat="1" lon="2"/></gpx>"#)
        XCTAssertEqual(result.waypoints.count, 1)
    }

    func testNotGpxIsRejected() {
        XCTAssertThrowsError(try parse("<kml><Placemark/></kml>"))
    }

    func testExternalEntitiesAreRefused() {
        XCTAssertThrowsError(try parse("""
        <?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
        <gpx><wpt lat="1" lon="1"><name>&x;</name></wpt></gpx>
        """))
    }

    func testImportMessageCounts() {
        XCTAssertEqual(GpxActions.importMessage(waypoints: 1, routes: 0, tracks: 0), "Imported 1 waypoint and 0 routes.")
        XCTAssertEqual(GpxActions.importMessage(waypoints: 2, routes: 1, tracks: 0),
                       "Imported 2 waypoints and 1 route. Routes are in Saved routes.")
        XCTAssertEqual(GpxActions.importMessage(waypoints: 0, routes: 0, tracks: 2),
                       "Imported 0 waypoints, 0 routes and 2 tracks. Tracks are in Settings → Tracks.")
    }

    // Export ------------------------------------------------------------------------

    func testWaypointNamesUseTheNameElement() {
        let gpx = GpxCodec.export(waypoints: [waypoint("Reef edge")], route: [], routeName: "Route")
        XCTAssertTrue(gpx.contains("<name>Reef edge</name>"))
        XCTAssertFalse(gpx.contains("<n>"))
    }

    func testRouteNamesUseTheNameElement() {
        let gpx = GpxCodec.export(waypoints: [], route: [RoutePoint(lat: -18, lng: 147), RoutePoint(lat: -18.1, lng: 147.1)],
                                  routeName: "Passage north")
        XCTAssertTrue(gpx.contains("<name>Passage north</name>"))
        XCTAssertFalse(gpx.contains("<n>"))
    }

    func testSpecialCharactersAreEscaped() {
        let gpx = GpxCodec.export(waypoints: [waypoint("Rocks & \"shoal\" <5m> 'here'")], route: [], routeName: "Route")
        XCTAssertTrue(gpx.contains("&amp;"))
        XCTAssertTrue(gpx.contains("&quot;"))
        XCTAssertTrue(gpx.contains("&lt;5m&gt;"))
        XCTAssertTrue(gpx.contains("&apos;"))
    }

    func testOutputStaysWellFormedForHostileNames() {
        let hostile = "</name></wpt></gpx><script>"
        let gpx = GpxCodec.export(
            waypoints: [waypoint(hostile, desc: "note & more"), waypoint("Ampersand & co"), waypoint("Unicode ✛ ° é")],
            route: [RoutePoint(lat: -18, lng: 147), RoutePoint(lat: -18.2, lng: 147.2)],
            routeName: "Route <with> \"quotes\" & ampersand")
        let reader = read(gpx)
        XCTAssertEqual(reader.root, "gpx")
        XCTAssertEqual(reader.counts["wpt"], 3)
        XCTAssertEqual(reader.counts["rte"], 1)
        XCTAssertEqual(reader.names.count, 4)
        XCTAssertEqual(reader.names.first, hostile)
        XCTAssertEqual(reader.descs.first, "note & more")
    }

    func testIllegalControlCharactersAreDropped() {
        let gpx = GpxCodec.export(waypoints: [waypoint("Bell\u{7}and\u{0}null")], route: [], routeName: "Route")
        XCTAssertEqual(read(gpx).names.first, "Bellandnull")
    }

    func testCoordinatesUseADecimalPoint() {
        let gpx = GpxCodec.export(waypoints: [waypoint("Comma test")], route: [], routeName: "Route")
        XCTAssertTrue(gpx.contains("lat=\"-18.25\""))
        XCTAssertTrue(gpx.contains("lon=\"147.5\""))
    }

    func testCoordinatesNearZeroAreNotScientific() {
        let gpx = GpxCodec.export(waypoints: [Waypoint(id: "z", name: "Equator", lat: -0.0001, lng: 0.00005)],
                                  route: [RoutePoint(lat: 0.0001, lng: 0), RoutePoint(lat: 0.0002, lng: 0)], routeName: "Route")
        XCTAssertFalse(gpx.contains("e-"))
        XCTAssertFalse(gpx.contains("E-"))
        XCTAssertTrue(gpx.contains("lat=\"-0.0001\""))
        XCTAssertTrue(gpx.contains("lon=\"0.00005\""))
        _ = read(gpx)
    }

    func testDecimalFormatting() {
        XCTAssertEqual(GpxCodec.decimal(147.0), "147")
        XCTAssertEqual(GpxCodec.decimal(-0.0), "0")
        XCTAssertEqual(GpxCodec.decimal(1e-7), "0.0000001")
        XCTAssertEqual(GpxCodec.decimal(-18.65), "-18.65")
        XCTAssertEqual(GpxCodec.coordinate(-19.000000004), "-19")
        XCTAssertEqual(GpxCodec.coordinate(147.12345678), "147.1234568")
    }

    func testASingleRoutePointIsNotARoute() {
        let gpx = GpxCodec.export(waypoints: [], route: [RoutePoint(lat: -18, lng: 147)], routeName: "Route")
        XCTAssertFalse(gpx.contains("<rte>"))
        _ = read(gpx)
    }

    func testAnEmptyExportIsStillValid() {
        _ = read(GpxCodec.export(waypoints: [], route: [], routeName: "Route"))
    }

    func testABlankDescriptionIsOmitted() {
        let gpx = GpxCodec.export(waypoints: [waypoint("No notes", desc: "  ")], route: [], routeName: "Route")
        XCTAssertFalse(gpx.contains("<desc>"))
    }
}
