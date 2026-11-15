import CoreLocation
import MapLibre
import SwiftUI

/**
 Track recording (Android track/TrackFeature, TrackRecorder, TrackRecordingService):
 start and stop, the live trail on the chart and a chip with the distance so far,
 and the saved tracks (show on the chart, share as GPX, rename, delete).

 Recording carries on with the screen off through background location; iOS shows
 its blue location indicator instead of Android's notification. Whether a recording
 is running is saved, so it carries on after the app restarts until it is stopped.
 */
@MainActor
final class TrackFeature: ObservableObject {
    unowned let app: AppModel

    let store = TrackStore(dir: AppPaths.supportFolder("tracks"))

    @Published private(set) var isRecording = false
    @Published private(set) var liveDistanceM = 0.0
    @Published private(set) var shownId: String?
    @Published private(set) var summaries: [TrackSummary] = []

    private var livePoints: [TrackPoint] = []
    private var filter = TrackFilter()
    private var shownPoints: [TrackPoint] = []
    /// Start was tapped before location was allowed: start on the first fix.
    private var startWhenGpsArrives = false
    private var lastDrawAt = Date.distantPast
    private var drawTask: Task<Void, Never>?
    private var started = false

    // Same keys as Android's "seabathy_track_recording" preferences.
    private let prefs = UserDefaults(suiteName: "seabathy_track_recording") ?? .standard
    private static let keyId = "id", keyName = "name", keyStarted = "started"
    private static let locationUser = "track"
    private static let drawEvery: TimeInterval = 2
    // Long tracks are thinned for drawing: over 1,500 points, to within 2 m (see `trail`).

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        guard !started else { return }
        started = true
        app.map.onStyleLoaded { [weak self] _ in self?.installLayers() }
        app.location.onLocation { [weak self] location in self?.onFix(location) }
        summaries = store.summaries()
        // Still recording from before (the app was closed or the phone restarted).
        if let id = activeId {
            livePoints = store.points(id)
            liveDistanceM = TrackMath.summarise(id: id, name: "", points: livePoints).distanceM
            filter = TrackFilter(last: livePoints.last)
            isRecording = true
            app.location.start(for: Self.locationUser)
            app.location.setBackgroundNeeded(true, reason: Self.locationUser)
        }
    }

    var activeId: String? { prefs.string(forKey: Self.keyId) }
    var activeName: String { prefs.string(forKey: Self.keyName) ?? "Track" }
    var startedMillis: Int64 { (prefs.object(forKey: Self.keyStarted) as? NSNumber)?.int64Value ?? 0 }

    // Recording ---------------------------------------------------------------------

    func startRecording() {
        let location = app.location
        if location.isDenied {
            app.navigation.gps.explainLocationOff(purpose: "record a track")
            return
        }
        if !location.isAuthorized {
            startWhenGpsArrives = true
            app.navigation.gps.ensureOn()
            app.toast("Allow location and recording starts")
            return
        }
        if activeId == nil {
            let now = NavExtrasStyle.nowMillis()
            prefs.set(UUID().uuidString, forKey: Self.keyId)
            prefs.set(NavExtrasStyle.formatter("d MMM, HH:mm").string(from: NavExtrasStyle.date(now)), forKey: Self.keyName)
            prefs.set(NSNumber(value: now), forKey: Self.keyStarted)
            livePoints = []
            liveDistanceM = 0
            filter = TrackFilter()
        }
        isRecording = true
        app.location.start(for: Self.locationUser)
        app.location.setBackgroundNeeded(true, reason: Self.locationUser)
        app.toast("Recording track — it carries on with the screen off")
        drawNow()
    }

    func stopRecording() {
        guard let id = activeId else { return }
        let name = store.uniqueName(activeName)
        prefs.removeObject(forKey: Self.keyId)
        prefs.removeObject(forKey: Self.keyName)
        prefs.removeObject(forKey: Self.keyStarted)
        app.location.setBackgroundNeeded(false, reason: Self.locationUser)
        app.location.stop(for: Self.locationUser)
        let saved = store.finish(id, name: name)
        isRecording = false
        livePoints = []
        liveDistanceM = 0
        summaries = store.summaries()
        if let saved {
            app.toast("Track saved: \(saved.name), \(app.format.distance(saved.distanceM / NavExtrasStyle.metresPerNm))")
        } else {
            app.toast("Track discarded — the boat didn't move")
        }
        drawNow()
    }

    private func onFix(_ location: CLLocation) {
        if startWhenGpsArrives && app.location.isAuthorized {
            startWhenGpsArrives = false
            startRecording()
        }
        guard isRecording, let id = activeId else { return }
        let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
        // The phone's clock, not the fix's: fix times can be cached or old.
        guard let point = filter.accept(NavExtrasStyle.nowMillis(), location.coordinate.latitude,
                                        location.coordinate.longitude, accuracyM: accuracy) else { return }
        store.append(id, point)
        if let previous = livePoints.last, !TrackMath.isGap(previous, point) {
            liveDistanceM += TrackMath.distanceM(previous, point)
        }
        livePoints.append(point)
        drawThrottled()
    }

    // Chart ---------------------------------------------------------------------------

    private func installLayers() {
        guard let source = app.map.geoJSONSource(MapLayerIds.trackSource) else { return }
        let casing = MLNLineStyleLayer(identifier: MapLayerIds.trackCasing, source: source)
        casing.lineColor = NavExtrasStyle.colour(NavExtrasStyle.casing)
        casing.lineWidth = NavExtrasStyle.number(5)
        casing.lineOpacity = NavExtrasStyle.number(0.7)
        casing.lineJoin = NavExtrasStyle.text("round")
        casing.lineCap = NavExtrasStyle.text("round")
        app.map.add(casing, placement: .top)

        let live = MLNLineStyleLayer(identifier: MapLayerIds.trackLive, source: source)
        live.predicate = NavExtrasStyle.kind("live")
        live.lineColor = NavExtrasStyle.colour(NavExtrasStyle.track)
        live.lineWidth = NavExtrasStyle.number(3)
        live.lineJoin = NavExtrasStyle.text("round")
        live.lineCap = NavExtrasStyle.text("round")
        app.map.add(live, placement: .top)

        let saved = MLNLineStyleLayer(identifier: MapLayerIds.trackSaved, source: source)
        saved.predicate = NavExtrasStyle.kind("saved")
        saved.lineColor = NavExtrasStyle.colour(NavExtrasStyle.track)
        saved.lineWidth = NavExtrasStyle.number(3)
        saved.lineDashPattern = NSExpression(forConstantValue: [2, 1.5])
        saved.lineJoin = NavExtrasStyle.text("round")
        app.map.add(saved, placement: .top)
        drawNow()
    }

    private func drawThrottled() {
        let wait = Self.drawEvery - Date().timeIntervalSince(lastDrawAt)
        drawTask?.cancel()
        if wait <= 0 {
            drawNow()
            return
        }
        drawTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            self.drawNow()
        }
    }

    private func drawNow() {
        lastDrawAt = Date()
        var features: [String] = []
        if isRecording, let live = Self.trail(livePoints, kind: "live") { features.append(live) }
        if shownId != nil, let saved = Self.trail(shownPoints, kind: "saved") { features.append(saved) }
        app.map.setGeoJSON(MapLayerIds.trackSource, #"{"type":"FeatureCollection","features":["# + features.joined(separator: ",") + "]}")
    }

    /// A track as a GeoJSON MultiLineString, thinned for drawing when it is long.
    nonisolated static func trail(_ points: [TrackPoint], kind: String) -> String? {
        let lines = TrackMath.segments(points)
            .map { $0.count > 1_500 ? TrackMath.simplify($0, toleranceM: 2.0) : $0 }
            .filter { $0.count >= 2 }
        if lines.isEmpty { return nil }
        let coordinates = lines.map { line in
            "[" + line.map { String(format: "[%.7f,%.7f]", $0.lng, $0.lat) }.joined(separator: ",") + "]"
        }.joined(separator: ",")
        return #"{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[\#(coordinates)]},"properties":{"kind":"\#(kind)"}}"#
    }

    func showOnChart(_ track: TrackSummary) {
        let points = store.points(track.id)
        guard points.count >= 2 else {
            app.toast("That track has no points")
            return
        }
        shownId = track.id
        shownPoints = points
        drawNow()
        app.closePanels()
        if let bounds = NavExtrasStyle.bounds(points.map { (lat: $0.lat, lng: $0.lng) }) {
            app.map.fit(bounds, padding: 60)
        }
    }

    func hideFromChart() {
        shownId = nil
        shownPoints = []
        drawNow()
    }

    // Saved tracks -------------------------------------------------------------------

    func share(_ track: TrackSummary) {
        let points = store.points(track.id)
        guard points.count >= 2 else {
            app.toast("Couldn't create the GPX file")
            return
        }
        do {
            let url = try GpxCodec.writeShareFile(GpxCodec.trackFileName(track.name),
                                                  GpxCodec.exportTrack(name: track.name, points: points))
            app.share([url])
        } catch {
            DiagnosticLog.logError("TrackFeature", "track GPX export failed", error)
            app.toast("Couldn't create the GPX file")
        }
    }

    /// An error message for the name field, or nil when the rename was done.
    func rename(_ track: TrackSummary, to raw: String) -> String? {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return "Enter a name" }
        if store.isNameTaken(name, exceptId: track.id) { return "A track called \"\(name)\" already exists" }
        store.rename(track.id, name: name)
        summaries = store.summaries()
        return nil
    }

    func delete(_ track: TrackSummary) {
        store.delete(track.id)
        if shownId == track.id { hideFromChart() }
        summaries = store.summaries()
    }

    /// Saves tracks read from a GPX file and returns how many were saved.
    func importTracks(_ tracks: [(name: String?, points: [TrackPoint])]) -> Int {
        var saved = 0
        for t in tracks {
            let trimmed = t.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let base = trimmed.isEmpty ? "Imported track" : trimmed
            if store.add(UUID().uuidString, name: store.uniqueName(base), points: t.points) != nil { saved += 1 }
        }
        if saved > 0 { summaries = store.summaries() }
        return saved
    }

    /// Settings > Tracks.
    func showTracks() {
        summaries = store.summaries()
        app.present { TracksSheet(feature: self) }
    }

    // Text ----------------------------------------------------------------------------

    func distanceText(_ metres: Double) -> String { app.format.distance(metres / NavExtrasStyle.metresPerNm) }

    func listLine(_ track: TrackSummary) -> String {
        var line = ""
        // A recorded track is named for when it started: the date isn't said twice.
        let start = NavExtrasStyle.date(track.startMillis)
        if track.startMillis > 0 && !track.name.hasPrefix(NavExtrasStyle.formatter("d MMM").string(from: start)) {
            line += NavExtrasStyle.formatter("d MMM yyyy").string(from: start) + " · "
        }
        line += distanceText(track.distanceM)
        if track.endMillis > track.startMillis { line += " · " + TrackMath.duration(track.endMillis - track.startMillis) }
        return line
    }

    func span(_ track: TrackSummary) -> String {
        if track.startMillis <= 0 { return "\(track.pointCount) points, no times recorded" }
        let day = NavExtrasStyle.formatter("EEE d MMM yyyy")
        let s = NavExtrasStyle.date(track.startMillis), e = NavExtrasStyle.date(track.endMillis)
        if day.string(from: s) == day.string(from: e) {
            return "\(day.string(from: s)), \(Self.time(track.startMillis)) – \(Self.time(track.endMillis))"
        }
        return "\(day.string(from: s)) \(Self.time(track.startMillis)) – \(day.string(from: e)) \(Self.time(track.endMillis))"
    }

    nonisolated static func time(_ millis: Int64) -> String {
        NavExtrasStyle.formatter("HH:mm").string(from: NavExtrasStyle.date(millis))
    }
}

