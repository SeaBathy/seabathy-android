import MapLibre
import UIKit

/**
 The route, active-navigation and waypoint layers (Android BaseMapLayers'
 route/nav parts and WaypointOverlayController). Called from the feature's
 `map.onStyleLoaded` handlers, so they are re-added after every style load.
 */
@MainActor
enum NavMapLayers {
    private static let accent = UIColor(hex: "#00C8B4")
    private static let nav = UIColor(hex: "#E040FB")
    private static let casing = UIColor(hex: "#0B1622")

    private static func isGeometry(_ type: String) -> NSPredicate {
        NSPredicate(format: "$geometryType = %@", type)
    }

    // Route --------------------------------------------------------------------------

    static func installRoute(_ map: MapController, geoJSON: String) {
        guard let source = map.geoJSONSource(MapLayerIds.routeSource) else { return }
        source.shape = MapController.shape(fromGeoJSON: geoJSON)

        let casingLayer = MLNLineStyleLayer(identifier: MapLayerIds.routeCasing, source: source)
        casingLayer.predicate = isGeometry("LineString")
        casingLayer.lineColor = NSExpression(forConstantValue: casing)
        casingLayer.lineWidth = NSExpression(forConstantValue: 7)
        casingLayer.lineOpacity = NSExpression(forConstantValue: 0.85)
        casingLayer.lineCap = NSExpression(forConstantValue: "round")
        casingLayer.lineJoin = NSExpression(forConstantValue: "round")
        map.add(casingLayer, placement: .top)

        let line = MLNLineStyleLayer(identifier: MapLayerIds.routeLine, source: source)
        line.predicate = isGeometry("LineString")
        line.lineColor = NSExpression(forConstantValue: accent)
        line.lineWidth = NSExpression(forConstantValue: 4)
        line.lineDashPattern = NSExpression(forConstantValue: [2, 1])
        map.add(line, placement: .top)

        let pts = MLNCircleStyleLayer(identifier: MapLayerIds.routePoints, source: source)
        pts.predicate = isGeometry("Point")
        pts.circleRadius = NSExpression(forConstantValue: 7)
        pts.circleColor = NSExpression(forConstantValue: accent)
        pts.circleStrokeWidth = NSExpression(forConstantValue: 2)
        pts.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        map.add(pts, placement: .top)
    }

    // Active navigation: the leg solid, the line to the next point dashed, a ring on the point.

    static func installNav(_ map: MapController) {
        guard let source = map.geoJSONSource(MapLayerIds.navSource) else { return }

        let leg = MLNLineStyleLayer(identifier: MapLayerIds.navLeg, source: source)
        leg.predicate = NSPredicate(format: "kind == %@", "leg")
        leg.lineColor = NSExpression(forConstantValue: nav)
        leg.lineWidth = NSExpression(forConstantValue: 5)
        leg.lineCap = NSExpression(forConstantValue: "round")
        map.add(leg, placement: .top)

        let toTarget = MLNLineStyleLayer(identifier: MapLayerIds.navToTarget, source: source)
        toTarget.predicate = NSPredicate(format: "kind == %@", "toTarget")
        toTarget.lineColor = NSExpression(forConstantValue: nav)
        toTarget.lineWidth = NSExpression(forConstantValue: 2.5)
        toTarget.lineDashPattern = NSExpression(forConstantValue: [2, 2])
        map.add(toTarget, placement: .top)

        let target = MLNCircleStyleLayer(identifier: MapLayerIds.navTarget, source: source)
        target.predicate = isGeometry("Point")
        target.circleRadius = NSExpression(forConstantValue: 14)
        target.circleOpacity = NSExpression(forConstantValue: 0)
        target.circleStrokeWidth = NSExpression(forConstantValue: 3)
        target.circleStrokeColor = NSExpression(forConstantValue: nav)
        map.add(target, placement: .top)
    }

    // Waypoints: a coloured dot, the icon image on it and an optional name label.

    static let propertyId = "id"

