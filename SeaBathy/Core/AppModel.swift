import CoreLocation
import Network
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The map interaction armed by the top-bar tool buttons (Android ui/ToolMode.kt).
enum ToolMode: String {
    case none, waypoint, route, measure

    var hint: String? {
        switch self {
        case .none: return nil
        case .waypoint: return "Tap map to drop a waypoint at the centre crosshair"
        case .route: return "Move the map so the crosshair is on each point, then tap to add it"
        case .measure: return "Tap map to measure — tap CLEAR when done"
        }
    }
}

/// A sheet or full-screen view shown over the chart.
struct PresentedView: Identifiable {
    let id = UUID()
    let content: AnyView
    var detents: Set<PresentationDetent> = [.large]
}

/**
 The app's shared services and the features, wired together once
 (Android MainActivity's buildDomain/buildUi). Features get the AppModel in
 their init and reach each other through it; they must not hold it strongly
 (use `unowned let app: AppModel`).
 */
@MainActor
final class AppModel: ObservableObject {
    // Shared services
    let settings = AppSettings()
    let navSettings = NavSettingsStore()
    let map = MapController()
    let location = LocationService()

    // Features (constructed in init, started in `start()` once all exist)
    private(set) var waypoints: WaypointStore!
    private(set) var layers: LayersFeature!
    private(set) var navigation: NavigationFeature!
    private(set) var tides: TideFeature!
    private(set) var conditions: ConditionsFeature!
    private(set) var terrain: TerrainFeature!
    private(set) var basemap: BasemapController!

    // Chart-screen state
    @Published var toolMode: ToolMode = .none
    @Published var sidePanelOpen = false
    @Published var crosshairDepthText = ""
    @Published var crosshairVisible = true
    @Published var attributionText = ""
    @Published private(set) var isOnline = true

    // Presentation
    @Published var sheet: PresentedView?
    @Published var fullScreen: PresentedView?
    @Published var toastText: String?
    @Published var alertItem: AlertItem?

    struct AlertItem: Identifiable {
        let id = UUID()
        let title: String
        let message: String
        var actions: [AlertAction] = []
    }

    struct AlertAction: Identifiable {
        let id = UUID()
        let title: String
        var role: ButtonRole? = nil
        let handler: () -> Void
    }

    private let pathMonitor = NWPathMonitor()
    private var toastTask: Task<Void, Never>?
    private var connectivityHandlers: [() -> Void] = []

    init() {
        waypoints = WaypointStore(app: self)
        basemap = BasemapController(app: self)
        layers = LayersFeature(app: self)
        navigation = NavigationFeature(app: self)
        tides = TideFeature(app: self)
        conditions = ConditionsFeature(app: self)
        terrain = TerrainFeature(app: self)
        watchConnectivity()
    }

    private var started = false

    /// Called once the chart view exists. Order matters: the overlay stack goes on
    /// before the app's own marks so they draw above imported data.
    func start() {
        guard !started else { return }
        started = true
        basemap.start()
        layers.start()
        navigation.start()
        tides.start()
        conditions.start()
        terrain.start()
        map.onCameraIdle { [weak self] in
            guard let self else { return }
            let c = self.map.centre
            self.settings.saveCamera(.init(lat: c.latitude, lng: c.longitude, zoom: self.map.zoom,
                                           bearing: self.map.bearing, tilt: self.map.mapView?.camera.pitch ?? 0))
        }
    }

    // Convenience ------------------------------------------------------------------------

    var units: NavUnits { navSettings.settings.units }
    var format: NavFormat { navSettings.settings.format }

    /// The depth (negative-down metres) at a point from the visible depth layers, top first.
    func depthSample(lat: Double, lng: Double) -> Double? {
        layers.sampleDepth(lat: lat, lng: lng)
    }

    func setTool(_ mode: ToolMode) {
        let next = toolMode == mode ? .none : mode
        let leaving = toolMode
        toolMode = next
        crosshairVisible = next != .measure
        navigation.toolChanged(from: leaving, to: next)
        crosshairDepthText = ""
        if next != .measure { layers.refreshCrosshairDepth() }
    }

    // Presentation helpers ----------------------------------------------------------------

    func present<V: View>(detents: Set<PresentationDetent> = [.large], @ViewBuilder _ content: () -> V) {
        sheet = PresentedView(content: AnyView(content().environmentObject(self)), detents: detents)
    }

    func presentFullScreen<V: View>(@ViewBuilder _ content: () -> V) {
        fullScreen = PresentedView(content: AnyView(content().environmentObject(self)))
    }

    func dismissSheet() { sheet = nil }
    func dismissFullScreen() { fullScreen = nil }

    func closePanels() {
        sheet = nil
        sidePanelOpen = false
    }

    func toast(_ text: String) {
        toastText = text
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(2.5, Double(text.count) / 18) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.toastText = nil
        }
    }

    func alert(_ title: String, _ message: String, actions: [AlertAction] = []) {
        alertItem = AlertItem(title: title, message: message, actions: actions)
    }

    /// Asks a yes/no question; `onConfirm` runs on the confirm button.
    func confirm(_ title: String, _ message: String, confirm: String = "OK", destructive: Bool = false, onConfirm: @escaping () -> Void) {
        alert(title, message, actions: [
            AlertAction(title: confirm, role: destructive ? .destructive : nil, handler: onConfirm),
            AlertAction(title: "Cancel", role: .cancel, handler: {})
        ])
    }

    // Files ---------------------------------------------------------------------------

    private var pickerDelegate: PickerDelegate?

    /// Lets the user pick files. Each picked file is copied into the app's sandbox first
    /// (iOS gives no lasting access to files elsewhere), so the URLs stay valid.
    func pickFiles(_ types: [UTType], allowsMultiple: Bool = false, completion: @escaping ([URL]) -> Void) {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.allowsMultipleSelection = allowsMultiple
        let delegate = PickerDelegate { [weak self] urls in
            completion(urls)
            self?.pickerDelegate = nil
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        Self.topViewController()?.present(picker, animated: true)
    }

    /// The system share sheet for files or text.
    func share(_ items: [Any]) {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let top = Self.topViewController() {
            vc.popoverPresentationController?.sourceView = top.view
            vc.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            top.present(vc, animated: true)
        }
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    private final class PickerDelegate: NSObject, UIDocumentPickerDelegate {
        let done: ([URL]) -> Void
        init(done: @escaping ([URL]) -> Void) { self.done = done }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { done(urls) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { done([]) }
    }

    // Connectivity --------------------------------------------------------------------

    /// Runs when the internet comes back (online basemap, sea conditions refresh).
    func onConnectivityRestored(_ handler: @escaping () -> Void) { connectivityHandlers.append(handler) }

    private func watchConnectivity() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let was = self.isOnline
                self.isOnline = online
                if online && !was { self.connectivityHandlers.forEach { $0() } }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "seabathy.net"))
    }
}