// Views -------------------------------------------------------------------------------

/// The "Recording 1.2 nm" chip under the top bar while a track records (hidden while navigating).
struct TrackChipOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        TrackChipContent(feature: app.navigation.tracks, navigation: app.navigation)
    }
}

private struct TrackChipContent: View {
    @ObservedObject var feature: TrackFeature
    @ObservedObject var navigation: NavigationFeature

    var body: some View {
        if feature.isRecording && !navigation.isNavigating {
            Button {
                feature.showTracks()
            } label: {
                HStack(spacing: 8) {
                    Circle().fill(Theme.red).frame(width: 10, height: 10)
                    Text("Recording \(feature.distanceText(feature.liveDistanceM))")
                        .font(.footnote.weight(.bold))
                        .lineLimit(1)
                }
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Color(hex: "#E6111827"), in: Capsule())
                .overlay(Capsule().stroke(Theme.border, lineWidth: 0.5))
                .foregroundStyle(Theme.textPrimary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Track recording — open")
        }
    }
}

private struct TracksSheet: View {
    @ObservedObject var feature: TrackFeature
    @EnvironmentObject var app: AppModel

    var body: some View {
        SheetScaffold(title: "Tracks") {
            List {
                Section {
                    if feature.isRecording {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Recording · \(feature.distanceText(feature.liveDistanceM))")
                                .font(.headline)
                            Text("Started \(TrackFeature.time(feature.startedMillis)) · \(TrackMath.duration(NavExtrasStyle.nowMillis() - feature.startedMillis)) so far. Keeps recording with the screen off; stop it from here.")
                                .font(.footnote)
                                .foregroundStyle(Theme.textMuted)
                        }
                        Button {
                            feature.stopRecording()
                        } label: {
                            Text("Stop and save").bold().frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Text("Records where the boat goes, even with the screen off. Show a trip on the chart later, or share it as a GPX file.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textMuted)
                        Button {
                            app.dismissSheet()
                            feature.startRecording()
                        } label: {
                            Text("Start recording").bold().frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    if let id = feature.shownId {
                        let name = feature.summaries.first { $0.id == id }?.name ?? "track"
                        Button("Hide \(name) from the chart") { feature.hideFromChart() }
                    }
                }
                .listRowBackground(Theme.surface)

                Section("SAVED TRACKS") {
                    if feature.summaries.isEmpty {
                        Text("No saved tracks yet.").foregroundStyle(Theme.textMuted)
                    }
                    ForEach(feature.summaries) { track in
                        NavigationLink {
                            TrackDetailView(feature: feature, trackId: track.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(track.name).foregroundStyle(Theme.textPrimary)
                                Text(feature.listLine(track)).font(.caption).foregroundStyle(Theme.textMuted)
                            }
                        }
                    }
                }
                .listRowBackground(Theme.surface)
            }
        }
    }
}

private struct TrackDetailView: View {
    @ObservedObject var feature: TrackFeature
    let trackId: String
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmDelete = false

    private var track: TrackSummary? { feature.summaries.first { $0.id == trackId } }

    var body: some View {
        Group {
            if let track {
                content(track)
            } else {
                Text("This track was deleted.").foregroundStyle(Theme.textMuted)
            }
        }
        .background(Theme.panelColor)
    }

    private func content(_ track: TrackSummary) -> some View {
        let f = app.format
        return List {
            Section {
                Text(feature.span(track)).font(.footnote).foregroundStyle(Theme.textMuted)
                stat("Distance", feature.distanceText(track.distanceM))
                if track.endMillis > track.startMillis {
                    stat("Time", TrackMath.duration(track.endMillis - track.startMillis))
                    stat("Moving", TrackMath.duration(track.movingMillis))
                    stat("Average speed", f.speed(track.averageSpeedMs * NavExtrasStyle.msToKnots))
                    stat("Top speed", f.speed(track.topSpeedMs * NavExtrasStyle.msToKnots))
                }
            }
            .listRowBackground(Theme.surface)
            Section {
                Button {
                    feature.showOnChart(track)
                } label: {
                    Text("Show on chart").bold().frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button("Share GPX") { feature.share(track) }
                Button("Rename") {
                    newName = track.name
                    renaming = true
                }
                Button("Delete", role: .destructive) { confirmDelete = true }
            }
            .listRowBackground(Theme.surface)
        }
        .scrollContentBackground(.hidden)
        .navigationTitle(track.name)
        .navigationBarTitleDisplayMode(.inline)
        .alert("Rename track", isPresented: $renaming) {
            TextField("Track name", text: $newName)
            Button("Save") {
                if let error = feature.rename(track, to: newName) { app.toast(error) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Delete \(track.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                feature.delete(track)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(Theme.textMuted)
            Spacer()
            Text(value).font(.body.weight(.bold)).foregroundStyle(Theme.textPrimary)
        }
    }
}
