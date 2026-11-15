import MapLibre
import SwiftUI
import UIKit

/// Colours and small helpers shared by the nav-extras map layers (Android map/BaseMapLayers.kt).
enum NavExtrasStyle {
    static let accent = "#00C8B4"
    static let measure = "#F59E0B"
    static let white = "#FFFFFF"
    static let track = "#F43F5E"
    /// A dark outline under lines so they stay visible over depth colours of the same hue.
    static let casing = "#0B1622"

    static let msToKnots = 1.943844
    static let metresPerNm = 1852.0

    static func colour(_ hex: String) -> NSExpression { NSExpression(forConstantValue: UIColor(hex: hex)) }
    static func number(_ value: Double) -> NSExpression { NSExpression(forConstantValue: NSNumber(value: value)) }
    static func text(_ value: String) -> NSExpression { NSExpression(forConstantValue: value) }
    static func geometry(_ type: String) -> NSPredicate { NSPredicate(format: "$geometryType == %@", type) }
    static func kind(_ value: String) -> NSPredicate { NSPredicate(format: "kind == %@", value) }

    /// A dark round-ended casing line under a coloured line.
    static func casingLayer(_ id: String, source: MLNSource, width: Double) -> MLNLineStyleLayer {
        let layer = MLNLineStyleLayer(identifier: id, source: source)
        layer.predicate = geometry("LineString")
        layer.lineColor = colour(casing)
        layer.lineWidth = number(width)
        layer.lineOpacity = number(0.85)
        layer.lineCap = text("round")
        layer.lineJoin = text("round")
        return layer
    }

    /// Epoch milliseconds now (Android System.currentTimeMillis()).
    static func nowMillis() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }

    /// A US-locale date formatter, as Android's SimpleDateFormat(pattern, Locale.US).
    static func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = pattern
        return f
    }

    /// Inserts `layer` directly above the layer `aboveId` (or on top if it is missing).
    @MainActor
    static func insert(_ layer: MLNStyleLayer, above aboveId: String, map: MapController) {
        guard let style = map.style else { return }
        if let old = style.layer(withIdentifier: layer.identifier) { style.removeLayer(old) }
        if let above = style.layer(withIdentifier: aboveId) {
            style.insertLayer(layer, above: above)
        } else {
            style.addLayer(layer)
        }
    }

    /// The bounds of some points, padded so a single point (or a straight line) still has an area.
    static func bounds(_ points: [(lat: Double, lng: Double)]) -> GeoBounds? {
        guard let first = points.first else { return nil }
        var b = GeoBounds(west: first.lng, south: first.lat, east: first.lng, north: first.lat)
        for p in points {
            b.west = min(b.west, p.lng); b.east = max(b.east, p.lng)
            b.south = min(b.south, p.lat); b.north = max(b.north, p.lat)
        }
        let pad = 0.001
        b.west = max(b.west - pad, -180); b.east = min(b.east + pad, 180)
        b.south = max(b.south - pad, -90); b.north = min(b.north + pad, 90)
        return b
    }
}

/// Alerts shown with UIKit over whatever is on screen, sheets included
/// (a SwiftUI alert on the chart can't show while a sheet is up).
@MainActor
enum NavExtrasAlert {
    struct Action {
        let title: String
        var style: UIAlertAction.Style = .default
        var handler: () -> Void = {}
    }

    @discardableResult
    static func show(_ title: String, _ message: String, actions: [Action]) -> UIAlertController? {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        let list = actions.isEmpty ? [Action(title: "OK")] : actions
        for a in list {
            let handler = a.handler
            alert.addAction(UIAlertAction(title: a.title, style: a.style) { _ in handler() })
        }
        guard let top = AppModel.topViewController() else { return nil }
        top.present(alert, animated: true)
        return alert
    }

    static func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
