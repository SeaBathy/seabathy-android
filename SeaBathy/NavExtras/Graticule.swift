import MapLibre
import SwiftUI

/// A latitude and longitude grid for what's on screen, as GeoJSON (Android map/Graticule.kt).
enum Graticule {
    /// Degrees between lines, coarse to fine: round figures charts use, down to a tenth of a minute.
    static let steps: [Double] = [
        30, 20, 10, 5, 2, 1, 30 / 60.0, 20 / 60.0, 10 / 60.0, 5 / 60.0, 2 / 60.0, 1 / 60.0,
        0.5 / 60, 0.2 / 60, 0.1 / 60
    ]
    private static let maxLinesAcross = 6.0
    private static let maxLat = 85.0
    /// Where the labels sit, as a share of what's on screen: under the top bar, just in from the left.
    private static let labelInsetTop = 0.13
    private static let labelInsetLeft = 0.02

    /// The spacing for an area `spanDegrees` across its longer side.
    static func stepFor(_ spanDegrees: Double) -> Double {
        steps.first { spanDegrees / $0 >= maxLinesAcross - 2 && spanDegrees / $0 <= maxLinesAcross + 2 }
            ?? steps.first { spanDegrees / $0 >= maxLinesAcross - 2 }
            ?? steps[steps.count - 1]
    }

    /// "18°30′S", with the minutes only when the spacing needs them.
    static func label(_ value: Double, isLatitude: Bool, step: Double) -> String {
        let hemisphere: String
        if abs(value) < 1e-9 || (!isLatitude && abs(abs(value) - 180) < 1e-9) {
            // The equator, the prime meridian and the date line belong to neither side.
            hemisphere = ""
        } else if isLatitude {
            hemisphere = value < 0 ? "S" : "N"
        } else {
            hemisphere = value < 0 ? "W" : "E"
        }
        var whole = Int(floor(abs(value) + 1e-9))
        let fraction = (abs(value) - Double(whole)) * 60
        if step >= 1 { return "\(whole)°\(hemisphere)" }
        if step >= 1 / 60.0 - 1e-9 {
            var minutes = Int(fraction.rounded())
            if minutes == 60 {
                minutes = 0
                whole += 1
            }
            return String(format: "%ld°%02ld′%@", whole, minutes, hemisphere)
        }
        return String(format: "%ld°%04.1f′%@", whole, fraction, hemisphere)
    }

    /// Lines and labels for the area on screen. `east` may be below `west` when the date line is on screen.
    static func geoJSON(south: Double, west: Double, north: Double, east: Double) -> String {
        let s = min(max(south, -maxLat), maxLat)
        let n = min(max(north, -maxLat), maxLat)
        let e = east < west ? east + 360 : east
        let width = min(e - west, 360)
        let height = n - s
        if height <= 0 || width <= 0 { return MapLayerIds.emptyGeoJSON }
        let step = stepFor(max(height, width))
        var features: [String] = []
        func line(_ a: Double, _ b: Double, _ c: Double, _ d: Double) {
            features.append(String(format: #"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[%.6f,%.6f],[%.6f,%.6f]]},"properties":{}}"#, a, b, c, d))
        }
        func text(_ lng: Double, _ lat: Double, _ label: String) {
            features.append(String(format: #"{"type":"Feature","geometry":{"type":"Point","coordinates":[%.6f,%.6f]},"properties":{"label":"%@"}}"#, lng, lat, label))
        }
        let labelLat = n - height * labelInsetTop
        let labelLng = west + width * labelInsetLeft
        // Counted from zero, so lines land on round figures rather than building up rounding errors.
        var i = Int64(ceil(west / step - 1e-9))
        let lastLng = Int64(floor(e / step + 1e-9))
        while i <= lastLng {
            let lng = Double(i) * step
            line(lng, s, lng, n)
            // On the map beyond the date line a longitude still reads as -180 to 180.
            let shown = (lng + 540).truncatingRemainder(dividingBy: 360) - 180
            text(lng, labelLat, label(shown, isLatitude: false, step: step))
            i += 1
        }
        var j = Int64(ceil(s / step - 1e-9))
        let lastLat = Int64(floor(n / step + 1e-9))
        while j <= lastLat {
            let lat = Double(j) * step
            line(west, lat, e, lat)
            text(labelLng, lat, label(lat, isLatitude: true, step: step))
            j += 1
        }
        return #"{"type":"FeatureCollection","features":["# + features.joined(separator: ",") + "]}"
    }
}

/// Shows or hides the grid and redraws it when the map stops moving (Android GraticuleController).
@MainActor
final class GraticuleController: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var visible = false

    private static let navMarksLayer = "navmarks"

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        visible = app.settings.showGrid
        app.map.onStyleLoaded { [weak self] _ in self?.installLayers() }
        app.map.onCameraIdle { [weak self] in self?.refresh() }
    }

    func setVisible(_ value: Bool) {
        visible = value
        if let style = app.map.style {
            style.layer(withIdentifier: MapLayerIds.gridLine)?.isVisible = value
            style.layer(withIdentifier: MapLayerIds.gridLabel)?.isVisible = value
        }
        refresh()
    }

    /// Over the imported layers (and the nav marks), under routes and waypoints.
    private func installLayers() {
        guard let style = app.map.style, let source = app.map.geoJSONSource(MapLayerIds.gridSource) else { return }
        let base = style.layer(withIdentifier: Self.navMarksLayer) != nil ? Self.navMarksLayer : MapLayerIds.overlayAnchor

        let line = MLNLineStyleLayer(identifier: MapLayerIds.gridLine, source: source)
        line.predicate = NavExtrasStyle.geometry("LineString")
        line.lineColor = NavExtrasStyle.colour("#F4F6F8")
        line.lineOpacity = NavExtrasStyle.number(0.35)
        line.lineWidth = NavExtrasStyle.number(1)
        line.isVisible = visible
        NavExtrasStyle.insert(line, above: base, map: app.map)

        let label = MLNSymbolStyleLayer(identifier: MapLayerIds.gridLabel, source: source)
        label.predicate = NavExtrasStyle.geometry("Point")
        label.text = NSExpression(forKeyPath: "label")
        label.textFontNames = NSExpression(forConstantValue: [app.basemap.labelFont])
        label.textFontSize = NavExtrasStyle.number(11)
        label.textColor = NavExtrasStyle.colour("#F4F6F8")
        label.textHaloColor = NavExtrasStyle.colour("#0B1622")
        label.textHaloWidth = NavExtrasStyle.number(1.2)
        label.textAnchor = NavExtrasStyle.text("left")
        label.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0.3, dy: 0)))
        label.textAllowsOverlap = NSExpression(forConstantValue: true)
        label.textIgnoresPlacement = NSExpression(forConstantValue: true)
        label.isVisible = visible
        NavExtrasStyle.insert(label, above: MapLayerIds.gridLine, map: app.map)
        refresh()
    }

    /// Called when the map has stopped moving.
    func refresh() {
        guard visible, app.map.style != nil else { return }
        let b = app.map.visibleBounds
        app.map.setGeoJSON(MapLayerIds.gridSource, Graticule.geoJSON(south: b.south, west: b.west, north: b.north, east: b.east))
    }
}
