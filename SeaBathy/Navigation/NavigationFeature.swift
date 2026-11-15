import Combine
import CoreLocation
import SwiftUI
import UIKit

/**
 Routes and navigation (Android nav/NavigationFeature plus MainActivity's map-tap
 and tool handling): the route card, saved routes, the boat profile, live route
 navigation with its display and alarms, waypoints on the chart, and the GPS,
 track, anchor, measure, grid and nav-marks features it owns.
 */
@MainActor
final class NavigationFeature: ObservableObject {
    unowned let app: AppModel
    let routeLibrary: RouteLibrary
    let gps: GpsController
    let tracks: TrackFeature
    let anchor: AnchorFeature
    let measure: MeasureController
    let grid: GraticuleController
    let navMarks: NavMarksController

    /// Live navigation (what the HUD shows).
    let session: NavSession
    /// The route being planned on the chart.
    let planner: RoutePlanner
    let variationModel: NavVariation

    @Published private(set) var isNavigating = false
    /// The route card's readout; nil hides the card.
    @Published private(set) var routeCardText: String?
    @Published private(set) var routePoints: [RoutePoint] = []

    private var routeDepthNote: String?
    private var routeDepthTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private var lastSettings: NavSettings
    private var locationToken: UUID?

    static let msToKnots = 1.943844
    static let goToZoom = 14.0
    private static let courseUpMinSpeedMs = 0.5

