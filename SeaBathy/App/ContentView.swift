import SwiftUI

/// The chart screen (Android activity_main.xml): map, top bar, crosshair and depth,
/// tide chip and sea conditions, route/measure cards, navigation HUD, credits line,
/// and the side drawer.
struct ContentView: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        ChartScreen(app: app, nav: app.navSettings, settings: app.settings)
    }
}

/// Split out so the observed objects come from the AppModel.
private struct ChartScreen: View {
    @ObservedObject var app: AppModel
    @ObservedObject var nav: NavSettingsStore
    @ObservedObject var settings: AppSettings

    @State private var initialCamera: AppSettings.Camera?
    @State private var styleURL: URL?

    var body: some View {
        ZStack(alignment: .top) {
            if let initialCamera, let styleURL {
                ChartMapView(controller: app.map, initialCamera: initialCamera, styleURL: styleURL)
                    .ignoresSafeArea()
            } else {
                Theme.mapBackground.ignoresSafeArea()
            }

            if app.crosshairVisible {
                Crosshair(depthText: app.crosshairDepthText,
                          colour: Color(hex: settings.visual.cursorColor),
                          depthTextSize: settings.visual.depthTextSize)
                    .allowsHitTesting(false)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            VStack(spacing: 8) {
                TopBar(app: app, settings: settings)
                if app.navigation.isNavigating {
                    NavHudOverlay()
                }
                HStack(alignment: .top) {
                    Spacer()
                    VStack(alignment: .trailing, spacing: 8) {
                        TideChipOverlay()
                        ConditionsOverlay()
                    }
                }
                .padding(.horizontal, 10)
                Spacer()
                if let hint = app.toolMode.hint {
                    Text(hint)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Theme.panelColor.opacity(0.92), in: RoundedRectangle(cornerRadius: 10))
                        .padding(.horizontal, 16)
                        .allowsHitTesting(false)
                }
                if app.toolMode == .route {
                    RouteCardOverlay()
                } else if app.toolMode == .measure {
                    MeasureCardOverlay()
                }
                BottomCredits(app: app)
            }

            if let toast = app.toastText {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(Theme.surfaceRaised.opacity(0.97), in: RoundedRectangle(cornerRadius: 12))
                        .foregroundStyle(Theme.textPrimary)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 120)
                }
                .transition(.opacity)
                .allowsHitTesting(false)
            }

            if nav.settings.nightFilter {
                NightFilterOverlay().ignoresSafeArea().allowsHitTesting(false)
            }

            SideDrawer(app: app)
        }
        .animation(.easeInOut(duration: 0.2), value: app.toastText)
        .animation(.easeInOut(duration: 0.25), value: app.sidePanelOpen)
        .sheet(item: $app.sheet) { presented in
            presented.content
                .presentationDetents(presented.detents)
                .presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: $app.fullScreen) { presented in
            presented.content
        }
        .alert(item: $app.alertItem) { item in
            if item.actions.count >= 2 {
                let first = item.actions[0], second = item.actions[1]
                return Alert(title: Text(item.title), message: Text(item.message),
                             primaryButton: Self.button(first), secondaryButton: Self.button(second))
            }
            if let only = item.actions.first {
                return Alert(title: Text(item.title), message: Text(item.message), dismissButton: Self.button(only))
            }
            return Alert(title: Text(item.title), message: Text(item.message), dismissButton: .default(Text("OK")))
        }
        .onAppear {
            if initialCamera == nil {
                initialCamera = settings.loadCamera()
                styleURL = app.basemap.initialStyleURL
                DispatchQueue.main.async { app.start() }
            }
        }
    }

    private static func button(_ action: AppModel.AlertAction) -> Alert.Button {
        switch action.role {
        case .some(.destructive): return .destructive(Text(action.title), action: action.handler)
        case .some(.cancel): return .cancel(Text(action.title), action: action.handler)
        default: return .default(Text(action.title), action: action.handler)
        }
    }
}

/// The top bar (Android topBar): drawer button, coordinates, GPS and the three tools.
private struct TopBar: View {
    @ObservedObject var app: AppModel
    @ObservedObject var settings: AppSettings

    var body: some View {
        HStack(spacing: 6) {
            TopBarButton(systemImage: "line.3.horizontal", label: "Layers", tint: Theme.teal) {
                app.sidePanelOpen = true
            }
            CoordinatesButton(app: app, map: app.map, settings: settings)
            GpsButton(gps: app.navigation.gps)
            TopBarButton(systemImage: "mappin.and.ellipse", label: "Waypoint tool",
                         tint: app.toolMode == .waypoint ? Theme.teal : Theme.textMuted) { app.setTool(.waypoint) }
            TopBarButton(systemImage: "point.topleft.down.curvedto.point.bottomright.up", label: "Route tool",
                         tint: app.toolMode == .route ? Theme.teal : Theme.textMuted) { app.setTool(.route) }
            TopBarButton(systemImage: "ruler", label: "Measure tool",
                         tint: app.toolMode == .measure ? Theme.teal : Theme.textMuted) { app.setTool(.measure) }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Theme.navy.opacity(0.94).ignoresSafeArea(edges: .top))
    }
}

private struct CoordinatesButton: View {
    @ObservedObject var app: AppModel
    @ObservedObject var map: MapController
    @ObservedObject var settings: AppSettings

    var body: some View {
        Button {
            app.navigation.showCoordinateEntry()
        } label: {
            Text(CoordinateFormatter.formatPair(map.centre.latitude, map.centre.longitude, mode: settings.coordinateMode))
                .font(.system(size: settings.visual.coordinateTextSize, design: .monospaced))
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .foregroundStyle(Color(hex: settings.visual.coordinateColor))
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.horizontal, 6)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Coordinates: go to coordinates")
    }
}

private struct GpsButton: View {
    @ObservedObject var gps: GpsController

    var body: some View {
        TopBarButton(systemImage: gps.mode == .follow ? "location.fill" : "location", label: "GPS",
                     tint: gps.mode == .off ? Theme.textMuted : (gps.mode == .follow ? Theme.gpsFollow : Theme.gpsLocate)) {
            gps.cycle()
        }
    }
}

/// The credits line under the chart, naming every source shown, plus the not-for-navigation tag.
private struct BottomCredits: View {
    @ObservedObject var app: AppModel

    var body: some View {
        VStack(spacing: 4) {
            if !app.attributionText.isEmpty {
                Text(app.attributionText)
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.textMuted)
                    .lineLimit(2)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
            }
            Text("Not for navigation")
                .font(.caption2)
                .foregroundStyle(Theme.textSecondary)
                .padding(.horizontal, 8).padding(.vertical, 2)
                .background(Theme.panel, in: Capsule())
        }
        .padding(.bottom, 4)
        .allowsHitTesting(false)
    }
}
