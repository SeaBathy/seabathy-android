import Foundation

/// A named route kept for later (Android data/SavedRoute; same JSON field names).
struct SavedRoute: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var points: [RoutePoint]
    var updatedMillis: Int64

    init(id: String, name: String, points: [RoutePoint], updatedMillis: Int64) {
        self.id = id; self.name = name; self.points = points; self.updatedMillis = updatedMillis
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Route"
        points = try c.decodeIfPresent([RoutePoint].self, forKey: .points) ?? []
        updatedMillis = try c.decodeIfPresent(Int64.self, forKey: .updatedMillis) ?? 0
    }
}

/// Every saved route, newest edits first (Android data/RouteLibrary).
@MainActor
final class RouteLibrary: ObservableObject {
    @Published private(set) var routes: [SavedRoute]
    private let store: JSONFileStore<[SavedRoute]>?

    /// `persist: false` keeps routes in memory only (tests).
    init(persist: Bool = true) {
        if persist {
            let s = JSONFileStore<[SavedRoute]>(name: "saved_routes", default: [])
            store = s
            routes = s.load().filter { !$0.points.isEmpty }
        } else {
            store = nil
            routes = []
        }
    }

    var all: [SavedRoute] { routes.sorted { $0.updatedMillis > $1.updatedMillis } }

    func byId(_ id: String) -> SavedRoute? { routes.first { $0.id == id } }

    func isNameTaken(_ name: String, exceptId: String? = nil) -> Bool {
        let wanted = name.trimmingCharacters(in: .whitespaces)
        return routes.contains { $0.id != exceptId && $0.name.caseInsensitiveCompare(wanted) == .orderedSame }
    }

    /// A name not used yet: base, or "base (2)", "(3)"...
    func freeName(_ base: String) -> String {
        var clean = base.trimmingCharacters(in: .whitespaces)
        if clean.isEmpty { clean = "Route" }
        if !isNameTaken(clean) { return clean }
        var n = 2
        while isNameTaken("\(clean) (\(n))") { n += 1 }
        return "\(clean) (\(n))"
    }

    /// Saves a new route, or replaces the one with `id`. Returns it.
    @discardableResult
    func save(id: String?, name: String, points: [RoutePoint]) -> SavedRoute {
        let route = SavedRoute(id: id ?? UUID().uuidString.lowercased(), name: name.trimmingCharacters(in: .whitespaces),
                               points: points, updatedMillis: Int64(Date().timeIntervalSince1970 * 1000))
        if let index = routes.firstIndex(where: { $0.id == route.id }) {
            routes[index] = route
        } else {
            routes.append(route)
        }
        persist()
        return route
    }

    /// Contract entry point: adds a route under a free name.
    func add(name: String, points: [RoutePoint]) {
        guard !points.isEmpty else { return }
        save(id: nil, name: freeName(name), points: points)
    }

    func rename(id: String, name: String) {
        guard let route = byId(id) else { return }
        save(id: id, name: name, points: route.points)
    }

    func delete(id: String) {
        let before = routes.count
        routes.removeAll { $0.id == id }
        if routes.count != before { persist() }
    }

    private func persist() { store?.save(routes) }
}

/**
 The route being planned on the chart (Android map/RouteController): its points,
 which saved route (if any) it is, and the plan summary shown in the route card.
 Saved as it changes under Android's keys, so it survives the app being closed.
 */
final class RoutePlanner {
    private(set) var points: [RoutePoint]
    private(set) var savedId: String?
    private(set) var savedName: String?
    private var gpsSpeedKnots: Double?
    private let defaults: UserDefaults?
    private let settings: () -> NavSettings
    private let variation: (Double, Double) -> Double

    /// Closer than this to the last point is the same point.
    static let samePointM = 5.0

    private enum K {
        static let route = "route", linkId = "route_saved_id", linkName = "route_saved_name"
    }

    /// `defaults: nil` keeps the plan in memory only (tests).
    init(defaults: UserDefaults? = nil, settings: @escaping () -> NavSettings = { NavSettings() },
         variation: @escaping (Double, Double) -> Double = { _, _ in 0 }) {
        self.defaults = defaults
        self.settings = settings
        self.variation = variation
        var loaded: [RoutePoint] = []
        if let raw = defaults?.string(forKey: K.route), let data = raw.data(using: .utf8) {
            if let list = try? JSONDecoder().decode([RoutePoint].self, from: data) {
                loaded = list.filter { $0.lat.isFinite && $0.lng.isFinite }
            } else {
                defaults?.set(raw, forKey: K.route + "_unreadable")
                DiagnosticLog.log("Storage", "could not read route; kept a copy under route_unreadable")
            }
        }
        points = loaded
        savedId = defaults?.string(forKey: K.linkId)
        savedName = defaults?.string(forKey: K.linkName)
    }

