import Combine
import CoreLocation
import SwiftUI
import UIKit

/**
 Tides: where they are for (follow the boat, or a saved place picked on
 the map or found by name), downloading and caching them, the chip on the
 chart, and the tide screens. Depth readouts ask `tideNow()` / `tideHeightNow(lat:lng:)`.
 (Android tides/TideFeature.kt.)
 */
@MainActor
final class TideFeature: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var settings: TideSettings
    /// The chip's text; nil hides the chip.
    @Published private(set) var chipText: String?
    /// True while picking a tide spot on the map (the banner replaces the chip).
    @Published private(set) var picking = false
    @Published private(set) var loading = false
    /// True while long-range tides download and are analysed.
    @Published private(set) var saving = false
    @Published private(set) var lastError: String?
    /// Bumped whenever tide heights change, for anything showing them.
    @Published private(set) var revision = 0

    let places: TidePlaceStore
    let store: TideStore

    private var lastFix: (lat: Double, lng: Double)?
    private var lowestRunning = false
    private var lowestFailedAt: Int64 = 0
    private var lastFailedAt: Int64 = 0
    /// "Follow me" was chosen before a GPS fix arrived: fetch on the first one.
    private var waitingForFix = false
    private var started = false
    private var tickTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private var locationToken: UUID?

    /// How far a place's tides are used: beyond this the tide is someone else's, metres.
    static let tideReachM = 50_000.0
    private static let tag = "TideFeature"
    private static let tickNanos: UInt64 = 60_000_000_000
    private static let retryMs: Int64 = 5 * 60_000

    init(app: AppModel) {
        self.app = app
        settings = TideSettingsRepository.load()
        places = TidePlaceStore()
        store = TideStore()
    }

    var key: String { settings.placeId ?? TideStore.followKey }
    var place: TidePlace? { places.byId(settings.placeId) }
    var forecast: TideForecast? { store.forecast(key) }
    var format: NavFormat { app.format }

    // Setup and lifecycle -------------------------------------------------------------

    func start() {
        guard !started else { return }
        started = true
        // A saved place that was deleted elsewhere falls back to following.
        if settings.placeId != nil && place == nil {
            var s = settings
            s.placeId = nil
            update(s)
        }
        locationToken = app.location.onLocation { [weak self] location in
            self?.onLocation(location)
        }
        // Units changed, or navigation started/stopped: the chip changes or hides.
        app.navSettings.$settings.dropFirst().sink { [weak self] _ in
            Task { @MainActor [weak self] in self?.refreshChip() }
        }.store(in: &cancellables)
        app.navigation.objectWillChange.sink { [weak self] _ in
            Task { @MainActor [weak self] in self?.refreshChip() }
        }.store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification).sink { [weak self] _ in
            Task { @MainActor [weak self] in self?.onResume() }
        }.store(in: &cancellables)
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: TideFeature.tickNanos)
                self?.refreshChip()
            }
        }
        refreshChip()
        Task {
            await store.load(key)
            refreshChip()
            refreshIfNeeded()
        }
    }

    private func onResume() {
        refreshChip()
        refreshIfNeeded()
    }

    private func onLocation(_ location: CLLocation) {
        lastFix = (location.coordinate.latitude, location.coordinate.longitude)
        // The first fix replaces tides fetched for the phone's last known position
        // if the boat is somewhere else now (needsRefresh checks the distance).
        waitingForFix = false
        refreshIfNeeded()
    }

    // Tide for depth readouts ------------------------------------------------------------

    /// Tide above the survey datum now, when tides are applied to depths.
    func tideNow() -> Double? {
        guard settings.correctDepths else { return nil }
        return settings.heightAboveDatum(forecast, TideMath.nowMillis())
    }

    /**
     The tide to add at `lat`/`lng` now, metres above the survey's datum (Android tideNear):
     nil when tides aren't applied to depths, or the tides are for somewhere too far away
     (a saved place hundreds of km off has another place's tides). A typed-in (manual)
     tide has no place, so it is taken to be for here.
     */
    func tideHeightNow(lat: Double, lng: Double) -> Double? {
        guard coversPoint(lat: lat, lng: lng) else { return nil }
        return tideNow()
    }

    /// True when `lat`/`lng` is close enough to the tide spot for its tides to hold.
    func coversPoint(lat: Double, lng: Double) -> Bool {
        if settings.mode != .model { return true }
        guard let f = forecast else { return false }
        return TideGeo.distanceM(lat, lng, f.requestLat, f.requestLng) <= TideFeature.tideReachM
    }

    /// The tide now as a tide table gives it (above chart datum), for showing.
    func tideShown() -> Double? { settings.shownHeight(forecast, TideMath.nowMillis()) }

    // Screens --------------------------------------------------------------------------------

    /// The tide screen; the first time, it asks where the tides should be for.
    func showTides() {
        if !settings.locationChosen {
            showLocationPicker()
            return
        }
        showMain()
    }

    func showMain() {
        app.present { TideMainSheet(feature: self) }
    }

    func showLocationPicker() {
        app.present { TideLocationSheet(feature: self) }
    }

    /// Following the boat with no GPS fix yet: the last known position is used.
    var noFix: Bool { place == nil && lastFix == nil }
    /// Following the boat, and no position at all yet.
    var noPosition: Bool { place == nil && position() == nil }

    func followMe() {
        select(nil)
        if lastFix == nil {
            waitingForFix = true
            app.navigation.gps.ensureOn()
            app.toast("Finding your position…")
            // Until GPS answers, the phone's last known position is used.
        }
    }

    func choose(_ place: TidePlace) { select(place.id) }

    func deletePlace(_ place: TidePlace) {
        places.delete(id: place.id)
        store.forget(place.id)
        if settings.placeId == place.id { select(nil) } else { objectWillChange.send() }
    }

    func chooseSearchResult(_ result: TidePlaceResult) {
        let saved = places.add(name: result.name, lat: result.lat, lng: result.lng)
        select(saved.id)
    }

    func refreshTapped() { refresh(manual: true) }

    func deleteSavedTides() {
        store.deleteSaved(key)
        lastError = nil
        refreshChip()
        tidesChanged()
    }

    /// Settings were edited in the settings screen.
    func applySettings(_ updated: TideSettings) {
        guard updated != settings else { return }
        let modeChanged = updated.mode != settings.mode
        update(updated)
        tidesChanged()
        refreshChip()
        if modeChanged && updated.mode == .model { refreshIfNeeded() }
    }

    // Picking on the map -------------------------------------------------------------------

    func startMapPick() {
        app.closePanels()
        picking = true
        refreshChip()
    }

    func endMapPick() {
        picking = false
        refreshChip()
    }

    func saveMapPick() {
        guard app.map.mapView != nil else {
            app.toast("The map isn't ready yet")
            return
        }
        let centre = app.map.centre
        endMapPick()
        nameAndSave(lat: centre.latitude, lng: centre.longitude)
    }

    /// Suggests a name from the nearest town (OpenStreetMap), lets the user
    /// change it, saves and selects the place, then runs `then`.
    private func nameAndSave(lat: Double, lng: Double, then: (() -> Void)? = nil) {
        Task {
            let nearby = await store.nearbyName(lat: lat, lng: lng)
            let suggested = nearby ?? String(format: "Spot %.3f, %.3f", lat, lng)
            app.present {
                TideNameSheet(suggested: suggested, fromOpenStreetMap: nearby != nil) { [weak self] name in
                    guard let self else { return }
                    let saved = self.places.add(name: name, lat: lat, lng: lng)
                    self.select(saved.id)
                    then?()
                }
            }
        }
    }

    /**
     Downloads a year of tides for the place and keeps `period` of
     predictions offline. Following the boat, the spot is saved as a place first.
     */
    func saveOffline(_ period: TideSavePeriod) {
        guard let target = place else {
            guard let pos = position() else {
                app.toast("No position yet — turn on GPS")
                return
            }
            nameAndSave(lat: pos.lat, lng: pos.lng) { [weak self] in self?.saveOffline(period) }
            return
        }
        if saving { return }
        saving = true
        Task {
            do {
                let result = try await store.saveOffline(target.id, lat: target.lat, lng: target.lng, period: period)
                lastError = nil
                let until = result.savedUntilMillis.map { TideText.dayMonthYear($0) } ?? ""
                app.toast("Tides saved offline until \(until)")
            } catch {
                lastError = TideText.message(error, fallback: "Couldn't save tides")
            }
            saving = false
            refreshChip()
            tidesChanged()
        }
    }

    /// Switches tides to `placeId` (nil = follow me) and shows them.
    private func select(_ placeId: String?) {
        // Choosing a place means wanting its forecast, whatever the source was before.
        var s = settings
        s.placeId = placeId
        s.locationChosen = true
        s.mode = .model
        update(s)
        lastError = nil
        Task {
            await store.load(key)
            tidesChanged()
            refreshChip()
            showMain()
            refreshIfNeeded(force: forecast == nil)
        }
    }

    // Downloading ------------------------------------------------------------------------------

    /// Where tides are wanted now: the saved place, or the boat. Before a GPS
    /// fix, the phone's last known position, then where the saved tides are for.
    func position() -> (lat: Double, lng: Double)? {
        if let p = place { return (p.lat, p.lng) }
        if let fix = lastFix { return fix }
        if let last = app.location.lastLocation { return (last.coordinate.latitude, last.coordinate.longitude) }
        if let f = forecast { return (f.requestLat, f.requestLng) }
        return nil
    }

    private func refreshIfNeeded(force: Bool = false) {
        guard settings.mode == .model, let pos = position() else { return }
        // Don't retry a failing download on every GPS fix.
        if !force && TideMath.nowMillis() - lastFailedAt < TideFeature.retryMs { return }
        if force || store.needsRefresh(key, lat: pos.lat, lng: pos.lng) { refresh(manual: false) } else { ensureLowest() }
    }

    /// Works out chart datum (the lowest tide) for where the tides are, once
    /// per place, so heights read like a tide table instead of going below zero.
    private func ensureLowest() {
        guard settings.mode == .model, !lowestRunning, let pos = position() else { return }
        guard store.needsLowest(key, lat: pos.lat, lng: pos.lng) else { return }
        if TideMath.nowMillis() - lowestFailedAt < TideFeature.retryMs { return }
        let forKey = key
        lowestRunning = true
        Task {
            do {
                _ = try await store.findLowest(forKey, lat: pos.lat, lng: pos.lng)
                lowestRunning = false
                if forKey == key {
                    refreshChip()
                    tidesChanged()
                }
            } catch {
                lowestRunning = false
                lowestFailedAt = TideMath.nowMillis()
                DiagnosticLog.log(TideFeature.tag, "chart datum failed: \(error.localizedDescription)")
            }
        }
    }

    private func refresh(manual: Bool) {
        if loading { return }
        guard let pos = position() else {
            if manual { app.toast("No position yet — turn on GPS") }
            return
        }
        let forKey = key
        loading = true
        Task {
            do {
                _ = try await store.refresh(forKey, lat: pos.lat, lng: pos.lng)
                lastError = nil
                if manual { app.toast("Tides updated") }
            } catch {
                lastFailedAt = TideMath.nowMillis()
                let message = TideText.message(error, fallback: "Couldn't download tides")
                lastError = message
                if manual { app.toast(message) }
                DiagnosticLog.log(TideFeature.tag, "tide refresh failed: \(error.localizedDescription)")
            }
            loading = false
            if forKey != key { return }
            refreshChip()
            tidesChanged()
            ensureLowest()
        }
    }

    /// Tide heights changed: depth readouts that include the tide should update.
    private func tidesChanged() {
        revision += 1
        app.layers.refreshCrosshairDepth()
    }

    // Chip --------------------------------------------------------------------------------------

    func refreshChip() {
        let s = settings
        let now = TideMath.nowMillis()
        if !s.showChip || s.mode == .off || app.navigation.isNavigating || picking {
            if chipText != nil { chipText = nil }
            return
        }
        var where_ = ""
        if let name = place?.name {
            where_ = (name.count > 14 ? String(name.prefix(13)) + "…" : name) + " "
        }
        let f = format
        let text: String
        switch s.mode {
        case .manual:
            text = "Tide \(f.tideHeight(s.manualHeightM)) (manual)"
        default:
            let fc = forecast
            if let curve = fc?.curve, let height = s.shownHeight(fc, now) {
                let rate = curve.rateMPerHour(now) ?? 0.0
                let arrow = TideText.arrow(rate)
                var next = ""
                if let e = curve.nextExtremes(from: now, count: 1).first {
                    next = " • \(e.isHigh ? "H" : "L") \(TideText.hourMinute(e.timeMillis))"
                }
                text = "\(where_.isEmpty ? "Tide " : where_)\(arrow) \(f.tideHeight(height))\(next)"
            } else {
                text = !s.locationChosen ? "Tides: tap to set up" : "\(where_)Tide —"
            }
        }
        if chipText != text { chipText = text }
    }

    private func update(_ updated: TideSettings) {
        settings = updated
        TideSettingsRepository.save(updated)
    }
}

