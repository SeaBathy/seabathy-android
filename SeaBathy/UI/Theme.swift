import SwiftUI
import UIKit

/// The app's fixed graphite theme, same colours as SeaBathy for Android (res/values/colors.xml).
enum Theme {
    static let navy = Color(hex: "#111418")
    static let panelColor = Color(hex: "#181D22")
    static let surface = Color(hex: "#20262D")
    static let surfaceRaised = Color(hex: "#29313A")
    static let border = Color(hex: "#3A434D")
    static let teal = Color(hex: "#19C6B3")
    static let textPrimary = Color(hex: "#F4F6F8")
    static let textMuted = Color(hex: "#AEB7C2")
    static let textSecondary = textMuted
    static let red = Color(hex: "#EF4444")
    static let orange = Color(hex: "#F97316")
    static let yellow = Color(hex: "#EAB308")
    static let green = Color(hex: "#22C55E")
    static let blue = Color(hex: "#3B82F6")
    static let purple = Color(hex: "#A855F7")

    /// The translucent card behind map readouts (Android #AA0A0F1E).
    static let panel = Color(red: 0x0A / 255, green: 0x0F / 255, blue: 0x1E / 255).opacity(0xAA / 255)
    static let mapBackground = navy
    static let mapBackgroundUI = UIColor(hex: "#111418")

    /// GPS button tints (Android GPS_LOCATE_COLOR / GPS_FOLLOW_COLOR).
    static let gpsLocate = Color(hex: "#3B82F6")
    static let gpsFollow = Color(hex: "#22C55E")
}

extension Color {
    /// "#RRGGBB" or "#AARRGGBB" (Android order).
    init(hex: String) {
        self.init(uiColor: UIColor(hex: hex))
    }
}

extension UIColor {
    convenience init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        var v: UInt64 = 0
        Scanner(string: s).scanHexInt64(&v)
        let a, r, g, b: CGFloat
        if s.count == 8 {
            a = CGFloat((v >> 24) & 0xFF) / 255; r = CGFloat((v >> 16) & 0xFF) / 255
            g = CGFloat((v >> 8) & 0xFF) / 255; b = CGFloat(v & 0xFF) / 255
        } else {
            a = 1; r = CGFloat((v >> 16) & 0xFF) / 255; g = CGFloat((v >> 8) & 0xFF) / 255; b = CGFloat(v & 0xFF) / 255
        }
        self.init(red: r, green: g, blue: b, alpha: a)
    }

    /// "#RRGGBB".
    var hexString: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int(r * 255), Int(g * 255), Int(b * 255))
    }
}

// Shared building blocks ----------------------------------------------------------

/// The standard sheet frame: a title, a Done button, dark background. Use for every sheet.
struct SheetScaffold<Content: View>: View {
    let title: String
    var doneLabel: String = "Done"
    @ViewBuilder var content: () -> Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            content()
                .scrollContentBackground(.hidden)
                .background(Theme.panelColor)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(doneLabel) { dismiss() }
                    }
                }
        }
        .tint(Theme.teal)
        .preferredColorScheme(.dark)
    }
}

/// A rounded translucent card for things floating on the map (tide chip, readouts, route card).
struct MapCard<Content: View>: View {
    var padding: CGFloat = 10
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .padding(padding)
            .background(Theme.panelColor.opacity(0.94), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.border.opacity(0.6), lineWidth: 0.5))
            .foregroundStyle(Theme.textPrimary)
    }
}

/// A small capsule button used on map cards (UNDO, CLEAR, GO...).
struct CardButton: View {
    let title: String
    var systemImage: String? = nil
    var tint: Color = Theme.teal
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Theme.surfaceRaised, in: Capsule())
            .foregroundStyle(tint)
        }
        .buttonStyle(.plain)
    }
}

/// A square top-bar icon button (GPS, waypoint, route, measure).
struct TopBarButton: View {
    let systemImage: String
    let label: String
    var tint: Color = Theme.textMuted
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 19, weight: .medium))
                .frame(width: 44, height: 44)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(tint)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// A labelled number field that edits a Double (empty text keeps the old value).
struct NumberField: View {
    let label: String
    @Binding var value: Double
    var unit: String = ""
    var format: String = "%g"
    @State private var text = ""

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            TextField("", text: $text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 110)
                .onAppear { text = String(format: format, value) }
                .onChange(of: text) { new in
                    if let v = Double(new.replacingOccurrences(of: ",", with: ".")), v.isFinite { value = v }
                }
            if !unit.isEmpty { Text(unit).foregroundStyle(Theme.textMuted) }
        }
    }
}
