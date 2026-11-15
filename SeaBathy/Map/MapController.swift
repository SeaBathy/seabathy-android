import CoreLocation
import MapLibre
import UIKit

/// Every MapLibre source and layer id used by the app (Android map/MapLayerIds.kt).
enum MapLayerIds {
    /// An invisible layer every overlay (depth, MBTiles, XYZ) is inserted below, so
    /// waypoints, routes and measurements stay above imported data.
    static let overlayAnchorSource = "overlay-anchor-src"
    static let overlayAnchor = "overlay-anchor"

    static let gridSource = "grid-src", gridLine = "grid-line", gridLabel = "grid-label"
    static let routeSource = "route-src", routeCasing = "route-casing", routeLine = "route-line", routePoints = "route-pts"
    static let measureSource = "meas-src", measureCasing = "meas-casing", measureLine = "meas-line", measurePoints = "meas-pts"
    static let navSource = "nav-src", navLeg = "nav-leg", navToTarget = "nav-to-target", navTarget = "nav-target"
    static let trackSource = "track-src", trackCasing = "track-casing", trackLive = "track-live", trackSaved = "track-saved"
    static let anchorSource = "anchor-src", anchorFill = "anchor-fill", anchorRing = "anchor-ring", anchorPoint = "anchor-pt"
    static let gpsSource = "gps-src", gpsAccuracy = "gps-acc", gpsDot = "gps-dot"
    static let waypointSource = "waypoint-overlay-source", waypointLayer = "waypoint-overlay-layer"
    static let waypointIconLayer = "waypoint-overlay-icon-layer", waypointLabelLayer = "waypoint-overlay-label-layer"

    static func source(for layerId: String) -> String { "\(layerId)-src" }
    static func layer(for layerId: String) -> String { "\(layerId)-layer" }

    static let emptyGeoJSON = #"{"type":"FeatureCollection","features":[]}"#
}

/// Where a feature layer sits in the stack.
enum MapLayerPlacement {
    /// Below the overlay anchor, i.e. among imported data (depth, MBTiles, XYZ layers).
    case overlays
    /// Above all imported data: the app's own marks and lines. Later calls go on top.
    case top
}

/**
 Owns the MLNMapView and is the one place features talk to MapLibre through
 (Android MapHolder + MapStyleCoordinator's install order).

 Features register `onStyleLoaded` handlers once. After every style (re)load the
 controller installs the overlay anchor and then calls the handlers in
 registration order, so each feature re-adds its sources and layers.
 */
@MainActor
final class MapController: NSObject, ObservableObject {
    private(set) weak var mapView: MLNMapView?
    var style: MLNStyle? { mapView?.style }

    /// Bumped on every style load; work started for an older style checks it and gives up.
    private(set) var styleGeneration = 0
    @Published private(set) var styleReady = false

    @Published private(set) var centre = CLLocationCoordinate2D(latitude: 0, longitude: 0)
    @Published private(set) var zoom: Double = 1.5
    @Published private(set) var bearing: Double = 0

    private var styleHandlers: [(MLNStyle) -> Void] = []
    private var idleHandlers: [() -> Void] = []
    private var moveHandlers: [() -> Void] = []
    private var styleFailureHandler: (() -> Void)?

    /// A tap on the map: the tapped coordinate and screen point. The app decides what it means.
    var onTap: ((CLLocationCoordinate2D, CGPoint) -> Void)?
    /// A long press on the map.
    var onLongPress: ((CLLocationCoordinate2D, CGPoint) -> Void)?
    /// The user started dragging the map (releases GPS follow, as on a chartplotter).
    var onUserPan: (() -> Void)?

    // Registration -------------------------------------------------------------

    func onStyleLoaded(_ handler: @escaping (MLNStyle) -> Void) {
        styleHandlers.append(handler)
        if let style, styleReady { handler(style) }
    }

    func onCameraIdle(_ handler: @escaping () -> Void) { idleHandlers.append(handler) }
    func onCameraMove(_ handler: @escaping () -> Void) { moveHandlers.append(handler) }
    func onStyleFailed(_ handler: @escaping () -> Void) { styleFailureHandler = handler }

