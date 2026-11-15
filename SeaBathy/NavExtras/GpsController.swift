import Combine
import CoreLocation
import MapLibre
import SwiftUI
import UIKit

/// The GPS button's states (Android GpsController.Mode).
enum GpsMode { case off, locate, follow }

/**
 The GPS button (off, locate, follow) and the position marker (Android
 map/GpsController + MainActivity.cycleGps). Locate centres once; follow keeps
 the boat on screen, turned course-up when that setting is on.
 */
@MainActor
final class GpsController: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var mode: GpsMode = .off

    private var lastFix: CLLocation?
    private var centredOnce = false
    private var started = false
    private var cancellables = Set<AnyCancellable>()

    private static let user = "gps"
    private static let locateZoom = 14.0
    /// Closer than this to the last fix counts as not having moved, metres.
    private static let stillM = 1.0
    private static let courseUpMinSpeedMs = 0.5
    private static let headingImage = "gps-heading-arrow"
    private static let headingLayer = "gps-heading"

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        guard !started else { return }
        started = true
        app.map.onStyleLoaded { [weak self] _ in self?.styleLoaded() }
        app.location.onLocation { [weak self] location in self?.handle(location) }
        app.location.$authorization
            .dropFirst()
            .sink { [weak self] status in self?.authorizationChanged(status) }
            .store(in: &cancellables)
    }

    /// The last usable GPS speed in knots, while the GPS is on.
    var speedKnots: Double? {
        guard mode != .off, let fix = lastFix, fix.speed >= 0 else { return nil }
        return fix.speed * NavExtrasStyle.msToKnots
    }

    /// The latest fix while the GPS is on.
    var currentFix: CLLocation? { mode == .off ? nil : lastFix }

    // Modes ---------------------------------------------------------------------------

    /// Off -> locate -> follow -> off, asking for location permission when turning on.
    func cycle() {
        switch mode {
        case .off: mode = .locate
        case .locate: mode = .follow
        case .follow: mode = .off
        }
        centredOnce = false
        if mode == .off {
            app.location.stop(for: Self.user)
            clearMarker()
            return
        }
        // Locking onto follow snaps to the last fix straight away rather than
        // waiting for the next one, which may never come if the boat is still.
        if mode == .follow, let fix = lastFix { recentre(on: fix) }
        turnOnLocation()
    }

    /// Turns the GPS on (locate) if it is off. Used by tides, conditions and the 3D view.
    func ensureOn() {
        if mode == .off { cycle() }
    }

    /// Straight to follow mode (navigation keeps the boat on screen).
    func follow() {
        mode = .follow
        centredOnce = false
        if let fix = lastFix { recentre(on: fix) }
        turnOnLocation()
    }

    /// The user moved the chart: follow drops to locate so the next fix doesn't drag
    /// the chart back. Returns true when the mode changed.
    @discardableResult
    func releaseFollow() -> Bool {
        guard mode == .follow else { return false }
        mode = .locate
        centredOnce = true
        return true
    }

    /// Back to off, e.g. when location permission is refused.
    func turnOff() {
        mode = .off
        centredOnce = false
        app.location.stop(for: Self.user)
        clearMarker()
    }

    private func turnOnLocation() {
        let location = app.location
        if location.isDenied {
            turnOff()
            explainLocationOff(purpose: "show your position on the chart")
            return
        }
        if location.isAuthorized && Self.isApproximateOnly {
            turnOff()
            explainApproximate()
            return
        }
        location.start(for: Self.user)
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        guard mode != .off else { return }
        if status == .denied || status == .restricted {
            // Drop back to off so the button does not claim to be tracking.
            turnOff()
            explainLocationOff(purpose: "show your position on the chart")
        } else if (status == .authorizedWhenInUse || status == .authorizedAlways) && Self.isApproximateOnly {
            turnOff()
            explainApproximate()
        }
    }

    private static var isApproximateOnly: Bool {
        CLLocationManager().accuracyAuthorization == .reducedAccuracy
    }

    /// Location is off for the app: say how to turn it on in Settings.
    func explainLocationOff(purpose: String) {
        NavExtrasAlert.show("Location", "Location is turned off for SeaBathy. To \(purpose), allow location for SeaBathy in Settings.", actions: [
            .init(title: "Open Settings", handler: { NavExtrasAlert.openAppSettings() }),
            .init(title: "Not now", style: .cancel)
        ])
    }

    private func explainApproximate() {
        NavExtrasAlert.show("Location", "SeaBathy needs Precise Location to show your position: approximate location can be kilometres out. Turn on Precise Location for SeaBathy in Settings.", actions: [
            .init(title: "Open Settings", handler: { NavExtrasAlert.openAppSettings() }),
            .init(title: "Not now", style: .cancel)
        ])
    }

    // Fixes -------------------------------------------------------------------------

    private func handle(_ location: CLLocation) {
        guard location.horizontalAccuracy >= 0 else { return }
        let previous = lastFix
        lastFix = location
        guard mode != .off else { return }
        // GPS reports every second even when still; don't redraw the chart for the same spot.
        if let previous, centredOnce, previous.distance(from: location) < Self.stillM,
           Self.heading(previous) == Self.heading(location) {
            return
        }
        draw(location)
        switch mode {
        case .locate:
            if !centredOnce {
                app.map.fly(to: location.coordinate.latitude, location.coordinate.longitude, zoom: Self.locateZoom)
                centredOnce = true
            }
        case .follow:
            recentre(on: location)
            centredOnce = true
        case .off:
            break
        }
    }

    /// The course to turn the chart to (course-up), or nil to leave it.
    private func chartBearing(_ location: CLLocation) -> Double? {
        guard app.navSettings.settings.courseUp, location.course >= 0, location.speed > Self.courseUpMinSpeedMs else { return nil }
        return location.course
    }

    private func recentre(on location: CLLocation) {
        guard let map = app.map.mapView else { return }
        if let bearing = chartBearing(location) {
            // Jumped rather than animated: NavigationFeature also turns the chart for course-up,
            // and a second camera animation would cut this one short.
            map.setCenter(location.coordinate, zoomLevel: map.zoomLevel, direction: bearing, animated: false)
        } else {
            map.setCenter(location.coordinate, animated: true)
        }
    }

    /// The direction of travel, when moving fast enough for it to mean anything.
    private static func heading(_ location: CLLocation) -> Int? {
        guard location.course >= 0, location.speed > courseUpMinSpeedMs else { return nil }
        return Int(location.course.rounded())
    }

    // Marker --------------------------------------------------------------------------

    private func draw(_ location: CLLocation) {
        let lat = location.coordinate.latitude, lng = location.coordinate.longitude
        var features: [String] = []
        let accuracy = location.horizontalAccuracy
        if accuracy > 0 {
            let metresPerDegLat = 111_320.0
            let metresPerDegLng = max(metresPerDegLat * cos(GeoMath.rad(lat)), 1)
            let steps = 48
            let ring = (0...steps).map { i -> String in
                let angle = 2 * Double.pi * Double(i) / Double(steps)
                let pLat = lat + accuracy * cos(angle) / metresPerDegLat
                let pLng = lng + accuracy * sin(angle) / metresPerDegLng
                return String(format: "[%.7f,%.7f]", pLng, pLat)
            }.joined(separator: ",")
            features.append(#"{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[\#(ring)]]},"properties":{}}"#)
        }
        let heading = Self.heading(location)
        let props = heading.map { #"{"hasHeading":1,"heading":\#($0)}"# } ?? #"{"hasHeading":0,"heading":0}"#
        features.append(String(format: #"{"type":"Feature","geometry":{"type":"Point","coordinates":[%.7f,%.7f]},"properties":%@}"#, lng, lat, props))
        app.map.setGeoJSON(MapLayerIds.gpsSource, #"{"type":"FeatureCollection","features":["# + features.joined(separator: ",") + "]}")
    }

    /// Clears the position marker without changing the mode.
    func clearMarker() {
        app.map.setGeoJSON(MapLayerIds.gpsSource, MapLayerIds.emptyGeoJSON)
    }

    private func styleLoaded() {
        // Added once every other feature has put its layers on, so the boat draws on top.
        let generation = app.map.styleGeneration
        Task { @MainActor [weak self] in
            guard let self, self.app.map.styleGeneration == generation else { return }
            self.installLayers()
        }
    }

    private func installLayers() {
        guard let source = app.map.geoJSONSource(MapLayerIds.gpsSource) else { return }
        app.map.setImage(Self.drawHeadingArrow(), name: Self.headingImage)

        let accuracy = MLNFillStyleLayer(identifier: MapLayerIds.gpsAccuracy, source: source)
        accuracy.predicate = NavExtrasStyle.geometry("Polygon")
        accuracy.fillColor = NavExtrasStyle.colour(NavExtrasStyle.accent)
        accuracy.fillOpacity = NavExtrasStyle.number(0.12)
        accuracy.fillOutlineColor = NavExtrasStyle.colour(NavExtrasStyle.accent)
        app.map.add(accuracy, placement: .top)

        let heading = MLNSymbolStyleLayer(identifier: Self.headingLayer, source: source)
        heading.predicate = NSPredicate(format: "hasHeading == 1")
        heading.iconImageName = NavExtrasStyle.text(Self.headingImage)
        heading.iconRotation = NSExpression(forKeyPath: "heading")
        heading.iconRotationAlignment = NavExtrasStyle.text("map")
        heading.iconAllowsOverlap = NSExpression(forConstantValue: true)
        heading.iconIgnoresPlacement = NSExpression(forConstantValue: true)
        app.map.add(heading, placement: .top)

        let dot = MLNCircleStyleLayer(identifier: MapLayerIds.gpsDot, source: source)
        dot.predicate = NavExtrasStyle.geometry("Point")
        dot.circleRadius = NavExtrasStyle.number(8)
        dot.circleColor = NavExtrasStyle.colour(NavExtrasStyle.accent)
        dot.circleStrokeWidth = NavExtrasStyle.number(2)
        dot.circleStrokeColor = NavExtrasStyle.colour(NavExtrasStyle.white)
        app.map.add(dot, placement: .top)

        if mode != .off, let fix = lastFix { draw(fix) } else { clearMarker() }
    }

    /// A pointer ahead of the dot, pointing up (north) before rotation.
    private static func drawHeadingArrow() -> UIImage {
        let size = CGSize(width: 44, height: 44)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 22, y: 1))
            path.addLine(to: CGPoint(x: 30, y: 13))
            path.addLine(to: CGPoint(x: 22, y: 10))
            path.addLine(to: CGPoint(x: 14, y: 13))
            path.close()
            UIColor(hex: NavExtrasStyle.accent).setFill()
            path.fill()
            UIColor.white.setStroke()
            path.lineWidth = 1.5
            path.lineJoinStyle = .round
            path.stroke()
            _ = ctx
        }
    }
}
