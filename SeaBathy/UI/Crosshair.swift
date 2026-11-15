import SwiftUI

/// The centre crosshair, as on Android: a cross in the middle of the map in the
/// chosen colour, with the depth under it in a small dark box beneath.
struct Crosshair: View {
    var depthText: String = ""
    var colour: Color = Theme.teal
    var depthTextSize: Double = 12

    var body: some View {
        ZStack {
            Text("\u{271B}")
                .font(.system(size: 18))
                .foregroundStyle(colour)
                .opacity(0.95)
                .shadow(color: .black.opacity(0.6), radius: 1.5)
                .frame(width: 24, height: 24)

            if !depthText.isEmpty {
                Text(depthText)
                    .font(.system(size: depthTextSize + 1, design: .monospaced))
                    .foregroundStyle(Theme.textPrimary)
                    .padding(5)
                    .background(Theme.panel)
                    .offset(y: 28)
                    .fixedSize()
            }
        }
    }
}
