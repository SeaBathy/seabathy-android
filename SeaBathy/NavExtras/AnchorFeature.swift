import AudioToolbox
import AVFoundation
import CoreLocation
import MapLibre
import SwiftUI
import UIKit
import UserNotifications

/// Why the anchor alarm is sounding.
enum AnchorAlarm { case dragging, noGps }

/**
 The anchor alarm (Android nav/AnchorFeature, AnchorMonitor, AnchorWatchService):
 drop the anchor at the boat (or the centre dot), pick a swing circle, see it on
 the chart with a chip showing how far the boat is from the anchor. The alarm
 sounds when the boat drags out of the circle or GPS goes quiet for a minute,
 and keeps watching with the screen off through background location; the siren
 plays through the audio background mode, with a time-sensitive notification.
 */
@MainActor
final class AnchorFeature: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var watch: AnchorWatch?
    @Published private(set) var status: AnchorStatus?
    @Published private(set) var alarm: AnchorAlarm?
    /// Silenced for this alarm; it can sound again once it clears and comes back.
    @Published private(set) var silenced = false
    @Published private(set) var maxDistanceM = 0.0

    private let repository = AnchorRepository()
    private let siren = AnchorSiren()
    private var lastFix: CLLocation?
    private var lastFixAt = Date()
    private var tickTask: Task<Void, Never>?
    private var alertController: UIAlertController?
    private var started = false

    private static let locationUser = "anchor"
    private static let gpsQuiet: TimeInterval = 60
    private static let tickSeconds: UInt64 = 5
    private static let circlePoints = 72
    static let dragRed = "#DC2626"

    init(app: AppModel) {
        self.app = app
    }

    var isWatching: Bool { watch != nil }
    var lastRadiusM: Double { repository.lastRadiusM }

    func start() {
        guard !started else { return }
        started = true
        app.map.onStyleLoaded { [weak self] _ in self?.installLayers() }
        app.location.onLocation { [weak self] location in self?.onFix(location) }
        // Still anchored from last time: carry on watching.
        if let anchor = repository.load() {
            watch = AnchorWatch(anchor: anchor)
            beginWatching()
            app.navigation.gps.ensureOn()
        }
    }

    /// Settings > Anchor alarm.
    func showAnchor() {
        app.present(detents: [.medium, .large]) { AnchorSheet(feature: self) }
    }

    // Setting the anchor ---------------------------------------------------------------

    func dropAtBoat(radiusM: Double) -> Bool {
        guard let fix = lastFix ?? app.location.lastLocation else {
            app.navigation.gps.ensureOn()
            app.toast("Waiting for GPS — try again in a moment")
            return false
        }
        setAnchor(AnchorSpot(lat: fix.coordinate.latitude, lng: fix.coordinate.longitude,
                             radiusM: radiusM, setMillis: NavExtrasStyle.nowMillis()))
        return true
    }

    func dropAtCentre(radiusM: Double) {
        let c = app.map.centre
        setAnchor(AnchorSpot(lat: c.latitude, lng: c.longitude, radiusM: radiusM, setMillis: NavExtrasStyle.nowMillis()))
    }

    func setRadius(_ radiusM: Double) {
        repository.lastRadiusM = radiusM
        if var anchor = watch?.anchor {
            anchor.radiusM = radiusM
            setAnchor(anchor, keepStats: true)
        }
    }

    func stopWatching() {
        setAnchor(nil)
    }

    /// Sets, moves or (nil) lifts the anchor. With `keepStats` (a new circle size) the furthest swing is kept.
    private func setAnchor(_ anchor: AnchorSpot?, keepStats: Bool = false) {
        let wasWatching = isWatching
        let previous = watch
        repository.save(anchor)
        if let anchor {
            let next = AnchorWatch(anchor: anchor)
            if keepStats, let previous { next.carryOver(previous) }
            watch = next
        } else {
            watch = nil
        }
        if anchor == nil || !keepStats || previous == nil { status = nil }
        maxDistanceM = watch?.maxDistanceM ?? 0
        calm()
        if let anchor {
            app.navigation.gps.ensureOn()
            requestNotificationPermission()
            lastFixAt = Date()
            beginWatching()
            if let fix = lastFix { evaluate(fix) }
            if !wasWatching { app.toast("Anchor alarm on — \(app.format.offset(anchor.radiusM)) circle") }
        } else {
            endWatching()
            app.toast("Anchor alarm off")
        }
        refreshMap()
    }

    private func beginWatching() {
        lastFixAt = Date()
        app.location.start(for: Self.locationUser)
        app.location.setBackgroundNeeded(true, reason: Self.locationUser)
        tickTask?.cancel()
        tickTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: AnchorFeature.tickSeconds * 1_000_000_000)
                guard let self, !Task.isCancelled else { return }
                self.tick()
            }
        }
    }

    private func endWatching() {
        tickTask?.cancel()
        tickTask = nil
        app.location.setBackgroundNeeded(false, reason: Self.locationUser)
        app.location.stop(for: Self.locationUser)
    }

    // Watching ----------------------------------------------------------------------------

    private func onFix(_ location: CLLocation) {
        lastFix = location
        lastFixAt = Date()
        if isWatching { evaluate(location) }
    }

    /// Every few seconds: GPS silent for a minute is an alarm too, and a boat sitting
    /// outside the circle keeps sounding even if fixes stop changing.
    private func tick() {
        guard isWatching else { return }
        if Date().timeIntervalSince(lastFixAt) > Self.gpsQuiet {
            raise(.noGps)
        } else if let fix = lastFix {
            evaluate(fix)
        }
    }

    /// Stops the sound until the alarm clears and comes back.
    func silence() {
        guard alarm != nil else { return }
        silenced = true
        siren.stop()
        dismissAlert()
    }

    private func evaluate(_ location: CLLocation) {
        guard let w = watch else { return }
        let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
        let s = w.update(lat: location.coordinate.latitude, lng: location.coordinate.longitude, accuracyM: accuracy)
        status = s
        maxDistanceM = w.maxDistanceM
        if s.dragging {
            raise(.dragging)
        } else if let current = alarm, !silenced {
            // Not acknowledged yet: keep ringing although the boat is back inside (or GPS is back).
            raise(current)
        } else if !s.gpsPoor {
            calm()
        } else if alarm == .noGps {
            // A rough fix still proves GPS is alive.
            calm()
        }
    }

    private func raise(_ why: AnchorAlarm) {
        let changed = alarm != why
        if changed {
            alarm = why
            silenced = false
        }
        if silenced {
            siren.stop()
            return
        }
        let settings = app.navSettings.settings
        siren.start(sound: settings.alarmSound, vibrate: settings.alarmVibrate)
        if changed {
            postNotification(why)
            showAlert(why)
        }
    }

    private func calm() {
        alarm = nil
        silenced = false
        siren.stop()
        dismissAlert()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.notificationId])
    }

    /// What the dragging alarm says: where the boat is now, inside the circle again or not.
    func draggingText() -> String {
        let f = app.format
        let r = watch?.anchor.radiusM
        let d = status?.distanceM
        let circle = r.map { "the \(f.offset($0)) circle" } ?? "the circle"
        guard let d else { return "The boat went outside \(circle)." }
        if let r, d <= r {
            return "The boat went outside \(circle). It's \(f.offset(d)) from the anchor now: check the anchor is holding."
        }
        return "The boat is \(f.offset(d)) from the anchor, outside \(circle)."
    }

    private func alarmText(_ why: AnchorAlarm) -> (title: String, message: String) {
        switch why {
        case .dragging: return ("Anchor dragging", draggingText())
        case .noGps: return ("No GPS", "SeaBathy lost GPS for over a minute, so it couldn't watch the anchor. Check GPS is on.")
        }
    }

    /// The in-app alert while the alarm sounds, shown over any sheet.
    private func showAlert(_ why: AnchorAlarm) {
        dismissAlert()
        let text = alarmText(why)
        alertController = NavExtrasAlert.show(text.title, text.message, actions: [
            .init(title: "Silence", handler: { [weak self] in
                self?.alertController = nil
                self?.silence()
            })
        ])
    }

    private func dismissAlert() {
        if let alert = alertController, alert.presentingViewController != nil {
            alert.dismiss(animated: true)
        }
        alertController = nil
    }

    // Notifications ------------------------------------------------------------------------

    private static let notificationId = "anchor-alarm"

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func postNotification(_ why: AnchorAlarm) {
        let text = alarmText(why)
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = text.message
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let request = UNNotificationRequest(identifier: Self.notificationId, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error { DiagnosticLog.log("Anchor", "couldn't post the alarm notification: \(error.localizedDescription)") }
        }
    }

    // Chart ----------------------------------------------------------------------------------

    private func installLayers() {
        guard let source = app.map.geoJSONSource(MapLayerIds.anchorSource) else { return }
        let fill = MLNFillStyleLayer(identifier: MapLayerIds.anchorFill, source: source)
        fill.predicate = NavExtrasStyle.geometry("Polygon")
        fill.fillColor = NavExtrasStyle.colour(NavExtrasStyle.measure)
        fill.fillOpacity = NavExtrasStyle.number(0.12)
        app.map.add(fill, placement: .top)

        let ring = MLNLineStyleLayer(identifier: MapLayerIds.anchorRing, source: source)
        ring.predicate = NavExtrasStyle.geometry("Polygon")
        ring.lineColor = NavExtrasStyle.colour(NavExtrasStyle.measure)
        ring.lineWidth = NavExtrasStyle.number(2.5)
        ring.lineDashPattern = NSExpression(forConstantValue: [3, 2])
        app.map.add(ring, placement: .top)

        let point = MLNCircleStyleLayer(identifier: MapLayerIds.anchorPoint, source: source)
        point.predicate = NavExtrasStyle.geometry("Point")
        point.circleRadius = NavExtrasStyle.number(6)
        point.circleColor = NavExtrasStyle.colour(NavExtrasStyle.measure)
        point.circleStrokeWidth = NavExtrasStyle.number(2)
        point.circleStrokeColor = NavExtrasStyle.colour(NavExtrasStyle.casing)
        app.map.add(point, placement: .top)
        refreshMap()
    }

    private func refreshMap() {
        guard let a = watch?.anchor else {
            app.map.setGeoJSON(MapLayerIds.anchorSource, MapLayerIds.emptyGeoJSON)
            return
        }
        let point = String(format: #"{"type":"Feature","geometry":{"type":"Point","coordinates":[%.7f,%.7f]},"properties":{}}"#, a.lng, a.lat)
        app.map.setGeoJSON(MapLayerIds.anchorSource,
                           #"{"type":"FeatureCollection","features":["# + Self.circle(a) + "," + point + "]}")
    }

    /// The swing circle as a GeoJSON polygon.
    nonisolated static func circle(_ a: AnchorSpot) -> String {
        let metresPerDegLat = 111_320.0
        let metresPerDegLng = metresPerDegLat * cos(a.lat * .pi / 180)
        let count = 72
        let ring = (0...count).map { i -> String in
            let angle = 2 * Double.pi * Double(i) / Double(count)
            let lat = a.lat + a.radiusM * cos(angle) / metresPerDegLat
            let lng = a.lng + a.radiusM * sin(angle) / metresPerDegLng
            return String(format: "[%.7f,%.7f]", lng, lat)
        }.joined(separator: ",")
        return #"{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[\#(ring)]]},"properties":{}}"#
    }

    // Text for the views ----------------------------------------------------------------------

    func chipText() -> String {
        let f = app.format
        if alarm == .noGps { return "Anchor · no GPS" }
        guard let s = status else { return "Anchor · waiting for GPS" }
        if s.gpsPoor { return "Anchor · GPS rough" }
        return "Anchor \(f.offset(s.distanceM)) / \(f.offset(watch?.anchor.radiusM ?? 0))"
    }

    func statusLine() -> String {
        let f = app.format
        if alarm == .noGps { return "No GPS for over a minute" }
        guard let s = status else { return "Waiting for GPS…" }
        if s.gpsPoor { return "GPS is rough right now (\(f.offset(s.distanceM)) from anchor)" }
        return "Boat \(f.offset(s.distanceM)) from anchor · furthest \(f.offset(maxDistanceM))"
    }
}

// Siren ---------------------------------------------------------------------------------

/**
 The anchor alarm's sound: a loud two-tone siren generated in memory, looping
 through the playback audio session (so it plays with the ring switch on silent
 and with the screen off), and a buzz that repeats, both until `stop`.
 */
@MainActor
final class AnchorSiren {
    private var player: AVAudioPlayer?
    private var buzzTask: Task<Void, Never>?

    var isRinging: Bool { player?.isPlaying == true }

    func start(sound: Bool, vibrate: Bool) {
        if sound && player == nil { player = ring() }
        if !sound { stopTone() }
        if vibrate && buzzTask == nil { buzz() }
        if !vibrate { stopBuzz() }
    }

    func stop() {
        stopTone()
        stopBuzz()
    }

    private func ring() -> AVAudioPlayer? {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            let p = try AVAudioPlayer(data: Self.sirenWav())
            p.numberOfLoops = -1
            p.volume = 1
            p.prepareToPlay()
            p.play()
            return p
        } catch {
            DiagnosticLog.log("AnchorSiren", "couldn't play the siren: \(error.localizedDescription)")
            return nil
        }
    }

    private func buzz() {
        // Long buzzes with short gaps, repeating until stopped.
        buzzTask = Task { @MainActor in
            while !Task.isCancelled {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(nanoseconds: 1_200_000_000)
            }
        }
    }

    private func stopTone() {
        guard let p = player else { return }
        p.stop()
        player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func stopBuzz() {
        buzzTask?.cancel()
        buzzTask = nil
    }

    /// One second of siren as a 16-bit mono WAV: 880 Hz then 660 Hz, with short gaps.
    nonisolated static func sirenWav() -> Data {
        let rate = 22_050
        var samples: [Int16] = []
        func tone(_ hz: Double, _ seconds: Double) {
            let n = Int(Double(rate) * seconds)
            for i in 0..<n {
                // Short fades at each end avoid clicks.
                let fade = min(1, Double(min(i, n - 1 - i)) / 200)
                let v = sin(2 * Double.pi * hz * Double(i) / Double(rate)) * 0.9 * fade
                samples.append(Int16(v * Double(Int16.max)))
            }
        }
        func silence(_ seconds: Double) {
            samples.append(contentsOf: [Int16](repeating: 0, count: Int(Double(rate) * seconds)))
        }
        tone(880, 0.4); silence(0.1); tone(660, 0.4); silence(0.1)

        var data = Data()
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { data.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { data.append(contentsOf: $0) } }
        let dataBytes = UInt32(samples.count * 2)
        data.append(contentsOf: Array("RIFF".utf8)); u32(36 + dataBytes)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        data.append(contentsOf: Array("data".utf8)); u32(dataBytes)
        for s in samples { u16(UInt16(bitPattern: s)) }
        return data
    }
}

// Views ---------------------------------------------------------------------------------

/// The "Anchor 12 m / 40 m" chip under the top bar while the anchor alarm is on (red while it sounds).
struct AnchorChipOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        AnchorChipContent(feature: app.navigation.anchor, navigation: app.navigation)
    }
}

