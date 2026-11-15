import CoreLocation
import Foundation

/**
 The phone's GPS, shared by every feature (Android LocationController + GpsController's provider).

 Features subscribe with `onLocation`. `startUpdates()` asks for permission the first
 time. Background updates (track recording, anchor watch, navigation with the
 screen off) are turned on with `setBackgroundNeeded(_:, reason:)`; each reason
 holds them on until it is released.
 */
@MainActor
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var lastLocation: CLLocation?
    @Published private(set) var authorization: CLAuthorizationStatus
    @Published private(set) var isRunning = false

    private let manager = CLLocationManager()
    private var handlers: [UUID: (CLLocation) -> Void] = [:]
    private var foregroundUsers = Set<String>()
    private var backgroundReasons = Set<String>()

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    var isAuthorized: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }
    var isDenied: Bool { authorization == .denied || authorization == .restricted }

    /// Calls `handler` with every new fix. Keep the token to stop with `remove`.
    @discardableResult
    func onLocation(_ handler: @escaping (CLLocation) -> Void) -> UUID {
        let id = UUID()
        handlers[id] = handler
        return id
    }

    func remove(_ token: UUID) { handlers[token] = nil }

    /// Starts updates for `user` (e.g. "gps", "track", "anchor"); they stop when every user has stopped.
    func start(for user: String) {
        foregroundUsers.insert(user)
        if authorization == .notDetermined {
            manager.requestWhenInUseAuthorization()
            return
        }
        guard isAuthorized else { return }
        if !isRunning {
            manager.startUpdatingLocation()
            isRunning = true
        }
    }

    func stop(for user: String) {
        foregroundUsers.remove(user)
        backgroundReasons.remove(user)
        applyBackground()
        if foregroundUsers.isEmpty && isRunning {
            manager.stopUpdatingLocation()
            isRunning = false
        }
    }

    /// Keeps updates running with the screen off for `reason` (needs the location background mode).
    func setBackgroundNeeded(_ needed: Bool, reason: String) {
        if needed {
            backgroundReasons.insert(reason)
            // "Always" makes background delivery reliable; ask once, it is fine if the user keeps "While using".
            if authorization == .authorizedWhenInUse { manager.requestAlwaysAuthorization() }
        } else {
            backgroundReasons.remove(reason)
        }
        applyBackground()
    }

    private func applyBackground() {
        let on = !backgroundReasons.isEmpty
        manager.allowsBackgroundLocationUpdates = on
        manager.showsBackgroundLocationIndicator = on
    }

    // CLLocationManagerDelegate -------------------------------------------------------

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            if self.isAuthorized, !self.foregroundUsers.isEmpty, !self.isRunning {
                self.manager.startUpdatingLocation()
                self.isRunning = true
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latest = locations.last else { return }
        Task { @MainActor in
            self.lastLocation = latest
            for h in self.handlers.values { h(latest) }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        DiagnosticLog.log("Location", "error: \(error.localizedDescription)")
    }
}
