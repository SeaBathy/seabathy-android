import SwiftUI

// Chart overlays placed by the app: the route card (bottom), the navigation
// display (top) and the night red filter (over everything, touches pass through).

/// The route planner card (Android routeCard): shown while the route tool is armed or a route is on the chart.
struct RouteCardOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        RouteCardContent(nav: app.navigation, store: app.navSettings)
    }
}

private struct RouteCardContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var nav: NavigationFeature
    @ObservedObject var store: NavSettingsStore
    @State var speedText = ""
    @FocusState var speedFocused: Bool

    init(nav: NavigationFeature, store: NavSettingsStore) {
        self.nav = nav
        self.store = store
    }

    var body: some View {
        if let text = nav.routeCardText {
            MapCard {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 6) {
                        Text("ROUTE").font(.caption2.weight(.semibold)).foregroundStyle(Theme.teal)
                        Spacer()
                        CardButton(title: "UNDO") { nav.undoRoutePoint() }
                        CardButton(title: "CLEAR", tint: Theme.textMuted) { nav.clearRoute() }
                        Button { nav.exportPlannedRoute() } label: {
                            Image(systemName: "square.and.arrow.up")
                                .font(.caption.weight(.semibold))
                                .frame(width: 30, height: 28)
                                .background(Theme.surfaceRaised, in: Capsule())
                                .foregroundStyle(Theme.teal)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Export GPX")
                    }
                    Text(text)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 5) {
                        wide("SAVE") { nav.saveCurrentRoute() }
                        wide("ROUTES") { nav.showRoutes() }
                        wide("LEGS") { nav.showLegs() }
                        wide("REVERSE") { nav.reverseRoute() }
                        wide("▶ GO", primary: true) { nav.goPlannedRoute() }
                    }
                    HStack(spacing: 6) {
                        sourceButton("GPS SPEED", selected: store.settings.speedSource == .gps,
                                     label: "Use live GPS speed for route ETA") { nav.setSpeedSource(.gps) }
                        sourceButton("CRUISE", selected: store.settings.speedSource == .cruise,
                                     label: "Use the cruise speed from the boat profile for times and ETAs") { nav.setSpeedSource(.cruise) }
                        TextField(store.settings.units.speed.label, text: $speedText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.center)
                            .font(.system(size: 12))
                            .focused($speedFocused)
                            .frame(width: 64, height: 28)
                            .background(Theme.surfaceRaised, in: Capsule())
                            .accessibilityLabel("Cruise speed")
                            .onChange(of: speedText) { value in
                                guard speedFocused, let v = Double(value.replacingOccurrences(of: ",", with: ".")) else { return }
                                nav.setCruiseSpeed(shown: v)
                            }
                        Text(store.settings.units.speed.label).font(.caption2).foregroundStyle(Theme.textMuted)
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(.horizontal, 8)
            .onAppear { showCruiseSpeed() }
            .onChange(of: store.settings.cruiseSpeedKn) { _ in if !speedFocused { showCruiseSpeed() } }
            .onChange(of: store.settings.units.speed) { _ in showCruiseSpeed() }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { speedFocused = false }
                }
            }
        }
    }

    private func showCruiseSpeed() {
        speedText = String(format: "%.1f", store.settings.cruiseSpeedKn * store.settings.units.speed.perKnot)
    }

    private func wide(_ title: String, primary: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .frame(maxWidth: .infinity, minHeight: 34)
                .background(primary ? Theme.teal : Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .foregroundStyle(primary ? Theme.navy : Theme.teal)
        }
        .buttonStyle(.plain)
    }

    private func sourceButton(_ title: String, selected: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .padding(.horizontal, 10)
                .frame(height: 28)
                .background(selected ? Theme.teal : Theme.surfaceRaised, in: Capsule())
                .foregroundStyle(selected ? Theme.navy : Theme.textMuted)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The navigation display (Android NavHud): shown at the top while navigating.
struct NavHudOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        NavHudContent(nav: app.navigation, session: app.navigation.session, store: app.navSettings)
    }
}

