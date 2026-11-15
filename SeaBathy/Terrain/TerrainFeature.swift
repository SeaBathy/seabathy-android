import Combine
import CoreLocation
import Foundation
import SwiftUI
import UIKit
import WebKit

/**
 Full-screen 3D view of one GeoTIFF depth layer (Android terrain/Terrain3DScreen).

 The viewer is the shared three.js page bundled in `terrain3d`. It is loaded
 from a private URL scheme and every request is answered here: the page's own
 files from the app bundle, the depth grid from memory, and the tiles of a map
 laid over the terrain (`/drape/…`) from an offline pack or its server. The
 `SeaBathy3D` object the page calls is defined by a small script and forwards
 to native code (see TerrainWebHost).
 */
@MainActor
final class TerrainFeature: ObservableObject {
    unowned let app: AppModel

    /// The 3D view is on screen (or being rebuilt after losing its drawing).
    @Published private(set) var isOpen = false
    /// The page's web view; replaced when the view is rebuilt lighter. Nil while rebuilding.
    @Published private(set) var webView: WKWebView?

    private var host: TerrainWebHost?
    private var source: DepthLayerSource?
    private var layerName = ""
    private var waypoints: [Waypoint] = []
    private var detail = Const.defaultDetail
    /**
     How far 3D is stepped down because the graphics ran short, 0 (none) to
     `Const.maxSafety`: smaller map memory and lower drawing resolution at each
     level. Remembered between opens, so one crash isn't repeated every time.
     */
    private var safety = 0
    /// How many times this opening of 3D has lost its drawing and been brought back.
    private var recoveries = 0
    private var openedAt = Date()
    private var pageReady = false
    private var watchdog: Task<Void, Never>?
    private var building = false
    private var conditionsPaused = false

    /// The grid currently shown, and its serialised form.
    private var grid: TerrainGrid?
    private(set) var payloadMeta: Data?
    private(set) var payloadGrid: Data?
    private var isolated: ClosedRange<Double>?
    /// The layer's colours on the chart, so 3D starts out looking the same.
    private var chartRamp: DepthRampSettings?
    /// Whether the grid covers the whole layer (not the on-screen area or an isolated range).
    private var wholeLayer = true

    /// The maps offered for the terrain's surface, in the order shown.
    private var drapes: [TerrainDrape] = []
    /// When a tile last couldn't reach its server: for a while after, only saved tiles are tried.
    private var lastNoSignalAt = Date.distantPast
    private var memoryWarningAt = Date.distantPast
    private var cancellables = Set<AnyCancellable>()

    /// Same name and keys as Android's "terrain3d" preferences file.
    private lazy var prefs: UserDefaults = UserDefaults(suiteName: "terrain3d") ?? .standard