    // Wiring from ChartMapView ---------------------------------------------------

    func attach(_ map: MLNMapView) {
        mapView = map
        centre = map.centerCoordinate
        zoom = map.zoomLevel
        bearing = map.direction
    }

    func styleDidLoad(_ style: MLNStyle) {
        styleGeneration += 1
        installOverlayAnchor(style)
        styleReady = true
        for handler in styleHandlers { handler(style) }
    }

    func styleDidFail(_ error: Error) {
        DiagnosticLog.log("Map", "style failed to load: \(error.localizedDescription)")
        styleFailureHandler?()
    }

    func cameraDidMove() {
        guard let map = mapView else { return }
        centre = map.centerCoordinate
        zoom = map.zoomLevel
        bearing = map.direction
        for h in moveHandlers { h() }
    }

    func cameraDidBecomeIdle() {
        cameraDidMove()
        for h in idleHandlers { h() }
    }

    // Style ------------------------------------------------------------------------

    func setStyle(url: URL) {
        styleReady = false
        mapView?.styleURL = url
    }

    private func installOverlayAnchor(_ style: MLNStyle) {
        guard style.layer(withIdentifier: MapLayerIds.overlayAnchor) == nil else { return }
        let source = style.source(withIdentifier: MapLayerIds.overlayAnchorSource)
            ?? MLNShapeSource(identifier: MapLayerIds.overlayAnchorSource, shape: nil, options: nil)
        if style.source(withIdentifier: MapLayerIds.overlayAnchorSource) == nil { style.addSource(source) }
        let anchor = MLNCircleStyleLayer(identifier: MapLayerIds.overlayAnchor, source: source)
        anchor.circleOpacity = NSExpression(forConstantValue: 0)
        anchor.isVisible = false
        style.addLayer(anchor)
    }

    /// Adds a layer at the given place in the stack (replacing one with the same id).
    func add(_ layer: MLNStyleLayer, placement: MapLayerPlacement) {
        guard let style else { return }
        if let old = style.layer(withIdentifier: layer.identifier) { style.removeLayer(old) }
        switch placement {
        case .overlays:
            if let anchor = style.layer(withIdentifier: MapLayerIds.overlayAnchor) {
                style.insertLayer(layer, below: anchor)
            } else {
                style.addLayer(layer)
            }
        case .top:
            style.addLayer(layer)
        }
    }

    /// Inserts `layer` directly below the layer with id `belowId` (or at the overlay level if it is missing).
    func insert(_ layer: MLNStyleLayer, below belowId: String) {
        guard let style else { return }
        if let old = style.layer(withIdentifier: layer.identifier) { style.removeLayer(old) }
        if let below = style.layer(withIdentifier: belowId) {
            style.insertLayer(layer, below: below)
        } else {
            add(layer, placement: .overlays)
        }
    }

    func removeLayer(_ id: String) {
        guard let style, let layer = style.layer(withIdentifier: id) else { return }
        style.removeLayer(layer)
    }

    func removeSource(_ id: String) {
        guard let style, let source = style.source(withIdentifier: id) else { return }
        style.removeSource(source)
    }

    /// The GeoJSON source with this id, created empty if missing.
    @discardableResult
    func geoJSONSource(_ id: String) -> MLNShapeSource? {
        guard let style else { return nil }
        if let existing = style.source(withIdentifier: id) as? MLNShapeSource { return existing }
        let source = MLNShapeSource(identifier: id, shape: nil, options: nil)
        style.addSource(source)
        return source
    }

    /// Replaces a GeoJSON source's data with a GeoJSON string (FeatureCollection, Feature or geometry).
    func setGeoJSON(_ sourceId: String, _ json: String) {
        guard let source = geoJSONSource(sourceId) else { return }
        source.shape = Self.shape(fromGeoJSON: json)
    }

    func setFeatures(_ sourceId: String, _ features: [MLNShape & MLNFeature]) {
        guard let source = geoJSONSource(sourceId) else { return }
        source.shape = MLNShapeCollectionFeature(shapes: features)
    }

