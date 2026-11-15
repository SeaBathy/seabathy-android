import CoreLocation
import XCTest

/**
 A tour of every main feature, run on the iPhone simulator in CI. Each test taps
 through one area the way a person would and saves screenshots (exported by CI
 as out/ui/NN-name.png). A missing button is logged and the test carries on, so
 one problem doesn't hide the rest. Tests run in name order and share app state.

 The boat is simulated at the mouth of Sydney Harbour (public, well-known water).
 Tides, sea conditions and seabed depths are fetched from the real services.
 */
final class SeaBathyTour: XCTestCase {
    static let boat = CLLocation(latitude: -33.835, longitude: 151.285)
    static let camera = "--camera=-33.835,151.285,12"

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = [Self.camera]
        if #available(iOS 16.4, *) {
            XCUIDevice.shared.location = XCUILocation(location: Self.boat)
        }
        addUIInterruptionMonitor(withDescription: "System alerts") { alert in
            for label in ["Allow While Using App", "Allow Once", "Allow", "Change to Always Allow", "Keep Only While Using", "OK"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
        _ = app.wait(for: .runningForeground, timeout: 20)
        sleep(4)
    }

    // MARK: - Helpers

    private static var shotIndex = 0

    /// Saves a screenshot named "NN-name".
    func shot(_ name: String) {
        Self.shotIndex += 1
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = String(format: "%02d-%@", Self.shotIndex, name)
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The first element whose label starts with (or contains) `text`.
    func element(_ text: String, contains: Bool = false) -> XCUIElement {
        let op = contains ? "CONTAINS[c]" : "BEGINSWITH[c]"
        let predicate = NSPredicate(format: "label \(op) %@", text)
        let button = app.buttons.matching(predicate).firstMatch
        if button.exists { return button }
        let cell = app.cells.matching(predicate).firstMatch
        if cell.exists { return cell }
        let other = app.descendants(matching: .any).matching(predicate).firstMatch
        return other
    }

    /// Taps a control by its visible text; logs (and carries on) if it never appears.
    @discardableResult
    func tap(_ text: String, contains: Bool = false, wait: TimeInterval = 8) -> Bool {
        let deadline = Date().addingTimeInterval(wait)
        repeat {
            let el = element(text, contains: contains)
            if el.exists && el.isHittable {
                el.tap()
                return true
            }
            if el.exists {
                // Off screen in a list: scroll towards it.
                app.swipeUp(velocity: .slow)
            }
            Thread.sleep(forTimeInterval: 0.4)
        } while Date() < deadline
        XCTFail("Couldn't find \"\(text)\"")
        shot("missing-\(text.prefix(24).replacingOccurrences(of: " ", with: "_"))")
        return false
    }

    /// Flips a SwiftUI toggle by its label.
    func toggle(_ label: String) {
        let sw = app.switches.matching(NSPredicate(format: "label BEGINSWITH[c] %@", label)).firstMatch
        for _ in 0..<6 where !sw.isHittable { app.swipeUp(velocity: .slow) }
        guard sw.exists else { XCTFail("No toggle \"\(label)\""); return }
        sw.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }

    /// The point on the map under the crosshair (the screen centre).
    var centre: XCUICoordinate { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)) }

    func tapMapCentre() {
        centre.tap()
        sleep(1)
    }

    /// Moves the chart a little, so the crosshair sits on a new spot.
    func panMap(dx: CGFloat, dy: CGFloat) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5 + dx, dy: 0.6 + dy))
        from.press(forDuration: 0.05, thenDragTo: to)
        sleep(1)
    }

    func openSettings() {
        tap("Layers")
        sleep(1)
        tap("Settings")
        sleep(2)
    }

    /// Opens a Settings row (Settings closes first, then the next screen opens).
    func settingsRow(_ title: String) {
        openSettings()
        tap(title)
        sleep(2)
    }

    func done() {
        let d = app.navigationBars.buttons["Done"].firstMatch
        if d.waitForExistence(timeout: 3) { d.tap() } else { app.swipeDown(velocity: .fast) }
        sleep(1)
    }

    func closeDrawer() {
        let close = app.buttons["Close"].firstMatch
        if close.exists && close.isHittable { close.tap() }
        sleep(1)
    }

    /// The location prompt is only handled once the app is touched.
    func nudge() { app.tap() }

    // MARK: - The tour

    func test01_StartAndGps() {
        shot("chart-start")
        tap("GPS")
        nudge()
        sleep(3)
        shot("gps-locate")
        tap("GPS")
        sleep(3)
        shot("gps-follow")
        tap("GPS")
        sleep(1)
    }

    func test02_GoToCoordinates() {
        tap("Coordinates")
        sleep(2)
        shot("goto-coordinates")
        app.swipeDown(velocity: .fast)
        sleep(1)
    }

    func test03_DrawerAndBasemap() {
        tap("Layers")
        sleep(1)
        shot("drawer")
        if tap("OpenStreetMap", contains: true) {
            sleep(5)
            closeDrawer()
            shot("basemap-osm")
            tap("Layers")
            tap("Dark", contains: false)
            sleep(4)
        }
        closeDrawer()
    }

    func test04_Settings() {
        openSettings()
        shot("settings-top")
        app.swipeUp(velocity: .fast)
        sleep(1)
        shot("settings-middle")
        app.swipeUp(velocity: .fast)
        sleep(1)
        shot("settings-bottom")
        done()
    }

    func test05_SeabedDepths() {
        settingsRow("Get seabed depths for this area")
        shot("seabed-confirm")
        tap("Download", wait: 6)
        // GMRT builds the file on request; allow it time.
        sleep(60)
        shot("seabed-layer")
    }

    func test06_DepthColours() {
        tap("Layers")
        sleep(1)
        shot("drawer-with-layer")
        if tap("COLOURS") {
            sleep(2)
            shot("depth-colours")
            tap("Reef and shallows", contains: true)
            sleep(2)
            done()
            closeDrawer()
            sleep(3)
            shot("depth-recoloured")
        } else {
            closeDrawer()
        }
    }

    func test07_ThreeD() {
        tap("Layers")
        sleep(1)
        if tap("3D VIEW") {
            sleep(25)
            shot("3d-view")
            tap("Close 3D view", wait: 5)
            sleep(2)
        } else {
            closeDrawer()
        }
    }

    func test08_Tides() {
        settingsRow("Tides")
        sleep(2)
        shot("tides-first")
        tap("Follow me", wait: 4)
        sleep(15)
        shot("tides-sheet")
        app.swipeUp(velocity: .slow)
        shot("tides-sheet-lower")
        done()
        sleep(2)
        shot("tide-chip")
    }

    func test09_SeaConditions() {
        settingsRow("Sea conditions")
        shot("conditions-sheet")
        if tap("Show on the map") {
            sleep(1)
            shot("conditions-layers")
            tap("Current speed")
            sleep(1)
        }
        done()
        sleep(40)
        shot("conditions-current")
        settingsRow("Sea conditions")
        if tap("Show on the map") {
            tap("Wind speed")
        }
        done()
        sleep(20)
        shot("conditions-wind")
    }

    func test10_Waypoint() {
        tap("Waypoint tool")
        tapMapCentre()
        sleep(1)
        shot("new-waypoint")
        tap("Save waypoint")
        sleep(1)
        tap("Waypoint tool")
        sleep(1)
        shot("waypoint-on-map")
        tap("Layers")
        sleep(1)
        app.swipeUp(velocity: .slow)
        shot("waypoint-list")
        closeDrawer()
        tapMapCentre()
        sleep(1)
        shot("waypoint-edit")
        app.swipeDown(velocity: .fast)
        sleep(1)
    }

    func test11_RouteLegsSave() {
        tap("Route tool")
        panMap(dx: 0.1, dy: 0.05)
        tapMapCentre()
        panMap(dx: -0.15, dy: 0.1)
        tapMapCentre()
        panMap(dx: -0.1, dy: -0.12)
        tapMapCentre()
        shot("route-card")
        if tap("LEGS") {
            sleep(2)
            shot("route-legs")
            done()
        }
        if tap("SAVE") {
            sleep(1)
            shot("route-save")
            tap("Save", wait: 4)
            sleep(1)
        }
    }

    func test12_SavedRoutesAndGo() {
        if element("ROUTES").exists { tap("ROUTES") } else { settingsRow("Saved routes") }
        sleep(2)
        shot("saved-routes")
        if tap("Go", wait: 5) {
            sleep(4)
            shot("navigating-hud")
            tap("stop", contains: true, wait: 4)
            sleep(1)
            let confirm = app.alerts.buttons.firstMatch
            if confirm.waitForExistence(timeout: 2) { app.alerts.buttons.element(boundBy: 0).tap() }
        } else {
            done()
        }
        if element("Route tool").exists && element("CLEAR").exists { tap("CLEAR") }
        sleep(1)
        shot("after-navigation")
    }

    func test13_Measure() {
        tap("Measure tool")
        tapMapCentre()
        panMap(dx: 0.2, dy: -0.1)
        tapMapCentre()
        shot("measure")
        tap("CLEAR")
        tap("Measure tool")
    }

    func test14_Track() {
        settingsRow("Tracks")
        shot("tracks-sheet")
        tap("Start recording")
        sleep(2)
        done()
        sleep(4)
        shot("track-chip")
        settingsRow("Tracks")
        tap("Stop and save")
        sleep(2)
        shot("track-saved")
        done()
    }

    func test15_Anchor() {
        settingsRow("Anchor alarm")
        shot("anchor-sheet")
        tap("Drop anchor", contains: true)
        sleep(2)
        shot("anchor-set")
        done()
        sleep(2)
        shot("anchor-chip")
        settingsRow("Anchor alarm")
        tap("Stop anchor alarm")
        sleep(1)
        done()
    }

    func test16_OnlineLayers() {
        settingsRow("Online map layers")
        shot("online-layers")
        if tap("Sentinel", contains: true) {
            sleep(1)
            tap("Add as a layer", wait: 3)
        }
        sleep(1)
        done()
        sleep(6)
        shot("satellite-layer")
    }

    func test17_OfflineMaps() {
        settingsRow("Offline maps")
        shot("offline-maps")
        done()
    }

    func test18_BoatAndNightFilter() {
        settingsRow("Boat & navigation")
        shot("boat-navigation")
        toggle("Night red filter")
        done()
        sleep(2)
        shot("night-filter")
        settingsRow("Boat & navigation")
        toggle("Night red filter")
        done()
    }

    func test19_BuoysAndBeacons() {
        openSettings()
        toggle("Buoys, beacons, pipelines")
        sleep(2)
        shot("navmarks-warning")
        tap("I understand", contains: true)
        tap("Show layer")
        sleep(1)
        done()
        sleep(4)
        shot("navmarks-map")
    }

    func test20_Grid() {
        openSettings()
        toggle("Latitude and longitude grid")
        done()
        sleep(3)
        shot("grid")
        openSettings()
        toggle("Latitude and longitude grid")
        done()
    }

    func test21_GpxExport() {
        settingsRow("Export waypoints and route")
        sleep(3)
        shot("gpx-share")
        let close = app.buttons["Close"].firstMatch
        if close.exists { close.tap() } else { app.swipeDown(velocity: .fast) }
        sleep(1)
    }

    func test22_Licences() {
        openSettings()
        tap("Licences and credits")
        sleep(1)
        shot("licences")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        done()
    }

    func test23_FinalChart() {
        sleep(3)
        shot("chart-final")
    }
}
