import Foundation
import UniformTypeIdentifiers

/// A GPX file couldn't be read; the message is shown to the user.
struct GpxError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/**
 Reads and writes GPX 1.0/1.1 (Android storage/GpxImporter.kt and util/GpxExporter.kt).
 Waypoints and routes from a file without names get "" (the importer names them).
 */
enum GpxCodec {
    struct Parsed: @unchecked Sendable {
        var waypoints: [Waypoint]
        var routes: [(name: String, points: [RoutePoint])]
        /// Tracks (trk), all segments joined. Name nil when the file has none.
        var tracks: [(name: String?, points: [TrackPoint])] = []
    }

    // Reading ------------------------------------------------------------------------

    static func parse(_ data: Data) throws -> Parsed {
        // No DTDs or entities: a GPX file comes from anywhere.
        if data.range(of: Data("<!DOCTYPE".utf8)) != nil || data.range(of: Data("<!ENTITY".utf8)) != nil {
            throw GpxError(message: "This file isn't valid GPX (it contains a DOCTYPE, which isn't allowed)")
        }
        let builder = GpxTreeBuilder()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = builder
        if !parser.parse() || builder.root == nil {
            let reason = parser.parserError?.localizedDescription ?? "unreadable XML"
            throw GpxError(message: "This file isn't valid GPX (\(reason))")
        }
        guard let root = builder.root, root.name == "gpx" else { throw GpxError(message: "This file isn't GPX") }

        var waypoints: [Waypoint] = []
        for wpt in root.children(named: "wpt") {
            guard let ll = wpt.latLng() else { continue }
            waypoints.append(Waypoint(id: UUID().uuidString, name: wpt.childText("name") ?? "", lat: ll.0, lng: ll.1,
                                      desc: wpt.childText("desc") ?? wpt.childText("cmt")))
        }
        var routes: [(name: String, points: [RoutePoint])] = []
        for rte in root.children(named: "rte") {
            var points: [RoutePoint] = []
            for pt in rte.children(named: "rtept") {
                guard let ll = pt.latLng() else { continue }
                points.append(RoutePoint(lat: ll.0, lng: ll.1, name: pt.childText("name")))
            }
            if points.count >= 2 { routes.append((name: rte.childText("name") ?? "", points: points)) }
        }
        var tracks: [(name: String?, points: [TrackPoint])] = []
        for trk in root.children(named: "trk") {
            var points: [TrackPoint] = []
            for seg in trk.children(named: "trkseg") {
                for pt in seg.children(named: "trkpt") {
                    guard let ll = pt.latLng() else { continue }
                    let time = pt.childText("time").flatMap { GpxCodec.parseTime($0) } ?? 0
                    points.append(TrackPoint(timeMillis: time, lat: ll.0, lng: ll.1))
                }
            }
            if points.count >= 2 { tracks.append((name: trk.childText("name"), points: points)) }
        }
        return Parsed(waypoints: waypoints, routes: routes, tracks: tracks)
    }

    /// GPX times are ISO 8601 UTC ("2024-06-21T03:15:00Z"), sometimes with an offset or fractions.
    static func parseTime(_ text: String) -> Int64? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        guard let date = withFraction.date(from: text) ?? plain.date(from: text) else { return nil }
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    // Writing ------------------------------------------------------------------------