/// Shared text helpers (Android's SimpleDateFormat patterns, en_US, local time).
enum TideText {
    static func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = pattern
        return f
    }

    static func string(_ millis: Int64, _ pattern: String) -> String { formatter(pattern).string(from: TideMath.date(millis)) }

    static func hourMinute(_ millis: Int64) -> String { string(millis, "HH:mm") }
    static func dayMonthYear(_ millis: Int64) -> String { string(millis, "d MMM yyyy") }
    static func dayMonth(_ millis: Int64) -> String { string(millis, "EEE d MMM") }

    static func arrow(_ rate: Double) -> String { rate > 0.02 ? "▲" : (rate < -0.02 ? "▼" : "■") }

    static func message(_ error: Error, fallback: String) -> String {
        if let e = error as? LocalizedError, let d = e.errorDescription, !d.isEmpty { return d }
        let d = error.localizedDescription
        return d.isEmpty ? fallback : d
    }

    /// "1h 05m" or "45m".
    static func inHours(_ ms: Int64) -> String {
        let minutes = Int((Double(abs(ms)) / 60_000.0).rounded())
        return minutes >= 60 ? "\(minutes / 60)h \(String(format: "%02d", minutes % 60))m" : "\(minutes)m"
    }

    static func size(_ bytes: Int64) -> String {
        bytes < 1024 * 1024 ? "\((bytes + 1023) / 1024) KB" : String(format: "%.1f MB", Double(bytes) / 1_048_576.0)
    }

    static func compass(_ degrees: Double) -> String {
        ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int((degrees + 22.5) / 45) % 8]
    }

    /// Local midnight `offset` days from `now`'s day.
    static func startOfDay(_ now: Int64, _ offset: Int) -> Int64 {
        let cal = Calendar.current
        let start = cal.startOfDay(for: TideMath.date(now))
        let day = cal.date(byAdding: .day, value: offset, to: start) ?? start
        return TideMath.millis(day)
    }
}

