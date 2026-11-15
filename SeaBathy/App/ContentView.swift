import SwiftUI

/// The chart screen: the map, the centre crosshair and the not-for-navigation tag.
struct ContentView: View {
    var body: some View {
        ZStack {
            ChartMapView()
                .ignoresSafeArea()

            Crosshair()
                .allowsHitTesting(false)

            VStack {
                Spacer()
                Text("Not for navigation")
                    .font(.caption2)
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Theme.panel, in: Capsule())
                    .padding(.bottom, 4)
            }
            .allowsHitTesting(false)
        }
        .background(Theme.mapBackground)
    }
}