    /// Waypoints and (two or more points) a route, as GPX 1.1. All text is XML-escaped.
    static func export(waypoints: [Waypoint], route: [RoutePoint], routeName: String) -> String {
        var gpx = header()
        for w in waypoints {
            gpx += #"  <wpt lat="\#(decimal(w.lat))" lon="\#(decimal(w.lng))">"#
            gpx += "<name>\(XmlEscape.text(w.name))</name>"
            if let desc = w.desc, !desc.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                gpx += "<desc>\(XmlEscape.text(desc))</desc>"
            }
            gpx += "</wpt>\n"
        }
        if route.count > 1 {
            gpx += "  <rte><name>\(XmlEscape.text(routeName))</name>"
            for p in route {
                let attrs = #"lat="\#(decimal(p.lat))" lon="\#(decimal(p.lng))""#
                if let name = p.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    gpx += "<rtept \(attrs)><name>\(XmlEscape.text(name))</name></rtept>"
                } else {
                    gpx += "<rtept \(attrs)/>"
                }
            }
            gpx += "</rte>\n"
        }
        gpx += "</gpx>\n"
        return gpx
    }

    /// A recorded track: one trkseg per stretch without a long GPS gap, each point with its UTC time.
    static func exportTrack(name: String, points: [TrackPoint]) -> String {
        var gpx = header()
        gpx += "  <trk><name>\(XmlEscape.text(name))</name>\n"
        for segment in TrackMath.segments(points) {
            gpx += "    <trkseg>\n"
            for p in segment {
                gpx += #"      <trkpt lat="\#(coordinate(p.lat))" lon="\#(coordinate(p.lng))">"#
                if p.timeMillis > 0 { gpx += "<time>\(isoTime(p.timeMillis))</time>" }
                gpx += "</trkpt>\n"
            }
            gpx += "    </trkseg>\n"
        }
        gpx += "  </trk>\n</gpx>\n"
        return gpx
    }

    private static func header() -> String {
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"SeaBathy\" " +
            "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
            "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"
    }

    /// The shortest exact decimal, never in scientific notation (xsd:decimal rejects "1e-05").
    static func decimal(_ value: Double) -> String {
        if value == 0 || !value.isFinite { return "0" }
        let text = "\(value)"
        let negative = text.hasPrefix("-")
        let body = negative ? String(text.dropFirst()) : text
        var mantissa = body
        var exponent = 0
        if let e = body.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            mantissa = String(body[..<e])
            exponent = Int(body[body.index(after: e)...]) ?? 0
        }
        var intPart = mantissa
        var fracPart = ""
        if let dot = mantissa.firstIndex(of: ".") {
            intPart = String(mantissa[..<dot])
            fracPart = String(mantissa[mantissa.index(after: dot)...])
        }
        var digits = intPart + fracPart
        var pointAt = intPart.count + exponent
        if pointAt <= 0 {
            digits = String(repeating: "0", count: -pointAt + 1) + digits
            pointAt = 1
        } else if pointAt > digits.count {
            digits += String(repeating: "0", count: pointAt - digits.count)
        }
        var whole = String(digits.prefix(pointAt))
        var frac = String(digits.dropFirst(pointAt))
        while frac.hasSuffix("0") { frac.removeLast() }
        while whole.count > 1 && whole.hasPrefix("0") { whole.removeFirst() }
        if whole.isEmpty { whole = "0" }
        let result = frac.isEmpty ? whole : whole + "." + frac
        return negative && result != "0" ? "-" + result : result
    }

    /// A recorded position to 7 decimals (about 1 cm): GPS fixes carry many meaningless digits.
    static func coordinate(_ value: Double) -> String {
        var text = String(format: "%.7f", value)
        if text.contains(".") {
            while text.hasSuffix("0") { text.removeLast() }
            if text.hasSuffix(".") { text.removeLast() }
        }
        if text == "-0" { text = "0" }
        return text
    }

    /// UTC ISO 8601 as Java's Instant.toString(): no fraction for whole seconds, else milliseconds.
    static func isoTime(_ millis: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = millis % 1000 == 0 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return f.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// Writes a GPX file into a fresh temporary export folder, ready for the share sheet.
    static func writeShareFile(_ fileName: String, _ gpx: String) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // Earlier exports have already been handed to the share sheet.
        if let old = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
            old.filter { $0.pathExtension.lowercased() == "gpx" }.forEach { try? FileManager.default.removeItem(at: $0) }
        }
        let url = folder.appendingPathComponent(fileName)
        try Data(gpx.utf8).write(to: url, options: .atomic)
        return url
    }

    /// A file name made from a track name: "seabathy_track_<name>.gpx".
    static func trackFileName(_ name: String) -> String {
        var cleaned = name.replacingOccurrences(of: "[^A-Za-z0-9 ._-]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "\\s+", with: "_", options: .regularExpression)
        if cleaned.isEmpty { cleaned = "track" }
        return "seabathy_track_\(cleaned).gpx"
    }
}

// XML tree ------------------------------------------------------------------------------

/// A minimal element tree; names are local (namespace prefixes dropped).
private final class GpxNode {
    let name: String
    let attributes: [String: String]
    var children: [GpxNode] = []
    var text = ""

    init(name: String, attributes: [String: String]) {
        self.name = name
        self.attributes = attributes
    }

    func children(named name: String) -> [GpxNode] { children.filter { $0.name == name } }

    var textContent: String { text + children.map { $0.textContent }.joined() }

    func childText(_ name: String) -> String? {
        guard let child = children.first(where: { $0.name == name }) else { return nil }
        let t = child.textContent.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    func latLng() -> (Double, Double)? {
        guard let latText = attributes["lat"], let lngText = attributes["lon"],
              let lat = Double(latText.trimmingCharacters(in: .whitespaces)),
              let lng = Double(lngText.trimmingCharacters(in: .whitespaces)),
              lat >= -90, lat <= 90, lng >= -180, lng <= 180 else { return nil }
        return (lat, lng)
    }
}

private final class GpxTreeBuilder: NSObject, XMLParserDelegate {
    var root: GpxNode?
    private var stack: [GpxNode] = []

    private static func local(_ name: String) -> String {
        if let colon = name.lastIndex(of: ":") { return String(name[name.index(after: colon)...]) }
        return name
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        var attrs: [String: String] = [:]
        for (k, v) in attributeDict { attrs[Self.local(k)] = v }
        let node = GpxNode(name: Self.local(elementName), attributes: attrs)
        if let parent = stack.last { parent.children.append(node) } else if root == nil { root = node }
        stack.append(node)
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        _ = stack.popLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        stack.last?.text += string
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        stack.last?.text += String(decoding: CDATABlock, as: UTF8.self)
    }