private struct NavHudContent: View {
    @ObservedObject var nav: NavigationFeature
    @ObservedObject var session: NavSession
    @ObservedObject var store: NavSettingsStore
    @State var compact = false

    private static let warnColour = Color(hex: "#FF6B6B")

    var body: some View {
        if let state = session.state {
            content(state, session.extras, store.settings)
                .padding(.horizontal, 8)
        }
    }

    private func content(_ state: NavState, _ extras: NavExtras, _ s: NavSettings) -> some View {
        let f = s.format
        let side = abs(state.xteM) < 1 ? "" : (state.xteM > 0 ? " R" : " L")
        var steer = "no course yet"
        if let turn = state.steerDegrees {
            let d = Int(abs(turn).rounded())
            steer = d < 2 ? "heading for it" : (turn > 0 ? "to waypoint ▶ \(d)°" : "to waypoint ◀ \(d)°")
        }
        let minDepth = s.minimumDepthM
        let fuel = state.fuelToEndLitres
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(state.finished ? "Arrived: \(state.targetLabel)" : "→ \(state.targetLabel)")
                        .font(.system(size: 16, weight: .bold)).lineLimit(2)
                    Text("Point \(state.targetIndex + 1) of \(state.pointCount)" + (compact ? "  ·  tap for more" : ""))
                        .font(.system(size: 12)).foregroundStyle(Theme.textMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { compact.toggle() }
                hudButton("chevron.left", "Previous point") { nav.previousPoint() }
                hudButton("chevron.right", "Next point") { nav.skipPoint() }
                hudButton(session.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                          session.muted ? "Alarms muted — tap to turn on" : "Mute alarms",
                          tint: session.muted ? Self.warnColour : Theme.textPrimary) { nav.toggleMute() }
                hudButton("stop.fill", "Stop navigating") { nav.confirmStop() }
            }
            if let alarm = extras.alarmText {
                Text(alarm)
                    .font(.system(size: 14, weight: .bold))
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(hex: "#B91C1C"), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            HStack(spacing: 4) {
                cell("DTW", f.distance(state.dtwNm))
                cell("BTW", f.bearing(state.btwTrue, variation: extras.variation))
                cell("XTE", f.offset(state.xteM) + side, warn: state.offCourse, sub: steer)
            }
            NavLaneView(xteM: state.xteM, limitM: s.xteLimitM, steerDegrees: state.steerDegrees)
                .frame(height: 64)
            if !compact {
                HStack(spacing: 4) {
                    cell("SOG", f.speed(state.sogKn))
                    cell("COG", f.bearing(state.cogTrue, variation: extras.variation))
                    cell("VMG", f.speed(state.vmgKn))
                }
                HStack(spacing: 4) {
                    cell("TTG", f.duration(state.ttgNextHours), sub: "ETA \(f.clock(state.etaNextMillis))")
                    cell("TO END", f.distance(state.remainingNm), sub: "ETA \(f.clock(state.etaEndMillis))")
                    cell("FUEL TO END", f.fuel(fuel), warn: fuel != nil && s.usableFuelLitres > 0 && fuel! > s.usableFuelLitres)
                }
                HStack(spacing: 4) {
                    cell("DEPTH", f.depth(extras.depthNowM), warn: extras.depthNowM.map { $0 < minDepth } ?? false,
                         sub: "need \(f.depth(minDepth))")
                    cell("AHEAD", f.depth(extras.depthAheadM), warn: extras.depthAheadM.map { $0 < minDepth } ?? false)
                    cell("TIDE", extras.tideM.map { String(format: "%.2f m", $0) } ?? "—")
                }
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .foregroundStyle(Theme.textPrimary)
        .background(Color(hex: "#F0111827"), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.border, lineWidth: 1))
        .contentShape(Rectangle())
    }

    private func cell(_ name: String, _ value: String, warn: Bool = false, sub: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(name).font(.system(size: 10)).foregroundStyle(Theme.textMuted)
            Text(value)
                .font(.system(size: 19, weight: .bold, design: .monospaced))
                .foregroundStyle(warn ? Self.warnColour : Theme.textPrimary)
                .lineLimit(1).minimumScaleFactor(0.6)
            if let sub {
                Text(sub).font(.system(size: 10)).foregroundStyle(Theme.textMuted).lineLimit(2)
            }
        }
        .padding(.horizontal, 6).padding(.vertical, 3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black.opacity(0.2), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func hudButton(_ symbol: String, _ label: String, tint: Color = Theme.textPrimary, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 44, height: 40)
                .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.border, lineWidth: 1))
                .foregroundStyle(tint)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/**
 The chartplotter "highway" (Android LaneView): the track line running away
 from the boat, its edges at the off-course limit, and the boat drawn offset by
 its cross-track error. Red outside the lane.
 */
struct NavLaneView: View {
    var xteM: Double
    var limitM: Double
    var steerDegrees: Double?

