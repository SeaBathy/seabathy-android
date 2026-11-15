import CoreLocation
import Foundation

/// Where the map is looking.
struct MapCamera {
    var center: CLLocationCoordinate2D
    var zoom: Double
    var bearing: Double
}

/// Remembers the map position between launches (AppSettingsRepository on Android).
enum CameraStore {
    private static let defaults = UserDefaults.standard
    private static let latKey = "cameraLat"
    private static let lonKey = "cameraLon"
    private static let zoomKey = "cameraZoom"
    private static let bearingKey = "cameraBearing"

    /// Same first-launch view as Android: the whole world.
    static let initial = MapCamera(center: CLLocationCoordinate2D(latitude: 15, longitude: 0), zoom: 1.5, bearing: 0)

    static func load() -> MapCamera {
        if let fromArgs = launchArgumentCamera() { return fromArgs }
        guard defaults.object(forKey: latKey) != nil else { return initial }
        return MapCamera(
            center: CLLocationCoordinate2D(latitude: defaults.double(forKey: latKey),
                                           longitude: defaults.double(forKey: lonKey)),
            zoom: defaults.double(forKey: zoomKey),
            bearing: defaults.double(forKey: bearingKey))
    }

    static func save(_ camera: MapCamera) {
        defaults.set(camera.center.latitude, forKey: latKey)
        defaults.set(camera.center.longitude, forKey: lonKey)
        defaults.set(camera.zoom, forKey: zoomKey)
        defaults.set(camera.bearing, forKey: bearingKey)
    }

    /// `--camera=<lat>,<lon>,<zoom>` on launch (used by the CI screenshot).
    private static func launchArgumentCamera() -> MapCamera? {
        let prefix = "--camera="
        guard let arg = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }) else { return nil }
        let parts = arg.dropFirst(prefix.count).split(separator: ",").compactMap { Double(String($0)) }
        guard parts.count == 3 else { return nil }
        return MapCamera(center: CLLocationCoordinate2D(latitude: parts[0], longitude: parts[1]), zoom: parts[2], bearing: 0)
    }
}