// The chip -------------------------------------------------------------------------------------

/// The "Tide ▼ 2.50 m • L 14:44" chip, top right under the top bar. Tap opens the tide screen.
/// While a tide spot is being picked on the map it shows the "Pick a tide spot" banner instead
/// (full width), so place this overlay across the top, under the top bar.
struct TideChipOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        TideChipContent(feature: app.tides)
    }
}

private struct TideChipContent: View {
    @ObservedObject var feature: TideFeature

    var body: some View {
        if feature.picking {
            TidePickBanner(feature: feature)
                .padding(.horizontal, 8)
        } else if let text = feature.chipText {
            HStack {
                Spacer(minLength: 0)
                Button { feature.showTides() } label: {
                    Text(text)
                        .font(.system(size: 13))
                        .lineLimit(1)
                        .foregroundStyle(Theme.textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color(hex: "#E6111827"), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.border, lineWidth: 1))
                        .shadow(color: .black.opacity(0.3), radius: 3, y: 1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Tide — open the tide screen")
            }
            .padding(.trailing, 10)
        }
    }
}

/// Shown while picking a tide spot on the map.
private struct TidePickBanner: View {
    @ObservedObject var feature: TideFeature

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Pick a tide spot")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Text("Put the centre dot on the water, then save.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textMuted)
            HStack(spacing: 8) {
                Button { feature.endMapPick() } label: {
                    Text("Cancel").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                Button { feature.saveMapPick() } label: {
                    Text("Save this spot").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
            }
            .tint(Theme.teal)
            .padding(.top, 8)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(hex: "#F0111827"), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.teal, lineWidth: 2))
        .shadow(color: .black.opacity(0.35), radius: 6, y: 2)
    }
}
