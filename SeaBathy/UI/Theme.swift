import SwiftUI
import UIKit

/// Colours shared with SeaBathy for Android (res/values/colors.xml).
enum Theme {
    static let teal = Color(red: 0x19 / 255, green: 0xC6 / 255, blue: 0xB3 / 255)
    static let textPrimary = Color(red: 0xF4 / 255, green: 0xF6 / 255, blue: 0xF8 / 255)
    static let textSecondary = textPrimary.opacity(0.75)
    /// The Android depth readout background, #AA0A0F1E.
    static let panel = Color(red: 0x0A / 255, green: 0x0F / 255, blue: 0x1E / 255).opacity(0xAA / 255)
    /// The blank-basemap background, #111418.
    static let mapBackground = Color(red: 0x11 / 255, green: 0x14 / 255, blue: 0x18 / 255)
    static let mapBackgroundUI = UIColor(red: 0x11 / 255, green: 0x14 / 255, blue: 0x18 / 255, alpha: 1)
}