    func parser(_ parser: XMLParser, resolveExternalEntityName name: String, systemID: String?) -> Data? {
        // Never load anything from outside the file.
        parser.abortParsing()
        return nil
    }
}

// Import and export actions -------------------------------------------------------------

/// Settings > Import GPX and Export waypoints and route (Android MainActivity
/// launchGpxImportPicker/exportGpx and NavigationFeature.importGpx).
@MainActor
enum GpxActions {
    /// Picks a GPX file and imports it. Any file type is offered: apps label .gpx inconsistently,
    /// and the importer checks the content.
    static func importGpx(app: AppModel) {
        app.pickFiles([.item], allowsMultiple: false) { urls in
            guard let url = urls.first else { return }
            importFile(app: app, url: url)
        }
    }

    /// Imports a GPX file already in the sandbox (picked, or opened from the Files app).
    static func importFile(app: AppModel, url: URL) {
        Task { @MainActor in
            let outcome: Result<GpxCodec.Parsed, Error> = await Task.detached(priority: .userInitiated) { () -> Result<GpxCodec.Parsed, Error> in
                do {
                    let data = try Data(contentsOf: url)
                    return .success(try GpxCodec.parse(data))
                } catch {
                    return .failure(error)
                }
            }.value
            switch outcome {
            case .failure(let error):
                DiagnosticLog.logError("GpxActions", "GPX import failed", error)
                let message = (error as? GpxError)?.message ?? "Couldn't read that GPX file"
                app.alert("SeaBathy", message)
            case .success(let parsed):
                apply(parsed, app: app)
            }
        }
    }

    private static func apply(_ result: GpxCodec.Parsed, app: AppModel) {
        var added = 0
        for w in result.waypoints {
            let trimmed = w.name.trimmingCharacters(in: .whitespacesAndNewlines)
            let base = trimmed.isEmpty ? app.waypoints.nextDefaultName() : trimmed
            app.waypoints.add(name: uniqueWaypointName(base, app: app), lat: w.lat, lng: w.lng,
                              color: Waypoint.defaultColor, description: w.desc)
            added += 1
        }
        if added > 0 { app.navigation.waypointsChanged() }
        for r in result.routes {
            let name = r.name.trimmingCharacters(in: .whitespacesAndNewlines)
            app.navigation.importRoute(name: name.isEmpty ? "Imported route" : name, points: r.points)
        }
        let routeCount = result.routes.count
        let savedTracks = app.navigation.tracks.importTracks(result.tracks)
        app.alert("SeaBathy", importMessage(waypoints: added, routes: routeCount, tracks: savedTracks))
    }

    /// The summary after an import, as on Android.
    nonisolated static func importMessage(waypoints: Int, routes: Int, tracks: Int) -> String {
        func count(_ n: Int, _ what: String) -> String { "\(n) \(what)\(n == 1 ? "" : "s")" }
        var message = "Imported \(count(waypoints, "waypoint"))"
        if tracks > 0 {
            message += ", \(count(routes, "route")) and \(count(tracks, "track"))."
        } else {
            message += " and \(count(routes, "route"))."
        }
        if routes > 0 { message += " Routes are in Saved routes." }
        if tracks > 0 { message += " Tracks are in Settings → Tracks." }
        return message
    }

    private static func uniqueWaypointName(_ base: String, app: AppModel) -> String {
        func taken(_ name: String) -> Bool {
            app.waypoints.all.contains { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        }
        if !taken(base) { return base }
        var n = 2
        while taken("\(base) (\(n))") { n += 1 }
        return "\(base) (\(n))"
    }

    /// Shares every waypoint and, when given, the route being planned, as one GPX file.
    static func exportAll(app: AppModel, route: [RoutePoint] = [], routeName: String = "Route") {
        let waypoints = app.waypoints.all
        if waypoints.isEmpty && route.count < 2 {
            app.toast("Add a waypoint or route before exporting")
            return
        }
        let stamp = NavExtrasStyle.formatter("yyyy-MM-dd_HHmm").string(from: Date())
        do {
            let url = try GpxCodec.writeShareFile("seabathy_\(stamp).gpx",
                                                  GpxCodec.export(waypoints: waypoints, route: route, routeName: routeName))
            app.share([url])
        } catch {
            DiagnosticLog.logError("GpxActions", "GPX export failed", error)
            app.toast("Couldn't create the GPX file")
        }
    }

    /// Shares one route as GPX (the route card's and saved routes' GPX buttons).
    static func shareRoute(app: AppModel, points: [RoutePoint], name: String) {
        if points.count < 2 {
            app.toast("Add at least two points to export a route")
            return
        }
        let stamp = NavExtrasStyle.formatter("yyyy-MM-dd_HHmm").string(from: Date())
        do {
            let url = try GpxCodec.writeShareFile("seabathy_\(stamp).gpx",
                                                  GpxCodec.export(waypoints: [], route: points, routeName: name))
            app.share([url])
        } catch {
            DiagnosticLog.logError("GpxActions", "route GPX export failed", error)
            app.toast("Couldn't create the GPX file")
        }
    }
}