    /// nil whenever there is no live speed right now.
    func updateGpsSpeedKnots(_ knots: Double?) {
        if let k = knots, k > 0 { gpsSpeedKnots = k } else { gpsSpeedKnots = nil }
    }

    func add(lat: Double, lng: Double, name: String? = nil) {
        // A double tap, or a tap on the last point again, would add a leg of no length.
        if let last = points.last, NavGeo.distanceM(last.lat, last.lng, lat, lng) < Self.samePointM { return }
        points.append(RoutePoint(lat: lat, lng: lng, name: name))
        changed()
    }

    func undo() {
        if !points.isEmpty { points.removeLast() }
        changed()
    }

    func clear() {
        points.removeAll()
        savedId = nil
        savedName = nil
        changed()
    }

    /// Swaps start and end, e.g. for the trip home.
    func reverse() {
        points.reverse()
        changed()
    }

    func load(_ route: SavedRoute) {
        points = route.points
        savedId = route.id
        savedName = route.name
        changed()
    }

    func markSaved(_ route: SavedRoute) {
        savedId = route.id
        savedName = route.name
        changed()
    }

    /// Forgets the saved-route link when that route is deleted.
    func forgetSaved(_ id: String) {
        if savedId == id {
            savedId = nil
            savedName = nil
            changed()
        }
    }

    func plan() -> RoutePlan { RoutePlan(points: points, speedKn: planSpeed().knots, burnLph: settings().fuelBurnLph) }

    /// The planning speed and where it came from.
    func planSpeed() -> (knots: Double, label: String) {
        let s = settings()
        if s.speedSource == .gps, let gps = gpsSpeedKnots { return (gps, "GPS") }
        if s.speedSource == .gps { return (s.cruiseSpeedKn, "cruise, no GPS fix") }
        return (s.cruiseSpeedKn, "cruise")
    }

    /// The readout shown in the route card, or nil when the card is idle.
    func summary(routeToolActive: Bool, nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String? {
        let s = settings()
        let format = NavFormat(units: s.units)
        let title = savedName ?? "Unsaved route"
        if points.isEmpty {
            return routeToolActive ? "Tap the map to add a point at the centre dot" : nil
        }
        if points.count < 2 { return "\(title) — 1 point, add another" }
        let plan = plan()
        let speed = planSpeed()
        let first = plan.legs[0]
        let legs = plan.legs.count
        let firstVariation = variation(first.from.lat, first.from.lng)
        var eta = ""
        if let hours = plan.totalHours {
            eta = " · ETA \(format.clock(nowMillis + Int64(hours * RoutePlan.hourMs), now: nowMillis))"
        }
        var lines = [
            "\(title) — \(legs) \(legs == 1 ? "leg" : "legs"), \(format.distance(plan.totalNm))",
            "\(format.duration(plan.totalHours)) at \(format.speed(speed.knots)) (\(speed.label))\(eta)",
            "\(legs > 1 ? "1st leg" : "Course") \(format.bearing(first.bearingTrue, variation: firstVariation))"
        ]
        if let fuel = plan.totalFuelLitres {
            let usable = s.usableFuelLitres
            let check: String
            if usable <= 0 {
                check = ""
            } else if fuel > usable {
                check = " · ⚠ more than the usable \(format.fuel(usable))"
            } else {
                check = " · \(format.fuel(usable - fuel)) spare"
            }
            lines.append("Fuel \(format.fuel(fuel))\(check)")
        }
        return lines.joined(separator: "\n")
    }

    private func changed() {
        guard let d = defaults else { return }
        if let data = try? JSONEncoder().encode(points), let raw = String(data: data, encoding: .utf8) {
            d.set(raw, forKey: K.route)
        }
        if let id = savedId { d.set(id, forKey: K.linkId) } else { d.removeObject(forKey: K.linkId) }
        if let name = savedName { d.set(name, forKey: K.linkName) } else { d.removeObject(forKey: K.linkName) }
    }
}
