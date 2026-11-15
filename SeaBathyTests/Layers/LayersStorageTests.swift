import XCTest
@testable import SeaBathy

// Ported from Android settings/XyzTilePresetsTest, storage/MbtilesWriterTest,
// storage/SeabedDownloadTest, data/DataSourceStoreTest and ui/LayerPanelBinderTest,
// plus a real SQLite round trip of a written MBTiles file.

final class XyzTilePresetsTests: XCTestCase {
    func testEveryPresetIsAValidHttpsTemplateWithCredit() {
        for preset in XyzTilePresets.all {
            XCTAssertNil(XyzTilePresets.validationError(preset.urlTemplate), "\(preset.label) is not a usable template")
            XCTAssertTrue(preset.urlTemplate.hasPrefix("https://"), "\(preset.label) should be served over https")
            XCTAssertFalse(preset.attribution.isEmpty, "\(preset.label) needs an attribution string")
        }
    }

    func testPresetIdsAreUnique() {
        let ids = XyzTilePresets.all.map { $0.id }
        XCTAssertEqual(ids.count, Set(ids).count)
    }

    func testSatelliteIsOfferedWithADefault() {
        XCTAssertFalse(XyzTilePresets.satellite.isEmpty)
        XCTAssertEqual(XyzTilePresets.defaultSatellite.category, .satellite)
    }

    func testLookupById() {
        XCTAssertNotNil(XyzTilePresets.byId(XyzTilePresets.all[0].id))
        XCTAssertNil(XyzTilePresets.byId("not-a-preset"))
        XCTAssertNil(XyzTilePresets.byId(nil))
    }

    func testValidationRejectsBadTemplates() {
        XCTAssertEqual(XyzTilePresets.validationError("   "), "Enter an XYZ tile URL")
        XCTAssertEqual(XyzTilePresets.validationError("ftp://tiles/{z}/{x}/{y}.png"), "URL must start with http:// or https://")
        XCTAssertEqual(XyzTilePresets.validationError("https://tiles/{z}/{x}.png"), "URL must contain {z}, {x} and {y}")
    }

    func testValidationAcceptsPlainTemplates() {
        XCTAssertNil(XyzTilePresets.validationError("  https://a/{z}/{x}/{y}.png  "))
        XCTAssertNil(XyzTilePresets.validationError("http://a/{z}/{y}/{x}"))
    }
}

final class MbtilesWriterTests: XCTestCase {
    func testTmsRowConversion() {
        XCTAssertEqual(layersTmsRow(z: 4, xyzY: 0), 15)
        XCTAssertEqual(layersTmsRow(z: 4, xyzY: 15), 0)
        XCTAssertEqual(layersTmsRow(z: 6, xyzY: layersTmsRow(z: 6, xyzY: 21)), 21)
        for z in 0...8 {
            for y in 0..<(1 << z) { XCTAssertEqual(layersTmsRow(z: z, xyzY: y), (1 << z) - 1 - y) }
        }
    }

    func testWrittenFileReadsBack() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("writer-\(UUID().uuidString).mbtiles")
        defer { try? FileManager.default.removeItem(at: file) }
        let bounds = GeoBounds(west: 151, south: -34, east: 151.5, north: -33.5)
        let writer = try MbtilesWriter(url: file)
        try writer.writeMetadata(name: "Test", format: "jpg", bounds: bounds, minZoom: 3, maxZoom: 5, attribution: "Credit")
        try writer.inTransaction {
            try writer.writeTile(z: 5, x: 29, y: 19, data: Data([1, 2, 3]))
        }
        writer.close()

        let db = try LayersMbtilesDatabase(url: file)
        defer { db.close() }
        let meta = db.metadata()
        XCTAssertEqual(meta.minZoom, 3)
        XCTAssertEqual(meta.maxZoom, 5)
        XCTAssertFalse(meta.isVector)
        XCTAssertEqual(meta.attribution, "Credit")
        XCTAssertEqual(meta.bounds, bounds)
        XCTAssertEqual(db.readTile(z: 5, x: 29, y: 19), Data([1, 2, 3]))
        XCTAssertNil(db.readTile(z: 5, x: 29, y: 18))
        XCTAssertNotNil(db.tileBounds(zoom: 5))
    }

    func testANonMbtilesFileIsRefused() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("not-\(UUID().uuidString).mbtiles")
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("hello".utf8).write(to: file)
        XCTAssertThrowsError(try LayersMbtilesDatabase(url: file))
    }
}