    static func shape(fromGeoJSON json: String) -> MLNShape? {
        guard let data = json.data(using: .utf8) else { return nil }
        do {
            return try MLNShape(data: data, encoding: String.Encoding.utf8.rawValue)
        } catch {
            DiagnosticLog.log("Map", "bad GeoJSON: \(error)")
            return nil
        }
    }

    /// Adds an image to the current style (for symbol layers).
    func setImage(_ image: UIImage, name: String) {
        style?.setImage(image, forName: name)
    }

    // Camera ---------------------------------------------------------------------------

    var visibleBounds: GeoBounds {
        guard let map = mapView else { return GeoBounds(west: -180, south: -85, east: 180, north: 85) }
        let b = map.visibleCoordinateBounds
        return GeoBounds(west: b.sw.longitude, south: b.sw.latitude, east: b.ne.longitude, north: b.ne.latitude)
    }

    func fly(to lat: Double, _ lng: Double, zoom: Double? = nil, animated: Bool = true) {
        guard let map = mapView else { return }
        let target = CLLocationCoordinate2D(latitude: lat, longitude: lng)
        if let zoom {
            map.setCenter(target, zoomLevel: zoom, animated: animated)
        } else {
            map.setCenter(target, animated: animated)
        }
    }

    func fit(_ bounds: GeoBounds, padding: CGFloat = 40, maxZoom: Double? = nil) {
        guard let map = mapView, bounds.isValid else { return }
        let b = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: bounds.south, longitude: bounds.west),
                                    ne: CLLocationCoordinate2D(latitude: bounds.north, longitude: bounds.east))
        let insets = UIEdgeInsets(top: padding, left: padding, bottom: padding, right: padding)
        map.setVisibleCoordinateBounds(b, edgePadding: insets, animated: maxZoom == nil) { [weak map] in
            guard let map, let maxZoom, map.zoomLevel > maxZoom else { return }
            map.setZoomLevel(maxZoom, animated: false)
        }
        if maxZoom != nil, let map = mapView, map.zoomLevel > maxZoom! {
            map.setZoomLevel(maxZoom!, animated: false)
        }
    }

    func setBearing(_ degrees: Double, animated: Bool = true) {
        mapView?.setDirection(degrees, animated: animated)
    }

    func point(for coordinate: CLLocationCoordinate2D) -> CGPoint {
        guard let map = mapView else { return .zero }
        return map.convert(coordinate, toPointTo: map)
    }

    func coordinate(for point: CGPoint) -> CLLocationCoordinate2D {
        guard let map = mapView else { return centre }
        return map.convert(point, toCoordinateFrom: map)
    }

    /// Features drawn by the given layers within `radius` points of a screen point.
    func features(at point: CGPoint, radius: CGFloat = 22, layers: Set<String>) -> [MLNFeature] {
        guard let map = mapView else { return [] }
        let rect = CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2)
        return map.visibleFeatures(in: rect, styleLayerIdentifiers: layers)
    }
}

/**
 Style expressions built from the MapLibre style-spec JSON form. iOS 26 refuses
 custom functions (such as MGL_MATCH or mgl_interpolate) inside
 `NSExpression(format:)` strings and the app would stop, so never use those here.
 */
enum MapExpr {
    /// ["interpolate", ["linear"], ["zoom"], z1, v1, z2, v2, ...]
    static func byZoom(_ stops: [(Double, Double)]) -> NSExpression {
        var json: [Any] = ["interpolate", ["linear"], ["zoom"]]
        for (zoom, value) in stops { json.append(zoom); json.append(value) }
        return NSExpression(mlnJSONObject: json)
    }

    /// ["match", ["get", key], case1, colour1, ..., fallback] with "#RRGGBB" colours.
    static func matchColour(_ key: String, _ cases: [(String, String)], fallback: String) -> NSExpression {
        var json: [Any] = ["match", ["get", key]]
        for (value, colour) in cases { json.append(value); json.append(colour) }
        json.append(fallback)
        return NSExpression(mlnJSONObject: json)
    }
}
