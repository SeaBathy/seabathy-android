import SwiftUI

/// The side panel (Android sidePanel): Settings, map layers, waypoints and the basemap.
struct SideDrawer: View {
    @ObservedObject var app: AppModel

    var body: some View {
        GeometryReader { geo in
            let width = min(380, geo.size.width * 0.86)
            ZStack(alignment: .leading) {
                if app.sidePanelOpen {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .onTapGesture { app.sidePanelOpen = false }
                        .transition(.opacity)

                    VStack(spacing: 0) {
                        HStack {
                            Text("Layers").font(.title3.weight(.semibold))
                            Spacer()
                            Button {
                                app.sidePanelOpen = false
                                app.present { SettingsView() }
                            } label: {
                                Label("Settings", systemImage: "gearshape")
                                    .font(.subheadline.weight(.semibold))
                            }
                            .buttonStyle(.bordered)
                            Button { app.sidePanelOpen = false } label: {
                                Image(systemName: "xmark").font(.headline).frame(width: 36, height: 36)
                            }
                            .accessibilityLabel("Close")
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)

                        List {
                            Section("Map layers") { LayerListSection() }
                            Section("Waypoints") { WaypointListSection() }
                            Section("Basemap") { BasemapPickerSection() }
                        }
                        .listStyle(.insetGrouped)
                        .scrollContentBackground(.hidden)
                    }
                    .frame(width: width)
                    .frame(maxHeight: .infinity)
                    .background(Theme.panelColor.ignoresSafeArea())
                    .foregroundStyle(Theme.textPrimary)
                    .transition(.move(edge: .leading))
                    .gesture(DragGesture().onEnded { value in
                        if value.translation.width < -60 { app.sidePanelOpen = false }
                    })
                }
            }
        }
        .environmentObject(app)
    }
}