    /// Runs `work` on the main actor after a pause (e.g. once a closing sheet has gone).
    static func later(_ seconds: Double, _ work: @escaping @MainActor () -> Void) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            work()
        }
    }

    init(app: AppModel) {
        self.app = app
        let variation = NavVariation()
        variationModel = variation
        routeLibrary = RouteLibrary()
        gps = GpsController(app: app)
        tracks = TrackFeature(app: app)
        anchor = AnchorFeature(app: app)
        measure = MeasureController(app: app)
        grid = GraticuleController(app: app)
        navMarks = NavMarksController(app: app)
        session = NavSession(app: app, variation: variation)
        let store = app.navSettings
        planner = RoutePlanner(defaults: .standard,
                               settings: { store.settings },
                               variation: { lat, lng in variation.variation(lat, lng) })
        lastSettings = store.settings
        routePoints = planner.points
    }

    func start() {
        // Android's install order; waypoints go on last so they stay above.
        navMarks.start()
        grid.start()
        tracks.start()
        app.map.onStyleLoaded { [weak self] _ in
            guard let self else { return }
            NavMapLayers.installRoute(self.app.map, geoJSON: NavGeoJson.lineWithVertices(self.planner.points))
        }
        measure.start()
        app.map.onStyleLoaded { [weak self] _ in
            guard let self else { return }
            NavMapLayers.installNav(self.app.map)
            self.session.refreshMap()
        }
        anchor.start()
        gps.start()
        app.map.onStyleLoaded { [weak self] _ in
            guard let self else { return }
            NavMapLayers.installWaypoints(self.app.map, waypoints: self.app.waypoints.all,
                                          labelFont: self.app.basemap.labelFont,
                                          namesVisible: self.app.settings.showWaypointNames)
        }

        app.map.onTap = { [weak self] coordinate, point in self?.handleTap(coordinate, point) }
        // A drag while following lets go of the boat, as on a chartplotter; tap GPS again to follow.
        app.map.onUserPan = { [weak self] in _ = self?.gps.releaseFollow() }
        locationToken = app.location.onLocation { [weak self] location in self?.onLocation(location) }

        app.navSettings.$settings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] new in self?.settingsChanged(new) }
            .store(in: &cancellables)
        app.waypoints.$all
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.waypointsChanged() }
            .store(in: &cancellables)
        app.settings.$showWaypointNames
            .receive(on: DispatchQueue.main)
            .sink { [weak self] visible in
                guard let self else { return }
                NavMapLayers.setNamesVisible(self.app.map, visible)
            }
            .store(in: &cancellables)

        variationModel.setNeeded(app.navSettings.settings.units.bearing == .magnetic)
        refreshRouteSummary()
        scheduleRouteDepthCheck()
        offerResume()
    }

    // Contract entry points ------------------------------------------------------------

    func toolChanged(from: ToolMode, to: ToolMode) {
        if from == .measure && to != .measure { measure.clear() }
        refreshRouteSummary()
    }

    func showNavSettings() {
        app.present { NavSettingsSheet(store: self.app.navSettings) }
    }

    func showRoutes() {
        app.present { SavedRoutesSheet() }
    }

    func showWaypointEditor(id: String) {
        guard app.waypoints.byId(id) != nil else { return }
        app.present(detents: [.medium, .large]) { WaypointEditSheet(waypointId: id) }
    }

    /// Tapping the coordinates in the top bar.
    func showCoordinateEntry() {
        let c = app.map.centre
        app.present(detents: [.medium, .large]) {
            CoordinateEditorSheet(
                title: "Go to coordinates", initialLat: c.latitude, initialLng: c.longitude,
                positiveLabel: "Create waypoint", showMoveButton: true,
                onMove: { [weak self] lat, lng in self?.flyTo(lat, lng) },
                onPositive: { [weak self] lat, lng in
                    // Let the coordinate sheet finish closing before the next one opens.
                    NavigationFeature.later(0.5) { self?.showNewWaypoint(lat: lat, lng: lng) }
                })
        }
    }

    /// A route from a GPX file: saved, and shown unless that would replace unsaved planning.
    func importRoute(name: String, points: [RoutePoint]) {
        guard !points.isEmpty else { return }
        let saved = routeLibrary.save(id: nil, name: routeLibrary.freeName(name.isEmpty ? "Imported route" : name), points: points)
        let unsavedWork = !planner.points.isEmpty && planner.savedId == nil
        if !unsavedWork {
            planner.load(saved)
            refreshRoute()
            fitRoute(saved.points)
        }
    }

    func waypointsChanged() {
        NavMapLayers.renderWaypoints(app.map, waypoints: app.waypoints.all)
    }

    /// The visible depth layers changed: the route's least depth may too.
    func layersChanged() { scheduleRouteDepthCheck() }

    // Map taps ---------------------------------------------------------------------------

    private func handleTap(_ coordinate: CLLocationCoordinate2D, _ point: CGPoint) {
        if let id = NavMapLayers.waypointId(app.map, at: point), let w = app.waypoints.byId(id) {
            switch app.toolMode {
            // Route and measure snap to a tapped waypoint, so a route can be planned through marks.
            case .route: addRoutePoint(lat: w.lat, lng: w.lng, name: w.name)
            case .measure: measure.add(lat: w.lat, lng: w.lng)
            default: showWaypointEditor(id: w.id)
            }
            return
        }
        switch app.toolMode {
        case .waypoint:
            // Placed at the centre crosshair, so a mark can be positioned precisely by panning.
            let c = app.map.centre
            showNewWaypoint(lat: c.latitude, lng: c.longitude)
        case .route:
            let c = app.map.centre
            if let last = planner.points.last, NavGeo.distanceM(last.lat, last.lng, c.latitude, c.longitude) < 1 {
                app.toast("Move the map to put the crosshair on the next point")
            } else {
                addRoutePoint(lat: c.latitude, lng: c.longitude)
            }
        case .measure:
            measure.add(lat: coordinate.latitude, lng: coordinate.longitude)
        case .none:
            guard navMarks.visible, let mark = navMarks.describe(at: point) else { return }
            app.alert(mark.title, mark.detail)
        }
    }

    // Waypoints ---------------------------------------------------------------------------

    func showNewWaypoint(lat: Double, lng: Double) {
        app.present(detents: [.medium, .large]) { NewWaypointSheet(lat: lat, lng: lng) }
    }

    /// Returns an error for the name field, or nil once saved.
    func saveNewWaypoint(name rawName: String, description: String, lat: Double, lng: Double,
                         color: String, icon: String) -> String? {
        let trimmed = rawName.trimmingCharacters(in: .whitespaces)
        let name = trimmed.isEmpty ? app.waypoints.nextDefaultName() : trimmed
        if app.waypoints.isNameTaken(name) { return "A waypoint called \"\(name)\" already exists" }
        app.waypoints.add(name: name, lat: lat, lng: lng, color: color, description: description, icon: icon)
        if app.toolMode == .waypoint { app.setTool(.waypoint) }
        return nil
    }

    func flyTo(_ lat: Double, _ lng: Double) {
        app.map.fly(to: lat, lng, zoom: Self.goToZoom)
    }

    // Route planning ----------------------------------------------------------------------

    func addRoutePoint(lat: Double, lng: Double, name: String? = nil) {
        planner.add(lat: lat, lng: lng, name: name)
        refreshRoute()
    }

    func undoRoutePoint() { planner.undo(); refreshRoute() }
    func clearRoute() { planner.clear(); refreshRoute() }

    func reverseRoute() {
        guard planner.points.count >= 2 else { return }
        planner.reverse()
        refreshRoute()
        app.toast("Route reversed")
    }

    func exportPlannedRoute() { shareRouteGpx(planner.points, name: planner.savedName ?? "Route") }

    func goPlannedRoute() { startNavigation(planner.points, name: planner.savedName) }

    func setSpeedSource(_ source: PlanSpeedSource) { app.navSettings.settings.speedSource = source }

    /// The cruise speed typed in the route card, in the speed unit shown.
    func setCruiseSpeed(shown: Double) {
        guard shown > 0, shown.isFinite else { return }
        let knots = shown / app.navSettings.settings.units.speed.perKnot
        guard knots <= NavSettings.maxCruiseKn else { return }
        if abs(app.navSettings.settings.cruiseSpeedKn - knots) > 1e-9 { app.navSettings.settings.cruiseSpeedKn = knots }
    }

    func refreshRoute() {
        routePoints = planner.points
        app.map.setGeoJSON(MapLayerIds.routeSource, NavGeoJson.lineWithVertices(planner.points))
        refreshRouteSummary()
        scheduleRouteDepthCheck()
    }

    func refreshRouteSummary() {
        let toolActive = app.toolMode == .route
        // While navigating the display at the top has it all; keep the chart clear unless editing the route.
        if session.isNavigating && !toolActive {
            routeCardText = nil
            return
        }
        let summary = planner.summary(routeToolActive: toolActive)
        if let s = summary, let note = routeDepthNote {
            routeCardText = s + "\n" + note
        } else {
            routeCardText = summary
        }
    }

    func saveCurrentRoute() {
        let points = planner.points
        guard points.count >= 2 else {
            app.toast("Add at least two points first")
            return
        }
        let existing = planner.savedId.flatMap { routeLibrary.byId($0) }
        let suggested = existing?.name ?? routeLibrary.freeName("Route \(routeLibrary.routes.count + 1)")
        let library = routeLibrary
        let exceptId = existing?.id
        app.present(detents: [.medium]) {
            RouteNameSheet(title: existing != nil ? "Save route" : "Save new route", initial: suggested,
                           validate: { name in Self.routeNameError(name, library: library, exceptId: exceptId) },
                           onName: { [weak self] name in self?.saveRoute(id: exceptId, name: name, points: points) })
        }
    }

    func saveRoute(id: String?, name: String, points: [RoutePoint]) {
        let saved = routeLibrary.save(id: id, name: name, points: points)
        planner.markSaved(saved)
        refreshRouteSummary()
        app.toast("Saved \(name)")
    }

    static func routeNameError(_ name: String, library: RouteLibrary, exceptId: String?) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return "Enter a name" }
        if library.isNameTaken(trimmed, exceptId: exceptId) { return "A route called \"\(trimmed)\" already exists" }
        return nil
    }

    func showLegs() {
        let points = planner.points
        guard points.count >= 2 else {
            app.toast("Add at least two points first")
            return
        }
        let speed = planner.planSpeed().knots
        let settings = app.navSettings.settings
        let plan = RoutePlan(points: points, speedKn: speed, burnLph: settings.fuelBurnLph)
        let depths: [RouteDepthCheck?] = plan.legs.map { leg in
            RouteDepthCheck.compute([leg.from, leg.to]) { lat, lng in self.app.depthSample(lat: lat, lng: lng) }
        }
        let anySurvey = depths.contains { $0 != nil }
        let variation = variationModel
        let title = planner.savedName ?? "Route legs"
        app.present {
            RouteLegsSheet(title: title, plan: plan, speedKn: speed, settings: settings,
                           variation: { lat, lng in variation.variation(lat, lng) },
                           departureMillis: Int64(Date().timeIntervalSince1970 * 1000),
                           legDepths: anySurvey ? depths : nil)
        }
    }

    /// Loads a saved route onto the chart.
    func loadRoute(_ route: SavedRoute) {
        planner.load(route)
        refreshRoute()
        fitRoute(route.points)
    }

    func navigateRoute(_ route: SavedRoute) {
        planner.load(route)
        refreshRoute()
        startNavigation(route.points, name: route.name)
    }

    func routeDeleted(_ route: SavedRoute) {
        planner.forgetSaved(route.id)
        refreshRouteSummary()
    }

    func shareRouteGpx(_ points: [RoutePoint], name: String) {
        guard points.count >= 2 else {
            app.toast("Add at least two points to export a route")
            return
        }
        let gpx = GpxCodec.export(waypoints: [], route: points, routeName: name)
        let folder = AppPaths.caches.appendingPathComponent("gpx", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(Self.fileName(name) + ".gpx")
        do {
            try gpx.write(to: url, atomically: true, encoding: .utf8)
            app.share([url])
        } catch {
            DiagnosticLog.logError("Navigation", "GPX export failed", error)
            app.toast("Couldn't create the GPX file")
        }
    }

    private static func fileName(_ name: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:?%*|\"<>").union(.newlines).union(.controlCharacters)
        let cleaned = name.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "Route" : String(cleaned.prefix(80))
    }

    func fitRoute(_ points: [RoutePoint]) {
        guard let first = points.first else { return }
        if points.count == 1 {
            flyTo(first.lat, first.lng)
            return
        }
        var b = GeoBounds(west: first.lng, south: first.lat, east: first.lng, north: first.lat)
        for p in points {
            b.west = min(b.west, p.lng); b.east = max(b.east, p.lng)
            b.south = min(b.south, p.lat); b.north = max(b.north, p.lat)
        }
        // A route due north or east has no width or height; give the box some.
        if b.east - b.west < 1e-5 { b.west -= 0.001; b.east += 0.001 }
        if b.north - b.south < 1e-5 { b.south -= 0.001; b.north += 0.001 }
        app.map.fit(b, padding: 60)
    }

    // Navigation -----------------------------------------------------------------------------

    func startNavigation(_ points: [RoutePoint], name: String?) {
        guard !points.isEmpty else {
            app.toast("Plan or load a route first")
            return
        }
        app.closePanels()
        if app.toolMode != .none { app.setTool(app.toolMode) }
        session.start(points: points, name: name)
        isNavigating = true
        startGpsFollow()
        refreshRouteSummary()
    }

    func confirmStop() {
        app.confirm("Stop navigating?", "", confirm: "Stop") { [weak self] in
            guard let self else { return }
            self.session.stop()
            self.isNavigating = false
            self.refreshRouteSummary()
        }
    }

    func previousPoint() { session.previous() }
    func skipPoint() { session.skip() }

    func toggleMute() {
        let muted = session.toggleMute()
        app.toast(muted ? "Alarms muted" : "Alarms on")
    }

    /// Follow mode with GPS on (asks for permission if needed), used when navigating.
    private func startGpsFollow() {
        if gps.mode == .off { gps.cycle() }
        if gps.mode == .locate { gps.cycle() }
    }

    private func offerResume() {
        guard let pending = session.pendingResume() else { return }
        NavigationFeature.later(1) { [weak self] in
            guard let self else { return }
            self.app.confirm("Resume navigation?", "You were navigating \(pending.label).", confirm: "Resume") { [weak self] in
                guard let self else { return }
                pending.resume()
                self.isNavigating = self.session.isNavigating
                self.startGpsFollow()
                self.refreshRouteSummary()
            }
        }
    }

    // GPS and settings ----------------------------------------------------------------------

    private func onLocation(_ location: CLLocation) {
        planner.updateGpsSpeedKnots(location.speed >= 0 ? location.speed * Self.msToKnots : nil)
        session.onLocation(location)
        if !planner.points.isEmpty { refreshRouteSummary() }
        // Course up: turn the chart to the boat's course while following and moving.
        let s = app.navSettings.settings
        if s.courseUp, gps.mode == .follow, location.course >= 0, location.speed > Self.courseUpMinSpeedMs {
            app.map.setBearing(location.course)
        }
    }

    private func settingsChanged(_ new: NavSettings) {
        let old = lastSettings
        lastSettings = new
        if old.courseUp && !new.courseUp { app.map.setBearing(0) }
        variationModel.setNeeded(new.units.bearing == .magnetic)
        session.settingsChanged()
        refreshRoute()
    }

    /**
     Samples the depth layers along the route after a short pause and adds the
     least depth to the route card, flagged when it is less than the boat's
     draft + safety margin.
     */
    private func scheduleRouteDepthCheck() {
        routeDepthTask?.cancel()
        routeDepthNote = nil
        let points = planner.points
        guard points.count >= 2 else { return }
        routeDepthTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled, let self else { return }
            let check = RouteDepthCheck.compute(points) { lat, lng in self.app.depthSample(lat: lat, lng: lng) }
            guard !Task.isCancelled else { return }
            let s = self.app.navSettings.settings
            let format = s.format
            if let c = check {
                if c.shallowestMetres > 0 {
                    self.routeDepthNote = "⚠ Route crosses drying ground (+\(format.depth(c.shallowestMetres)))"
                } else {
                    let water = -c.shallowestMetres
                    let covered = c.coveredFraction < 0.98 ? " (\(Int(c.coveredFraction * 100))% surveyed)" : ""
                    let warn = water < s.minimumDepthM ? "  ⚠ under your \(format.depth(s.minimumDepthM))" : ""
                    self.routeDepthNote = "Least depth \(format.depth(water))\(covered)\(warn)"
                }
            } else {
                self.routeDepthNote = nil
            }
            self.refreshRouteSummary()
        }
    }
}
