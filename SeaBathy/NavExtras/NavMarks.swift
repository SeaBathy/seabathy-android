import MapLibre
import SwiftUI
import UIKit

/**
 Buoys, beacons, lights, pipelines, submarine cables and mooring areas from free
 government data (and OpenStreetMap), bundled in Resources/navmarks (Android
 map/NavMarks.kt). Off at every start, and only shown after the user has ticked,
 each time they turn it on, that it is not for navigation.
 */
@MainActor
final class NavMarksController: ObservableObject {
    unowned let app: AppModel

    @Published private(set) var visible = false

    static let credit = "Nav marks: Transport for NSW, WA DoT, Vic Gov, Qld Gov, AMSA, NOPTA, Geoscience Australia, ACMA, © OpenStreetMap"
    static let notForNavigation = "Not for navigation. Positions may be old or wrong, and many marks are missing."

    private static let marksSource = "navmarks-src"
    private static let linesSource = "navlines-src"
    static let marksLayer = "navmarks"
    private static let pipelinesLayer = "navlines-pipelines"
    private static let cablesLayer = "navlines-cables"
    private static let areaFillLayer = "navlines-area-fill"
    private static let areaLineLayer = "navlines-area-line"
    private static let cablePattern = "navlines-cable-wave"
    private static let tapRadius: CGFloat = 24
    private static var layerIds: [String] { [areaFillLayer, areaLineLayer, pipelinesLayer, cablesLayer, marksLayer] }

    init(app: AppModel) {
        self.app = app
    }

    func start() {
        app.map.onStyleLoaded { [weak self] _ in self?.installLayers() }
        app.basemap.addCredits { [weak self] in
            guard let self, self.visible else { return [] }
            return [NavMarksController.credit]
        }
    }

    /// Turning on always asks for the not-for-navigation tick first; turning off is immediate.
    func requestVisible(_ wanted: Bool) {
        if !wanted {
            setVisible(false)
            return
        }
        guard !visible else { return }
        confirm { [weak self] accepted in
            if accepted { self?.setVisible(true) }
        }
    }

    private func setVisible(_ value: Bool) {
        visible = value
        if let style = app.map.style {
            for id in Self.layerIds { style.layer(withIdentifier: id)?.isVisible = value }
        }
        app.basemap.refreshAttribution()
    }

    /// What was tapped at a screen point, as a title and detail lines, or nil.
    func describe(at point: CGPoint) -> (title: String, detail: String)? {
        guard visible else { return nil }
        let feature = app.map.features(at: point, radius: Self.tapRadius, layers: [Self.marksLayer]).first
            ?? app.map.features(at: point, radius: Self.tapRadius, layers: [Self.pipelinesLayer, Self.cablesLayer]).first
            ?? app.map.features(at: point, radius: 1, layers: [Self.areaFillLayer]).first
        guard let feature else { return nil }
        func text(_ key: String) -> String? {
            guard let value = feature.attribute(forKey: key) else { return nil }
            let s = (value as? String) ?? "\(value)"
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty || trimmed == "null" ? nil : trimmed
        }
        let title: String
        if let label = text("label") {
            title = label
        } else {
            switch text("kind") {
            case "pipeline": title = "Pipeline"
            case "cable": title = "Submarine cable"
            case "area_anchorage": title = "Anchorage"
            case "area_mooring": title = "Mooring area"
            case "area_marina": title = "Marina"
            case "area_channel": title = "Shipping channel"
            default: title = "Mark"
            }
        }
        let detail = [text("name"), text("src").map { "Source: \($0)" }, Self.notForNavigation]
            .compactMap { $0 }
            .joined(separator: "\n")
        return (title, detail)
    }

    // Map ------------------------------------------------------------------------------

