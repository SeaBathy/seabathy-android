import XCTest
@testable import SeaBathy

final class TidePlaceStoreTests: XCTestCase {
    private let suite = "TidePlaceStoreTests"
    private var prefs: UserDefaults!

    override func setUp() {
        super.setUp()
        UserDefaults().removePersistentDomain(forName: suite)
        prefs = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testPlacesAreSavedKeptUniqueByNameAndSurviveAReload() {
        let store = TidePlaceStore(defaults: prefs)
        let reef = store.add(name: "Reef", lat: -18.6, lng: 147.2)
        let again = store.add(name: "reef", lat: -18.7, lng: 147.3)
        XCTAssertEqual(again.name, "reef (2)")

        store.rename(id: reef.id, name: "Outer reef")
        let reloaded = TidePlaceStore(defaults: prefs)
        XCTAssertEqual(reloaded.all.map { $0.name }, ["Outer reef", "reef (2)"])
        XCTAssertEqual(reloaded.byId(reef.id)!.lat, -18.6, accuracy: 1e-9)

        reloaded.delete(id: reef.id)
        XCTAssertNil(TidePlaceStore(defaults: prefs).byId(reef.id))
    }

    func testABlankNameGetsADefault() {
        XCTAssertEqual(TidePlaceStore(defaults: prefs).add(name: "  ", lat: 0, lng: 0).name, "Tide spot")
    }

    func testUnreadableSavedDataStartsEmptyInsteadOfCrashing() {
        prefs.set("{not json", forKey: "tide_places")
        XCTAssertEqual(TidePlaceStore(defaults: prefs).all.count, 0)
    }

    func testSettingsKeepAndroidKeys() {
        var s = TideSettings()
        s.mode = .manual
        s.manualHeightM = 1.25
        s.placeId = "abc"
        s.locationChosen = true
        TideSettingsRepository.save(s, prefs)
        XCTAssertEqual(prefs.string(forKey: "tide_mode"), "MANUAL")
        XCTAssertEqual(TideSettingsRepository.load(prefs), s)
        s.placeId = nil
        TideSettingsRepository.save(s, prefs)
        XCTAssertNil(prefs.string(forKey: "tide_place_id"))
    }
}