    /// An ordinary HTTP cache, kept for as long as each server says.
    private lazy var tileSession: URLSession = {
        let config = URLSessionConfiguration.default
        let dir = AppPaths.caches.appendingPathComponent("terrain-tiles", isDirectory: true)
        config.urlCache = URLCache(memoryCapacity: 8 * 1024 * 1024, diskCapacity: Const.tileCacheBytes, directory: dir)
        config.requestCachePolicy = .useProtocolCachePolicy
        config.timeoutIntervalForRequest = 10
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        app.location.onLocation { [weak self] location in
            self?.updateBoat(location)
        }
        app.waypoints.$all
            .dropFirst()
            .sink { [weak self] all in self?.waypointsChanged(all) }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)
            .sink { [weak self] _ in self?.memoryWarningAt = Date() }
            .store(in: &cancellables)
    }

    // Opening and closing -------------------------------------------------------------

    /// Opens the 3D view of a depth layer: the area on the chart when that is clearly
    /// smaller than the layer (Show all goes back to the whole layer).
    func open(layerId: String) {
        guard !isOpen, !building else { return }
        guard let layer = app.layers.layers.first(where: { $0.id == layerId }) else { return }
        guard let depthSource = app.layers.depthSource(for: layerId), depthSource.hasData else {
            app.toast("\(layer.name) has no depth data to show in 3D")
            return
        }
        app.closePanels()
        source = depthSource
        chartRamp = (layer.ramp ?? DepthRampFit.autoFit(depthSource)).normalised()
        layerName = layer.name
        waypoints = app.waypoints.all
        isolated = nil
        refreshDrapes()
        detail = Self.clampDetail(app.settings.rasterQuality.terrainDetail)
        recoveries = 0
        openedAt = Date()
        safety = min(max(prefs.integer(forKey: Keys.safety), 0), Const.maxSafety)
        // Lighter settings earned under an older version say nothing about this one.
        let version = Self.appVersion
        if prefs.string(forKey: Keys.safetyVersion) != version {
            DiagnosticLog.log(Const.tag, "new version: lighter settings reset from \(safety)")
            safety = 0
            prefs.set(version, forKey: Keys.safetyVersion)
            prefs.set(false, forKey: Keys.open)
        }
        // Still marked open: last time took the whole app down, so start a step lighter.
        if prefs.bool(forKey: Keys.open) {
            safety = min(safety + 1, Const.maxSafety)
            DiagnosticLog.log(Const.tag, "3D did not close cleanly last time: safety now \(safety)")
        }
        prefs.set(true, forKey: Keys.open)
        prefs.set(safety, forKey: Keys.safety)

        let region = visibleRegion(within: depthSource.bounds)
        app.toast(region != nil ? "Building 3D of the area on screen…" : "Building 3D view…")
        building = true
        Task {
            let built = await buildGrid(bounds: region ?? depthSource.bounds, range: nil)
            building = false
            guard built != nil else {
                prefs.set(false, forKey: Keys.open)
                source = nil
                app.toast("This layer has no depth data to show in 3D")
                return
            }
            show()
        }
    }

    func close() {
        guard isOpen || webView != nil || source != nil else { return }
        watchdog?.cancel()
        // A long, trouble-free session earns a step back up.
        if recoveries == 0 && safety > 0 && Date().timeIntervalSince(openedAt) > Const.trustedSessionSeconds { safety -= 1 }
        prefs.set(false, forKey: Keys.open)
        prefs.set(safety, forKey: Keys.safety)
        destroyView()
        if conditionsPaused {
            conditionsPaused = false
            app.conditions.resume()
        }
        grid = nil
        payloadMeta = nil
        payloadGrid = nil
        source = nil
        if isOpen {
            isOpen = false
            app.dismissFullScreen()
        }
    }

    /// Flies the 3D camera to a waypoint's pin (for "Go to" in the waypoint editor while 3D is open).
    func focusWaypoint(id: String) {
        evaluate("window.focusWaypoint && window.focusWaypoint(\(Self.json(id)))")
    }

    private func show() {
        guard webView == nil, source != nil else { return }
        let newHost = TerrainWebHost(feature: self)
        let view = newHost.makeWebView(userScript: bridgeScript())
        host = newHost
        webView = view
        pageReady = false
        if !isOpen {
            isOpen = true
            app.presentFullScreen { Terrain3DScreen(feature: self) }
        }
        // The sea conditions' moving lines would keep drawing, unseen, under the 3D view.
        if !conditionsPaused {
            conditionsPaused = true
            app.conditions.pause()
        }
        startWatchdog()
        view.load(URLRequest(url: TerrainWebHost.pageURL))
    }

    private func destroyView() {
        guard let view = webView else { return }
        webView = nil
        host?.tearDown(view)
        host = nil
    }

    private func startWatchdog() {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Const.pageReadyTimeoutNanos)
            guard !Task.isCancelled, let self else { return }
            if self.webView != nil && !self.pageReady {
                DiagnosticLog.log(Const.tag, "3D page never came up")
                self.recover()
            }
        }
    }

    /**
     The 3D view lost its drawing (WebGL context lost, the page's process ended,
     or the page never came up). Starts again in a new web view, one safety level
     lighter. After `Const.maxRecoveries` it goes back to the chart with a message.
     */
    private func recover() {
        watchdog?.cancel()
        recoveries += 1
        safety = min(safety + 1, Const.maxSafety)
        prefs.set(safety, forKey: Keys.safety)
        DiagnosticLog.log(Const.tag, "3D recovery #\(recoveries): safety=\(safety)")
        if recoveries > Const.maxRecoveries {
            close()
            app.toast("The 3D view ran out of memory. Try a smaller area on the chart.")
            return
        }
        destroyView()
        app.toast("3D view ran low on memory: reopening with a lighter map")
        Task {
            try? await Task.sleep(nanoseconds: Const.gpuReleaseNanos)
            guard let current = grid else { return }
            let built: TerrainGrid?
            if safety >= 2 {
                built = await buildGrid(bounds: current.bounds, range: isolated)
            } else {
                payloadMeta = serialiseMeta(current)
                built = current
            }
            if built == nil { close() } else if grid != nil { show() }
        }
    }

    /// The page's web content process ended (usually iOS reclaiming memory).
    func webContentProcessEnded() {
        DiagnosticLog.log(Const.tag, "3D web content process ended")
        if webView != nil { recover() }
    }

    // Grid ---------------------------------------------------------------------------

    private func buildGrid(bounds: GeoBounds, range: ClosedRange<Double>?) async -> TerrainGrid? {
        guard let depthSource = source else { return nil }
        wholeLayer = range == nil && bounds == depthSource.bounds
        let maxDim = gridMaxDim(bounds)
        let sampled = await Task.detached(priority: .userInitiated) { () -> TerrainGrid? in
            TerrainGridSampler.sample(source: depthSource, bounds: bounds, maxDim: maxDim, range: range)
        }.value
        // Closed while sampling: nothing to show.
        guard let sampled, source != nil else {
            if source != nil { DiagnosticLog.log(Const.tag, "terrain sampling found no data") }
            return nil
        }
        let gridData = await Task.detached(priority: .userInitiated) { () -> Data in
            sampled.values.withUnsafeBufferPointer { Data(buffer: $0) }
        }.value
        guard source != nil else { return nil }
        grid = sampled
        payloadGrid = gridData
        payloadMeta = serialiseMeta(sampled)
        DiagnosticLog.log(Const.tag, "grid ready: \(sampled.cols)x\(sampled.rows) range=\(sampled.minMetres)..\(sampled.maxMetres)")
        return sampled
    }

    private func serialiseMeta(_ g: TerrainGrid) -> Data {
        var meta: [String: Any] = [
            "name": layerName,
            "cols": g.cols,
            "rows": g.rows,
            "cellX": Self.finite(g.cellXMetres),
            "cellY": Self.finite(g.cellYMetres),
            "min": Self.finite(g.minMetres),
            "max": Self.finite(g.maxMetres),
            "detail": detail,
            "detailLimit": detailLimit(g.bounds),
            "drapeMaxSize": drapeMaxSize(),
            "safety": safety,
            "pixelRatio": Const.pixelRatios[min(max(safety, 0), Const.pixelRatios.count - 1)],
            "bounds": Self.boundsJson(g.bounds),
            // Showing less than the whole layer, so Show all has something to go back to.
            "partial": !wholeLayer,
            "waypoints": waypoints.map(Self.waypointJson),
            "drapes": drapeList(),
        ]
        if let isolated {
            meta["isolated"] = ["deep": isolated.lowerBound, "shallow": isolated.upperBound]
        } else {
            meta["isolated"] = NSNull()
        }
        if let chartRamp {
            meta["chart"] = TerrainChartPalette.pageChart(chartRamp)
        } else {
            meta["chart"] = NSNull()
        }
        return (try? JSONSerialization.data(withJSONObject: meta)) ?? Data("{}".utf8)
    }

    /// The part of the layer on screen, when that is clearly smaller than the whole layer:
    /// a large survey then starts on the area being looked at. Nil means the whole layer.
    private func visibleRegion(within layerBounds: GeoBounds) -> GeoBounds? {
        guard app.map.mapView != nil else { return nil }
        let visible = app.map.visibleBounds
        let west = max(layerBounds.west, visible.west)
        let east = min(layerBounds.east, visible.east)
        let south = max(layerBounds.south, visible.south)
        let north = min(layerBounds.north, visible.north)
        if east <= west || north <= south { return nil }
        let visibleArea = (east - west) * (north - south)
        let layerArea = (layerBounds.east - layerBounds.west) * (layerBounds.north - layerBounds.south)
        return visibleArea < layerArea * Const.region3dFraction
            ? GeoBounds(west: west, south: south, east: east, north: north) : nil
    }

    private static func clampDetail(_ cells: Int) -> Int { min(max(cells, Const.minDetail), Const.maxDetail) }

    /// RAM in GiB, which sets how much 3D this phone is asked to hold.
    private var totalMemoryGib: Double { Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824 }

    /// iOS has no "low memory now" query: a memory warning in the last minute counts.
    private var lowOnMemory: Bool { Date().timeIntervalSince(memoryWarningAt) < 60 }

    /// Longest side of the whole-survey map, which also sets the memory for the sharper levels round the view.
    private func drapeMaxSize() -> Int {
        let gib = totalMemoryGib
        let base: Int
        if gib < 3.5 { base = Const.minDrapeSize } else if gib < 5.5 { base = 1536 } else { base = Const.maxDrapeSize }
        return max(base >> safety, Const.minDrapeSize / 2)
    }

    /// Grid cells along the longer side: the chosen detail, held to what this phone can draw.
    private func gridMaxDim(_ bounds: GeoBounds) -> Int { min(detail, detailLimit(bounds)) }

    /// The most cells along the longer side this phone holds for `bounds` right now.
    private func detailLimit(_ bounds: GeoBounds) -> Int {
        let cells = TerrainBudget.cells(totalGib: totalMemoryGib, lowMemory: lowOnMemory, safety: safety)
        let wide = (bounds.east - bounds.west) * cos((bounds.north + bounds.south) / 2 * .pi / 180)
        let tall = bounds.north - bounds.south
        let aspect = max(wide, tall) / max(min(wide, tall), 1e-9)
        return max(TerrainBudget.longestSide(cells: cells, aspect: aspect), Const.minDetail)
    }

    // Page calls ---------------------------------------------------------------------

    private func evaluate(_ js: String) {
        webView?.evaluateJavaScript(js, completionHandler: nil)
    }

    private func reloadPage() {
        evaluate("window.showBusy(null); window.reloadGrid()")
    }

    private func showBusy(_ text: String) {
        evaluate("window.showBusy(\(Self.json(text)))")
    }

    /// Moves the boat marker to a live GPS fix: heading is the course over ground, speed in knots.
    private func updateBoat(_ location: CLLocation) {
        guard webView != nil else { return }
        let c = location.coordinate
        guard c.latitude.isFinite, c.longitude.isFinite else { return }
        let fix: [String: Any] = [
            "lat": c.latitude,
            "lng": c.longitude,
            "heading": Self.orNull(location.course >= 0 ? location.course : nil),
            "knots": Self.orNull(location.speed >= 0 ? location.speed * Const.metresPerSecondToKnots : nil),
            "accuracy": Self.orNull(location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil),
        ]
        evaluate("window.updateBoat && window.updateBoat(\(Self.json(fix)))")
    }

    /// Shows the app's current waypoints, e.g. after one was renamed or deleted.
    private func waypointsChanged(_ all: [Waypoint]) {
        guard webView != nil else { return }
        waypoints = all
        evaluate("window.setWaypointList && window.setWaypointList(\(Self.json(all.map(Self.waypointJson))))")
    }

    /// Calls from the page that need no answer.
    func handleBridge(_ name: String, _ args: [Any]) {
        func num(_ i: Int) -> Double? {
            guard i < args.count else { return nil }
            if let n = args[i] as? NSNumber { return n.doubleValue }
            if let s = args[i] as? String { return Double(s) }
            return nil
        }
        func str(_ i: Int) -> String? { i < args.count ? args[i] as? String : nil }

        switch name {
        case "close":
            close()
        case "isolate":
            if let deep = num(0), let shallow = num(1) { isolate(deep: deep, shallow: shallow) }
        case "addWaypoint":
            if let lat = num(0), let lng = num(1), let depth = num(2) { addWaypoint(lat: lat, lng: lng, depth: depth) }
        case "editWaypoint":
            if let id = str(0), app.waypoints.byId(id) != nil { app.navigation.showWaypointEditor(id: id) }
        case "startGps":
            // Off -> locate, through the same permission flow as the GPS button.
            app.navigation.gps.ensureOn()
        case "showAll":
            showAll()
        case "setDetail":
            if let cells = num(0), cells.isFinite { setDetail(Int(cells)) }
        case "saveOffline":
            if let id = str(0) { saveOffline(id) }
        case "contextLost":
            DiagnosticLog.log(Const.tag, "3D WebGL context lost")
            if webView != nil { recover() }
        case "ready":
            pageReady = true
            watchdog?.cancel()
            if let last = app.location.lastLocation, Date().timeIntervalSince(last.timestamp) < 120 { updateBoat(last) }
        case "noWebGL":
            noWebGL()
        case "resetSafety":
            resetSafety()
        case "log":
            if let text = str(0) { DiagnosticLog.log(Const.tag, String(text.prefix(Const.maxLogLength))) }
        default:
            break
        }
    }

    /// Calls from the page that return a JSON string.
    func handleSyncBridge(_ name: String, _ args: [Any]) -> String {
        func str(_ i: Int) -> String { i < args.count ? (args[i] as? String ?? "") : "" }
        switch name {
        case "addCustomDrape": return addCustomDrape(name: str(0), url: str(1))
        case "removeCustomDrape": return removeCustomDrape(id: str(0))
        case "units": return unitsJson()
        default: return ""
        }
    }

    private func isolate(deep: Double, shallow: Double) {
        guard let current = grid else { return }
        let range = min(deep, shallow)...max(deep, shallow)
        guard let area = TerrainGridSampler.boundsWithin(current, range: range) else {
            app.toast("No data in that depth range")
            return
        }
        showBusy("Isolating depths…")
        isolated = range
        Task {
            if await buildGrid(bounds: area, range: range) == nil {
                isolated = nil
                app.toast("No data in that depth range")
                evaluate("window.showBusy(null)")
                return
            }
            reloadPage()
        }
    }

    private func showAll() {
        guard let all = source?.bounds else { return }
        showBusy("Loading whole layer…")
        isolated = nil
        Task {
            _ = await buildGrid(bounds: all, range: nil)
            reloadPage()
        }
    }

    private func setDetail(_ cells: Int) {
        detail = Self.clampDetail(cells)
        guard let current = grid else { return }
        showBusy("Rebuilding…")
        Task {
            _ = await buildGrid(bounds: current.bounds, range: isolated)
            reloadPage()
        }
    }

    private func addWaypoint(lat: Double, lng: Double, depth: Double) {
        let added = app.waypoints.add(
            name: app.waypoints.nextDefaultName(), lat: lat, lng: lng, color: Waypoint.defaultColor,
            description: String(format: "Depth %.1f m (marked in 3D)", abs(depth)))
        app.navigation.waypointsChanged()
        waypoints = app.waypoints.all
        evaluate("window.onWaypointAdded(\(Self.json(Self.waypointJson(added))))")
    }

    /// WebGL wouldn't start: a new web view may do it, but after a GPU failure only restarting the app will.
    private func noWebGL() {
        DiagnosticLog.log(Const.tag, "3D page: WebGL not available (recoveries=\(recoveries))")
        guard webView != nil else { return }
        if recoveries == 0 {
            recover()
        } else {
            close()
            app.toast("The phone's graphics are out of memory. Close and reopen SeaBathy to use 3D again.")
        }
    }

    /// The user asked for full quality again: the view restarts with nothing stepped down.
    private func resetSafety() {
        watchdog?.cancel()
        safety = 0
        recoveries = 0
        prefs.set(0, forKey: Keys.safety)
        destroyView()
        Task {
            try? await Task.sleep(nanoseconds: Const.gpuReleaseNanos)
            guard let current = grid else { return }
            if await buildGrid(bounds: current.bounds, range: isolated) == nil { close() } else if grid != nil { show() }
        }
    }

    /**
     Closes 3D and sends the user to Offline maps for the map they picked. The
     download screen can't yet be opened pre-set to an area and map, so the
     message says what to choose there.
     */
    private func saveOffline(_ id: String) {
        guard let drape = drapes.first(where: { $0.id == id }), drape.isSavable, grid != nil else { return }
        close()
        app.toast("To keep \(drape.label) for this area, download it in Offline maps")
        Task {
            // Let the full-screen view finish closing before a sheet goes up.
            try? await Task.sleep(nanoseconds: 700_000_000)
            app.layers.showOfflineMaps()
        }
    }

    private func unitsJson() -> String {
        let u = app.units
        return Self.json([
            "depthPerM": u.depth.perMetre,
            "depthLabel": u.depth.label,
            "speedPerKnot": u.speed.perKnot,
            "speedLabel": u.speed.label,
        ] as [String: Any])
    }

    /// Defines `window.SeaBathy3D` before the page runs, forwarding each call to native code.
    /// Calls that return a value go through `prompt`, which WebKit answers synchronously.
    private func bridgeScript() -> String {
        let units = unitsJson()
        return """
        (function () {
          var handlers = window.webkit && window.webkit.messageHandlers;
          function post(name, args) {
            try { handlers.\(TerrainWebHost.handlerName).postMessage({ name: name, args: args }); } catch (e) {}
          }
          function ask(name, args) {
            try {
              var r = window.prompt("\(TerrainWebHost.promptPrefix)" + name, JSON.stringify(args));
              return r == null ? "" : String(r);
            } catch (e) { return ""; }
          }
          var units = \(units);
          window.SeaBathy3D = {
            units: function () { return JSON.stringify(units); },
            close: function () { post("close", []); },
            isolate: function (deep, shallow) { post("isolate", [Number(deep), Number(shallow)]); },
            addWaypoint: function (lat, lng, depth) { post("addWaypoint", [Number(lat), Number(lng), Number(depth)]); },
            editWaypoint: function (id) { post("editWaypoint", [String(id)]); },
            startGps: function () { post("startGps", []); },
            showAll: function () { post("showAll", []); },
            setDetail: function (cells) { post("setDetail", [Number(cells)]); },
            addCustomDrape: function (name, url) {
              return ask("addCustomDrape", [String(name), String(url)]) || JSON.stringify({ error: "Couldn't add that map" });
            },
            removeCustomDrape: function (id) { return ask("removeCustomDrape", [String(id)]) || "[]"; },
            saveOffline: function (id) { post("saveOffline", [String(id)]); },
            contextLost: function () { post("contextLost", []); },
            ready: function () { post("ready", []); },
            noWebGL: function () { post("noWebGL", []); },
            resetSafety: function () { post("resetSafety", []); },
            log: function (text) { post("log", [String(text)]); }
          };
          window.addEventListener("error", function (e) {
            var m = e && e.message ? String(e.message) : "";
            if (m.indexOf("ResizeObserver loop") !== 0) { post("log", ["js error: " + m]); }
          });
        })();
        """
    }

    // Maps over the terrain ------------------------------------------------------------

    /// Rebuilds the Map list: the app's default maps, then the layer stack's, then the user's own links.
    private func refreshDrapes() {
        var list: [TerrainDrape] = []
        for drape in defaultDrapes() + app.layers.terrainDrapes() + customDrapes() {
            if let i = list.firstIndex(where: { $0.id == drape.id }) { list[i] = drape } else { list.append(drape) }
        }
        drapes = list
    }

    private func drapeList() -> [[String: Any]] {
        drapes.map { d in
            [
                "id": d.id,
                "label": d.label,
                "minzoom": d.minZoom,
                "maxzoom": d.maxZoom,
                "group": d.group.rawValue,
                "offline": d.isOffline,
                // Only an online map whose provider allows offline copies can be downloaded for the area.
                "savable": d.isSavable,
                "colour": Self.orNull(d.colour),
                "attribution": d.attribution,
                "bounds": Self.orNull(d.bounds.map { Self.boundsJson($0) }),
            ] as [String: Any]
        }
    }

    /// The built-in online map: Sentinel-2 cloudless, whose licence allows saving it offline.
    private func defaultDrapes() -> [TerrainDrape] {
        let p = DataSource.defaultPreset
        return [TerrainDrape(id: "online-\(p.id)", label: "Sentinel-2 satellite", minZoom: 0, maxZoom: Const.sentinel2MaxZoom,
                             urlTemplate: p.urlTemplate, group: .defaultMap, attribution: p.attribution, colour: "#0e7490")]
    }

    private func loadCustom() -> [TerrainCustomDrape] {
        guard let text = prefs.string(forKey: Keys.customDrapes), let data = text.data(using: .utf8),
              let list = try? JSONDecoder().decode([TerrainCustomDrape].self, from: data) else { return [] }
        return list.filter { !$0.id.trimmingCharacters(in: .whitespaces).isEmpty && TerrainLinkCheck.validationError($0.url) == nil }
    }

    private func saveCustom(_ list: [TerrainCustomDrape]) {
        guard let data = try? JSONEncoder().encode(list), let text = String(data: data, encoding: .utf8) else { return }
        prefs.set(text, forKey: Keys.customDrapes)
    }

    private func customDrapes() -> [TerrainDrape] {
        loadCustom().map { c in
            // A link the user added: its credit names the server the tiles come from.
            let host = TerrainLinkCheck.host(c.url) ?? c.name
            return TerrainDrape(id: c.id, label: c.name, minZoom: 0, maxZoom: OverlayLayer.defaultMaxZoom,
                                urlTemplate: c.url, group: .custom, attribution: "Map: \(host)")
        }
    }

    /// Adds a custom map link; returns { id, drapes } or { error }.
    private func addCustomDrape(name: String, url: String) -> String {
        let link = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let error = TerrainLinkCheck.validationError(link) { return Self.json(["error": error]) }
        var list = loadCustom()
        if list.contains(where: { $0.url == link }) { return Self.json(["error": "That map is already in the list"]) }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = trimmed.isEmpty ? (TerrainLinkCheck.host(link) ?? "Custom map") : trimmed
        let added = TerrainCustomDrape(id: "custom-\(UUID().uuidString.lowercased())", name: String(label.prefix(Const.maxNameLength)), url: link)
        list.append(added)
        saveCustom(list)
        refreshDrapes()
        DiagnosticLog.log(Const.tag, "custom map added: \(added.name)")
        return Self.json(["id": added.id, "drapes": drapeList()] as [String: Any])
    }

    private func removeCustomDrape(id: String) -> String {
        saveCustom(loadCustom().filter { $0.id != id })
        refreshDrapes()
        return Self.json(drapeList())
    }

    /**
     One tile of a map laid over the terrain, or nil (the page leaves it transparent).
     An offline pack is read off the main thread; an online map gets one attempt
     only. At sea a dead connection is normal, so after one fails only tiles
     already saved are tried for a while.
     */
    func tileData(drapeId: String, z: Int, x: Int, y: Int) async -> Data? {
        guard let drape = drapes.first(where: { $0.id == drapeId }),
              z >= 0, z <= Const.maxDrapeZoom, x >= 0, y >= 0, x < (1 << z), y < (1 << z) else { return nil }
        if let read = drape.readTile {
            let data = await Task.detached(priority: .userInitiated) { () -> Data? in read(z, x, y) }.value
            if let data, !data.isEmpty { return data }
        }
        guard let template = drape.urlTemplate else { return nil }
        let link = template.replacingOccurrences(of: "{z}", with: String(z))
            .replacingOccurrences(of: "{x}", with: String(x))
            .replacingOccurrences(of: "{y}", with: String(y))
        guard let url = URL(string: link) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("SeaBathy/\(Self.appVersion) (iOS 3D view)", forHTTPHeaderField: "User-Agent")
        let savedOnly = Date().timeIntervalSince(lastNoSignalAt) < Const.noSignalSeconds
        if savedOnly { request.cachePolicy = .returnCacheDataDontLoad }
        do {
            let (data, response) = try await tileSession.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            guard (200..<300).contains(status), !data.isEmpty, data.count <= Const.maxTileBytes else { return nil }
            return data
        } catch let error as URLError {
            // Cancelled by the page moving on isn't a lost signal, and neither is a request for saved tiles only.
            if error.code != .cancelled && !savedOnly { lastNoSignalAt = Date() }
            return nil
        } catch {
            return nil
        }
    }

    // Helpers ------------------------------------------------------------------------

    private static func waypointJson(_ w: Waypoint) -> [String: Any] {
        ["id": w.id, "name": w.name, "color": w.color, "lat": w.lat, "lng": w.lng, "desc": Self.orNull(w.desc)]
    }

    private static func boundsJson(_ b: GeoBounds) -> [String: Any] {
        ["west": b.west, "south": b.south, "east": b.east, "north": b.north]
    }

    private static func finite(_ d: Double) -> Any { orNull(d.isFinite ? d : nil) }

    private static func orNull(_ value: Any?) -> Any {
        if let value { return value }
        return NSNull()
    }

    /// JSON text for a dictionary, array or string (as a JavaScript literal).
    static func json(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        return text
    }

    private static var appVersion: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return "\(short) (\(build))"
    }

    private enum Keys {
        static let safety = "safety_level"
        static let open = "view_open"
        static let safetyVersion = "safety_version"
        static let customDrapes = "custom_drapes"
    }

    private enum Const {
        static let tag = "Terrain3D"
        static let defaultDetail = 512
        static let minDetail = 128
        static let maxDetail = 2048
        static let metresPerSecondToKnots = 1.9438444924574
        static let maxDrapeZoom = 22
        static let maxDrapeSize = 2048
        static let minDrapeSize = 1024
        static let maxSafety = 3
        /// Drawing resolution at each safety level, in screen pixels per CSS pixel.
        static let pixelRatios: [Double] = [2.0, 1.5, 1.25, 1.0]
        /// Times the view is brought back after losing its drawing, before giving up.
        static let maxRecoveries = 3
        /// Time for the old view's GPU memory to go before the new one asks for its own.
        static let gpuReleaseNanos: UInt64 = 800_000_000
        static let pageReadyTimeoutNanos: UInt64 = 30_000_000_000
        static let trustedSessionSeconds: TimeInterval = 120
        static let maxLogLength = 400
        static let maxTileBytes = 4 * 1024 * 1024
        static let tileCacheBytes = 100 * 1024 * 1024
        static let noSignalSeconds: TimeInterval = 20
        /// Sentinel-2 cloudless is 10 m imagery: zoom 14 is its full detail.
        static let sentinel2MaxZoom = 14
        static let maxNameLength = 60
        static let region3dFraction = 0.6
    }
}

/// A map link the user added in 3D (saved as JSON, same fields as Android).
struct TerrainCustomDrape: Codable, Equatable {
    var id: String
    var name: String
    var url: String

    init(id: String, name: String, url: String) {
        self.id = id; self.name = name; self.url = url
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        url = try c.decodeIfPresent(String.self, forKey: .url) ?? ""
    }
}
