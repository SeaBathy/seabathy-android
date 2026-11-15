import SwiftUI

/**
 The tide over the next day and a half: a smooth curve with high and low
 water marked, a "now" marker, nights shaded, day and hour labels. Drag
 along it to see the tide at any time. (Android tides/TideGraphView.kt.)
 */
struct TideGraphView: View {
    let curve: TideCurve
    /// Heights in the units chosen in Settings.
    let format: NavFormat
    /// Added to every height (chart datum offset), so the graph matches the readouts.
    let offsetM: Double
    let nowMillis: Int64
    /// Left edge of the chart; 36 hours are shown from here.
    let windowStartMillis: Int64
    /// Sunset-to-sunrise spans, shaded darker.
    let nights: [(Int64, Int64)]

    /// Where the finger is (or was last), shown with its time and height.
    @State private var cursorMillis: Int64?

    private static let beforeMs: Int64 = 3 * 3_600_000
    private static let afterMs: Int64 = 33 * 3_600_000
    private static let samples: Int64 = 240
    private static let plotLeft: CGFloat = 36
    private static let plotRightInset: CGFloat = 12

    private static let card = Color(hex: "#0F1A27")
    private static let water = Color(hex: "#38BDF8")
    private static let high = Color(hex: "#38BDF8")
    private static let low = Color(hex: "#94A3B8")
    private static let nowColor = Color(hex: "#FFB020")
    private static let muted = Color(hex: "#94A3B8")

    private enum Align { case left, center }