    private func installLayers() {
        guard let style = app.map.style,
              let marksURL = Bundle.main.url(forResource: "navmarks", withExtension: "geojson", subdirectory: "navmarks"),
              let linesURL = Bundle.main.url(forResource: "navlines", withExtension: "geojson", subdirectory: "navmarks") else {
            DiagnosticLog.log("NavMarks", "bundled nav marks not found")
            return
        }
        for key in NavMarkIcons.keys { style.setImage(NavMarkIcons.draw(key), forName: key) }
        style.setImage(NavMarkIcons.cableWave(), forName: Self.cablePattern)

        let lines: MLNSource
        if let existing = style.source(withIdentifier: Self.linesSource) {
            lines = existing
        } else {
            let source = MLNShapeSource(identifier: Self.linesSource, url: linesURL, options: nil)
            style.addSource(source)
            lines = source
        }
        let marks: MLNSource
        if let existing = style.source(withIdentifier: Self.marksSource) {
            marks = existing
        } else {
            let source = MLNShapeSource(identifier: Self.marksSource, url: marksURL, options: nil)
            style.addSource(source)
            marks = source
        }

        let areaKinds = ["area_anchorage", "area_mooring", "area_marina", "area_channel"]
        let areaColour = MapExpr.matchColour("kind", [("area_channel", "#64748B"), ("area_mooring", "#0EA5E9"), ("area_marina", "#0EA5E9")],
                                              fallback: "#7C3AED")

        let fill = MLNFillStyleLayer(identifier: Self.areaFillLayer, source: lines)
        fill.predicate = NSPredicate(format: "kind IN %@", areaKinds)
        fill.fillColor = areaColour
        fill.fillOpacity = NavExtrasStyle.number(0.12)

        let areaLine = MLNLineStyleLayer(identifier: Self.areaLineLayer, source: lines)
        areaLine.predicate = NSPredicate(format: "kind IN %@", areaKinds)
        areaLine.lineColor = areaColour
        areaLine.lineWidth = NavExtrasStyle.number(1.5)
        areaLine.lineDashPattern = NSExpression(forConstantValue: [3, 2])

        let pipelines = MLNLineStyleLayer(identifier: Self.pipelinesLayer, source: lines)
        pipelines.predicate = NavExtrasStyle.kind("pipeline")
        pipelines.lineColor = NavExtrasStyle.colour("#111111")
        pipelines.lineWidth = MapExpr.byZoom([(5, 1.5), (12, 3)])
        pipelines.lineDashPattern = NSExpression(forConstantValue: [0.1, 2])
        pipelines.lineCap = NavExtrasStyle.text("round")

        let cables = MLNLineStyleLayer(identifier: Self.cablesLayer, source: lines)
        cables.predicate = NavExtrasStyle.kind("cable")
        cables.linePattern = NavExtrasStyle.text(Self.cablePattern)
        cables.lineWidth = NavExtrasStyle.number(7)

        let symbols = MLNSymbolStyleLayer(identifier: Self.marksLayer, source: marks)
        symbols.iconImageName = NSExpression(forKeyPath: "icon")
        symbols.iconAnchor = NavExtrasStyle.text("bottom")
        symbols.iconAllowsOverlap = NSExpression(forConstantValue: true)
        symbols.iconScale = MapExpr.byZoom([(6, 0.5), (10, 0.8), (14, 1.15), (17, 1.5)])

        // Just over the imported layers, under the grid, tracks, routes and waypoints.
        var below = MapLayerIds.overlayAnchor
        for layer in [fill, areaLine, pipelines, cables, symbols] as [MLNStyleLayer] {
            layer.isVisible = visible
            NavExtrasStyle.insert(layer, above: below, map: app.map)
            below = layer.identifier
        }
    }

    // The not-for-navigation tick ------------------------------------------------------------

    /// Shown over the Settings sheet with UIKit, since the toggle lives in a sheet.
    /// Shown as the app's own sheet: a UIKit page sheet raised over (or just after) the
    /// Settings sheet was silently refused, so the layer could never be turned on.
    private func confirm(_ onResult: @escaping (Bool) -> Void) {
        app.present(detents: [.medium, .large]) {
            NavMarksConfirmView { [weak app] accepted in
                app?.dismissSheet()
                onResult(accepted)
            }
            .interactiveDismissDisabled(true)
        }
    }
}

private final class NavMarksHostHolder {
    weak var host: UIViewController?
}

