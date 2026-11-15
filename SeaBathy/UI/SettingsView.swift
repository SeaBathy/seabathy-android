import SwiftUI

/// Settings (Android ui/SettingsPanel.kt), same sections, rows and wording.
struct SettingsView: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        SettingsContent(app: app, settings: app.settings, navMarks: app.navigation.navMarks)
    }
}

private struct SettingsContent: View {
    let app: AppModel
    @ObservedObject var settings: AppSettings
    @ObservedObject var navMarks: NavMarksController

    static let colourNames = ["Marine teal", "Green", "Orange", "Red", "Purple", "White"]
    static let colourValues = ["#19C6B3", "#22C55E", "#F97316", "#EF4444", "#A855F7", "#F4F6F8"]
    static let coordinateSizes: [Double] = [11, 12, 13, 14, 15, 16]
    static let depthSizes: [Double] = [10, 11, 12, 13, 14, 15, 16]
    static let privacyPolicyURL = URL(string: "https://seabathy.com/privacy")!

    var body: some View {
        SheetScaffold(title: "Settings") {
            List {
                Section("Map display") {
                    colourPicker("Crosshair colour", selection: Binding(
                        get: { settings.visual.cursorColor }, set: { settings.visual.cursorColor = $0 }))
                    colourPicker("Coordinate colour", selection: Binding(
                        get: { settings.visual.coordinateColor }, set: { settings.visual.coordinateColor = $0 }))
                    Picker("Coordinate format", selection: $settings.coordinateMode) {
                        ForEach(Array(CoordinateFormatter.modes.enumerated()), id: \.offset) { i, mode in
                            Text(CoordinateFormatter.modeLabels[i]).tag(mode)
                        }
                    }
                    Picker("Crosshair depth", selection: Binding(
                        get: { settings.visual.depthDisplayMode },
                        set: { settings.visual.depthDisplayMode = $0; app.layers.refreshCrosshairDepth() })) {
                        ForEach(Array(DepthDisplayMode.values.enumerated()), id: \.offset) { i, mode in
                            Text(DepthDisplayMode.labels[i]).tag(mode)
                        }
                    }
                    Picker("Coordinate text size", selection: Binding(
                        get: { settings.visual.coordinateTextSize }, set: { settings.visual.coordinateTextSize = $0 })) {
                        ForEach(Self.coordinateSizes, id: \.self) { Text("\(Int($0)) pt").tag($0) }
                    }
                    Picker("Depth text size", selection: Binding(
                        get: { settings.visual.depthTextSize }, set: { settings.visual.depthTextSize = $0 })) {
                        ForEach(Self.depthSizes, id: \.self) { Text("\(Int($0)) pt").tag($0) }
                    }
                    Toggle("Show waypoint names on map", isOn: $settings.showWaypointNames)
                    Toggle("Latitude and longitude grid", isOn: Binding(
                        get: { settings.showGrid },
                        set: { settings.showGrid = $0; app.navigation.grid.setVisible($0) }))
                    Toggle("Buoys, beacons, pipelines (beta)", isOn: Binding(
                        get: { navMarks.visible },
                        set: { wanted in
                            // The not-for-navigation tick can't show over this sheet: close it first.
                            if wanted { close { navMarks.requestVisible(true) } } else { navMarks.requestVisible(false) }
                        }))
                }

                Section("Map layers") {
                    row("Get seabed depths for this area", "Free depth data, anywhere in the world") { close { app.layers.getSeabedDepths() } }
                    row("Load MBTiles file", "Charts and map packs (.mbtiles)") { close { app.layers.importMbtiles() } }
                    row("Load GeoTIFF", "Depth surveys (.tif)") { close { app.layers.importGeoTiff() } }
                    Picker(selection: Binding(
                        get: { settings.rasterQuality },
                        set: { quality in
                            settings.rasterQuality = quality
                            app.toast("\(quality.label) quality set. Surveys loaded from now on use it; ones already on the map change the next time SeaBathy starts.")
                        })) {
                        ForEach(RasterQuality.allCases) { q in
                            Text("\(q.label) — \(q.detail)").tag(q)
                        }
                    } label: {
                        Text("GeoTIFF quality")
                    }
                }

                Section {
                    row("Survey colours", "Palette, depth range, shading") { close { app.layers.chooseDepthLayerForColours() } }
                } header: {
                    Text("Depth colours")
                } footer: {
                    Text("Colours and depth range for survey layers. Depths outside the range are hidden.")
                }

                Section("Online maps") {
                    row("Online map layers", app.basemap.customBasemapConfigured ? "Custom basemap set" : "Satellite, topo, or your own tile link") {
                        close { app.layers.showOnlineLayers() }
                    }
                }

                Section("Offline data") {
                    row("Offline maps", "Download the area on screen") { close { app.layers.showOfflineMaps() } }
                }

                Section("Navigation") {
                    row("Boat & navigation", "Speed, fuel, draft, units, alarms") { close { app.navigation.showNavSettings() } }
                    row("Saved routes", "Load, go, rename, export") { close { app.navigation.showRoutes() } }
                    row("Tides", "Heights, highs and lows, offline") { close { app.tides.showTides() } }
                    row("Anchor alarm", "Alarm if the boat drags") { close { app.navigation.anchor.showAnchor() } }
                    row("Tracks", "Record where the boat goes, share as GPX") { close { app.navigation.tracks.showTracks() } }
                    row("Sea conditions", "Currents, waves, wind, sea temperature, offline") { close { app.conditions.show() } }
                }

                Section {
                    row("Import GPX", "From a plotter or another app") { close { GpxActions.importGpx(app: app) } }
                    row("Export waypoints and route", "Share as a GPX file") { close { GpxActions.exportAll(app: app, route: app.navigation.planner.points, routeName: app.navigation.planner.savedName ?? "Route") } }
                    row("Export debug log", "What the app did, and any errors") { shareDebugLog() }
                    row("Privacy policy", "No account, no ads, no tracking: what the app sends and why") {
                        UIApplication.shared.open(Self.privacyPolicyURL)
                    }
                    NavigationLink {
                        LicencesView()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Licences and credits")
                            Text("Open-source libraries and data sources").font(.caption).foregroundStyle(Theme.textMuted)
                        }
                    }
                } header: {
                    Text("Data")
                } footer: {
                    Text("If something goes wrong, share this log so it can be fixed.\n\nSeaBathy \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · Not for navigation.")
                }
            }
        }
    }

    private func row(_ title: String, _ detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Theme.textPrimary)
                Text(detail).font(.caption).foregroundStyle(Theme.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
    }

    private func colourPicker(_ title: String, selection: Binding<String>) -> some View {
        Picker(title, selection: selection) {
            ForEach(Array(Self.colourValues.enumerated()), id: \.offset) { i, value in
                Text(Self.colourNames[i]).tag(value)
            }
            if !Self.colourValues.contains(selection.wrappedValue) {
                Text("Custom").tag(selection.wrappedValue)
            }
        }
    }

    /// Settings closes first, as the next screen works on the chart or opens its own sheet.
    private func close(_ then: @escaping () -> Void) {
        app.dismissSheet()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { then() }
    }

    private func shareDebugLog() {
        let url = DiagnosticLog.url
        if FileManager.default.fileExists(atPath: url.path) {
            app.share([url])
        } else {
            app.toast("The debug log is empty")
        }
    }
}

/// Licences and credits (Android assets/licenses.txt), from the bundled licenses.txt.
struct LicencesView: View {
    private var text: String {
        guard let url = Bundle.main.url(forResource: "licenses", withExtension: "txt"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return s
    }

    var body: some View {
        ScrollView {
            Text(text)
                .font(.system(size: 12, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .background(Theme.panelColor)
        .navigationTitle("Licences and credits")
        .navigationBarTitleDisplayMode(.inline)
    }
}
