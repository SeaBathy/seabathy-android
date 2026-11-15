import CoreLocation
import UIKit

/// Extra readings shown with a NavState (Android NavExtras).
struct NavExtras: Equatable {
    /// Water under the boat now, metres (survey depth plus tide), or nil if unknown.
    var depthNowM: Double?
    /// Shallowest water just ahead on the current course, metres, or nil.
    var depthAheadM: Double?
    var tideM: Double?
    var variation: Double
    /// Red banner text for an active alarm, or nil.
    var alarmText: String?
}

/**
 Runs route navigation (Android NavigationController): feeds GPS fixes to the
 NavigationEngine, checks the water under and ahead of the boat, raises alarms,
 draws the active leg and publishes what the HUD shows. The active route is
 saved, so navigation can be resumed after the app is closed.
 */
@MainActor
final class NavSession: ObservableObject {
    unowned let app: AppModel
    private let variation: NavVariation

    @Published private(set) var state: NavState?
    @Published private(set) var extras = NavExtras(depthNowM: nil, depthAheadM: nil, tideM: nil, variation: 0, alarmText: nil)
    @Published private(set) var muted = false

    private let alarms = NavAlarmPlayer()
    private var engine: NavigationEngine?
    private var routeName: String?
    private(set) var lastFix: NavFix?
    private var depthTask: Task<Void, Never>?
    private var depthNowM: Double?
    private var depthAheadM: Double?
    private var aheadDistanceM: Double?

    static let msToKnots = 1.943844
    /// How far ahead to look for shallow water: this much time at the current speed.
    private static let lookaheadS = 60.0
    private static let minLookaheadM = 50.0
    private static let maxLookaheadM = 600.0
    private static let lookaheadSamples = 6
    private enum K {
        static let points = "nav_active_points", target = "nav_active_target", name = "nav_active_name"
    }

    init(app: AppModel, variation: NavVariation) {
        self.app = app
        self.variation = variation
    }

    var isNavigating: Bool { engine != nil }
    private var settings: NavSettings { app.navSettings.settings }

    func start(points: [RoutePoint], name: String?) {
        guard !points.isEmpty else { return }
        engine = NavigationEngine(points: points, settings: settings)
        routeName = name
        alarms.muted = false
        muted = false
        depthNowM = nil
        depthAheadM = nil
        beginBackgroundWork()
        applyScreenOn()
        save()
        if let fix = lastFix {
            onFix(fix)
        } else {
            state = placeholderState()
            extras = makeExtras(nil, fix: nil)
            app.toast("Waiting for GPS…")
        }
    }

    func stop() {
        engine = nil
        routeName = nil
        depthTask?.cancel()
        alarms.clearAll()
        state = nil
        clearMap()
        UIApplication.shared.isIdleTimerDisabled = false
        app.location.setBackgroundNeeded(false, reason: "nav")
        app.location.stop(for: "nav")
        let d = UserDefaults.standard
        d.removeObject(forKey: K.points)
        d.removeObject(forKey: K.target)
        d.removeObject(forKey: K.name)
    }

    func skip() {
        engine?.skip()
        alarms.clear(.offCourse)
        save()
        if let fix = lastFix { onFix(fix) }
    }

    func previous() {
        engine?.previous(lastFix)
        alarms.clear(.offCourse)
        save()
        if let fix = lastFix { onFix(fix) }
    }

    /// Turns alarm sound and vibration off or on; returns whether they are now off.
    @discardableResult
    func toggleMute() -> Bool {
        alarms.muted.toggle()
        muted = alarms.muted
        return alarms.muted
    }

    /// New settings (units, limits, alarms) take effect at once.
    func settingsChanged() {
        engine?.settings = settings
        applyScreenOn()
        if let fix = lastFix, engine != nil { onFix(fix) }
    }

