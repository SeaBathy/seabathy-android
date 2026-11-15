import Combine
import CoreLocation
import MapLibre
import SwiftUI
import UIKit

/// One line of the values box, ready to draw.
struct ConditionsBoxRow: Identifiable, Equatable {
    let id: Int
    let text: String
    /// Where the arrow points on screen (degrees clockwise from up), nil for no arrow.
    let arrowDegrees: Double?
    let layer: ConditionsColourChoice?
    let highlighted: Bool
}

/// What the values box shows.
struct ConditionsBoxState: Equatable {
    var title: String
    var rows: [ConditionsBoxRow]
    var warning: String?
}

/// What the bottom card shows while a layer is on the map.
struct ConditionsCardState: Equatable {
    var title: String
    var warning: String?
    /// Colour key: 16 colours low to high, and three labels.
    var legendColours: [UInt32]?
    var legendLow = ""
    var legendMid = ""
    var legendHigh = ""
    /// Time slider: hours, the hour shown, and whether it follows the clock.
    var hours = 0
    var hourIndex = 0
    var showsSlider = false
    var live = true
}

/// Lets a picture nobody wants any more stop part-way.
final class ConditionsCancel: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return value }
    func cancel() { lock.lock(); value = true; lock.unlock() }
}

/**
 Sea conditions for fishing (Android ConditionsFeature): surface currents
 (tidal flow included), waves, swell and wind forecast hourly for up to 8 days,
 plus satellite sea temperature, breaks, chlorophyll, sea level and salinity,
 and temperature at depth from an ocean model. Downloaded round the spot (and
 for areas saved by hand) and kept, so it works offline at sea. Shown as a
 colour layer and moving lines over the chart, a time slider to step through the
 forecast, and the values at the boat or the crosshair.
 */
@MainActor
final class ConditionsFeature: ObservableObject {
    unowned let app: AppModel

    // Preference keys: the same as Android's.
    private enum K {
        static let shown = "conditions_shown", area = "conditions_area", colour = "conditions_colour"
        static let opacity = "conditions_opacity", days = "conditions_days", satellite = "conditions_satellite"
        static let style = "conditions_direction_style", box = "conditions_box", spotGps = "conditions_spot_gps"
        static let spot = "conditions_spot", auto = "conditions_keep_updated", autoHours = "conditions_update_hours"
        static let boxOpen = "conditions_box_open", lastFix = "conditions_last_fix", preview = "conditions_preview"
        static let belowDepth = "conditions_below_depth_m", tempRange = "conditions_temperature_range"
    }

    private static let runCheckMs: Int64 = 15 * 60_000
    private static let fixSaveMs: Int64 = 60_000
    private static let startupDelayNs: UInt64 = 8_000_000_000
    private static let autoRetryMs: Int64 = 15 * 60_000
    private static let playStepNs: UInt64 = 650_000_000
    private static let tickNs: UInt64 = 5 * 60 * 1_000_000_000

    private static let imageSource = "conditions-image-src"
    private static let imageLayer = "conditions-image"
    private static let arrowSource = "conditions-arrows-src"
    private static let arrowLayer = "conditions-arrows"
    private static let arrowImage = "conditions-arrow"

    private let defaults = UserDefaults.standard
    private let store: ConditionsStore
    private let client = ConditionsClient()
    /// Reading and drawing on one queue, downloads on another at lower priority.
    private let workQueue = DispatchQueue(label: "seabathy.conditions", qos: .userInitiated)
    private let downloadQueue = DispatchQueue(label: "seabathy.conditions.download", qos: .utility)

    // Settings ------------------------------------------------------------------------------
    @Published private(set) var shown: Bool
    @Published private(set) var colour: ConditionsColourChoice
    /// The lines always go with what the map shows: wind lines on wind, the current under the rest.
    private let arrows = ConditionsArrowChoice.same
    @Published private(set) var opacity: Double
    @Published private(set) var days: Int
    @Published private(set) var withSatellite: Bool
    @Published private(set) var belowDepthM: Int
    /// A range for the temperature colours, in °C; nil stretches them to the area.
    @Published private(set) var temperatureRange: (Double, Double)?
    @Published private(set) var directionStyle: ConditionsDirectionStyle
    @Published private(set) var boxShown: Bool
    @Published private(set) var followGps: Bool
    @Published private(set) var fixedSpot: (Double, Double)?
    /// Hours between automatic updates of the spot's download; 0 = only when asked.
    @Published private(set) var autoHours: Int
    @Published private(set) var boxOpen: Bool

    // State shown by the views --------------------------------------------------------------
    @Published private(set) var box: ConditionsBoxState?
    @Published private(set) var card: ConditionsCardState?
    @Published private(set) var picking = false
    @Published private(set) var downloading = false
    @Published private(set) var status: String?
    @Published private(set) var playing = false
    @Published private(set) var summaries: [ConditionsSummary] = []
    @Published private(set) var areaId: String?

    private var keepUpdated: Bool { autoHours > 0 }
    private var flow: ConditionsArrowChoice { arrows.resolve(colour) }

    private var gpsFix: (Double, Double)?
    private var fixSavedAt: Int64 = 0
    private var lastAutoAttempt: Int64 = 0
    private var latestRun: Int64?
    private var lastRunCheck: Int64 = 0
    private var nextDownloadAllowed: Int64 = 0
    private var spotArea: ConditionsArea?
    private var area: ConditionsArea?
    /// The area the box last read from: a tap on a line shows that one.
    private var boxArea: ConditionsArea?
    private var live = true
    private var pickedMillis: Int64 = 0
    private var timeMillis: Int64 { live ? ConditionsLogic.nowMillis() : pickedMillis }
    private var startupDone = false
    private var paused = false
    private var appInBackground = false

    private var drawTask: Task<Void, Never>?
    private var drawCancel: ConditionsCancel?
    private var drawingKey: String?
    private var drawingArrows: ConditionsArrowChoice?
    private var drawingStyle: ConditionsDirectionStyle?
    private var lastImage: UIImage?
    private var lastPictureKey: String?
    private var scaleCache: (String, ConditionsColourScale)?
    private var previewSavedAt: Int64 = 0

    private var playTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var retrySpotTask: Task<Void, Never>?
    private var retryShownTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()

    private let flowView = ConditionsFlowLinesView(frame: .zero)

    init(app: AppModel) {
        self.app = app
        store = ConditionsStore(dir: AppPaths.supportFolder("conditions"))
        let d = UserDefaults.standard
        func pair(_ key: String) -> (Double, Double)? {
            guard let parts = d.string(forKey: key)?.split(separator: ",").compactMap({ Double($0) }), parts.count == 2 else { return nil }
            return (parts[0], parts[1])
        }
        shown = d.bool(forKey: K.shown)
        areaId = d.string(forKey: K.area)
        let savedColour = d.string(forKey: K.colour).flatMap { ConditionsColourChoice(rawValue: $0) }
        colour = (savedColour == nil || savedColour == ConditionsColourChoice.none) ? .current : savedColour!
        opacity = min(max((d.object(forKey: K.opacity) as? NSNumber)?.doubleValue ?? 0.65, 0), 1)
        days = min(max((d.object(forKey: K.days) as? NSNumber)?.intValue ?? 7, 1), ConditionsClient.maxDays)
        withSatellite = d.object(forKey: K.satellite) == nil ? true : d.bool(forKey: K.satellite)
        belowDepthM = (d.object(forKey: K.belowDepth) as? NSNumber)?.intValue ?? ConditionsArea.defaultBelowDepthM
        if let range = pair(K.tempRange), range.0 < range.1 { temperatureRange = range } else { temperatureRange = nil }
        directionStyle = d.string(forKey: K.style).flatMap { ConditionsDirectionStyle(rawValue: $0) } ?? .flow
        boxShown = d.object(forKey: K.box) == nil ? true : d.bool(forKey: K.box)
        followGps = d.object(forKey: K.spotGps) == nil ? true : d.bool(forKey: K.spotGps)
        fixedSpot = pair(K.spot)
        gpsFix = pair(K.lastFix)
        let keepOn = d.object(forKey: K.auto) == nil ? true : d.bool(forKey: K.auto)
        autoHours = (d.object(forKey: K.autoHours) as? NSNumber)?.intValue ?? (keepOn ? 6 : 0)
        boxOpen = d.bool(forKey: K.boxOpen)
    }

