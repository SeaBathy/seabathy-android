import Foundation

/**
 The waypoint collection and its persistence (Android data/WaypointStore +
 WaypointRepository). Saved as a JSON list of Waypoint, the same shape as
 Android's "wpts" value. Waypoints saved before icons existed decode with the
 default icon (Waypoint's tolerant decoder does Android's migration).
 */
@MainActor
final class WaypointStore: ObservableObject {
    unowned let app: AppModel
    @Published private(set) var all: [Waypoint]
    private let store: JSONFileStore<[Waypoint]>

    init(app: AppModel) {
        self.app = app
        let s = JSONFileStore<[Waypoint]>(name: "wpts", default: [])
        store = s
        all = s.load()
    }

    func byId(_ id: String) -> Waypoint? { all.first { $0.id == id } }

    /// Whether a waypoint other than `exceptId` already uses this name (any case).
    func isNameTaken(_ name: String, exceptId: String? = nil) -> Bool {
        let wanted = name.trimmingCharacters(in: .whitespaces)
        return all.contains { $0.id != exceptId && $0.name.caseInsensitiveCompare(wanted) == .orderedSame }
    }

    @discardableResult
    func add(name: String, lat: Double, lng: Double, color: String = Waypoint.defaultColor,
             description: String? = nil, icon: String = WaypointIcon.defaultIconId) -> Waypoint {
        let desc = description?.trimmingCharacters(in: .whitespacesAndNewlines)
        let waypoint = Waypoint(id: UUID().uuidString.lowercased(), name: name, lat: lat, lng: lng, color: color,
                                desc: (desc?.isEmpty ?? true) ? nil : desc, icon: icon)
        all.append(waypoint)
        save()
        return waypoint
    }

    func update(_ waypoint: Waypoint) {
        guard let index = all.firstIndex(where: { $0.id == waypoint.id }) else { return }
        all[index] = waypoint
        save()
    }

    func remove(id: String) {
        all.removeAll { $0.id == id }
        save()
    }

    /// The next free "WPT n" name, for a waypoint saved without one.
    func nextDefaultName() -> String {
        var index = 1
        while isNameTaken("WPT \(index)") { index += 1 }
        return "WPT \(index)"
    }

    /// `base`, or "base (2)", "(3)"... when taken (GPX import).
    func uniqueName(_ base: String) -> String {
        if !isNameTaken(base) { return base }
        var n = 2
        while isNameTaken("\(base) (\(n))") { n += 1 }
        return "\(base) (\(n))"
    }

    private func save() { store.save(all) }
}