    func onLocation(_ location: CLLocation) {
        let fix = NavFix(lat: location.coordinate.latitude, lng: location.coordinate.longitude,
                         sogKn: location.speed >= 0 ? location.speed * Self.msToKnots : nil,
                         cogTrue: location.course >= 0 ? location.course : nil,
                         timeMillis: Int64(location.timestamp.timeIntervalSince1970 * 1000))
        lastFix = fix
        if engine != nil { onFix(fix) }
    }

    /// Redraws the map lines after a style rebuild.
    func refreshMap() {
        guard let fix = lastFix, let engine else { return }
        drawMap(fix, engine.update(fix))
    }

    /// Offers to carry on with a route that was being followed when the app closed.
    func pendingResume() -> (label: String, resume: () -> Void)? {
        let d = UserDefaults.standard
        guard let raw = d.string(forKey: K.points) else { return nil }
        let points = raw.data(using: .utf8).flatMap { try? JSONDecoder().decode([RoutePoint].self, from: $0) } ?? []
        if points.isEmpty {
            d.removeObject(forKey: K.points)
            return nil
        }
        let name = d.string(forKey: K.name)
        let target = d.object(forKey: K.target) as? Int ?? -1
        return (name ?? "the route", { [weak self] in
            guard let self else { return }
            self.engine = NavigationEngine(points: points, settings: self.settings, startTargetIndex: target >= 0 ? target : nil)
            self.routeName = name
            self.beginBackgroundWork()
            self.applyScreenOn()
            if let fix = self.lastFix {
                self.onFix(fix)
            } else {
                self.state = self.placeholderState()
                self.extras = self.makeExtras(nil, fix: nil)
            }
        })
    }

    // Internals -----------------------------------------------------------------------

    private func beginBackgroundWork() {
        app.location.start(for: "nav")
        app.location.setBackgroundNeeded(true, reason: "nav")
        NavAlarmPlayer.requestNotificationPermission()
    }

    private func onFix(_ fix: NavFix) {
        guard let engine else { return }
        let before = engine.targetIndex
        let st = engine.update(fix)
        if engine.targetIndex != before { save() }
        checkDepth(fix)
        render(fix, st)
    }

    private func render(_ fix: NavFix, _ st: NavState) {
        let s = settings
        alarms.soundOn = s.alarmSound
        alarms.vibrateOn = s.alarmVibrate
        let format = s.format

        if let label = st.arrivedLabel {
            let message = st.finished ? "Arrived: \(label) (end of route)" : "Arrived: \(label)"
            if s.arrivalAlarm { alarms.trigger(st.finished ? .finished : .arrival, message: message) }
            app.toast(message)
        }

        let minDepth = s.minimumDepthM
        let shallowNow = depthNowM.map { $0 < minDepth } ?? false
        let shallowAhead = depthAheadM.map { $0 < minDepth } ?? false

        var alarmText: String?
        if shallowNow {
            alarmText = "SHALLOW  \(format.depth(depthNowM)) (need \(format.depth(minDepth)))"
        } else if shallowAhead {
            alarmText = "SHALLOW AHEAD  \(format.depth(depthAheadM)) in \(format.offset(aheadDistanceM ?? 0))"
        } else if st.offCourse {
            alarmText = "OFF COURSE  \(format.offset(st.xteM)) \(st.xteM > 0 ? "right" : "left") of the line"
        }

        if st.offCourse && s.offCourseAlarm {
            alarms.trigger(.offCourse, message: "OFF COURSE  \(format.offset(st.xteM)) \(st.xteM > 0 ? "right" : "left") of the line")
        } else {
            alarms.clear(.offCourse)
        }
        if (shallowNow || shallowAhead) && s.shallowAlarm {
            alarms.trigger(.shallow, message: alarmText ?? "SHALLOW")
        } else {
            alarms.clear(.shallow)
        }

        state = st
        extras = makeExtras(alarmText, fix: fix)
        drawMap(fix, st)
    }

