import CoreLocation
import Foundation
import MapLibre
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/**
 Owns the overlay stack: imported MBTiles, GeoTIFF depth surveys and online XYZ
 tile layers (Android OverlayLayerController, LayerImportCoordinator,
 DepthQueryController, the offline download controller and MainActivity's
 layer handling).

 The stack runs bottom to top internally; `layers` is published top first, as
 listed. Every overlay goes below the map's overlay anchor, so appending puts a
 new layer above the other overlays but under waypoints, routes and measurements.
 */
@MainActor
final class LayersFeature: ObservableObject {
    unowned let app: AppModel

    /// Top of the stack first, as listed in the drawer.
    @Published private(set) var layers: [OverlayLayer] = []
    /// Saved layers that couldn't be loaded this session (kept, tried again next launch).
    @Published private(set) var unavailable: [OverlayLayer] = []
    /// Text of the import progress sheet while one is up.
    @Published private(set) var progressTitle = ""
    @Published private(set) var progressText = ""
    /// The running offline download, if any.
    @Published private(set) var download: DownloadState?

    struct DownloadState {
        var sourceName: String
        var text: String
        var fraction: Double
    }

    let dataSources = DataSourceStore()

    private var stack: [OverlayLayer] = []
    private var unloaded: [OverlayLayer] = []
    private var databases: [String: LayersMbtilesDatabase] = [:]
    private var mbtilesMeta: [String: LayersMbtilesMetadata] = [:]
    private var depthSources: [String: DepthLayerSource] = [:]
    private var grids: [String: DepthGrid] = [:]
    private var images: [String: UIImage] = [:]
    private var layerBounds: [String: GeoBounds] = [:]
    private var recolourTasks: [String: Task<Void, Never>] = [:]
    private var depthTask: Task<Void, Never>?
    private let store = OverlayLayerStore()
    private var restored = false
    /// While the first restore runs, restored layers go in here, under any added meanwhile.
    private var restoreInsertIndex = 0
    private var progressSheetId: UUID?
    private var downloadSheetId: UUID?
    private var downloadRunner: LayersTileDownloadRunner?
    private var downloadTask: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private static let tag = "SeaBathyLayers"
    private static let contourColour = "#00C8B4"
    private static let contourSourceLayer = "contours"
    private static let showAreaPadding: CGFloat = 32

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        dataSources.load()
        app.map.onStyleLoaded { [weak self] _ in self?.attachAll() }
        app.map.onCameraIdle { [weak self] in self?.refreshCrosshairDepth() }
        restore()
        reportInterruptedDownloads()
    }

    // MARK: - Public queries

    func depthSource(for layerId: String) -> DepthLayerSource? { depthSources[layerId] }

    /// The visible depth layers' sources, top of the stack first.
    func visibleDepthSources() -> [DepthLayerSource] {
        stack.reversed().filter { $0.visible }.compactMap { depthSources[$0.id] }
    }

    /// Survey depth (negative-down metres) at a point from the visible depth layers, top first.
    func sampleDepth(lat: Double, lng: Double) -> Double? {
        for source in visibleDepthSources() {
            if let value = source.sampleMetres(lat: lat, lng: lng), value.isFinite { return value }
        }
        return nil
    }

    /// Credits for every visible overlay.
    func visibleAttributions() -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for layer in stack where layer.visible {
            var text = layer.attribution.trimmingCharacters(in: .whitespacesAndNewlines)
            // A pack loaded without a credit uses the one written in the file.
            if text.isEmpty { text = mbtilesMeta[layer.id]?.attribution ?? "" }
            if text.isEmpty { continue }
            // Esri's terms ask for "Powered by Esri".
            if layer.source.range(of: "arcgis", options: .caseInsensitive) != nil && !text.contains("Powered by Esri") {
                text += " · Powered by Esri"
            }
            if seen.insert(text).inserted { out.append(text) }
        }
        return out
    }

    /// Raster layers that can cover the 3D terrain, top first: offline packs read from the file, and XYZ links.
    func terrainDrapes() -> [TerrainDrape] {
        stack.reversed().compactMap { layer -> TerrainDrape? in
            switch layer.kind {
            case .xyz:
                return TerrainDrape(id: layer.id, label: layer.name, minZoom: 0,
                                    maxZoom: min(max(layer.maxZoom, 1), OverlayLayer.defaultMaxZoom),
                                    urlTemplate: layer.source, attribution: layer.attribution)
            case .mbtiles:
                guard let db = databases[layer.id] else { return nil }
                let meta = mbtilesMeta[layer.id] ?? db.metadata()
                if meta.isVector { return nil }
                return TerrainDrape(id: layer.id, label: layer.name, minZoom: meta.minZoom, maxZoom: meta.maxZoom,
                                    attribution: layer.attribution.isEmpty ? meta.attribution : layer.attribution,
                                    bounds: meta.bounds ?? layerBounds[layer.id],
                                    readTile: { z, x, y in db.readTile(z: z, x: x, y: y) })
            case .geotiff:
                return nil
            }
        }
    }

    func boundsOf(_ id: String) -> GeoBounds? { layerBounds[id] ?? depthSources[id]?.bounds }

    /// The least zoom an MBTiles pack draws at.
    func minZoomOf(_ id: String) -> Double? { mbtilesMeta[id].map { Double($0.minZoom) } }

    func hasDepth(_ id: String) -> Bool { depthSources[id] != nil }

    // MARK: - Depth at the crosshair

    func refreshCrosshairDepth() {
        depthTask?.cancel()
        guard depthEnabledForCurrentTool() else {
            app.crosshairDepthText = ""
            return
        }
        depthTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 60_000_000)
            guard let self, !Task.isCancelled else { return }
            let c = self.app.map.centre
            self.app.crosshairDepthText = self.depthText(lat: c.latitude, lng: c.longitude)
        }
    }

    private func depthEnabledForCurrentTool() -> Bool {
        let tool = app.toolMode
        switch app.settings.visual.depthDisplayMode {
        case DepthDisplayMode.off: return false
        case DepthDisplayMode.normal: return tool == .none
        case DepthDisplayMode.waypoint: return tool == .waypoint
        default: return tool == .none || tool == .waypoint
        }
    }

    /// A survey gives an exact sounding; otherwise the nearest contour only bounds the depth.
    private func depthText(lat: Double, lng: Double) -> String {
        if let metres = sampleDepth(lat: lat, lng: lng) { return formatDepth(metres) }
        if let contour = nearestContourMetres(lat: lat, lng: lng) { return ">\(contour)m" }
        return ""
    }

    /// Survey metres are negative down; above zero the bottom dries.
    private func formatDepth(_ surveyMetres: Double) -> String {
        surveyMetres > 0 ? "+" + app.format.depth(surveyMetres) + " dries" : app.format.depth(-surveyMetres)
    }

    private func nearestContourMetres(lat: Double, lng: Double) -> Int? {
        guard let style = app.map.style else { return nil }
        let ids = Set(stack.filter { $0.visible && $0.kind == .mbtiles }
            .map { MapLayerIds.layer(for: $0.id) }
            .filter { style.layer(withIdentifier: $0) is MLNLineStyleLayer })
        if ids.isEmpty { return nil }
        let point = app.map.point(for: CLLocationCoordinate2D(latitude: lat, longitude: lng))
        for radius in [15.0, 30.0, 55.0, 110.0] as [CGFloat] {
            var shallowest: Int?
            for feature in app.map.features(at: point, radius: radius, layers: ids) {
                var raw: Int?
                for key in ["Contour", "contour", "depth", "DEPTH"] {
                    if let n = feature.attribute(forKey: key) as? NSNumber { raw = n.intValue; break }
                }
                guard let value = raw else { continue }
                let metres = abs(value)
                if metres > 0 && (shallowest == nil || metres < shallowest!) { shallowest = metres }
            }
            if let shallowest { return shallowest }
        }
        return nil
    }

    // MARK: - Restore

    private struct Prepared {
        var layer: OverlayLayer
        var database: LayersMbtilesDatabase? = nil
        var meta: LayersMbtilesMetadata? = nil
        var source: DepthLayerSource? = nil
        var grid: DepthGrid? = nil
        var image: UIImage? = nil
        var bounds: GeoBounds? = nil
    }

    private func restore() {
        let saved = store.load()
        DiagnosticLog.log(Self.tag, "restore: \(saved.count) saved layer(s)")
        if saved.isEmpty {
            restored = true
            return
        }
        let maxDimension = app.settings.rasterQuality.maxDimension
        Task { [weak self] in
            var missing: [OverlayLayer] = []
            var failed: [OverlayLayer] = []
            for record in saved {
                let prepared = await LayersFeature.prepare(record, maxDimension: maxDimension)
                guard let self else {
                    prepared?.database?.close()
                    return
                }
                if let prepared {
                    self.commitRestored(prepared)
                } else if LayersFeature.isConfirmedMissing(record) {
                    missing.append(record)
                } else {
                    failed.append(record)
                    self.unloaded.append(record)
                }
            }
            guard let self else { return }
            self.restored = true
            self.persist()
            self.stackChanged()
            self.sweepOrphans()
            var notes: [String] = []
            if !missing.isEmpty {
                notes.append("Removed from your layers because the file is no longer on this device: "
                             + missing.map { $0.name }.joined(separator: ", "))
            }
            if !failed.isEmpty {
                notes.append("Could not reload " + failed.map { $0.name }.joined(separator: ", "))
            }
            if !notes.isEmpty { self.alertSoon("Map layers", notes.joined(separator: "\n\n")) }
            DiagnosticLog.log(Self.tag, "restore complete: \(self.stack.count)/\(saved.count) loaded")
        }
    }

    /// Loads a saved layer off the main thread without touching shared state.
    nonisolated private static func prepare(_ record: OverlayLayer, maxDimension: Int) async -> Prepared? {
        await Task.detached(priority: .userInitiated) { () -> Prepared? in
            switch record.kind {
            case .xyz:
                return Prepared(layer: record)
            case .mbtiles:
                let url = AppPaths.resolve(record.source)
                guard FileManager.default.fileExists(atPath: url.path) else { return nil }
                do {
                    let db = try LayersMbtilesDatabase(url: url)
                    let meta = db.metadata()
                    return Prepared(layer: record, database: db, meta: meta,
                                    bounds: meta.bounds ?? db.tileBounds(zoom: meta.maxZoom))
                } catch {
                    DiagnosticLog.log("SeaBathyLayers", "restore MBTiles \(record.name) FAILED: \(error)")
                    return nil
                }
            case .geotiff:
                let url = AppPaths.resolve(record.source)
                guard FileManager.default.fileExists(atPath: url.path) else { return nil }
                do {
                    let result = try GeoTiffImport.read(url: url, maxDimension: maxDimension)
                    let grid = result.source.previewGrid()
                    var layer = record
                    if layer.ramp == nil { layer.ramp = DepthRampFit.autoFit(result.source) }
                    let image = DepthImageRenderer.image(for: grid, ramp: layer.ramp ?? DepthRampSettings())
                    return Prepared(layer: layer, source: result.source, grid: grid, image: image, bounds: grid.bounds)
                } catch {
                    DiagnosticLog.log("SeaBathyLayers", "restore GeoTIFF \(record.name) FAILED: \(error)")
                    return nil
                }
            }
        }.value
    }

    /// Only a file that is positively gone drops its layer.
    nonisolated private static func isConfirmedMissing(_ record: OverlayLayer) -> Bool {
        if record.kind == .xyz { return false }
        return !FileManager.default.fileExists(atPath: AppPaths.resolve(record.source).path)
    }

    private func register(_ p: Prepared) {
        let id = p.layer.id
        if let db = p.database { databases[id] = db }
        if let meta = p.meta { mbtilesMeta[id] = meta }
        if let source = p.source { depthSources[id] = source }
        if let grid = p.grid { grids[id] = grid }
        if let image = p.image { images[id] = image }
        if let bounds = p.bounds { layerBounds[id] = bounds }
    }

    private func commitRestored(_ p: Prepared) {
        register(p)
        if restoreInsertIndex >= stack.count {
            stack.append(p.layer)
            restoreInsertIndex = stack.count
            attach(p.layer)
        } else {
            stack.insert(p.layer, at: restoreInsertIndex)
            restoreInsertIndex += 1
            reattachStack()
        }
        publish()
        app.basemap.refreshAttribution()
    }

    /// Deletes app copies in the Layers and seabed folders that no saved layer uses.
    private func sweepOrphans() {
        let referenced = Set((unloaded + stack).map { AppPaths.resolve($0.source).standardizedFileURL.path })
        let folders = [Self.layersFolder, Self.seabedFolder]
        let cutoff = Date().addingTimeInterval(-60)
        Task.detached(priority: .background) {
            let fm = FileManager.default
            for folder in folders {
                guard let files = try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey]) else { continue }
                for file in files {
                    let path = file.standardizedFileURL.path
                    let base = path.replacingOccurrences(of: "-journal", with: "")
                        .replacingOccurrences(of: "-wal", with: "").replacingOccurrences(of: "-shm", with: "")
                    if referenced.contains(base) { continue }
                    let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
                    if date < cutoff, (try? fm.removeItem(at: file)) != nil {
                        DiagnosticLog.log("SeaBathyLayers", "orphan sweep: deleted \(file.lastPathComponent)")
                    }
                }
            }
        }
    }

    // MARK: - Map attachment

    private func attachAll() {
        for layer in stack { attach(layer) }
    }

    private func reattachStack() {
        for layer in stack { detach(layer.id) }
        for layer in stack { attach(layer) }
    }

    private func detach(_ id: String) {
        app.map.removeLayer(MapLayerIds.layer(for: id))
        app.map.removeSource(MapLayerIds.source(for: id))
    }

    private func paintOpacity(_ layer: OverlayLayer) -> Double { layer.visible ? layer.sanitisedOpacity : 0 }

    private func attach(_ layer: OverlayLayer) {
        guard let style = app.map.style, app.map.styleReady else { return }
        detach(layer.id)
        let sourceId = MapLayerIds.source(for: layer.id)
        let layerId = MapLayerIds.layer(for: layer.id)
        switch layer.kind {
        case .mbtiles:
            guard let db = databases[layer.id] else {
                DiagnosticLog.log(Self.tag, "attachMbtiles: no open database for \(layer.name)")
                return
            }
            let meta = mbtilesMeta[layer.id] ?? db.metadata()
            mbtilesMeta[layer.id] = meta
            if layerBounds[layer.id] == nil, let b = meta.bounds ?? db.tileBounds(zoom: meta.maxZoom) { layerBounds[layer.id] = b }
            guard let url = Self.mbtilesURL(db.url) else {
                app.toast("Could not display \(layer.name)")
                return
            }
            if meta.isVector {
                let source = MLNVectorTileSource(identifier: sourceId, configurationURL: url)
                style.addSource(source)
                let line = MLNLineStyleLayer(identifier: layerId, source: source)
                line.sourceLayerIdentifier = Self.contourSourceLayer
                line.lineColor = NSExpression(forConstantValue: UIColor(hex: Self.contourColour))
                line.lineWidth = NSExpression(forConstantValue: 1)
                line.lineOpacity = NSExpression(forConstantValue: paintOpacity(layer))
                app.map.add(line, placement: .overlays)
            } else {
                let source = MLNRasterTileSource(identifier: sourceId, configurationURL: url, tileSize: 256)
                style.addSource(source)
                let raster = MLNRasterStyleLayer(identifier: layerId, source: source)
                raster.rasterOpacity = NSExpression(forConstantValue: paintOpacity(layer))
                app.map.add(raster, placement: .overlays)
            }
        case .geotiff:
            guard let b = layerBounds[layer.id], let image = images[layer.id] else {
                DiagnosticLog.log(Self.tag, "attachGeoTiff: no image for \(layer.name)")
                return
            }
            let quad = MLNCoordinateQuad(
                topLeft: CLLocationCoordinate2D(latitude: b.north, longitude: b.west),
                bottomLeft: CLLocationCoordinate2D(latitude: b.south, longitude: b.west),
                bottomRight: CLLocationCoordinate2D(latitude: b.south, longitude: b.east),
                topRight: CLLocationCoordinate2D(latitude: b.north, longitude: b.east))
            let source = MLNImageSource(identifier: sourceId, coordinateQuad: quad, image: image)
            style.addSource(source)
            let raster = MLNRasterStyleLayer(identifier: layerId, source: source)
            raster.rasterOpacity = NSExpression(forConstantValue: paintOpacity(layer))
            // Depth cells are values, not photographs: keep their edges crisp.
            raster.rasterResamplingMode = NSExpression(forConstantValue: "nearest")
            raster.rasterFadeDuration = NSExpression(forConstantValue: 0)
            app.map.add(raster, placement: .overlays)
        case .xyz:
            let source = MLNRasterTileSource(identifier: sourceId, tileURLTemplates: [layer.source], options: [
                .minimumZoomLevel: NSNumber(value: 0),
                .maximumZoomLevel: NSNumber(value: layer.sanitisedMaxZoom),
                .tileSize: NSNumber(value: 256),
            ])
            style.addSource(source)
            let raster = MLNRasterStyleLayer(identifier: layerId, source: source)
            raster.rasterOpacity = NSExpression(forConstantValue: paintOpacity(layer))
            app.map.add(raster, placement: .overlays)
        }
    }

    /// MapLibre opens MBTiles through `mbtiles:///absolute/path`.
    private static func mbtilesURL(_ file: URL) -> URL? {
        let path = file.standardizedFileURL.path
        if let url = URL(string: "mbtiles://" + path) { return url }
        guard let encoded = path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) else { return nil }
        return URL(string: "mbtiles://" + encoded)
    }

    private func applyPaint(_ layer: OverlayLayer) {
        guard let mapLayer = app.map.style?.layer(withIdentifier: MapLayerIds.layer(for: layer.id)) else { return }
        let opacity = NSExpression(forConstantValue: paintOpacity(layer))
        if let raster = mapLayer as? MLNRasterStyleLayer {
            raster.rasterOpacity = opacity
        } else if let line = mapLayer as? MLNLineStyleLayer {
            line.lineOpacity = opacity
        }
    }

    // MARK: - Stack changes

    private func publish() {
        layers = Array(stack.reversed())
        unavailable = unloaded
    }

    /// Saved only once the first restore has finished: before that the stack is partial.
    private func persist() {
        if restored { store.save(unloaded + stack) }
    }

    private func stackChanged() {
        publish()
        app.basemap.refreshAttribution()
        refreshCrosshairDepth()
        // The route's least depths depend on which surveys are shown.
        app.navigation?.layersChanged()
    }

    /// Adds at the top, or with `belowDepthLayers` just under the lowest depth survey
    /// (opaque imagery laid over a survey hid it completely).
    private func appendLayer(_ layer: OverlayLayer, belowDepthLayers: Bool = false) {
        if belowDepthLayers, let lowest = stack.firstIndex(where: { depthSources[$0.id] != nil }) {
            stack.insert(layer, at: lowest)
            if lowest < restoreInsertIndex { restoreInsertIndex += 1 }
            reattachStack()
        } else {
            stack.append(layer)
            attach(layer)
        }
        persist()
        stackChanged()
    }

    private func update(_ id: String, _ change: (inout OverlayLayer) -> Void) -> OverlayLayer? {
        guard let i = stack.firstIndex(where: { $0.id == id }) else { return nil }
        change(&stack[i])
        return stack[i]
    }

    func setVisible(_ id: String, _ visible: Bool) {
        guard let layer = update(id, { $0.visible = visible }) else { return }
        DiagnosticLog.log(Self.tag, "setVisible: \(layer.name) visible=\(visible)")
        applyPaint(layer)
        persist()
        stackChanged()
    }

    func setOpacity(_ id: String, _ opacity: Double, persistNow: Bool) {
        guard let layer = update(id, { $0.opacity = min(max(opacity, 0), 1) }) else { return }
        applyPaint(layer)
        publish()
        if persistNow { persist() }
    }

    func rename(_ id: String, _ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, update(id, { $0.name = trimmed }) != nil else { return }
        persist()
        stackChanged()
    }

    /// Moves a layer up (+1) or down (-1) the draw order.
    func move(_ id: String, _ delta: Int) {
        guard let current = stack.firstIndex(where: { $0.id == id }) else { return }
        let target = min(max(current + delta, 0), stack.count - 1)
        if target == current { return }
        let layer = stack.remove(at: current)
        stack.insert(layer, at: target)
        reattachStack()
        persist()
        stackChanged()
    }

    func sendToBottom(_ id: String) {
        guard let current = stack.firstIndex(where: { $0.id == id }), current > 0 else { return }
        let layer = stack.remove(at: current)
        stack.insert(layer, at: 0)
        reattachStack()
        persist()
        stackChanged()
    }

    /// Removes a layer. Only the app's own copy of its file is deleted, never the original.
    func remove(_ id: String) {
        let layer: OverlayLayer
        if let i = stack.firstIndex(where: { $0.id == id }) {
            layer = stack.remove(at: i)
            if i < restoreInsertIndex { restoreInsertIndex -= 1 }
            detach(id)
        } else if let i = unloaded.firstIndex(where: { $0.id == id }) {
            layer = unloaded.remove(at: i)
        } else {
            return
        }
        databases.removeValue(forKey: id)?.close()
        mbtilesMeta.removeValue(forKey: id)
        depthSources.removeValue(forKey: id)
        grids.removeValue(forKey: id)
        images.removeValue(forKey: id)
        layerBounds.removeValue(forKey: id)
        recolourTasks.removeValue(forKey: id)?.cancel()
        deleteAppCopy(of: layer)
        persist()
        stackChanged()
    }

    private static var layersFolder: URL { AppPaths.documentsFolder("Layers") }
    private static var seabedFolder: URL { AppPaths.supportFolder("seabed") }

    private func deleteAppCopy(of layer: OverlayLayer) {
        guard layer.kind != .xyz else { return }
        let file = AppPaths.resolve(layer.source).standardizedFileURL
        let stillUsed = (stack + unloaded).contains { AppPaths.resolve($0.source).standardizedFileURL.path == file.path }
        let parent = file.deletingLastPathComponent().standardizedFileURL.path
        let owned = [Self.layersFolder, Self.seabedFolder].contains { $0.standardizedFileURL.path == parent }
        guard owned, !stillUsed else { return }
        Task.detached(priority: .utility) {
            let fm = FileManager.default
            for suffix in ["", "-journal", "-wal", "-shm"] { try? fm.removeItem(atPath: file.path + suffix) }
            DiagnosticLog.log("SeaBathyLayers", "remove \(layer.kind.rawValue): deleted the app's copy \(file.lastPathComponent)")
        }
    }

    // MARK: - Depth colours

    func setRamp(_ id: String, _ ramp: DepthRampSettings) {
        let normalised = ramp.normalised()
        guard let layer = update(id, { $0.ramp = normalised }) else { return }
        persist()
        stackChanged()
        guard let grid = grids[id] else { return }
        recolourTasks[id]?.cancel()
        let name = layer.name
        recolourTasks[id] = Task { [weak self] in
            // A burst of slider changes renders only the last one.
            try? await Task.sleep(nanoseconds: 40_000_000)
            if Task.isCancelled { return }
            let image = await Task.detached(priority: .userInitiated) { DepthImageRenderer.image(for: grid, ramp: normalised) }.value
            guard let self, !Task.isCancelled else { return }
            guard let image else {
                self.app.toast("Could not apply the new colours to \(name)")
                return
            }
            self.images[id] = image
            if let source = self.app.map.style?.source(withIdentifier: MapLayerIds.source(for: id)) as? MLNImageSource {
                source.image = image
            } else if let current = self.stack.first(where: { $0.id == id }) {
                self.attach(current)
            }
        }
    }

    func showDepthColours(layerId: String) {
        guard let layer = stack.first(where: { $0.id == layerId }) else { return }
        guard let source = depthSources[layerId] else {
            app.toast("\(layer.name) has no depth data to recolour")
            return
        }
        // The colours sheet leaves the map visible; the layer list would cover it.
        app.sidePanelOpen = false
        let ramp = layer.ramp ?? DepthRampFit.autoFit(source)
        let units = app.units
        presentAfterDismiss(detents: [.medium, .large]) { [weak self] in
            DepthRampSheet(layerName: layer.name, ramp: ramp, source: source, units: units) { newRamp in
                self?.setRamp(layerId, newRamp)
            }
        }
    }

    func chooseDepthLayerForColours() {
        let depthLayers = layers.filter { depthSources[$0.id] != nil }
        if depthLayers.isEmpty {
            app.toast("Load a GeoTIFF depth layer first")
        } else if depthLayers.count == 1 {
            showDepthColours(layerId: depthLayers[0].id)
        } else {
            presentAfterDismiss(detents: [.medium, .large]) { [weak self] in
                LayersChoiceSheet(title: "Choose a depth layer", items: depthLayers.map { ($0.id, $0.name) }) { id in
                    self?.showDepthColours(layerId: id)
                }
            }
        }
    }

    func open3d(_ layer: OverlayLayer) {
        guard depthSources[layer.id] != nil else {
            app.toast("\(layer.name) has no depth data to show in 3D")
            return
        }
        app.sidePanelOpen = false
        app.terrain.open(layerId: layer.id)
    }

    /// Closes the drawer and fits the layer on screen (tapping its name).
    func showLayer(_ layer: OverlayLayer) {
        guard let b = boundsOf(layer.id) else {
            app.toast("\(layer.name) has no known area to show")
            return
        }
        app.sidePanelOpen = false
        _ = app.navigation.gps.releaseFollow()
        // A pack drawn only from zoom 10 in shows nothing when its whole area is fitted: go to where it shows.
        if let minZoom = minZoomOf(layer.id), let view = app.map.mapView,
           Self.fitZoom(b, size: view.bounds.size, padding: Self.showAreaPadding) < minZoom {
            app.map.fly(to: b.centerLat, b.centerLng, zoom: minZoom)
        } else {
            showArea(b)
        }
    }

    private func showArea(_ b: GeoBounds) {
        app.map.fit(b, padding: Self.showAreaPadding)
    }

    /// The zoom that fits `b` in a view of `size` (MapLibre iOS: 512 points per tile).
    static func fitZoom(_ b: GeoBounds, size: CGSize, padding: CGFloat) -> Double {
        let w = Double(max(size.width - 2 * padding, 1)), h = Double(max(size.height - 2 * padding, 1))
        let fx = max((b.east - b.west) / 360, 1e-9)
        let north = LayersTiles.forward(lat: b.north, lng: 0).y, south = LayersTiles.forward(lat: b.south, lng: 0).y
        let fy = max((north - south) / (2 * Double.pi * 6378137.0), 1e-9)
        return min(log2(w / (512 * fx)), log2(h / (512 * fy)))
    }

    // MARK: - Sheets

    /// Presents a sheet, after any open one has gone (SwiftUI drops a sheet swapped mid-animation).
    func presentAfterDismiss<V: View>(detents: Set<PresentationDetent> = [.large], @ViewBuilder _ content: @escaping () -> V) {
        if app.sheet != nil {
            app.dismissSheet()
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 450_000_000)
                self?.app.present(detents: detents) { content() }
            }
        } else {
            app.present(detents: detents) { content() }
        }
    }

    func showOnlineLayers() {
        presentAfterDismiss { LayersXyzSheet() }
    }

    func showCustomBasemap() {
        app.sidePanelOpen = false
        presentAfterDismiss { LayersCustomBasemapSheet() }
    }

    func showOfflineMaps() {
        presentAfterDismiss { LayersDataSourceSheet() }
    }

    func showRename(_ layer: OverlayLayer) {
        presentAfterDismiss(detents: [.medium]) { LayersRenameSheet(layerId: layer.id, name: layer.name) }
    }

    private func showProgress(_ title: String, _ text: String) {
        progressTitle = title
        progressText = text
        app.present(detents: [.height(160)]) { LayersProgressSheet() }
        progressSheetId = app.sheet?.id
    }

    private func hideProgress() {
        if let id = progressSheetId, app.sheet?.id == id { app.dismissSheet() }
        progressSheetId = nil
    }

    /// Shows an alert once any sheet has gone: one raised while a sheet closes is never shown.
    private func alertSoon(_ title: String, _ message: String, actions: [AppModel.AlertAction] = [], closeSheet: Bool = false) {
        if closeSheet && app.sheet != nil { app.dismissSheet() }
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            self?.app.alert(title, message, actions: actions)
        }
    }

    private func importFailed(_ error: Error, fallback: String) {
        DiagnosticLog.log(Self.tag, "import FAILED: \(error)")
        alertSoon("Couldn't load the file", LayersErrorText.describe(error, fallback: fallback))
    }

    // MARK: - XYZ layers and the custom basemap

    func addXyz(name: String, urlTemplate: String, attribution: String, opacity: Double = 1, maxZoom: Int = OverlayLayer.defaultMaxZoom) {
        // Online imagery is opaque: under any depth survey.
        appendLayer(OverlayLayer(id: OverlayLayerStore.newLayerId(), kind: .xyz, name: name, source: urlTemplate,
                                 opacity: min(max(opacity, 0), 1), attribution: attribution, maxZoom: maxZoom),
                    belowDepthLayers: true)
    }

    func addXyzPreset(_ preset: XyzTilePreset) {
        addXyz(name: preset.label, urlTemplate: preset.urlTemplate, attribution: preset.attribution,
               opacity: preset.category == .satellite ? XyzTilePresets.satelliteDefaultOpacity : 1, maxZoom: preset.maxZoom)
        app.toast("Added \(preset.label)")
    }

    func useAsBasemap(url: String, attribution: String) {
        app.basemap.saveCustomBasemap(url: url, attribution: attribution)
        app.basemap.select(BasemapType.custom)
    }

    // MARK: - Imports

    func importGeoTiff() {
        app.pickFiles([.tiff]) { [weak self] urls in
            guard let url = urls.first else { return }
            self?.importGeoTiffFile(url)
        }
    }

    func importMbtiles() {
        var types: [UTType] = []
        if let mbtiles = UTType("com.seabathy.mbtiles") { types.append(mbtiles) }
        if let byExtension = UTType(filenameExtension: "mbtiles"), !types.contains(byExtension) { types.append(byExtension) }
        types.append(.database)
        app.pickFiles(types) { [weak self] urls in
            guard let url = urls.first else { return }
            self?.importMbtilesFile(url)
        }
    }

    /// A file opened from Files or shared to the app.
    func importFile(_ url: URL) {
        switch url.pathExtension.lowercased() {
        case "tif", "tiff": importGeoTiffFile(url)
        case "mbtiles": importMbtilesFile(url)
        default: app.alert("Couldn't load the file", "SeaBathy opens GeoTIFF (.tif) depth surveys and MBTiles (.mbtiles) maps.")
        }
    }

    private enum Adopted {
        case file(URL, created: Bool)
        case alreadyLoaded(OverlayLayer)
    }

    /// Moves a picked file into Documents/Layers. A same-named file of the same size is the same file:
    /// shown again if it is loaded, reused if not.
    private func adopt(_ url: URL, ext: String, fallbackPrefix: String) throws -> Adopted {
        let fm = FileManager.default
        let dir = Self.layersFolder
        if url.deletingLastPathComponent().standardizedFileURL.path == dir.standardizedFileURL.path {
            if let existing = layerUsing(url) { return .alreadyLoaded(existing) }
            return .file(url, created: false)
        }
        let display = url.deletingPathExtension().lastPathComponent
        let base = LayersFileNames.safeBaseName(display, fallback: LayersFileNames.timestampedFallback(fallbackPrefix))
        let sameName = dir.appendingPathComponent("\(base).\(ext)")
        let size = { (u: URL) -> Int64? in ((try? fm.attributesOfItem(atPath: u.path))?[.size] as? NSNumber)?.int64Value }
        if fm.fileExists(atPath: sameName.path), let a = size(sameName), let b = size(url), a == b {
            try? fm.removeItem(at: url)
            if let existing = layerUsing(sameName) { return .alreadyLoaded(existing) }
            return .file(sameName, created: false)
        }
        let target = LayersFileNames.uniqueFile(in: dir, base: base, ext: ext)
        do {
            try fm.moveItem(at: url, to: target)
        } catch {
            try fm.copyItem(at: url, to: target)
        }
        // A moved file keeps its old date; the orphan sweep only touches old files.
        try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
        return .file(target, created: true)
    }

    private func layerUsing(_ file: URL) -> OverlayLayer? {
        let path = file.standardizedFileURL.path
        return stack.first { $0.kind != .xyz && AppPaths.resolve($0.source).standardizedFileURL.path == path }
    }

    private func showExisting(_ layer: OverlayLayer) {
        if !layer.visible { setVisible(layer.id, true) }
        if let b = boundsOf(layer.id) { showArea(b) }
        app.toast("\(layer.name) is already loaded")
    }

    func importGeoTiffFile(_ picked: URL) {
        app.closePanels()
        let display = picked.deletingPathExtension().lastPathComponent
        let adopted: Adopted
        do {
            adopted = try adopt(picked, ext: "tif", fallbackPrefix: "survey")
        } catch {
            importFailed(error, fallback: "Could not import this TIFF file")
            return
        }
        switch adopted {
        case .alreadyLoaded(let layer):
            showExisting(layer)
        case .file(let file, let created):
            openGeoTiff(file, name: display.isEmpty ? "GeoTIFF" : display, attribution: "",
                        title: "Importing", text: "Reading GeoTIFF… this can take a moment",
                        deleteOnFailure: created, fallback: "Could not import this TIFF file")
        }
    }

    private struct GeoTiffOpened {
        let source: DepthLayerSource
        let grid: DepthGrid
        let ramp: DepthRampSettings
        let image: UIImage?
        let projection: String
    }

    private func openGeoTiff(_ file: URL, name: String, attribution: String, title: String, text: String,
                             deleteOnFailure: Bool, fallback: String) {
        showProgress(title, text)
        let maxDimension = app.settings.rasterQuality.maxDimension
        let started = Date()
        Task { [weak self] in
            let outcome: Swift.Result<GeoTiffOpened, Error> = await Task.detached(priority: .userInitiated) {
                do {
                    let result = try GeoTiffImport.read(url: file, maxDimension: maxDimension)
                    let grid = result.source.previewGrid()
                    let ramp = DepthRampFit.autoFit(result.source)
                    let image = DepthImageRenderer.image(for: grid, ramp: ramp)
                    return .success(GeoTiffOpened(source: result.source, grid: grid, ramp: ramp, image: image,
                                                  projection: result.projectionLabel))
                } catch {
                    return .failure(error)
                }
            }.value
            guard let self else { return }
            self.hideProgress()
            switch outcome {
            case .success(let opened):
                DiagnosticLog.log(Self.tag, "GeoTIFF loaded: \(name) in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
                let id = OverlayLayerStore.newLayerId()
                self.depthSources[id] = opened.source
                self.grids[id] = opened.grid
                if let image = opened.image { self.images[id] = image }
                self.layerBounds[id] = opened.grid.bounds
                self.appendLayer(OverlayLayer(id: id, kind: .geotiff, name: name, source: AppPaths.storable(file),
                                              attribution: attribution, ramp: opened.ramp))
                self.showArea(opened.grid.bounds)
                self.app.toast("Loaded \(name) (\(opened.projection)) — depths \(Int(opened.source.shallowestMetres)) to \(Int(opened.source.deepestMetres)) m")
            case .failure(let error):
                if deleteOnFailure { try? FileManager.default.removeItem(at: file) }
                self.importFailed(error, fallback: fallback)
            }
        }
    }

    func importMbtilesFile(_ picked: URL) {
        app.closePanels()
        let adopted: Adopted
        do {
            adopted = try adopt(picked, ext: "mbtiles", fallbackPrefix: "layer")
        } catch {
            importFailed(error, fallback: "Could not import this MBTiles file")
            return
        }
        switch adopted {
        case .alreadyLoaded(let layer):
            showExisting(layer)
        case .file(let file, let created):
            let display = file.deletingPathExtension().lastPathComponent
            openMbtiles(file, name: display, attribution: "", text: "Importing MBTiles…",
                        deleteOnFailure: created, fallback: "Could not import this MBTiles file")
        }
    }

    private func openMbtiles(_ file: URL, name: String, attribution: String, text: String,
                             deleteOnFailure: Bool, fallback: String) {
        showProgress("Importing", text)
        Task { [weak self] in
            let outcome: Swift.Result<(LayersMbtilesDatabase, LayersMbtilesMetadata, GeoBounds?), Error> =
                await Task.detached(priority: .userInitiated) {
                    do {
                        let db = try LayersMbtilesDatabase(url: file)
                        let meta = db.metadata()
                        return .success((db, meta, meta.bounds ?? db.tileBounds(zoom: meta.maxZoom)))
                    } catch {
                        return .failure(error)
                    }
                }.value
            guard let self else { return }
            self.hideProgress()
            switch outcome {
            case .success(let (db, meta, bounds)):
                let id = OverlayLayerStore.newLayerId()
                self.databases[id] = db
                self.mbtilesMeta[id] = meta
                if let bounds { self.layerBounds[id] = bounds }
                // Imagery and raster charts go under depth surveys; a vector chart's lines stay on top.
                self.appendLayer(OverlayLayer(id: id, kind: .mbtiles, name: name, source: AppPaths.storable(file),
                                              attribution: attribution),
                                 belowDepthLayers: !meta.isVector)
                self.app.toast("Loaded \(name)")
            case .failure(let error):
                if deleteOnFailure { try? FileManager.default.removeItem(at: file) }
                self.importFailed(error, fallback: fallback)
            }
        }
    }

    // MARK: - Seabed depths (GMRT)

    func getSeabedDepths() {
        let visible = app.map.visibleBounds
        let c = app.map.centre
        let area = SeabedDownload.areaFor(south: visible.south, west: visible.west, north: visible.north, east: visible.east,
                                          centreLat: c.latitude, centreLng: c.longitude)
        let note = area.trimmed
            ? " The most one download holds is about \(SeabedDownload.maxSpanKm) km across, so this is the middle of the screen."
            : ""
        app.confirm("Get seabed depths?",
                    "Downloads depths for the area on screen from GMRT, which blends ship surveys with the global "
                    + "GEBCO grid, and adds them as a layer that also works in 3D and offline. Detail is best where ships "
                    + "have surveyed; elsewhere it's much coarser. Up to about 55 MB.\(note) Not for navigation.",
                    confirm: "Download") { [weak self] in
            self?.app.sidePanelOpen = false
            self?.downloadSeabed(area)
        }
    }

    private func downloadSeabed(_ area: SeabedDownload.Area) {
        let target = Self.seabedFolder.appendingPathComponent("\(area.name).tif")
        if let existing = layerUsing(target) {
            showExisting(existing)
            return
        }
        let fm = FileManager.default
        let haveFile = (((try? fm.attributesOfItem(atPath: target.path))?[.size] as? NSNumber)?.int64Value ?? 0) > 0
        if haveFile {
            openGeoTiff(target, name: area.name, attribution: SeabedDownload.credit, title: "Seabed depths",
                        text: "Opening the depths…", deleteOnFailure: false, fallback: "Couldn't get the seabed depths")
            return
        }
        guard let url = URL(string: SeabedDownload.url(area)) else { return }
        DiagnosticLog.log(Self.tag, "seabed download: \(area.name)")
        showProgress("Seabed depths", "Asking for the depths… this can take a moment")
        Task { [weak self] in
            var shownMb: Int64 = 0
            do {
                let temp = try await LayersFileDownloader().download(url, userAgent: "SeaBathy (iOS seabed depths)") { bytes in
                    let mb = bytes / 1_000_000
                    if mb != shownMb {
                        shownMb = mb
                        Task { @MainActor [weak self] in self?.progressText = "Downloading the depths… \(mb) MB" }
                    }
                }
                defer { try? FileManager.default.removeItem(at: temp) }
                let head = (try? FileHandle(forReadingFrom: temp))
                let headData = head?.readData(ofLength: 4) ?? Data()
                try? head?.close()
                if !SeabedDownload.looksLikeTiff(headData) { throw LayersError("The depth service has no data for this area") }
                do {
                    try FileManager.default.moveItem(at: temp, to: target)
                } catch {
                    throw LayersError("Couldn't save the depths")
                }
                guard let self else { return }
                self.hideProgress()
                self.openGeoTiff(target, name: area.name, attribution: SeabedDownload.credit, title: "Seabed depths",
                                 text: "Opening the depths…", deleteOnFailure: true, fallback: "Couldn't get the seabed depths")
            } catch {
                guard let self else { return }
                self.hideProgress()
                DiagnosticLog.log(Self.tag, "seabed download FAILED: \(error)")
                self.alertSoon("Couldn't get the seabed depths", LayersErrorText.describe(error, fallback: "Couldn't get the seabed depths"))
            }
        }
    }

    // MARK: - Offline map downloads

    private static let downloadDefaultMinZoom = 10
    private static let downloadDefaultMaxZoom = 14
    /// More than this is almost always a mistake: refused.
    static let maxReasonableTileCount: Int64 = 200_000
    private static let suggestedTileCount: Int64 = 30_000

    func addDataSource(name: String, urlTemplate: String, attribution: String) {
        dataSources.add(name: name, urlTemplate: urlTemplate, attribution: attribution)
        objectWillChange.send()
    }

    func removeDataSource(_ source: DataSource) {
        dataSources.remove(id: source.id)
        objectWillChange.send()
    }

    /// Opens the download screen for the area on screen. Nothing is fetched until "Assess".
    func beginDownload(_ source: DataSource) {
        let bounds = app.map.visibleBounds
        guard app.map.mapView != nil, bounds.isValid else {
            alertSoon("Offline maps", "Could not read the current map view — try again once the map has finished loading", closeSheet: true)
            return
        }
        var deepest = max(Self.downloadDefaultMaxZoom, Self.downloadDefaultMinZoom)
        while deepest > Self.downloadDefaultMinZoom &&
                OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: Self.downloadDefaultMinZoom...deepest).tileCount > Self.suggestedTileCount {
            deepest -= 1
        }
        let minZoom = Self.downloadDefaultMinZoom
        presentAfterDismiss {
            LayersDownloadZoneSheet(source: source, bounds: bounds, defaultMinZoom: minZoom, defaultMaxZoom: deepest)
        }
    }

    /// Counts the tiles, then fetches a few to estimate the size from real tiles.
    /// Returns the message and whether it is a usable estimate.
    func assess(_ source: DataSource, bounds: GeoBounds, zoomRange: ClosedRange<Int>) async -> (ok: Bool, message: String) {
        let quick = OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: zoomRange)
        if quick.tileCount <= 0 { return (false, "No tiles in this area at these zooms") }
        if quick.tileCount > Self.maxReasonableTileCount {
            return (false, "That's \(OfflineDownloadEstimator.formatTileCount(quick.tileCount)) tiles — zoom in or lower the max zoom")
        }
        if !app.isOnline { return (false, "No internet: connect to check the size and download") }
        let client = LayersTileFetchClient()
        let sampler = TileSampler { url in await client.fetch(url) }
        let result = await sampler.sample(urlTemplate: source.urlTemplate, bounds: bounds, zoomRange: zoomRange)
        let estimate = result.averageBytes.map {
            OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: zoomRange, averageTileBytes: $0)
        } ?? quick
        let basis = result.sampledSizes.isEmpty
            ? "from a typical tile size: no sample tiles came back, so check the link if the download fails"
            : "from \(result.sampledSizes.count) sample tiles"
        return (true, "About \(OfflineDownloadEstimator.formatTileCount(quick.tileCount)) tiles, "
                + "~\(OfflineDownloadEstimator.formatBytes(estimate.estimatedBytes)) (\(basis)).")
    }

    func startDownload(_ source: DataSource, bounds: GeoBounds, zoomRange: ClosedRange<Int>) {
        if download != nil {
            alertSoon("Offline maps", "Another download is still running. Wait for it to finish, or cancel it.", closeSheet: true)
            return
        }
        if OfflineDownloadEstimator.estimateTiles(bounds, zoomRange: zoomRange).tileCount > Self.maxReasonableTileCount {
            alertSoon("Offline maps", "That's too big to download: zoom in or lower the max zoom, then assess again", closeSheet: true)
            return
        }
        if !app.isOnline {
            alertSoon("Offline maps", "No internet: connect, then download", closeSheet: true)
            return
        }
        let runner = LayersTileDownloadRunner(isOnline: { [weak self] in
            await MainActor.run { self?.app.isOnline ?? false }
        })
        downloadRunner = runner
        download = DownloadState(sourceName: source.name, text: "Starting…", fraction: 0)
        // iOS gives a backgrounded app a little time only; keep going for as long as it allows.
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Offline map download") { [weak self] in
            DiagnosticLog.log("OfflineTileDownload", "background time ran out")
            self?.endBackgroundTask()
        }
        showDownloadProgress()
        app.toast("Downloading \(source.name). Keep SeaBathy open: iOS pauses downloads soon after you leave the app.")

        downloadTask = Task { [weak self] in
            do {
                let result = try await runner.download(source: source, bounds: bounds, zoomRange: zoomRange, waiting: {
                    Task { @MainActor [weak self] in
                        self?.download?.text = "No internet — waiting for the connection to come back…"
                    }
                }, progress: { done, total, bytes in
                    Task { @MainActor [weak self] in
                        guard let self, self.download != nil else { return }
                        self.download?.fraction = total > 0 ? Double(done) / Double(total) : 0
                        self.download?.text = "\(done) / \(total) tiles — \(OfflineDownloadEstimator.formatBytes(bytes))"
                    }
                })
                self?.downloadFinished(source: source, result: result)
            } catch {
                self?.downloadFailed(source: source, error: error)
            }
        }
    }

    func cancelDownload() {
        DiagnosticLog.log("OfflineTileDownload", "download cancelled by user")
        downloadTask?.cancel()
        downloadRunner?.cancel()
    }

    func showDownloadProgress() {
        guard download != nil else { return }
        presentAfterDismiss(detents: [.height(220)]) { LayersDownloadProgressSheet() }
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000)
            self?.downloadSheetId = self?.app.sheet?.id
        }
    }

    private func closeDownloadSheet() {
        if let id = downloadSheetId, app.sheet?.id == id { app.dismissSheet() }
        downloadSheetId = nil
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func downloadFinished(source: DataSource, result: LayersTileDownloadRunner.Result) {
        download = nil
        downloadRunner = nil
        downloadTask = nil
        endBackgroundTask()
        closeDownloadSheet()
        let incomplete = result.failedTiles > 0
        let saved = "\(OfflineDownloadEstimator.formatBytes(result.totalBytes)) saved to OfflineDownloads/\(result.file.lastPathComponent)"
        let detail = incomplete
            ? "\(source.name): \(result.failedTiles) of \(result.totalTiles) tiles could not be downloaded and will be blank offline. \(saved)"
            : "\(source.name): \(saved)"
        let file = result.file
        alertSoon(incomplete ? "Download incomplete" : "Download complete", detail + "\n\nImport it as a layer now?", actions: [
            AppModel.AlertAction(title: "Import", handler: { [weak self] in
                self?.openMbtiles(file, name: source.name, attribution: source.attribution, text: "Reading downloaded tiles…",
                                  deleteOnFailure: false, fallback: "Could not import the downloaded file")
            }),
            AppModel.AlertAction(title: "Not now", role: .cancel, handler: {}),
        ])
    }

    private func downloadFailed(source: DataSource, error: Error) {
        download = nil
        downloadRunner = nil
        downloadTask = nil
        endBackgroundTask()
        closeDownloadSheet()
        if error is CancellationError {
            app.toast("Download cancelled")
        } else {
            DiagnosticLog.log("OfflineTileDownload", "download failed: \(error)")
            alertSoon("Download failed", LayersErrorText.describe(error, fallback: "\(source.name) could not be downloaded"))
        }
    }

    /// A download stopped part-way (the app was closed) leaves a partial file: removed, and the user told.
    private func reportInterruptedDownloads() {
        let dir = LayersTileDownloadRunner.downloadsFolder
        let cutoff = Date().addingTimeInterval(-120)
        Task { [weak self] in
            let removed = await Task.detached(priority: .utility) {
                LayersFileNames.deleteStaleDownloadParts(in: dir, olderThan: cutoff)
            }.value
            guard let self, removed.contains(where: { $0.lastPathComponent.hasSuffix(".part") }) else { return }
            self.app.alert("Offline maps",
                           "An offline map download was interrupted before it finished (the phone closed the app) "
                           + "and the partial file was removed. Start it again from Settings → Offline maps.")
        }
    }
}