    var body: some View {
        GeometryReader { geo in
            Canvas { context, size in
                draw(&context, size)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let left = TideGraphView.plotLeft
                        let right = geo.size.width - TideGraphView.plotRightInset
                        guard right > left else { return }
                        let span = TideGraphView.beforeMs + TideGraphView.afterMs
                        let fraction = min(max((value.location.x - left) / (right - left), 0), 1)
                        cursorMillis = windowStartMillis + Int64(Double(span) * Double(fraction))
                    }
            )
        }
        .frame(height: 250)
        .accessibilityLabel("Tide chart")
    }

    // Drawing ---------------------------------------------------------------------------------

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        let width = size.width
        let height = size.height
        ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 14), with: .color(TideGraphView.card))

        let from = windowStartMillis
        let to = windowStartMillis + TideGraphView.beforeMs + TideGraphView.afterMs
        var samples: [(t: Int64, v: Double)] = []
        for i in 0...TideGraphView.samples {
            let t = from + (to - from) * i / TideGraphView.samples
            if let h = curve.heightAt(t) { samples.append((t, h + offsetM)) }
        }
        if samples.count < 2 { return }

        // Plot area, leaving room for labels around it.
        let left = TideGraphView.plotLeft
        let right = width - TideGraphView.plotRightInset
        let top: CGFloat = 46
        let bottom = height - 62
        let minV = samples.map { $0.v }.min() ?? 0
        let maxV = samples.map { $0.v }.max() ?? 0
        let minH = floor(minV * 2) / 2
        let maxH = ceil(maxV * 2) / 2
        let range = max(maxH - minH, 0.5)
        func x(_ t: Int64) -> CGFloat { left + CGFloat(Double(t - from) / Double(to - from)) * (right - left) }
        func y(_ v: Double) -> CGFloat { top + CGFloat((maxH - v) / range) * (bottom - top) }

        // Night, behind everything else.
        for (dusk, dawn) in nights {
            if dawn <= from || dusk >= to { continue }
            let x0 = x(max(dusk, from))
            let x1 = x(min(dawn, to))
            ctx.fill(Path(CGRect(x: x0, y: top - 6, width: x1 - x0, height: bottom - top + 6)), with: .color(Color.black.opacity(0x73 / 255.0)))
        }

        // Height lines every half metre (whole metres for big tides), labelled at the left.
        let step = format.tideChartStep(range)
        let perMetre = format.tideUnitsPerMetre()
        var level = ceil(minH * perMetre / step - 1e-6) * step
        while level / perMetre <= maxH + 1e-6 {
            let lineY = y(level / perMetre)
            line(&ctx, from: CGPoint(x: left, y: lineY), to: CGPoint(x: right, y: lineY), color: Color.white.opacity(0x1F / 255.0), width: 1)
            text(&ctx, String(format: step < 1 ? "%.1f" : "%.0f", level + 0.0), size: 11, color: TideGraphView.muted,
                 bold: false, x: 8, baseline: lineY + 4, align: .left)
            level += step
        }

        // Midnights (dashed, with the day) and 6-hourly ticks along the bottom.
        let cal = Calendar.current
        let comps = cal.dateComponents([.year, .month, .day, .hour], from: TideMath.date(from))
        var t = TideMath.millis(cal.date(from: comps) ?? TideMath.date(from)) + 3_600_000
        let nowX = x(nowMillis)
        while t < to {
            let hour = cal.component(.hour, from: TideMath.date(t))
            if hour == 0 {
                // Midnight: a dashed line, and the new day's name in the time row.
                var dash = Path()
                dash.move(to: CGPoint(x: x(t), y: top - 6))
                dash.addLine(to: CGPoint(x: x(t), y: bottom))
                ctx.stroke(dash, with: .color(Color.white.opacity(0x40 / 255.0)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                if abs(x(t) - nowX) > 30 {
                    let label = TideText.string(t, "EEE d")
                    let w = measure(&ctx, label, size: 12, bold: true)
                    let lx = min(max(x(t) - w / 2, left), right - w)
                    text(&ctx, label, size: 12, color: Color(hex: "#E2E8F0"), bold: true, x: lx, baseline: bottom + 52, align: .left)
                }
            } else if hour % 6 == 0 && abs(x(t) - nowX) > 26 {
                // On their own row below the low-water labels; NOW takes the slot it falls in.
                text(&ctx, String(format: "%02d:00", hour), size: 11, color: TideGraphView.muted, bold: false,
                     x: x(t), baseline: bottom + 52, align: .center)
            }
            t += 3_600_000
        }

        // The curve, with a soft fill below it.
        var path = Path()
        for (i, s) in samples.enumerated() {
            let p = CGPoint(x: x(s.t), y: y(s.v))
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        var fill = path
        fill.addLine(to: CGPoint(x: x(samples[samples.count - 1].t), y: bottom))
        fill.addLine(to: CGPoint(x: x(samples[0].t), y: bottom))
        fill.closeSubpath()
        ctx.fill(fill, with: .linearGradient(
            Gradient(colors: [TideGraphView.water.opacity(0x66 / 255.0), TideGraphView.water.opacity(0x08 / 255.0)]),
            startPoint: CGPoint(x: 0, y: top), endPoint: CGPoint(x: 0, y: bottom)))
        ctx.stroke(path, with: .color(TideGraphView.water), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))

        // High and low water: a dot and its time and height.
        for e in curve.extremes(from: from, to: to) {
            let v = e.heightM + offsetM
            let cx = x(e.timeMillis)
            let cy = y(v)
            dot(&ctx, CGPoint(x: cx, y: cy), radius: 5, color: e.isHigh ? TideGraphView.high : TideGraphView.low)
            // Already past: just the dot, so its label doesn't crowd the NOW marker.
            if e.timeMillis < nowMillis { continue }
            let lx = min(max(cx, left + 22), right - 22)
            let time = TideText.hourMinute(e.timeMillis)
            let metres = format.tideMark(v)
            if e.isHigh {
                text(&ctx, time, size: 12, color: Color(hex: "#F8FAFC"), bold: true, x: lx, baseline: cy - 22, align: .center)
                text(&ctx, metres, size: 11, color: TideGraphView.muted, bold: false, x: lx, baseline: cy - 9, align: .center)
            } else {
                text(&ctx, time, size: 12, color: Color(hex: "#F8FAFC"), bold: true, x: lx, baseline: cy + 21, align: .center)
                text(&ctx, metres, size: 11, color: TideGraphView.muted, bold: false, x: lx, baseline: cy + 34, align: .center)
            }
        }

        // Now, when it's on this chart.
        if nowMillis >= from && nowMillis <= to, let h = curve.heightAt(nowMillis) {
            let ny = y(h + offsetM)
            line(&ctx, from: CGPoint(x: nowX, y: top - 4), to: CGPoint(x: nowX, y: bottom), color: TideGraphView.nowColor, width: 2)
            dot(&ctx, CGPoint(x: nowX, y: ny), radius: 6, color: TideGraphView.nowColor)
            text(&ctx, "NOW", size: 11, color: TideGraphView.nowColor, bold: true,
                 x: min(max(nowX, left + 14), right - 14), baseline: bottom + 52, align: .center)
        }

        if let c = cursorMillis, let h = curve.heightAt(c) {
            let rate = curve.rateMPerHour(c) ?? 0.0
            let label = "\(TideText.string(c, "EEE HH:mm"))   \(format.tideHeight(h + offsetM)) \(TideText.arrow(rate))"
            drawCursor(&ctx, x: x(c), y: y(h + offsetM), top: top, bottom: bottom, width: width, label: label)
        }
    }

    private func drawCursor(_ ctx: inout GraphicsContext, x: CGFloat, y: CGFloat, top: CGFloat, bottom: CGFloat, width: CGFloat, label: String) {
        line(&ctx, from: CGPoint(x: x, y: top - 4), to: CGPoint(x: x, y: bottom), color: Color.white.opacity(0xCC / 255.0), width: 1.5)
        dot(&ctx, CGPoint(x: x, y: y), radius: 6, color: .white)
        let w = measure(&ctx, label, size: 13, bold: true) + 16
        let bx = min(max(x - w / 2, 4), max(4, width - w - 4))
        let rect = CGRect(x: bx, y: 4, width: w, height: 24)
        ctx.fill(Path(roundedRect: rect, cornerRadius: 8), with: .color(Color(hex: "#F8FAFC")))
        text(&ctx, label, size: 13, color: TideGraphView.card, bold: true, x: rect.midX, baseline: rect.maxY - 7, align: .center)
    }

    // Pieces ----------------------------------------------------------------------------------

    private func line(_ ctx: inout GraphicsContext, from: CGPoint, to: CGPoint, color: Color, width: CGFloat) {
        var p = Path()
        p.move(to: from)
        p.addLine(to: to)
        ctx.stroke(p, with: .color(color), lineWidth: width)
    }

    private func dot(_ ctx: inout GraphicsContext, _ centre: CGPoint, radius: CGFloat, color: Color) {
        let rect = CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2)
        ctx.fill(Path(ellipseIn: rect), with: .color(color))
        ctx.stroke(Path(ellipseIn: rect), with: .color(TideGraphView.card), lineWidth: 2)
    }

    private func resolved(_ ctx: inout GraphicsContext, _ s: String, size: CGFloat, color: Color, bold: Bool) -> GraphicsContext.ResolvedText {
        ctx.resolve(Text(s).font(.system(size: size, weight: bold ? .bold : .regular)).foregroundColor(color))
    }

    private func measure(_ ctx: inout GraphicsContext, _ s: String, size: CGFloat, bold: Bool) -> CGFloat {
        resolved(&ctx, s, size: size, color: .white, bold: bold).measure(in: CGSize(width: 2000, height: 200)).width
    }

    /// Draws text with its baseline at `baseline` (as Android's drawText), left-aligned or centred on `x`.
    private func text(_ ctx: inout GraphicsContext, _ s: String, size: CGFloat, color: Color, bold: Bool,
                      x: CGFloat, baseline: CGFloat, align: Align) {
        let r = resolved(&ctx, s, size: size, color: color, bold: bold)
        let m = r.measure(in: CGSize(width: 2000, height: 200))
        let originX = align == .center ? x - m.width / 2 : x
        // The system font's ascent is a little under its point size.
        ctx.draw(r, in: CGRect(x: originX, y: baseline - size * 0.95, width: m.width, height: m.height))
    }
}