    static func installWaypoints(_ map: MapController, waypoints: [Waypoint], labelFont: String, namesVisible: Bool) {
        guard let source = map.geoJSONSource(MapLayerIds.waypointSource) else { return }
        // Added every time: MapLibre drops custom images with the old style.
        registeredImages.removeAll()
        registerImages(map, for: waypoints)
        source.shape = MapController.shape(fromGeoJSON: waypointGeoJSON(waypoints))

        let dot = MLNCircleStyleLayer(identifier: MapLayerIds.waypointLayer, source: source)
        dot.circleRadius = NSExpression(forConstantValue: 8)
        dot.circleColor = NSExpression(forKeyPath: "color")
        dot.circleStrokeWidth = NSExpression(forConstantValue: 2)
        dot.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        map.add(dot, placement: .top)

        let icon = MLNSymbolStyleLayer(identifier: MapLayerIds.waypointIconLayer, source: source)
        icon.iconImageName = NSExpression(forKeyPath: "img")
        icon.iconScale = NSExpression(forConstantValue: 1)
        // Every icon always draws, like the dot under it; only labels avoid each other.
        icon.iconAllowsOverlap = NSExpression(forConstantValue: true)
        icon.iconIgnoresPlacement = NSExpression(forConstantValue: true)
        map.add(icon, placement: .top)

        let label = MLNSymbolStyleLayer(identifier: MapLayerIds.waypointLabelLayer, source: source)
        label.text = NSExpression(forKeyPath: "name")
        label.textFontNames = NSExpression(forConstantValue: [labelFont])
        // Hidden below zoom 11, fading in over the next zoom, growing to 16 pt at zoom 18.
        label.textFontSize = NSExpression(format: "mgl_interpolate:withCurveType:parameters:stops:($zoomLevel, 'linear', nil, %@)",
                                          [11: 10, 13: 12, 18: 16] as NSDictionary)
        label.textOpacity = NSExpression(format: "mgl_interpolate:withCurveType:parameters:stops:($zoomLevel, 'linear', nil, %@)",
                                         [11: 0, 12: 1] as NSDictionary)
        label.textColor = NSExpression(forConstantValue: UIColor.white)
        label.textHaloColor = NSExpression(forConstantValue: UIColor.black)
        label.textHaloWidth = NSExpression(forConstantValue: 1.2)
        label.textAnchor = NSExpression(forConstantValue: "top")
        label.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 1.4)))
        label.textJustification = NSExpression(forConstantValue: "center")
        label.textAllowsOverlap = NSExpression(forConstantValue: false)
        label.isVisible = namesVisible
        map.add(label, placement: .top)
    }

    static func renderWaypoints(_ map: MapController, waypoints: [Waypoint]) {
        guard map.style != nil else { return }
        registerImages(map, for: waypoints)
        map.setGeoJSON(MapLayerIds.waypointSource, waypointGeoJSON(waypoints))
    }

    static func setNamesVisible(_ map: MapController, _ visible: Bool) {
        map.style?.layer(withIdentifier: MapLayerIds.waypointLabelLayer)?.isVisible = visible
    }

    /// The waypoint under a screen point, if any.
    static func waypointId(_ map: MapController, at point: CGPoint) -> String? {
        let features = map.features(at: point, radius: 24,
                                    layers: [MapLayerIds.waypointLayer, MapLayerIds.waypointIconLayer])
        for f in features {
            if let id = f.attribute(forKey: propertyId) as? String { return id }
        }
        return nil
    }

    static func waypointGeoJSON(_ waypoints: [Waypoint]) -> String {
        NavGeoJson.featureCollection(waypoints.map { w in
            let icon = WaypointIcon.byId(w.icon).id
            let props = "{\"id\":\"\(NavGeoJson.jsonEscape(w.id))\",\"color\":\"\(NavGeoJson.jsonEscape(safeColor(w.color)))\","
                + "\"icon\":\"\(icon)\",\"img\":\"\(imageName(icon: icon, color: w.color))\","
                + "\"name\":\"\(NavGeoJson.jsonEscape(w.name))\"}"
            return NavGeoJson.point(lat: w.lat, lng: w.lng, properties: props)
        })
    }

    // Icon images ----------------------------------------------------------------------

    private static var registeredImages = Set<String>()

    private static func safeColor(_ hex: String) -> String {
        UIColor(hex: hex).hexString
    }

    static func imageName(icon: String, color: String) -> String {
        "wpt-\(icon)-\(safeColor(color).dropFirst())"
    }

    private static func registerImages(_ map: MapController, for waypoints: [Waypoint]) {
        for w in waypoints {
            let icon = WaypointIcon.byId(w.icon)
            let name = imageName(icon: icon.id, color: w.color)
            if registeredImages.contains(name) { continue }
            map.setImage(image(icon: icon, color: UIColor(hex: w.color)), name: name)
            registeredImages.insert(name)
        }
    }

    /// The waypoint's coloured dot with its white symbol, as a symbol-layer image.
    static func image(icon: WaypointIcon, color: UIColor) -> UIImage {
        let size: CGFloat = 22
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        return renderer.image { _ in
            let rect = CGRect(x: 1, y: 1, width: size - 2, height: size - 2)
            let circle = UIBezierPath(ovalIn: rect)
            color.setFill()
            circle.fill()
            UIColor.white.setStroke()
            circle.lineWidth = 2
            circle.stroke()
            let config = UIImage.SymbolConfiguration(pointSize: 10, weight: .bold)
            if let symbol = UIImage(systemName: icon.symbol, withConfiguration: config)?
                .withTintColor(.white, renderingMode: .alwaysOriginal) {
                let s = symbol.size
                symbol.draw(in: CGRect(x: (size - s.width) / 2, y: (size - s.height) / 2, width: s.width, height: s.height))
            }
        }
    }
}