final class SeabedDownloadTests: XCTestCase {
    func testSmallAreaIsAskedForAsItIs() {
        let a = SeabedDownload.areaFor(south: -20.2, west: 149.4, north: -19.9, east: 149.7, centreLat: -20.05, centreLng: 149.55)
        XCTAssertEqual(a.south, -20.2, accuracy: 1e-9)
        XCTAssertEqual(a.east, 149.7, accuracy: 1e-9)
        XCTAssertFalse(a.trimmed)
        XCTAssertTrue(a.name.contains("20.05S") && a.name.contains("149.55E"), a.name)
    }

    func testZoomedFarOutAsksForTheMiddle() {
        let a = SeabedDownload.areaFor(south: -40, west: 120, north: 0, east: 170, centreLat: -20, centreLng: 149)
        XCTAssertTrue(a.trimmed)
        XCTAssertEqual(a.east - a.west, 4.0, accuracy: 1e-9)
        XCTAssertEqual((a.east + a.west) / 2, 149.0, accuracy: 1e-9)
    }

    func testDateLineKeepsTheCrosshairsSide() {
        let a = SeabedDownload.areaFor(south: -20, west: 179, north: -18, east: -179, centreLat: -19, centreLng: 179.5)
        XCTAssertEqual(a.east, 180.0, accuracy: 1e-9)
        XCTAssertLessThan(a.west, 180.0)
        let b = SeabedDownload.areaFor(south: -20, west: 179, north: -18, east: -179, centreLat: -19, centreLng: -179.5)
        XCTAssertEqual(b.west, -180.0, accuracy: 1e-9)
    }

    func testLinkAsksForAGeoTiffOfThatArea() {
        let url = SeabedDownload.url(SeabedDownload.Area(south: 28.0, west: -88.5, north: 28.5, east: -88.0, trimmed: false))
        XCTAssertTrue(url.contains("west=-88.50000") && url.contains("north=28.50000") && url.contains("format=geotiff"), url)
    }

    func testTiffMagic() {
        XCTAssertTrue(SeabedDownload.looksLikeTiff(Data([0x49, 0x49, 42, 0])))
        XCTAssertTrue(SeabedDownload.looksLikeTiff(Data([0x4D, 0x4D, 0, 42])))
        XCTAssertFalse(SeabedDownload.looksLikeTiff(Data("<htm".utf8)))
    }
}

final class DataSourceStoreTests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        suiteName = "layers-test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
    }

    func testFreshStoreIsSeededWithTheDefaultPreset() {
        let store = DataSourceStore(defaults: defaults)
        store.load()
        XCTAssertEqual(store.all, [DataSource.defaultPreset])
    }

    func testAddedSourceSurvivesReload() {
        let store = DataSourceStore(defaults: defaults)
        store.load()
        store.add(name: "My Tiles", urlTemplate: "https://example.com/{z}/{x}/{y}.png", attribution: "(c) Example")
        let reloaded = DataSourceStore(defaults: defaults)
        reloaded.load()
        XCTAssertEqual(reloaded.all.count, 2)
        let added = reloaded.all.first { !$0.builtIn }
        XCTAssertEqual(added?.name, "My Tiles")
        XCTAssertEqual(added?.urlTemplate, "https://example.com/{z}/{x}/{y}.png")
        XCTAssertEqual(added?.attribution, "(c) Example")
    }

    func testUserSourceCanBeRemovedButNotTheBuiltIn() {
        let store = DataSourceStore(defaults: defaults)
        store.load()
        let added = store.add(name: "Temp", urlTemplate: "https://example.com/{z}/{x}/{y}.png", attribution: "")
        XCTAssertTrue(store.remove(id: added.id))
        XCTAssertFalse(store.all.contains { $0.id == added.id })
        XCTAssertFalse(store.remove(id: DataSource.defaultPreset.id))
        XCTAssertTrue(store.all.contains { $0.id == DataSource.defaultPreset.id })
        XCTAssertFalse(store.remove(id: "not-a-real-id"))
    }

    func testOverlayStackRoundTrips() {
        let store = OverlayLayerStore(defaults: defaults)
        let layer = OverlayLayer(id: OverlayLayerStore.newLayerId(), kind: .xyz, name: "Tiles",
                                 source: "https://a/{z}/{x}/{y}.png", opacity: 0.7)
        store.save([layer])
        XCTAssertEqual(store.load(), [layer])
    }
}

final class LayerPanelFormatTests: XCTestCase {
    func testSliderStepsAreWholePercent() {
        for step in 0...LayerPanelFormat.opacitySteps {
            XCTAssertEqual(LayerPanelFormat.formatOpacity(Double(step) / Double(LayerPanelFormat.opacitySteps)), "\(step)%")
        }
    }

    func testOutOfRangeOpacityIsClamped() {
        XCTAssertEqual(LayerPanelFormat.formatOpacity(-0.2), "0%")
        XCTAssertEqual(LayerPanelFormat.formatOpacity(1.7), "100%")
    }
}