    // Start-up ---------------------------------------------------------------------------

    func start() {
        app.basemap.addCredits { [weak self] in self?.credits() ?? [] }
        flowView.project = { [weak self] coordinate in self?.app.map.point(for: coordinate) ?? .zero }
        flowView.centre = { [weak self] in self?.app.map.mapView?.centerCoordinate }
        app.map.onStyleLoaded { [weak self] _ in self?.onStyleInstalled() }
        app.map.onCameraMove { [weak self] in self?.updateBox() }
        app.map.onCameraIdle { [weak self] in
            self?.updateBox()
            self?.showNewestHere()
        }
        app.location.onLocation { [weak self] location in
            self?.onLocation(lat: location.coordinate.latitude, lng: location.coordinate.longitude)
        }
        app.onConnectivityRestored { [weak self] in self?.onConnectivityRestored() }
        app.navSettings.$settings.dropFirst().sink { [weak self] _ in
            Task { @MainActor in self?.unitsChanged() }
        }.store(in: &cancellables)
        app.navigation.$isNavigating.dropFirst().sink { [weak self] _ in
            Task { @MainActor in self?.updateBox() }
        }.store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in
            Task { @MainActor in self?.enteredBackground() }
        }.store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification).sink { [weak self] _ in
            Task { @MainActor in self?.enteredForeground() }
        }.store(in: &cancellables)
        startTicker()

        let store = self.store
        let wantedId = areaId
        Task { [weak self] in
            let started = Date()
            let loaded = await Self.run(self?.workQueue ?? DispatchQueue.global()) { () -> (ConditionsArea?, ConditionsArea?, [ConditionsSummary]) in
                let list = store.summaries()
                let id = (wantedId.flatMap { w in list.contains { $0.id == w } ? w : nil }) ?? list.first?.id
                let picked = id.flatMap { store.load($0) }
                return (picked, store.load(ConditionsLogic.spotId), list)
            }
            guard let self else { return }
            DiagnosticLog.log("Conditions", "saved areas read in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
            self.area = loaded.0
            self.areaId = loaded.0?.id ?? self.areaId
            self.spotArea = loaded.1
            self.summaries = loaded.2
            // Last time's picture straight away; the fresh one replaces it when drawn.
            if self.shown, let picked = loaded.0, self.lastImage == nil {
                let key = self.previewKey(picked, self.colour)
                if self.defaults.string(forKey: K.preview) == key, let image = UIImage(contentsOfFile: self.previewURL.path) {
                    self.showPicture(picked, image, key: "preview")
                }
            }
            if self.shown { self.redraw() } else { self.updateBox() }
            self.app.basemap.refreshAttribution()
            self.removeOldAreas()
            // Let the chart and its layers load first; the update can wait a few seconds.
            try? await Task.sleep(nanoseconds: Self.startupDelayNs)
            self.startupDone = true
            self.checkForUpdates(force: true)
        }
    }

    /// Runs blocking work on a queue and waits for it without holding the main thread.
    nonisolated private static func run<T>(_ queue: DispatchQueue, _ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { (continuation: CheckedContinuation<T, Never>) in
            queue.async { continuation.resume(returning: work()) }
        }
    }

    private func startTicker() {
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.tickNs)
                guard !Task.isCancelled, let self else { return }
                if self.shown && self.live { self.redraw() } else { self.updateBox() }
                self.checkForUpdates()
            }
        }
    }

    /// The style was rebuilt: everything custom is gone, so put the layers back.
    private func onStyleInstalled() {
        attachFlowView()
        installArrowLayer()
        // Put the picture back (drawn before) rather than drawing it again.
        if shown, let a = area, let image = lastImage { showPicture(a, image, key: lastPictureKey ?? "") }
        if shown { redraw() } else { clearMap() }
    }

    private func attachFlowView() {
        guard let mapView = app.map.mapView, flowView.superview !== mapView else { return }
        flowView.removeFromSuperview()
        flowView.frame = mapView.bounds
        flowView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        mapView.addSubview(flowView)
    }

    private func installArrowLayer() {
        guard let style = app.map.style else { return }
        style.setImage(Self.arrowBitmap(), forName: Self.arrowImage)
        guard let source = app.map.geoJSONSource(Self.arrowSource) else { return }
        guard style.layer(withIdentifier: Self.arrowLayer) == nil else { return }
        let layer = MLNSymbolStyleLayer(identifier: Self.arrowLayer, source: source)
        layer.iconImageName = NSExpression(forConstantValue: Self.arrowImage)
        layer.iconRotation = NSExpression(forKeyPath: "rot")
        layer.iconScale = NSExpression(forKeyPath: "size")
        layer.iconAllowsOverlap = NSExpression(forConstantValue: true)
        layer.iconIgnoresPlacement = NSExpression(forConstantValue: true)
        layer.iconRotationAlignment = NSExpression(forConstantValue: "map")
        layer.iconPitchAlignment = NSExpression(forConstantValue: "map")
        layer.iconOpacity = NSExpression(forConstantValue: NSNumber(value: arrowOpacity))
        insertAboveOverlays(layer, style: style)
    }

    /// Above every imported layer, under the app's own marks.
    private func insertAboveOverlays(_ layer: MLNStyleLayer, style: MLNStyle) {
        if let old = style.layer(withIdentifier: layer.identifier) { style.removeLayer(old) }
        if let anchor = style.layer(withIdentifier: MapLayerIds.overlayAnchor) {
            style.insertLayer(layer, above: anchor)
        } else {
            style.addLayer(layer)
        }
    }

    // App lifecycle and the 3D view ------------------------------------------------------

    /// Stops the moving lines and Play (while the 3D view is open).
    func pause() {
        paused = true
        tickTask?.cancel()
        setPlaying(false)
        flowView.stop()
    }

    /// Starts them again after `pause`.
    func resume() {
        paused = false
        guard !appInBackground else { return }
        startTicker()
        if shown { redraw() } else { updateBox() }
        if startupDone { checkForUpdates() }
    }

    private func enteredBackground() {
        appInBackground = true
        tickTask?.cancel()
        setPlaying(false)
        // No drawing while the app is in the background.
        flowView.stop()
    }

    private func enteredForeground() {
        appInBackground = false
        if paused || app.terrain.isOpen { return }
        startTicker()
        if shown { redraw() } else { updateBox() }
        // Back to the app after a while: is there a newer forecast?
        if startupDone { checkForUpdates() }
    }

    /// A GPS fix: the spot follows it when set to.
    private func onLocation(lat: Double, lng: Double) {
        gpsFix = (lat, lng)
        let now = ConditionsLogic.nowMillis()
        // Remembered now and then, not on every fix.
        if now - fixSavedAt > Self.fixSaveMs {
            fixSavedAt = now
            defaults.set(String(format: "%.5f,%.5f", lat, lng), forKey: K.lastFix)
        }
        guard followGps else { return }
        updateBox()
        keepSpotUpdated()
    }

    private func onConnectivityRestored() {
        DiagnosticLog.log("Conditions", "back online: checking for updates")
        checkForUpdates(force: true)
        updateBox()
    }

    private func unitsChanged() {
        if shown, let a = area { refreshCard(a) }
        updateBox()
    }

    /// The credits for whatever is shown, for the attribution line.
    func credits() -> [String] {
        guard shown, let a = area else { return [] }
        return ConditionsLogic.credits(a)
    }

    // Showing ------------------------------------------------------------------------------

    private func setShown(_ value: Bool) {
        shown = value
        defaults.set(value, forKey: K.shown)
        if value { redraw() } else { clearMap() }
        app.basemap.refreshAttribution()
    }

    private func clearMap() {
        drawCancel?.cancel()
        drawTask?.cancel()
        drawTask = nil
        setPlaying(false)
        card = nil
        flowView.stop()
        updateBox()
        app.map.removeLayer(Self.imageLayer)
        app.map.removeSource(Self.imageSource)
        if app.map.style != nil { app.map.setGeoJSON(Self.arrowSource, MapLayerIds.emptyGeoJSON) }
        lastPictureKey = nil
    }

    private func scaleKey(_ a: ConditionsArea, _ choice: ConditionsColourChoice) -> String {
        let range = choice.isTemperature ? temperatureRange.map { "\($0.0),\($0.1)" } ?? "auto" : "-"
        return "\(a.id):\(a.fetchedMillis):\(choice.rawValue):\(range)"
    }

    private func cachedScale(_ a: ConditionsArea, _ choice: ConditionsColourChoice) -> ConditionsColourScale? {
        guard let cache = scaleCache, cache.0 == scaleKey(a, choice) else { return nil }
        return cache.1
    }

    /// Draws the colour picture and arrows for the current choices and time, off the main thread.
    private func redraw() {
        guard shown, let a = area else {
            clearMap()
            return
        }
        refreshCard(a)
        updateBox()
        let time = timeMillis
        let choice = colour
        let arrowChoice = flow
        let style = directionStyle
        showFlowLines(a, time)
        // A satellite picture doesn't change with the time; a range set for the colours changes any temperature's.
        let rangeText = temperatureRange.map { "\($0.0),\($0.1)" } ?? "auto"
        let key = "\(a.id):\(a.fetchedMillis):\(choice.rawValue):" + (choice.forecast ? "\(time / ConditionsLogic.pictureStepMs)" : "0") +
            (choice.isTemperature ? ":\(rangeText)" : "")
        // Already drawing this very picture: let it finish.
        if key == drawingKey, drawTask != nil, arrowChoice == drawingArrows, style == drawingStyle { return }
        drawingKey = key
        drawingArrows = arrowChoice
        drawingStyle = style
        drawCancel?.cancel()
        drawTask?.cancel()
        let cancel = ConditionsCancel()
        drawCancel = cancel

        let satellite = choice.satellite.flatMap { a.satellites[$0] }
        let wantsPicture = !(choice == .none || (choice.satellite != nil && satellite == nil) || (choice.forecast && a.forecast == nil))
        let reuse = key == lastPictureKey ? lastImage : nil
        let knownScale = cachedScale(a, choice)
        let range = temperatureRange
        let sKey = scaleKey(a, choice)
        let maxSide: Int
        if let satellite {
            maxSide = max(64, min(ConditionsLogic.maxPictureSide, max(satellite.grid.rows, satellite.grid.cols) * 2))
        } else {
            maxSide = ConditionsLogic.forecastPictureSide
        }

        drawTask = Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let result = await Self.run(queue) { () -> (UIImage?, ConditionsColourScale?, String) in
                var image: UIImage?
                var scale = knownScale
                if wantsPicture {
                    if scale == nil { scale = ConditionsLogic.scale(a, choice, range: range) }
                    if let reuse {
                        image = reuse
                    } else if let sampler = ConditionsLogic.sampler(a, choice, time), let s = scale {
                        let started = Date()
                        let pixels = ConditionsPicture.render(south: a.south, west: a.west, north: a.north, east: a.east,
                                                              maxSide: maxSide, scale: s, sampler: sampler,
                                                              cancelled: { cancel.isCancelled })
                        if !cancel.isCancelled {
                            image = ConditionsPicture.image(pixels)
                            DiagnosticLog.log("Conditions", "picture \(choice.rawValue) \(pixels.width)x\(pixels.height) in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
                        }
                    }
                }
                let json = style.arrows ? ConditionsLogic.arrowGeoJson(a, arrowChoice, time) : MapLayerIds.emptyGeoJSON
                return (image, scale, json)
            }
            guard let self, !cancel.isCancelled else { return }
            self.drawTask = nil
            if let scale = result.1, knownScale == nil {
                self.scaleCache = (sKey, scale)
                if let shownArea = self.area { self.refreshCard(shownArea) }
            }
            self.showPicture(a, result.0, key: key)
            if self.app.map.style != nil { self.app.map.setGeoJSON(Self.arrowSource, result.2) }
            if let image = result.0 { self.savePreview(a, choice, image) }
        }
    }

    private func showPicture(_ a: ConditionsArea, _ image: UIImage?, key: String) {
        guard let style = app.map.style else {
            // Drawn before the map was ready: kept, and put on when it is.
            if let image {
                lastImage = image
                lastPictureKey = key
            }
            return
        }
        guard let image else {
            app.map.removeLayer(Self.imageLayer)
            app.map.removeSource(Self.imageSource)
            lastPictureKey = nil
            return
        }
        let quad = MLNCoordinateQuad(
            topLeft: CLLocationCoordinate2D(latitude: a.north, longitude: a.west),
            bottomLeft: CLLocationCoordinate2D(latitude: a.south, longitude: a.west),
            bottomRight: CLLocationCoordinate2D(latitude: a.south, longitude: a.east),
            topRight: CLLocationCoordinate2D(latitude: a.north, longitude: a.east))
        let source: MLNImageSource
        if let existing = style.source(withIdentifier: Self.imageSource) as? MLNImageSource {
            existing.coordinates = quad
            if image !== lastImage || lastPictureKey == nil || lastPictureKey != key { existing.image = image }
            source = existing
        } else {
            source = MLNImageSource(identifier: Self.imageSource, coordinateQuad: quad, image: image)
            style.addSource(source)
        }
        if style.layer(withIdentifier: Self.imageLayer) == nil {
            let layer = MLNRasterStyleLayer(identifier: Self.imageLayer, source: source)
            layer.rasterOpacity = NSExpression(forConstantValue: NSNumber(value: opacity))
            layer.rasterFadeDuration = NSExpression(forConstantValue: NSNumber(value: 0))
            // Under the arrows, above every imported layer.
            if let arrowLayer = style.layer(withIdentifier: Self.arrowLayer) {
                style.insertLayer(layer, below: arrowLayer)
            } else {
                insertAboveOverlays(layer, style: style)
            }
        }
        lastImage = image
        lastPictureKey = key
    }

    // The last picture, saved so the next start shows colours at once.

    private var previewURL: URL { AppPaths.supportFolder("conditions").appendingPathComponent("preview.png") }

    private func previewKey(_ a: ConditionsArea, _ choice: ConditionsColourChoice) -> String {
        "\(a.id):\(a.fetchedMillis):\(choice.rawValue)"
    }

    private func savePreview(_ a: ConditionsArea, _ choice: ConditionsColourChoice, _ image: UIImage) {
        let key = previewKey(a, choice)
        let now = ConditionsLogic.nowMillis()
        if key == defaults.string(forKey: K.preview) && now - previewSavedAt < ConditionsLogic.pictureStepMs { return }
        previewSavedAt = now
        let url = previewURL
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let saved = await Self.run(queue) { () -> Bool in
                guard let png = image.pngData() else { return false }
                return (try? png.write(to: url, options: .atomic)) != nil
            }
            if saved { self?.defaults.set(key, forKey: K.preview) }
        }
    }

    private var arrowOpacity: Double { max(0.55, opacity) }

    private func applyOpacity() {
        guard let style = app.map.style else { return }
        (style.layer(withIdentifier: Self.imageLayer) as? MLNRasterStyleLayer)?.rasterOpacity = NSExpression(forConstantValue: NSNumber(value: opacity))
        (style.layer(withIdentifier: Self.arrowLayer) as? MLNSymbolStyleLayer)?.iconOpacity = NSExpression(forConstantValue: NSNumber(value: arrowOpacity))
    }

    /// Moving flow lines for the chosen direction, at `time`; off when not chosen.
    private func showFlowLines(_ a: ConditionsArea, _ time: Int64) {
        let choice = flow
        guard directionStyle.flow, !paused, !appInBackground, let field = ConditionsLogic.flowField(a, choice, time) else {
            flowView.stop()
            return
        }
        attachFlowView()
        flowView.show(field: field, reference: choice.reference, south: a.south, west: a.west, north: a.north, east: a.east)
    }

    // Bottom card ---------------------------------------------------------------------------

    private func refreshCard(_ a: ConditionsArea) {
        let forecast = a.forecast
        let time = timeMillis
        let units = app.units
        let format = NavFormat(units: units)
        let usesForecast = (colour.forecast || (directionStyle != .none && flow != .none)) && forecast != nil
        // "Wind speed", not "Wind speed · wind flow": the lines are the layer's own.
        let ownFlow = ConditionsLogic.ownFlow.contains(colour) && flow == ConditionsArrowChoice.same.resolve(colour)
        let layerName = colour == .tempBelow ? "Temperature \(format.depth(Double(a.belowDepthM))) down (model)" : colour.label
        var whatParts: [String] = []
        if colour != .none { whatParts.append(layerName) }
        if flow != .none, forecast != nil, !ownFlow { whatParts.append(flow.short) }
        let what = whatParts.isEmpty ? "Sea conditions" : whatParts.joined(separator: " · ")
        let satellite = colour.satellite.flatMap { a.satellites[$0] }
        let source = colour == .tempBelow ? "model" : "satellite"
        let whenText: String
        if let kind = colour.satellite, satellite == nil {
            whenText = "No \(kind.label.lowercased()) saved here: update the area"
        } else if colour.forecast && forecast == nil {
            whenText = "No forecast saved for this area"
        } else if usesForecast, let forecast, time > forecast.endMillis + ConditionsForecastGrid.hourMs {
            whenText = "The forecast ran out \(ConditionsLogic.day(forecast.endMillis)): update it"
        } else if usesForecast {
            whenText = ConditionsLogic.clock(time) + (live ? " (now)" : "") + (satellite.map { " · \(source) \(ConditionsLogic.day($0.dateMillis))" } ?? "")
        } else if let satellite {
            whenText = "\(source), \(ConditionsLogic.day(satellite.dateMillis))"
        } else {
            whenText = ""
        }
        var state = ConditionsCardState(title: whenText.isEmpty ? "\(what) — \(a.name)" : "\(what)\n\(whenText)")
        state.warning = ConditionsLogic.staleNote(fetchedMillis: a.fetchedMillis, nowMillis: ConditionsLogic.nowMillis(), online: app.isOnline)
        let noLegend = colour == .none || (colour.satellite != nil && satellite == nil) || (colour.forecast && forecast == nil)
        if !noLegend, let scale = cachedScale(a, colour) ?? quickScale(a, colour) {
            state.legendColours = (0..<16).map { scale.colourAt(Double($0) / 15) }
            state.legendLow = ConditionsLogic.legendValue(scale.valueAt(0), colour: colour, units: units)
            state.legendMid = ConditionsLogic.legendValue(scale.valueAt(0.5), colour: colour, units: units)
            // The fixed ranges show anything stronger in the top colour.
            state.legendHigh = ConditionsLogic.legendValue(scale.valueAt(1), colour: colour, units: units) + (colour.fixedRange ? "+" : "")
        }
        if usesForecast, let forecast {
            state.showsSlider = true
            state.hours = forecast.times.count
            state.hourIndex = ConditionsLogic.hourIndex(forecast, time)
            state.live = live
        }
        card = state
    }

    /// Scales that need no sorting of the area's values can be made on the spot.
    private func quickScale(_ a: ConditionsArea, _ choice: ConditionsColourChoice) -> ConditionsColourScale? {
        if choice.fixedRange { return ConditionsColourScale.forChoice(choice) }
        if choice.isTemperature, let range = temperatureRange { return ConditionsColourScale.temperatureRange(low: range.0, high: range.1) }
        return nil
    }

    /// The slider moved to an hour.
    func pickHour(_ index: Int) {
        guard let forecast = area?.forecast else { return }
        setPlaying(false)
        live = false
        pickedMillis = forecast.times[min(max(index, 0), forecast.times.count - 1)]
        redraw()
    }

    func goLive() {
        setPlaying(false)
        live = true
        redraw()
    }

    func togglePlay() { setPlaying(!playing) }

    func step(_ hours: Int) { step(hours, wrap: false, fromPlay: false) }

    private func step(_ hours: Int, wrap: Bool, fromPlay: Bool) {
        if !fromPlay { setPlaying(false) }
        guard let forecast = area?.forecast else { return }
        let current = ConditionsLogic.hourIndex(forecast, timeMillis)
        var next = current + hours
        // Playing goes round again from the start; stepping stops at the ends.
        if wrap && next >= forecast.times.count { next = 0 }
        next = min(max(next, 0), forecast.times.count - 1)
        live = false
        pickedMillis = forecast.times[next]
        redraw()
    }

    private func setPlaying(_ value: Bool) {
        if playing == value { return }
        playing = value
        playTask?.cancel()
        playTask = nil
        guard value else { return }
        playTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.playStepNs)
                guard !Task.isCancelled, let self, self.playing else { return }
                self.step(1, wrap: true, fromPlay: true)
            }
        }
    }

    /// The ✕ on the card.
    func hide() { setShown(false) }

    // Values box ---------------------------------------------------------------------------

    private func updateBox() {
        // While navigating, the navigation display already fills the top of the chart.
        guard boxShown, app.map.mapView != nil, !(app.navigation?.isNavigating ?? false) else {
            if box != nil { box = nil }
            return
        }
        let time = timeMillis
        let target = app.map.centre
        let a: ConditionsArea
        let lat: Double
        let lng: Double
        var wanted: Set<String>?
        var title: String
        let shownArea = area
        if shown, let shownArea, shownArea.contains(lat: target.latitude, lng: target.longitude) {
            // The map layer is on: everything, where the crosshair is.
            a = shownArea
            lat = target.latitude
            lng = target.longitude
            if !boxOpen { wanted = Set(["Wind"] + [ConditionsLogic.rowNameOf(colour)].compactMap { $0 }) }
            title = "At the crosshair · " + (live ? "now" : ConditionsLogic.clock(time))
        } else {
            let spot = self.spot()
            let data = [spotArea, shownArea].compactMap { $0 }.first { area in spot.map { area.contains(lat: $0.0, lng: $0.1) } ?? false }
            guard let spot, let data else {
                let text: String
                if downloading {
                    text = "Sea conditions · downloading…"
                } else if spot == nil && followGps {
                    text = "Sea conditions · waiting for the GPS"
                } else if keepUpdated && !app.isOnline {
                    text = "Sea conditions · none saved here (offline)"
                } else {
                    text = "Sea conditions · tap to set up"
                }
                boxArea = nil
                let next = ConditionsBoxState(title: text, rows: [], warning: nil)
                if box != next { box = next }
                return
            }
            a = data
            lat = spot.0
            lng = spot.1
            if !boxOpen {
                var names: Set<String> = ["Wind"]
                if shown, let name = ConditionsLogic.rowNameOf(colour) { names.insert(name) }
                wanted = names
            }
            title = (followGps ? "At the boat" : "At your spot") + " · " + (live ? "now" : ConditionsLogic.clock(time))
        }
        boxArea = a
        // The card already says when the layer's area is old.
        let warning = (shown && a === shownArea) ? nil
            : ConditionsLogic.staleNote(fetchedMillis: a.fetchedMillis, nowMillis: ConditionsLogic.nowMillis(), online: app.isOnline)
        // Wind first (it's what the box shows closed), then the rest in a fixed order.
        let order = ConditionsLogic.rowOrder
        let values = ConditionsLogic.values(a, lat: lat, lng: lng, time: time, format: app.format)
            .filter { wanted == nil || wanted!.contains($0.row) }
            .sorted { (order.firstIndex(of: $0.row) ?? order.count) < (order.firstIndex(of: $1.row) ?? order.count) }
        let bearing = app.map.bearing
        let rows = values.prefix(7).enumerated().map { (pair) -> ConditionsBoxRow in
            let i = pair.offset
            let value = pair.element
            let layer = ConditionsLogic.layerOfRow(value.row, area: a, colour: colour)
            let highlighted = shown && layer != nil && (layer == colour || (colour == .chlorophyll && layer?.satellite != nil))
            // As it points on the chart, which may be turned.
            return ConditionsBoxRow(id: i, text: value.text, arrowDegrees: value.towardsDegrees.map { $0 - bearing },
                                    layer: layer, highlighted: highlighted)
        }
        if values.isEmpty {
            if let forecast = a.forecast, time > forecast.endMillis + ConditionsForecastGrid.hourMs {
                title = "The forecast has run out: update it"
            } else {
                title = "No sea data here (on land?)"
            }
        }
        let next = ConditionsBoxState(title: title, rows: rows, warning: warning)
        if box != next { box = next }
    }

    func toggleBoxOpen() {
        boxOpen.toggle()
        defaults.set(boxOpen, forKey: K.boxOpen)
        updateBox()
    }

    /// A line of the box was tapped: show that on the map, from the area the box was reading; or hide it if it's showing.
    func tapRow(_ row: ConditionsBoxRow) {
        guard let layer = row.layer else { return }
        toggleLayer(layer, from: boxArea)
    }

    private func toggleLayer(_ layer: ConditionsColourChoice, from: ConditionsArea? = nil) {
        if shown && colour == layer && (from == nil || from?.id == area?.id) {
            setShown(false)
            return
        }
        colour = layer
        defaults.set(colour.rawValue, forKey: K.colour)
        lastPictureKey = nil
        if let from, from.id != area?.id {
            area = from
            areaId = from.id
            defaults.set(from.id, forKey: K.area)
            scaleCache = nil
            app.basemap.refreshAttribution()
        }
        if area == nil { area = spotArea }
        if !shown { setShown(true) } else { redraw() }
    }

    // The spot ------------------------------------------------------------------------------

    /// The boat when following the GPS (this app's latest fix, or the phone's last known position), else the set point.
    private func spot() -> (Double, Double)? {
        if followGps {
            if let gpsFix { return gpsFix }
            if let last = app.location.lastLocation { return (last.coordinate.latitude, last.coordinate.longitude) }
            return nil
        }
        return fixedSpot
    }

    var spotLabel: String {
        if followGps { return "Following the GPS" }
        guard let s = fixedSpot else { return "Not set" }
        return ConditionsLogic.defaultName(south: s.0, west: s.1, north: s.0, east: s.1)
    }

    /// Keeps the area round the spot downloaded: if there's none, it's old, or the spot has moved out of its middle.
    private func keepSpotUpdated(force: Bool = false) {
        if !keepUpdated || downloading { return }
        let now = ConditionsLogic.nowMillis()
        if !force && now - lastAutoAttempt < Self.autoRetryMs { return }
        // No position yet: keep the last spot's area fresh, or the area on the map's.
        let position: (Double, Double)
        if let s = spot() {
            position = s
        } else if let a = spotArea ?? area {
            position = ((a.south + a.north) / 2, (a.west + a.east) / 2)
        } else {
            DiagnosticLog.log("Conditions", "spot not updated: no GPS position yet and nothing saved to go by")
            return
        }
        let saved = spotArea
        if let saved, !ConditionsPolicy.needsUpdate(fetchedMillis: saved.fetchedMillis, latestRunMillis: latestRun, nowMillis: now),
           saved.middleContains(lat: position.0, lng: position.1) {
            return
        }
        if !app.isOnline {
            DiagnosticLog.log("Conditions", "spot needs an update but there's no internet: showing the saved data")
            return
        }
        if now < nextDownloadAllowed {
            // Too soon after the last one: try again when it's allowed.
            retrySpotTask?.cancel()
            let wait = UInt64(max(nextDownloadAllowed - now + 500, 0)) * 1_000_000
            retrySpotTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: wait)
                guard !Task.isCancelled else { return }
                self?.keepSpotUpdated(force: true)
            }
            return
        }
        lastAutoAttempt = now
        let reason: String
        if let saved {
            reason = saved.middleContains(lat: position.0, lng: position.1)
                ? "saved \(ConditionsLogic.ago(saved.fetchedMillis, now)), newest forecast run \(latestRun.map { ConditionsLogic.ago($0, now) } ?? "unknown")"
                : "the spot moved out of the saved area's middle"
        } else {
            reason = "nothing saved yet"
        }
        DiagnosticLog.log("Conditions", "updating the spot: \(reason)")
        let b = ConditionsLogic.boxAround(lat: position.0, lng: position.1, radiusKm: ConditionsLogic.spotRadiusKm)
        download(south: b[0], west: b[1], north: b[2], east: b[3], replacingId: ConditionsLogic.spotId, quiet: true)
    }

    /// When online, at most every 15 minutes (or now, with `force`): is a newer forecast run out? Then update.
    private func checkForUpdates(force: Bool = false) {
        if !keepUpdated { return }
        let now = ConditionsLogic.nowMillis()
        if !force && now - lastRunCheck < Self.runCheckMs { return }
        if !app.isOnline { return }
        lastRunCheck = now
        let client = self.client
        Task { [weak self] in
            guard let queue = self?.downloadQueue else { return }
            let run = await Self.run(queue) { client.latestRunMillis() }
            guard let self else { return }
            if let run { self.latestRun = run }
            DiagnosticLog.log("Conditions", "update check: newest forecast run \(run.map { ConditionsLogic.ago($0, ConditionsLogic.nowMillis()) } ?? "couldn't be read")")
            self.keepSpotUpdated(force: true)
            self.updateShownArea()
        }
    }

    /// The area on the map, when it isn't the spot's: updated the same way, after any download under way.
    private func updateShownArea() {
        guard let a = area, a.id != ConditionsLogic.spotId, keepUpdated else { return }
        let now = ConditionsLogic.nowMillis()
        if !ConditionsPolicy.needsUpdate(fetchedMillis: a.fetchedMillis, latestRunMillis: latestRun, nowMillis: now) || !app.isOnline { return }
        if downloading || now < nextDownloadAllowed {
            retryShownTask?.cancel()
            let wait = UInt64(max(nextDownloadAllowed - now, 5_000) + 500) * 1_000_000
            retryShownTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: wait)
                guard !Task.isCancelled else { return }
                self?.updateShownArea()
            }
            return
        }
        DiagnosticLog.log("Conditions", "updating \(a.name): saved \(ConditionsLogic.ago(a.fetchedMillis, now))")
        download(south: a.south, west: a.west, north: a.north, east: a.east, replacingId: a.id, quiet: true)
    }

    /// Removes saved areas of no more use: forecasts that ran out days ago, and older copies covered by a newer download.
    private func removeOldAreas() {
        let store = self.store
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let result = await Self.run(queue) { () -> ([String], [ConditionsSummary]) in
                let now = ConditionsLogic.nowMillis()
                var ids: [String] = []
                for (summary, reason) in ConditionsPolicy.toRemove(store.summaries(), keepId: ConditionsLogic.spotId, nowMillis: now) {
                    store.delete(summary.id)
                    ids.append(summary.id)
                    DiagnosticLog.log("Conditions", "removed \(summary.name): " + (reason == .expired ? "its forecast ran out" : "a newer download covers it"))
                }
                return (ids, store.summaries())
            }
            guard let self else { return }
            self.summaries = result.1
            if let current = self.area, result.0.contains(current.id) {
                self.area = nil
                self.showNewestHere()
            }
        }
    }

    /// The map shows the newest saved data where it's looking, online or not.
    private func showNewestHere() {
        guard shown, let list = store.cachedSummaries() else { return }
        let target = app.map.centre
        guard let newest = ConditionsPolicy.newestCovering(lat: target.latitude, lng: target.longitude, areas: list) else { return }
        if let current = area {
            if newest.id == current.id { return }
            if current.contains(lat: target.latitude, lng: target.longitude) && newest.fetchedMillis <= current.fetchedMillis { return }
        }
        let store = self.store
        let id = newest.id
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let loaded = await Self.run(queue) { store.load(id) }
            guard let self, let loaded, self.shown else { return }
            DiagnosticLog.log("Conditions", "map now shows \(loaded.name), the newest data here")
            self.area = loaded
            self.areaId = loaded.id
            self.defaults.set(loaded.id, forKey: K.area)
            self.scaleCache = nil
            self.redraw()
            self.app.basemap.refreshAttribution()
        }
    }

    func followTheGps() {
        followGps = true
        if gpsFix == nil { app.navigation.gps.ensureOn() }
        spotChanged()
    }

    /// Puts the map in charge of choosing: the sheet closes and a banner says what to do.
    func startPicking() {
        app.dismissSheet()
        picking = true
        // Start from the current spot, so "set" without moving keeps it.
        if let s = fixedSpot { app.map.fly(to: s.0, s.1) }
    }

    func endPicking(set: Bool) {
        guard picking else { return }
        picking = false
        guard set else { return }
        let target = app.map.centre
        followGps = false
        fixedSpot = (target.latitude, target.longitude)
        defaults.set(String(format: "%.5f,%.5f", target.latitude, target.longitude), forKey: K.spot)
        spotChanged()
        app.toast("Spot set")
    }

    private func spotChanged() {
        defaults.set(followGps, forKey: K.spotGps)
        keepSpotUpdated(force: true)
        updateBox()
    }

    // Settings sheet actions -------------------------------------------------------------

    /// Opens the Sea conditions sheet (Settings > Sea conditions, or a tap on the box).
    func show() {
        if picking { endPicking(set: false) }
        refreshSummaries()
        app.present { ConditionsSheet(feature: self) }
    }

    private func refreshSummaries() {
        if let cached = store.cachedSummaries() { summaries = cached }
        let store = self.store
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let list = await Self.run(queue) { store.summaries() }
            self?.summaries = list
        }
    }

    var hasAnything: Bool { area != nil || spotArea != nil || !summaries.isEmpty }

    var shownLabel: String { shown ? colour.label : "Off" }

    /// "Show on the map": nil is Off.
    func chooseLayer(_ choice: ConditionsColourChoice?) {
        guard let choice else {
            setShown(false)
            return
        }
        if !hasAnything {
            app.toast("Nothing downloaded yet: tap Update now")
            return
        }
        if shown && colour == choice { return }
        toggleLayer(choice)
    }

    func setAutoUpdate(_ on: Bool) {
        autoHours = on ? 1 : 0
        defaults.set(autoHours, forKey: K.autoHours)
        checkForUpdates(force: true)
    }

    var autoLabel: String { ConditionsLogic.autoLabel(autoHours) }
    var isAutoUpdating: Bool { keepUpdated }

    /// "Update now": the area round the spot.
    func updateNow() {
        if downloading { return }
        let position: (Double, Double)
        if let s = spot() {
            position = s
        } else if let a = spotArea {
            position = ((a.south + a.north) / 2, (a.west + a.east) / 2)
        } else {
            app.toast("Waiting for the GPS: set a spot, or turn the GPS on")
            return
        }
        if !app.isOnline {
            app.toast("No internet: the saved data is still shown")
            return
        }
        let b = ConditionsLogic.boxAround(lat: position.0, lng: position.1, radiusKm: ConditionsLogic.spotRadiusKm)
        lastAutoAttempt = ConditionsLogic.nowMillis()
        download(south: b[0], west: b[1], north: b[2], east: b[3], replacingId: ConditionsLogic.spotId)
    }

    var updateNowDetail: String {
        "\(ConditionsLogic.dayText(days)) of forecast" + (withSatellite ? " + satellite" : "")
    }

    func setDirectionStyle(_ style: ConditionsDirectionStyle) {
        directionStyle = style
        defaults.set(style.rawValue, forKey: K.style)
        redraw()
    }

    func toggleBoxShown() {
        boxShown.toggle()
        defaults.set(boxShown, forKey: K.box)
        updateBox()
    }

    var boxShownLabel: String { boxShown ? "On: at the boat or spot; at the crosshair while a layer shows" : "Off" }

    var temperatureRangeLabel: String {
        guard let range = temperatureRange else { return "Automatic: stretched to the area, so small breaks show" }
        let format = app.format
        return "\(format.temperature(range.0)) to \(format.temperature(range.1))"
    }

    /// Where the range editor starts: the range set, or the automatic one for what's on the map.
    var temperatureRangeStart: (Double, Double) {
        if let range = temperatureRange { return range }
        if let a = area ?? spotArea, let values = a.satellites[.seaTemp]?.values {
            let scale = ConditionsColourScale.forChoice(.seaTempSatellite, values: values)
            return (scale.low, scale.high)
        }
        return (20, 30)
    }

    /// nil is automatic. Celsius.
    func applyTemperatureRange(_ range: (Double, Double)?) {
        temperatureRange = range
        if let range {
            defaults.set(String(format: "%.3f,%.3f", range.0, range.1), forKey: K.tempRange)
        } else {
            defaults.removeObject(forKey: K.tempRange)
        }
        scaleCache = nil
        lastPictureKey = nil
        if shown { redraw() }
    }

    var belowDepthLabel: String { "\(app.format.depth(Double(belowDepthM))) down" }

    func setBelowDepth(_ depth: Int) {
        if depth == belowDepthM { return }
        belowDepthM = depth
        defaults.set(depth, forKey: K.belowDepth)
        refreshBelowDepth()
    }

    /// Live while the slider moves; saved when it is let go.
    func setOpacity(_ value: Double, save: Bool) {
        opacity = min(max(value, 0), 1)
        applyOpacity()
        if save { defaults.set(Float(opacity), forKey: K.opacity) }
    }

    func setDays(_ value: Int) {
        days = value
        defaults.set(value, forKey: K.days)
    }

    func toggleSatellite() {
        withSatellite.toggle()
        defaults.set(withSatellite, forKey: K.satellite)
    }

    /// The detail line of a saved area in the list.
    func detail(for s: ConditionsSummary) -> String {
        let active = s.id == area?.id
        var parts: [String] = []
        if active && shown { parts.append("Showing") }
        if let until = s.forecastUntilMillis {
            parts.append(until < ConditionsLogic.nowMillis() ? "forecast ran out \(ConditionsLogic.day(until))" : "forecast to \(ConditionsLogic.day(until))")
        }
        if s.satellites.count == 1, let only = s.satellites.first {
            parts.append(only.label.lowercased())
        } else if s.satellites.count > 1 {
            parts.append("\(s.satellites.count) ocean layers")
        }
        parts.append("got \(app.format.clock(s.fetchedMillis))")
        parts.append(String(format: "%.0f KB", Double(s.bytes) / 1024))
        return parts.joined(separator: " · ")
    }

    func isActive(_ s: ConditionsSummary) -> Bool { s.id == area?.id }

    func selectArea(_ id: String, goTo: Bool) {
        let store = self.store
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let loaded = await Self.run(queue) { store.load(id) }
            guard let self else { return }
            guard let loaded else {
                self.app.toast("Couldn't read that area")
                return
            }
            self.area = loaded
            self.areaId = id
            self.defaults.set(id, forKey: K.area)
            self.lastPictureKey = nil
            self.scaleCache = nil
            if goTo {
                self.app.dismissSheet()
                self.app.map.fit(GeoBounds(west: loaded.west, south: loaded.south, east: loaded.east, north: loaded.north))
            }
            if !self.shown { self.setShown(true) } else { self.redraw() }
            self.app.basemap.refreshAttribution()
        }
    }

    func updateArea(_ id: String) {
        let store = self.store
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let saved = await Self.run(queue) { store.load(id) }
            guard let self, let saved else { return }
            self.download(south: saved.south, west: saved.west, north: saved.north, east: saved.east, replacingId: saved.id)
        }
    }

    func renameArea(_ id: String, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let store = self.store
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let renamed = await Self.run(queue) { try? store.rename(id, name: trimmed) }
            guard let self else { return }
            self.summaries = store.cachedSummaries() ?? self.summaries
            if let renamed = renamed ?? nil, self.area?.id == id {
                self.area = renamed
                self.redraw()
            }
            if let renamed = renamed ?? nil, self.spotArea?.id == id { self.spotArea = renamed }
        }
    }

    func deleteArea(_ id: String) {
        if id == ConditionsLogic.spotId {
            spotArea = nil
            // Not straight back: it downloads again at the next update.
            lastAutoAttempt = ConditionsLogic.nowMillis()
        }
        let wasShown = area?.id == id
        if wasShown { area = nil }
        let store = self.store
        let wantedId = areaId
        Task { [weak self] in
            guard let queue = self?.workQueue else { return }
            let result = await Self.run(queue) { () -> (ConditionsArea?, [ConditionsSummary]) in
                store.delete(id)
                let list = store.summaries()
                guard wasShown else { return (nil, list) }
                let next = (wantedId.flatMap { w in w != id && list.contains { $0.id == w } ? w : nil }) ?? list.first?.id
                return (next.flatMap { store.load($0) }, list)
            }
            guard let self else { return }
            self.summaries = result.1
            if wasShown {
                self.area = result.0
                self.areaId = result.0?.id
                if self.area == nil { self.setShown(false) } else { self.redraw() }
                self.app.basemap.refreshAttribution()
            }
            self.updateBox()
        }
    }

    // Downloading ----------------------------------------------------------------------

    /// "Save the area on screen": up to about 500 km, the side of the date line the crosshair is on.
    func downloadScreenArea() {
        if downloading { return }
        let bounds = app.map.visibleBounds
        var south = bounds.south, north = bounds.north, west = bounds.west, east = bounds.east
        // The date line on screen: the data services stop there, so take the side the crosshair is on.
        let crossesDateLine = east < west || abs(east - west) > 180
        if crossesDateLine {
            if app.map.centre.longitude >= 0 { east = ConditionsArea.maxLng } else { west = -ConditionsArea.maxLng }
        }
        // Zoomed out too far: take the middle of the screen, the most an area can be.
        let tooBig = north - south > ConditionsLogic.maxSpanDeg || east - west > ConditionsLogic.maxSpanDeg
        func fit(_ lo: Double, _ hi: Double) -> (Double, Double) {
            let mid = (lo + hi) / 2
            let span = min(max(hi - lo, ConditionsLogic.minSpanDeg), ConditionsLogic.maxSpanDeg)
            return (mid - span / 2, mid + span / 2)
        }
        let la = fit(south, north)
        south = max(la.0, -ConditionsArea.maxLat)
        north = min(la.1, ConditionsArea.maxLat)
        let lo = fit(west, east)
        west = max(lo.0, -ConditionsArea.maxLng)
        east = min(lo.1, ConditionsArea.maxLng)
        var note: String?
        if tooBig {
            note = "This was the middle \(Int((ConditionsLogic.maxSpanDeg * 111).rounded())) km of the screen, the most one area holds."
        } else if crossesDateLine {
            note = "This was the side of the date line the crosshair is on."
        }
        download(south: south, west: west, north: north, east: east, replacingId: nil, note: note)
    }

    private func setStatus(_ text: String?) { status = text }

    /// After "How far down" changes: only the temperature at the new depth, for the area on the map (or the spot's).
    private func refreshBelowDepth() {
        let depthText = app.format.depth(Double(belowDepthM))
        guard let a = area ?? spotArea, !downloading else {
            app.toast("Saved: the next download gets the temperature \(depthText) down")
            return
        }
        if !app.isOnline {
            app.toast("No internet: the temperature \(depthText) down comes with the next update")
            return
        }
        let depth = belowDepthM
        downloading = true
        status = "Downloading the temperature \(depthText) down…"
        let client = self.client
        let store = self.store
        Task { [weak self] in
            guard let queue = self?.downloadQueue else { return }
            let result = await Self.run(queue) { () -> Result<ConditionsArea, Error> in
                do {
                    let grid = try client.satellite(.tempBelow, south: a.south, west: a.west, north: a.north, east: a.east, belowDepthM: depth)
                    var sats = a.satellites
                    sats[.tempBelow] = grid
                    let fresh = ConditionsArea(id: a.id, name: a.name, south: a.south, west: a.west, north: a.north, east: a.east,
                                               fetchedMillis: ConditionsLogic.nowMillis(), forecast: a.forecast, satellites: sats, belowDepthM: depth)
                    try store.save(fresh)
                    return .success(fresh)
                } catch {
                    return .failure(error)
                }
            }
            guard let self else { return }
            self.status = nil
            self.downloading = false
            switch result {
            case .success(let fresh):
                if self.spotArea?.id == fresh.id { self.spotArea = fresh }
                if self.area?.id == fresh.id {
                    self.area = fresh
                    self.lastPictureKey = nil
                    self.scaleCache = nil
                }
                self.summaries = store.cachedSummaries() ?? self.summaries
                if self.shown { self.redraw() } else { self.updateBox() }
                self.app.toast("Temperature \(depthText) down updated for \(fresh.name)")
            case .failure(let error):
                DiagnosticLog.logError("Conditions", "temperature at depth download FAILED", error)
                self.app.toast("Couldn't get the temperature \(depthText) down: \(ConditionsClient.message(error))")
            }
        }
    }

    /// Downloads an area; `replacingId` updates that saved area (otherwise one with the same bounds is replaced).
    /// `quiet`: an automatic update, with no messages.
    private func download(south: Double, west: Double, north: Double, east: Double, replacingId: String?,
                          note: String? = nil, quiet: Bool = false) {
        if downloading { return }
        let now = ConditionsLogic.nowMillis()
        if now < nextDownloadAllowed {
            if !quiet { app.toast("Just downloaded: try again in \((nextDownloadAllowed - now + 999) / 1000) s") }
            return
        }
        nextDownloadAllowed = now + ConditionsLogic.minDownloadGapMs
        downloading = true
        updateBox()
        let dayCount = days
        let satellites = withSatellite
        let depth = belowDepthM
        status = "Downloading the forecast…"
        let client = self.client
        let store = self.store
        let progress: @Sendable (String) -> Void = { [weak self] text in
            guard let self else { return }
            Task { @MainActor in self.setStatus(text) }
        }
        Task { [weak self] in
            guard let queue = self?.downloadQueue else { return }
            let result = await Self.run(queue) { () -> (ConditionsArea?, [String]) in
                var problems: [String] = []
                // Named: that one (even if it isn't saved yet). Otherwise one already covering these bounds.
                let replacing: ConditionsArea?
                if let replacingId {
                    replacing = store.load(replacingId)
                } else {
                    replacing = store.summaries().first { $0.sameBoundsAs(south, west, north, east) }.flatMap { store.load($0.id) }
                }
                let span = max(north - south, east - west)
                let grid = ConditionsGeoGrid.covering(south: south, west: west, north: north, east: east,
                                                      step: max(ConditionsLogic.minStepDeg, span / Double(ConditionsLogic.maxPointsPerSide - 1)),
                                                      maxPerSide: ConditionsLogic.maxPointsPerSide)
                let windGrid = ConditionsGeoGrid.covering(south: south, west: west, north: north, east: east,
                                                          step: max(ConditionsLogic.minStepDeg, span / Double(ConditionsLogic.maxWindPointsPerSide - 1)),
                                                          maxPerSide: ConditionsLogic.maxWindPointsPerSide)
                var forecast: ConditionsForecastGrid?
                do {
                    forecast = try client.forecast(grid: grid, days: dayCount, windGrid: windGrid) { p in
                        progress("Downloading the forecast… \(Int((p * 100).rounded()))%")
                    }
                } catch {
                    DiagnosticLog.logError("Conditions", "forecast download FAILED", error)
                    problems.append("Forecast: \(ConditionsClient.message(error))")
                }
                var sats: [ConditionsSatelliteKind: ConditionsSatelliteGrid] = [:]
                if satellites {
                    for kind in ConditionsSatelliteKind.allCases {
                        progress("Downloading \(kind.label.lowercased())…")
                        do {
                            sats[kind] = try client.satellite(kind, south: south, west: west, north: north, east: east, belowDepthM: depth)
                        } catch {
                            DiagnosticLog.logError("Conditions", "\(kind.rawValue) download FAILED", error)
                            // Salinity and the rest often have nothing near a coast: only said for the main ones.
                            if kind.required { problems.append("\(kind.label): \(ConditionsClient.message(error))") }
                        }
                    }
                }
                if forecast == nil && sats.isEmpty { return (nil, problems) }
                // Keep what an update couldn't get again rather than losing it, unless it was for another depth.
                if let replacing {
                    for (kind, grid) in replacing.satellites where sats[kind] == nil {
                        if kind == .tempBelow && replacing.belowDepthM != depth { continue }
                        sats[kind] = grid
                    }
                }
                let name = replacing?.name ?? (replacingId == ConditionsLogic.spotId ? ConditionsLogic.spotName
                    : ConditionsLogic.defaultName(south: south, west: west, north: north, east: east))
                let newArea = ConditionsArea(id: replacing?.id ?? replacingId ?? UUID().uuidString, name: name,
                                             south: south, west: west, north: north, east: east,
                                             fetchedMillis: ConditionsLogic.nowMillis(), forecast: forecast ?? replacing?.forecast,
                                             satellites: sats, belowDepthM: depth)
                do {
                    try store.save(newArea)
                    return (newArea, problems)
                } catch {
                    problems.append("Saving: \(ConditionsClient.message(error))")
                    return (nil, problems)
                }
            }
            self?.finishDownload(result.0, problems: result.1, note: note, quiet: quiet)
        }
    }

    private func finishDownload(_ result: ConditionsArea?, problems: [String], note: String?, quiet: Bool) {
        status = nil
        downloading = false
        summaries = store.cachedSummaries() ?? summaries
        guard let result else {
            if !quiet { app.toast("Couldn't get the sea conditions. " + problems.joined(separator: " ")) }
            updateBox()
            return
        }
        if result.id == ConditionsLogic.spotId { spotArea = result }
        // An update of the spot only changes the map if the spot's area is the one on it.
        let showIt = !quiet || area == nil || area?.id == result.id
        if showIt {
            area = result
            areaId = result.id
            defaults.set(result.id, forKey: K.area)
            lastPictureKey = nil
            scaleCache = nil
        }
        if !quiet {
            live = true
            if !shown { setShown(true) } else { redraw() }
            let saved = problems.isEmpty ? "Sea conditions saved for \(result.name)." : "Saved \(result.name), but: " + problems.joined(separator: " ")
            app.toast([saved, note].compactMap { $0 }.joined(separator: " "))
        } else if shown {
            redraw()
        } else {
            updateBox()
        }
        app.basemap.refreshAttribution()
        removeOldAreas()
    }

    // Helpers ------------------------------------------------------------------------------

    /// A white arrow with a dark edge, pointing up (north); the layer turns it.
    private static func arrowBitmap() -> UIImage {
        let s: CGFloat = 30
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: s, height: s))
        return renderer.image { context in
            let path = UIBezierPath()
            path.move(to: CGPoint(x: s * 0.5, y: s * 0.06))
            path.addLine(to: CGPoint(x: s * 0.82, y: s * 0.46))
            path.addLine(to: CGPoint(x: s * 0.6, y: s * 0.46))
            path.addLine(to: CGPoint(x: s * 0.6, y: s * 0.94))
            path.addLine(to: CGPoint(x: s * 0.4, y: s * 0.94))
            path.addLine(to: CGPoint(x: s * 0.4, y: s * 0.46))
            path.addLine(to: CGPoint(x: s * 0.18, y: s * 0.46))
            path.close()
            path.lineWidth = s * 0.1
            path.lineJoinStyle = .round
            UIColor(hex: "#E60B1622").setStroke()
            path.stroke()
            UIColor.white.setFill()
            path.fill()
        }
    }
}
