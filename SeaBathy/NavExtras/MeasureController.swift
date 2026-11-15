import MapLibre
import SwiftUI

/// The measurement path and its line on the chart (Android map/MeasureController.kt).
@MainActor
final class MeasureController: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var points: [RoutePoint] = []

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        app.map.onStyleLoaded { [weak self] _ in self?.installLayers() }
    }

    func add(lat: Double, lng: Double) {
        points.append(RoutePoint(lat: lat, lng: lng))
        refresh()
    }

    func undo() {
        guard !points.isEmpty else { return }
        points.removeLast()
        refresh()
    }

    func clear() {
        points.removeAll()
        refresh()
    }

    /// The readout shown in the measurement card, or nil when nothing is measured.
    var summary: String? { Self.summary(for: points) }

    nonisolated static func summary(for points: [RoutePoint]) -> String? {
        if points.isEmpty { return nil }
        if points.count < 2 { return "Tap another point…" }
        let nm = GeoMath.totalNm(points)
        // The bearing of the last leg drawn: for one measurement, first point to second.
        let bearing = GeoMath.bearingDegrees(points[points.count - 2], points[points.count - 1])
        return String(format: "%@  —  %03.0f°T", GeoMath.formatDistance(nm), bearing)
    }

    /// A line through the points plus a dot at each one (Android GeoJson.lineWithVertices).
    nonisolated static func geoJSON(_ points: [RoutePoint]) -> String {
        var features: [String] = []
        if points.count >= 2 {
            let coords = points.map { String(format: "[%.7f,%.7f]", $0.lng, $0.lat) }.joined(separator: ",")
            features.append(#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[\#(coords)]},"properties":{}}"#)
        }
        for p in points {
            features.append(String(format: #"{"type":"Feature","geometry":{"type":"Point","coordinates":[%.7f,%.7f]},"properties":{}}"#, p.lng, p.lat))
        }
        return #"{"type":"FeatureCollection","features":["# + features.joined(separator: ",") + "]}"
    }

    private func refresh() {
        app.map.setGeoJSON(MapLayerIds.measureSource, Self.geoJSON(points))
    }

    private func installLayers() {
        guard let source = app.map.geoJSONSource(MapLayerIds.measureSource) else { return }
        app.map.add(NavExtrasStyle.casingLayer(MapLayerIds.measureCasing, source: source, width: 6), placement: .top)

        let line = MLNLineStyleLayer(identifier: MapLayerIds.measureLine, source: source)
        line.predicate = NavExtrasStyle.geometry("LineString")
        line.lineColor = NavExtrasStyle.colour(NavExtrasStyle.measure)
        line.lineWidth = NavExtrasStyle.number(2)
        app.map.add(line, placement: .top)

        let dots = MLNCircleStyleLayer(identifier: MapLayerIds.measurePoints, source: source)
        dots.predicate = NavExtrasStyle.geometry("Point")
        dots.circleRadius = NavExtrasStyle.number(5)
        dots.circleColor = NavExtrasStyle.colour(NavExtrasStyle.measure)
        dots.circleStrokeWidth = NavExtrasStyle.number(1.5)
        dots.circleStrokeColor = NavExtrasStyle.colour(NavExtrasStyle.casing)
        app.map.add(dots, placement: .top)
        refresh()
    }
}

/// The measurement card at the bottom of the chart while the measure tool is armed
/// (Android measureCard): the distance and bearing, with UNDO and CLEAR.
struct MeasureCardOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        MeasureCardContent(measure: app.navigation.measure)
    }
}

private struct MeasureCardContent: View {
    @ObservedObject var measure: MeasureController

    var body: some View {
        if let summary = measure.summary {
            MapCard {
                HStack(spacing: 10) {
                    Image(systemName: "ruler")
                        .foregroundStyle(Color(hex: NavExtrasStyle.measure))
                    Text(summary)
                        .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.7)
                    Spacer(minLength: 4)
                    CardButton(title: "UNDO") { measure.undo() }
                    CardButton(title: "CLEAR") { measure.clear() }
                }
            }
            .padding(.horizontal, 10)
        }
    }
}