    var body: some View {
        Canvas { context, size in
            let w = size.width, h = size.height
            guard w > 0, h > 0 else { return }
            let limit = max(limitM, 1)
            let outside = abs(xteM) > limit
            let cx = w / 2
            let bottomHalf = w * 0.30
            let topHalf = w * 0.06
            let top = h * 0.08
            // The lane slides opposite to the boat's offset, so the boat stays centred.
            let shift = CGFloat(min(max(-xteM / limit, -2.2), 2.2)) * bottomHalf
            var lane = Path()
            lane.move(to: CGPoint(x: cx + shift - bottomHalf, y: h))
            lane.addLine(to: CGPoint(x: cx + shift * 0.2 - topHalf, y: top))
            lane.addLine(to: CGPoint(x: cx + shift * 0.2 + topHalf, y: top))
            lane.addLine(to: CGPoint(x: cx + shift + bottomHalf, y: h))
            lane.closeSubpath()
            context.fill(lane, with: .color(outside ? Color(hex: "#66EF4444") : Color(hex: "#4419C6B3")))

            var edges = Path()
            edges.move(to: CGPoint(x: cx + shift - bottomHalf, y: h))
            edges.addLine(to: CGPoint(x: cx + shift * 0.2 - topHalf, y: top))
            edges.move(to: CGPoint(x: cx + shift + bottomHalf, y: h))
            edges.addLine(to: CGPoint(x: cx + shift * 0.2 + topHalf, y: top))
            context.stroke(edges, with: .color(Color(hex: "#E2E8F0")), lineWidth: 2)

            var centre = Path()
            centre.move(to: CGPoint(x: cx + shift, y: h))
            centre.addLine(to: CGPoint(x: cx + shift * 0.2, y: top))
            context.stroke(centre, with: .color(Color(hex: "#E040FB")), style: StrokeStyle(lineWidth: 3, dash: [10, 8]))

            // The boat, pointing up the lane and turned by the steering needed.
            let boatSize = h * 0.22
            let by = h - boatSize * 0.9
            let angle = -CGFloat(min(max(steerDegrees ?? 0, -60), 60)) * 0.5
            var boat = Path()
            boat.move(to: CGPoint(x: 0, y: -boatSize))
            boat.addLine(to: CGPoint(x: boatSize * 0.45, y: boatSize * 0.5))
            boat.addLine(to: CGPoint(x: 0, y: boatSize * 0.25))
            boat.addLine(to: CGPoint(x: -boatSize * 0.45, y: boatSize * 0.5))
            boat.closeSubpath()
            let transform = CGAffineTransform(translationX: cx, y: by).rotated(by: angle * .pi / 180)
            let placed = boat.applying(transform)
            context.fill(placed, with: .color(outside ? Color(hex: "#EF4444") : Color(hex: "#FFB020")))
            context.stroke(placed, with: .color(Color(hex: "#0B1622")), lineWidth: 1.5)
        }
        .accessibilityHidden(true)
    }
}

/// Dims the screen red for night vision when the Night red filter is on; touches pass through.
struct NightFilterOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        NightFilterContent(store: app.navSettings)
    }
}

private struct NightFilterContent: View {
    @ObservedObject var store: NavSettingsStore

    var body: some View {
        // Dark red at about 45% opacity: dims and reddens without hiding the chart.
        Color(hex: "#73400000")
            .opacity(store.settings.nightFilter ? 1 : 0)
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }
}
