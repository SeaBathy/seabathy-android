import Foundation

/// A spot the user wants tides for: a harbour, a reef, a ramp.
struct TidePlace: Codable, Equatable, Identifiable {
    let id: String
    var name: String
    let lat: Double
    let lng: Double
}

/// The user's saved tide places, in the order they were added (same JSON and key as Android).
final class TidePlaceStore {
    private static let key = "tide_places"
    private let defaults: UserDefaults
    private var places: [TidePlace]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        places = TidePlaceStore.load(defaults)
    }

    var all: [TidePlace] { places }

    func byId(_ id: String?) -> TidePlace? {
        guard let id else { return nil }
        return places.first { $0.id == id }
    }

    @discardableResult
    func add(name: String, lat: Double, lng: Double) -> TidePlace {
        let place = TidePlace(id: UUID().uuidString.lowercased(), name: freeName(name), lat: lat, lng: lng)
        places.append(place)
        persist()
        return place
    }

    func rename(id: String, name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let index = places.firstIndex(where: { $0.id == id }), !clean.isEmpty else { return }
        places[index].name = clean
        persist()
    }

    func delete(id: String) {
        let before = places.count
        places.removeAll { $0.id == id }
        if places.count != before { persist() }
    }

    private func taken(_ name: String) -> Bool {
        places.contains { $0.name.caseInsensitiveCompare(name) == .orderedSame }
    }

    private func freeName(_ base: String) -> String {
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        let clean = trimmed.isEmpty ? "Tide spot" : trimmed
        if !taken(clean) { return clean }
        var n = 2
        while taken("\(clean) (\(n))") { n += 1 }
        return "\(clean) (\(n))"
    }

    private static func load(_ defaults: UserDefaults) -> [TidePlace] {
        guard let raw = defaults.string(forKey: key), let data = raw.data(using: .utf8) else { return [] }
        guard let list = try? JSONDecoder().decode([TidePlace].self, from: data) else { return [] }
        return list.filter { $0.lat.isFinite && $0.lng.isFinite }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(places), let text = String(data: data, encoding: .utf8) else { return }
        defaults.set(text, forKey: TidePlaceStore.key)
    }
}