/// Android MainActivity.confirmNavMarks, same wording.
private struct NavMarksConfirmView: View {
    let onDone: (Bool) -> Void
    @State private var understood = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("This layer shows buoys, beacons, lights, moorings, pipelines and submarine cables gathered from free government data and OpenStreetMap.")
                    Text("It is NOT a nautical chart and must not be used for navigation. Marks can be moved, removed or added at any time, positions may be old or wrong, many marks are missing, and some types are guessed from the data. Always use official charts, notices to mariners and your own eyes.")
                    Button {
                        understood.toggle()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: understood ? "checkmark.square.fill" : "square")
                                .font(.title3)
                                .foregroundStyle(understood ? Theme.teal : Theme.textMuted)
                            Text("I understand this is not for navigation")
                                .foregroundStyle(Theme.textPrimary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .font(.callout)
                .foregroundStyle(Theme.textPrimary)
                .padding(20)
            }
            .background(Theme.panelColor)
            .navigationTitle("Not for navigation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDone(false) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Show layer") { onDone(true) }
                        .disabled(!understood)
                }
            }
        }
        .tint(Theme.teal)
        .preferredColorScheme(.dark)
    }
}

/**
 The mark icons, drawn the way they look from the boat in IALA region A: beacons
 as a pile with their topmark, buoys as a floating shape (can for port, cone for
 starboard, pillar for the rest), lights as a post with a lamp. Keys are
 "category|structure". Drawn in a 34 x 40 point box at 2x.
 */
enum NavMarkIcons {
    static let keys = [
        "card_e|beacon", "card_e|buoy", "card_n|beacon", "card_n|buoy", "card_s|beacon", "card_s|buoy", "card_w|beacon", "card_w|buoy",
        "isolated|beacon", "isolated|buoy", "isolated|virtual", "lead|beacon", "lead|light", "lighthouse|light", "light|light",
        "mooring|beacon", "mooring|buoy", "other|beacon", "other|buoy", "other|other", "other|virtual", "port|beacon", "port|buoy",
        "port|virtual", "safe|beacon", "safe|buoy", "special|beacon", "special|buoy", "special|virtual", "stbd|beacon", "stbd|buoy",
        "stbd|virtual", "wreck|other", "pref_port|beacon", "pref_port|buoy", "pref_stbd|beacon", "pref_stbd|buoy"
    ]

    private static let red = UIColor(hex: "#E3262F")
    private static let green = UIColor(hex: "#17A34A")
    private static let yellow = UIColor(hex: "#F5C518")
    private static let black = UIColor(hex: "#111111")
    private static let grey = UIColor(hex: "#94A3B8")
    private static let orange = UIColor(hex: "#F97316")
    private static let magenta = UIColor(hex: "#D6249F")
    private static let water = UIColor(hex: "#38BDF8")

    private static let topmarked: Set<String> = ["port", "stbd", "pref_port", "pref_stbd", "card_n", "card_e", "card_s", "card_w", "isolated", "safe", "special"]

    private static func bodyColours(_ cat: String) -> [UIColor] {
        switch cat {
        case "port": return [red]
        case "stbd": return [green]
        case "pref_port": return [red, green, red]
        case "pref_stbd": return [green, red, green]
        case "card_n": return [black, yellow]
        case "card_s": return [yellow, black]
        case "card_e": return [black, yellow, black]
        case "card_w": return [yellow, black, yellow]
        case "isolated": return [black, red, black]
        case "safe": return [red, .white, red]
        case "special": return [yellow]
        case "lead": return [orange]
        case "mooring": return [UIColor(hex: "#F8FAFC")]
        default: return [grey]
        }
    }

    private static func rect(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat) -> CGRect {
        CGRect(x: l, y: t, width: r - l, height: b - t)
    }

    private static func outline(_ c: CGContext, _ path: CGPath) {
        c.saveGState()
        c.addPath(path)
        c.setStrokeColor(black.cgColor)
        c.setLineWidth(1)
        c.setLineJoin(.round)
        c.strokePath()
        c.restoreGState()
    }

    private static func fill(_ c: CGContext, _ path: CGPath, _ colour: UIColor) {
        c.addPath(path)
        c.setFillColor(colour.cgColor)
        c.fillPath()
    }

    private static func fillRect(_ c: CGContext, _ r: CGRect, _ colour: UIColor) {
        c.setFillColor(colour.cgColor)
        c.fill(r)
    }

