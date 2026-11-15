import SwiftUI

/// The centre crosshair, as on Android: a teal cross in the middle of the map,
/// with room underneath for the depth readout (added with the depth layers).
struct Crosshair: View {
    var depthText: String = ""

    var body: some View {
        ZStack {
            Text("\u{271B}")
                .font(.system(size: 18))
                .foregroundStyle(Theme.teal)
                .opacity(0.95)
                .shadow(color: .black.opacity(0.6), radius: 1.5)
                .frame(width: 24, height: 24)

            if !depthText.isEmpty {
                Text(depthText)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Theme.textPrimary)
                    .padding(5)
                    .background(Theme.panel)
                    .offset(y: 28)
            }
        }
    }
}