private struct AnchorChipContent: View {
    @ObservedObject var feature: AnchorFeature
    @ObservedObject var navigation: NavigationFeature

    var body: some View {
        if feature.isWatching && !navigation.isNavigating {
            let red = feature.alarm != nil
            Button {
                feature.showAnchor()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "anchor").font(.footnote.weight(.bold))
                    Text(feature.chipText()).font(.footnote.weight(.bold)).lineLimit(1)
                }
                .padding(.leading, 10).padding(.trailing, 12).padding(.vertical, 6)
                .background(red ? Color(hex: AnchorFeature.dragRed) : Color(hex: "#E6111827"), in: Capsule())
                .overlay(Capsule().stroke(red ? Color(hex: AnchorFeature.dragRed) : Theme.border, lineWidth: 0.5))
                .foregroundStyle(Theme.textPrimary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Anchor alarm — open")
        }
    }
}

private struct AnchorSheet: View {
    @ObservedObject var feature: AnchorFeature
    @EnvironmentObject var app: AppModel
    @State private var radius: Double = AnchorWatch.defaultRadiusM
    @State private var confirmStop = false

    var body: some View {
        SheetScaffold(title: "Anchor alarm") {
            List {
                Section {
                    if let w = feature.watch {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(feature.statusLine())
                                .font(.headline)
                                .foregroundStyle(feature.alarm != nil ? Color(hex: AnchorFeature.dragRed) : Theme.textPrimary)
                            Text("Anchored at \(TrackFeature.time(w.anchor.setMillis))")
                                .font(.footnote).foregroundStyle(Theme.textMuted)
                        }
                        if feature.alarm != nil && !feature.silenced {
                            Button("Silence") { feature.silence() }
                                .foregroundStyle(Color(hex: AnchorFeature.dragRed))
                        }
                    } else {
                        Text("Sounds an alarm if the boat swings outside the circle or GPS stops. Keeps watching with the screen off or another app open.")
                            .font(.footnote).foregroundStyle(Theme.textMuted)
                    }
                }
                .listRowBackground(Theme.surface)

                Section {
                    Picker("Alarm circle", selection: $radius) {
                        ForEach(AnchorWatch.radiiM, id: \.self) { r in
                            Text(app.format.offset(r)).tag(r)
                        }
                    }
                    .onChange(of: radius) { value in
                        if value != (feature.watch?.anchor.radiusM ?? feature.lastRadiusM) { feature.setRadius(value) }
                    }
                } footer: {
                    Text("Allow for your anchor line plus a boat length.")
                }
                .listRowBackground(Theme.surface)

                Section {
                    Button {
                        if feature.dropAtBoat(radiusM: radius) { app.dismissSheet() }
                    } label: {
                        Text(feature.isWatching ? "Move anchor to the boat" : "Drop anchor at the boat")
                            .bold().frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Use the centre dot") {
                        feature.dropAtCentre(radiusM: radius)
                        app.dismissSheet()
                    }
                    if feature.isWatching {
                        Button("Stop anchor alarm", role: .destructive) { confirmStop = true }
                    }
                }
                .listRowBackground(Theme.surface)
            }
            .confirmationDialog("Stop the anchor alarm?", isPresented: $confirmStop, titleVisibility: .visible) {
                Button("Stop", role: .destructive) {
                    feature.stopWatching()
                    app.dismissSheet()
                }
                Button("Cancel", role: .cancel) {}
            }
        }
        .onAppear {
            let current = feature.watch?.anchor.radiusM ?? feature.lastRadiusM
            radius = AnchorWatch.radiiM.contains(current) ? current : AnchorWatch.defaultRadiusM
        }
    }
}