    private static func strokeRect(_ c: CGContext, _ r: CGRect) {
        outline(c, CGPath(rect: r, transform: nil))
    }

    private static func tri(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ s: CGFloat, up: Bool, _ colour: UIColor) {
        let p = CGMutablePath()
        if up {
            p.move(to: CGPoint(x: x, y: y - s)); p.addLine(to: CGPoint(x: x + s, y: y + s * 0.7)); p.addLine(to: CGPoint(x: x - s, y: y + s * 0.7))
        } else {
            p.move(to: CGPoint(x: x, y: y + s)); p.addLine(to: CGPoint(x: x + s, y: y - s * 0.7)); p.addLine(to: CGPoint(x: x - s, y: y - s * 0.7))
        }
        p.closeSubpath()
        fill(c, p, colour)
        outline(c, p)
    }

    private static func ball(_ c: CGContext, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ colour: UIColor) {
        let p = CGPath(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2), transform: nil)
        fill(c, p, colour)
        outline(c, p)
    }

    private static func bands(_ c: CGContext, _ r: CGRect, _ colours: [UIColor]) {
        let h = r.height / CGFloat(colours.count)
        for (i, colour) in colours.enumerated() {
            fillRect(c, CGRect(x: r.minX, y: r.minY + CGFloat(i) * h, width: r.width, height: h + 0.5), colour)
        }
    }

    private static func line(_ c: CGContext, _ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ colour: UIColor, _ width: CGFloat) {
        c.saveGState()
        c.setStrokeColor(colour.cgColor)
        c.setLineWidth(width)
        c.move(to: CGPoint(x: x1, y: y1))
        c.addLine(to: CGPoint(x: x2, y: y2))
        c.strokePath()
        c.restoreGState()
    }

    private static func topmark(_ c: CGContext, _ cat: String, _ x: CGFloat, _ y: CGFloat) {
        switch cat {
        case "card_n": tri(c, x, y - 7, 4, up: true, black); tri(c, x, y + 1, 4, up: true, black)
        case "card_s": tri(c, x, y - 7, 4, up: false, black); tri(c, x, y + 1, 4, up: false, black)
        case "card_e": tri(c, x, y - 8, 4, up: true, black); tri(c, x, y + 2, 4, up: false, black)
        case "card_w": tri(c, x, y - 6, 4, up: false, black); tri(c, x, y + 1, 4, up: true, black)
        case "isolated": ball(c, x, y - 7, 3.2, black); ball(c, x, y, 3.2, black)
        case "safe": ball(c, x, y - 3, 4, red)
        case "special":
            line(c, x - 4, y - 7, x + 4, y + 1, yellow, 3)
            line(c, x + 4, y - 7, x - 4, y + 1, yellow, 3)
        case "port", "pref_port":
            let r = rect(x - 4.5, y - 8, x + 4.5, y + 1)
            fillRect(c, r, red)
            strokeRect(c, r)
        case "stbd", "pref_stbd":
            tri(c, x, y - 3, 5.5, up: true, green)
        default:
            break
        }
    }

    static func draw(_ key: String) -> UIImage {
        let parts = key.split(separator: "|").map(String.init)
        let cat = parts.first ?? "other"
        let structure = parts.count > 1 ? parts[1] : "other"
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        return UIGraphicsImageRenderer(size: CGSize(width: 34, height: 40), format: format).image { ctx in
            let c = ctx.cgContext
            let x: CGFloat = 17, base: CGFloat = 34
            if cat == "wreck" {
                line(c, 6, 22, 28, 22, black, 2)
                line(c, 12, 15, 12, 29, black, 2)
                line(c, 17, 13, 17, 31, black, 2)
                line(c, 22, 15, 22, 29, black, 2)
            } else if structure == "light" {
                if cat == "lighthouse" {
                    let p = CGMutablePath()
                    p.move(to: CGPoint(x: x - 4, y: base)); p.addLine(to: CGPoint(x: x - 2.5, y: 18))
                    p.addLine(to: CGPoint(x: x + 2.5, y: 18)); p.addLine(to: CGPoint(x: x + 4, y: base))
                    p.closeSubpath()
                    fill(c, p, .white); outline(c, p)
                    let lamp = rect(x - 3.5, 15, x + 3.5, 19)
                    fillRect(c, lamp, red); strokeRect(c, lamp)
                } else {
                    let post = rect(x - 1.5, 20, x + 1.5, base)
                    fillRect(c, post, UIColor(hex: "#334155")); strokeRect(c, post)
                    ball(c, x, 19, 3.2, cat == "lead" ? orange : UIColor(hex: "#FDE047"))
                    fillRect(c, rect(x - 5, base - 1, x + 5, base + 1), black)
                }
            } else if structure == "virtual" {
                c.saveGState()
                c.setStrokeColor(magenta.cgColor)
                c.setLineWidth(2)
                c.setLineDash(phase: 0, lengths: [3, 2])
                c.strokeEllipse(in: CGRect(x: x - 9, y: 22 - 9, width: 18, height: 18))
                c.restoreGState()
                topmark(c, cat, x, 25)
            } else if structure == "beacon" {
                let pile = rect(x - 2, 15, x + 2, base)
                bands(c, pile, bodyColours(cat)); strokeRect(c, pile)
                if topmarked.contains(cat) {
                    topmark(c, cat, x, 12)
                } else if cat == "lead" {
                    tri(c, x, 9, 5, up: true, orange)
                } else {
                    ball(c, x, 11, 3, grey)
                }
                fillRect(c, rect(x - 6, base - 1, x + 6, base + 1), black)
            } else if structure == "buoy" {
                let shape = CGMutablePath()
                switch cat {
                case "port":
                    shape.addRect(rect(x - 7, base - 15, x + 7, base - 2))
                case "stbd":
                    shape.move(to: CGPoint(x: x - 8, y: base - 2)); shape.addLine(to: CGPoint(x: x, y: base - 18))
                    shape.addLine(to: CGPoint(x: x + 8, y: base - 2)); shape.closeSubpath()
                case "mooring", "other":
                    shape.addEllipse(in: CGRect(x: x - 7, y: base - 8 - 7, width: 14, height: 14))
                default:
                    shape.move(to: CGPoint(x: x - 8, y: base - 2)); shape.addLine(to: CGPoint(x: x - 3, y: base - 20))
                    shape.addLine(to: CGPoint(x: x + 3, y: base - 20)); shape.addLine(to: CGPoint(x: x + 8, y: base - 2))
                    shape.closeSubpath()
                }
                c.saveGState()
                c.addPath(shape)
                c.clip()
                bands(c, rect(x - 9, base - 21, x + 9, base - 1), bodyColours(cat))
                c.restoreGState()
                outline(c, shape)
                if !["port", "stbd", "mooring", "other"].contains(cat) { topmark(c, cat, x, base - 24) }
                let wave = CGMutablePath()
                wave.move(to: CGPoint(x: x - 11, y: base))
                wave.addQuadCurve(to: CGPoint(x: x, y: base), control: CGPoint(x: x - 5, y: base - 2))
                wave.addQuadCurve(to: CGPoint(x: x + 11, y: base), control: CGPoint(x: x + 5, y: base + 2))
                c.saveGState()
                c.addPath(wave)
                c.setStrokeColor(water.cgColor)
                c.setLineWidth(1.5)
                c.strokePath()
                c.restoreGState()
            } else {
                ball(c, x, 22, 5, grey)
            }
        }
    }

    /// A short red wave, repeated along submarine cables.
    static func cableWave() -> UIImage {
        let w: CGFloat = 16, h: CGFloat = 7
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            let c = ctx.cgContext
            let p = CGMutablePath()
            for i in 0...Int(w) {
                let y = h / 2 + CGFloat(sin(Double(i) / Double(w) * Double.pi * 2)) * 2.2
                if i == 0 { p.move(to: CGPoint(x: 0, y: y)) } else { p.addLine(to: CGPoint(x: CGFloat(i), y: y)) }
            }
            c.addPath(p)
            c.setStrokeColor(UIColor(hex: "#DC2626").cgColor)
            c.setLineWidth(1.4)
            c.strokePath()
        }
    }
}