    /// Samples the survey under the boat and along its course for the next minute or so.
    private func checkDepth(_ fix: NavFix) {
        if depthBusy { return }
        depthBusy = true
        depthTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.depthBusy = false }
            // No tide is added here: the tide forecast is a separate feature on this platform.
            func water(_ survey: Double?) -> Double? { survey.map { -$0 } }
            self.depthNowM = water(self.app.depthSample(lat: fix.lat, lng: fix.lng))
            if let cog = fix.cogTrue, let sog = fix.sogKn, sog > 0.5 {
                let lookM = min(max(sog / Self.msToKnots * Self.lookaheadS, Self.minLookaheadM), Self.maxLookaheadM)
                var shallowest: Double?
                for step in 1...Self.lookaheadSamples {
                    let d = lookM * Double(step) / Double(Self.lookaheadSamples)
                    let p = NavGeo.offset(fix.lat, fix.lng, bearing: cog, distanceM: d)
                    guard let w = water(self.app.depthSample(lat: p.lat, lng: p.lng)) else { continue }
                    shallowest = shallowest.map { min($0, w) } ?? w
                }
                self.depthAheadM = shallowest
                self.aheadDistanceM = lookM
            } else {
                self.depthAheadM = nil
                self.aheadDistanceM = nil
            }
            await Task.yield()
            // Show the new depths (and raise a shallow alarm) without waiting for the next fix.
            guard let engine = self.engine, let last = self.lastFix else { return }
            self.render(last, engine.update(last))
        }
    }

    private var depthBusy = false

    private func makeExtras(_ alarmText: String?, fix: NavFix?) -> NavExtras {
        NavExtras(depthNowM: depthNowM, depthAheadM: depthAheadM,
                  tideM: fix.flatMap { app.tides?.tideHeightNow(lat: $0.lat, lng: $0.lng) },
                  variation: fix.map { variation.variation($0.lat, $0.lng) } ?? 0, alarmText: alarmText)
    }

    private func drawMap(_ fix: NavFix, _ st: NavState) {
        let features = [
            NavGeoJson.lineString([st.origin, st.target], properties: "{\"kind\":\"leg\"}"),
            NavGeoJson.lineString([RoutePoint(lat: fix.lat, lng: fix.lng), st.target], properties: "{\"kind\":\"toTarget\"}"),
            NavGeoJson.point(lat: st.target.lat, lng: st.target.lng)
        ]
        app.map.setGeoJSON(MapLayerIds.navSource, NavGeoJson.featureCollection(features))
    }

    private func clearMap() {
        app.map.setGeoJSON(MapLayerIds.navSource, MapLayerIds.emptyGeoJSON)
    }

    private func applyScreenOn() {
        UIApplication.shared.isIdleTimerDisabled = engine != nil && settings.keepScreenOn
    }

    private func save() {
        guard let engine else { return }
        let d = UserDefaults.standard
        if let data = try? JSONEncoder().encode(engine.points), let raw = String(data: data, encoding: .utf8) {
            d.set(raw, forKey: K.points)
        }
        d.set(engine.targetIndex, forKey: K.target)
        if let name = routeName { d.set(name, forKey: K.name) } else { d.removeObject(forKey: K.name) }
    }

    /// Shown before the first fix arrives.
    private func placeholderState() -> NavState? {
        guard let engine else { return nil }
        let index = max(0, engine.targetIndex)
        let target = engine.points[index]
        return NavState(targetIndex: index, target: target, targetLabel: engine.label(index), pointCount: engine.points.count,
                        origin: target, dtwNm: .nan, btwTrue: .nan, legBearingTrue: .nan, xteM: 0, steerDegrees: nil,
                        sogKn: nil, cogTrue: nil, vmgKn: nil, ttgNextHours: nil, etaNextMillis: nil, remainingNm: .nan,
                        ttgEndHours: nil, etaEndMillis: nil, fuelToEndLitres: nil, offCourse: false, arrivedLabel: nil,
                        finished: false)
    }
}
