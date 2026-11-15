import SwiftUI

private let conditionsWarning = Color(hex: "#F5B942")

/// The values box under the tide chip (wind, waves and current at the boat or
/// spot, or everything at the crosshair while a layer is on), or the banner
/// while the spot is being chosen on the map. The moving flow lines are drawn
/// by the feature on a view laid over the map itself.
struct ConditionsOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        ConditionsBoxView(feature: app.conditions)
    }
}

private struct ConditionsBoxView: View {
    @ObservedObject var feature: ConditionsFeature

    var body: some View {
        if feature.picking {
            pickBanner
        } else if let box = feature.box {
            boxView(box)
        }
    }

    private var pickBanner: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Choose the spot").font(.headline)
            Text("Move the map so the crosshair is on the spot, then tap Set.")
                .font(.footnote)
                .foregroundStyle(Theme.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button { feature.endPicking(set: false) } label: {
                    Text("Cancel").frame(maxWidth: .infinity, minHeight: 40)
                        .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
                }
                Button { feature.endPicking(set: true) } label: {
                    Text("Set").fontWeight(.semibold).frame(maxWidth: .infinity, minHeight: 40)
                        .background(Theme.teal, in: RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(Theme.navy)
                }
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .frame(maxWidth: 300)
        .background(Theme.panelColor.opacity(0.96), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.teal, lineWidth: 2))
        .foregroundStyle(Theme.textPrimary)
    }

    private func boxView(_ box: ConditionsBoxState) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Button { feature.show() } label: {
                    Text(box.title)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textMuted)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sea conditions — open the settings")
                Button { feature.toggleBoxOpen() } label: {
                    Text(feature.boxOpen ? "▴" : "▾")
                        .font(.system(size: 16))
                        .frame(minWidth: 32, minHeight: 24)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(feature.boxOpen ? "Show less" : "Show waves, swell, current and sea")
            }
            ForEach(box.rows) { row in
                Button { feature.tapRow(row) } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 13, weight: .bold))
                            .rotationEffect(.degrees(row.arrowDegrees ?? 0))
                            .frame(width: 18, height: 18)
                            .opacity(row.arrowDegrees == nil ? 0 : 1)
                        Text(row.text)
                            .font(.system(size: 13))
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 3)
                    .frame(minHeight: 30, alignment: .leading)
                    .foregroundStyle(row.highlighted ? Theme.teal : Theme.textPrimary)
                    .background(row.highlighted ? Theme.teal.opacity(0.2) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(row.highlighted ? Theme.teal : Color.clear, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
            if let warning = box.warning {
                Text(warning)
                    .font(.system(size: 11))
                    .foregroundStyle(conditionsWarning)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 3)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .frame(maxWidth: 300, alignment: .leading)
        .background(Color(hex: "#E6111827"), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.border, lineWidth: 0.5))
        .foregroundStyle(Theme.textPrimary)
    }
}

/// The card at the bottom while a sea-conditions layer is on the map: what and
/// when, the colour key, and the time slider with Play (Android's conditions card).
struct ConditionsCardOverlay: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        ConditionsCardView(feature: app.conditions)
    }
}

private struct ConditionsCardView: View {
    @ObservedObject var feature: ConditionsFeature

    var body: some View {
        if let card = feature.card {
            content(card)
        }
    }

    private func content(_ card: ConditionsCardState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 6) {
                Text(card.title)
                    .font(.system(size: 13, weight: .bold))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                smallButton("gearshape", label: "Sea conditions settings") { feature.show() }
                smallButton("xmark", label: "Hide sea conditions") { feature.hide() }
            }
            if let warning = card.warning {
                Text(warning).font(.system(size: 11)).foregroundStyle(conditionsWarning)
            }
            if let colours = card.legendColours {
                VStack(spacing: 2) {
                    LinearGradient(colors: colours.map { Color(uiColor: ConditionsColourScale.uiColour($0)) },
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(height: 8)
                        .clipShape(RoundedRectangle(cornerRadius: 3))
                    HStack {
                        Text(card.legendLow).frame(maxWidth: .infinity, alignment: .leading)
                        Text(card.legendMid).frame(maxWidth: .infinity, alignment: .center)
                        Text(card.legendHigh).frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textMuted)
                }
                .padding(.top, 4)
            }
            if card.showsSlider {
                HStack(spacing: 4) {
                    Button { feature.togglePlay() } label: {
                        Text(feature.playing ? "Pause" : "Play")
                            .font(.system(size: 14))
                            .frame(width: 58, height: 36)
                            .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .accessibilityLabel(feature.playing ? "Pause" : "Play the forecast")
                    Button { feature.step(-1) } label: {
                        Text("◀").frame(width: 36, height: 36).background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .accessibilityLabel("An hour earlier")
                    Slider(value: Binding(get: { Double(card.hourIndex) },
                                          set: { feature.pickHour(Int($0.rounded())) }),
                           in: 0...Double(max(card.hours - 1, 1)), step: 1)
                        .accessibilityLabel("Forecast time")
                    Button { feature.step(1) } label: {
                        Text("▶").frame(width: 36, height: 36).background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .accessibilityLabel("An hour later")
                    Button { feature.goLive() } label: {
                        Text("Now")
                            .font(.system(size: 14, weight: .semibold))
                            .frame(width: 50, height: 36)
                            .background(Theme.teal, in: RoundedRectangle(cornerRadius: 8))
                            .foregroundStyle(Theme.navy)
                    }
                    .opacity(card.live ? 0.45 : 1)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 8)
        .background(Color(hex: "#EE111827"), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.border, lineWidth: 0.5))
        .foregroundStyle(Theme.textPrimary)
        .tint(Theme.teal)
        .padding(.horizontal, 8)
    }

    private func smallButton(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 36, height: 32)
                .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// Settings sheet -------------------------------------------------------------------------

/// The Sea conditions sheet (Android's conditions panel), same wording.
struct ConditionsSheet: View {
    @ObservedObject var feature: ConditionsFeature
    @State private var optionsFor: ConditionsSummary?
    @State private var showOptions = false
    @State private var renaming: ConditionsSummary?
    @State private var showRename = false
    @State private var renameText = ""
    @State private var deleting: ConditionsSummary?
    @State private var showDelete = false

    var body: some View {
        SheetScaffold(title: "Sea conditions") {
            List {
                Section {
                    caption("Wind, waves, swell and currents (tidal flow included) for up to 8 days, plus sea temperature and its " +
                            "breaks, chlorophyll, sea level and salinity from satellites, and temperature at depth from an ocean model. " +
                            "Worldwide, and saved on the phone for use offline.")
                    NavigationLink {
                        ConditionsChoiceList(title: "Show on the map",
                                             options: ["Off"] + ConditionsLogic.mapChoices.map { $0.label },
                                             selected: feature.shown ? (ConditionsLogic.mapChoices.firstIndex(of: feature.colour).map { $0 + 1 } ?? 0) : 0) { i in
                            feature.chooseLayer(i == 0 ? nil : ConditionsLogic.mapChoices[i - 1])
                        }
                    } label: { row("Show on the map", feature.shownLabel) }
                    caption("Or tap a line in the box on the chart. Each shows its own direction lines; sea temperature and chlorophyll " +
                            "show the current's. Temperature breaks are where warm and cold water meet, often where fish gather.")
                }

                Section("Spot") {
                    NavigationLink {
                        ConditionsChoiceList(title: "Spot", options: ["Follow the GPS", "Choose on the map"],
                                             selected: feature.followGps ? 0 : 1) { i in
                            if i == 0 { feature.followTheGps() } else { feature.startPicking() }
                        }
                    } label: { row("Spot", feature.spotLabel) }
                    NavigationLink {
                        ConditionsChoiceList(title: "Update automatically",
                                             options: [ConditionsLogic.autoLabel(1), ConditionsLogic.autoLabel(0)],
                                             selected: feature.isAutoUpdating ? 0 : 1) { i in
                            feature.setAutoUpdate(i == 0)
                        }
                    } label: { row("Update automatically", feature.autoLabel) }
                    caption("Online, the app checks for a new forecast every 15 minutes and when you open it, and downloads " +
                            "\(Int(ConditionsLogic.spotRadiusKm)) km round the spot when there is one. Each update replaces the last; " +
                            "old copies and forecasts that ran out are removed. Offline, the latest saved data is shown.")
                    Button { feature.updateNow() } label: {
                        row(feature.downloading ? "Downloading…" : "Update now", feature.updateNowDetail)
                    }
                }

                Section("On the map") {
                    NavigationLink {
                        ConditionsChoiceList(title: "Direction shown as", options: ConditionsDirectionStyle.allCases.map { $0.label },
                                             selected: ConditionsDirectionStyle.allCases.firstIndex(of: feature.directionStyle)) { i in
                            feature.setDirectionStyle(ConditionsDirectionStyle.allCases[i])
                        }
                    } label: { row("Direction shown as", feature.directionStyle.label) }
                    Button { feature.toggleBoxShown() } label: { row("Values box on the chart", feature.boxShownLabel) }
                    NavigationLink {
                        ConditionsTemperatureRangeView(feature: feature)
                    } label: { row("Sea temperature colours", feature.temperatureRangeLabel) }
                    NavigationLink {
                        ConditionsChoiceList(title: "How far down",
                                             options: ConditionsLogic.belowDepths.map { feature.app.format.depth(Double($0)) },
                                             selected: ConditionsLogic.belowDepths.firstIndex(of: feature.belowDepthM)) { i in
                            feature.setBelowDepth(ConditionsLogic.belowDepths[i])
                        }
                    } label: { row("Temperature at depth", feature.belowDepthLabel) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Colour opacity \(Int((feature.opacity * 100).rounded()))%")
                            .font(.footnote)
                            .foregroundStyle(Theme.textMuted)
                        Slider(value: Binding(get: { feature.opacity }, set: { feature.setOpacity($0, save: false) }),
                               in: 0...1,
                               onEditingChanged: { editing in if !editing { feature.setOpacity(feature.opacity, save: true) } })
                            .accessibilityLabel("Colour opacity")
                    }
                }

                Section("Saved areas") {
                    if feature.summaries.isEmpty { caption("None yet.") }
                    ForEach(feature.summaries, id: \.id) { s in
                        Button {
                            optionsFor = s
                            showOptions = true
                        } label: {
                            row((feature.isActive(s) ? "● " : "") + s.name, feature.detail(for: s))
                        }
                    }
                }

                Section("Downloads") {
                    Button { feature.downloadScreenArea() } label: {
                        row(feature.downloading ? "Downloading…" : "Save the area on screen", "As well as the spot: another fishing ground")
                    }
                    NavigationLink {
                        ConditionsChoiceList(title: "Days of forecast", options: ConditionsLogic.dayChoices.map { ConditionsLogic.dayText($0) },
                                             selected: ConditionsLogic.dayChoices.firstIndex(of: feature.days)) { i in
                            feature.setDays(ConditionsLogic.dayChoices[i])
                        }
                    } label: {
                        row("Days of forecast to save", "\(ConditionsLogic.dayText(feature.days)) (up to \(ConditionsClient.maxDays))")
                    }
                    Button { feature.toggleSatellite() } label: {
                        row("Satellite and ocean layers", feature.withSatellite ? "Yes (usually 2 to 5 days old)" : "No")
                    }
                    if let status = feature.status { caption(status) }
                    caption("Forecast model points are about 8 km apart, so it's less accurate close to shore and in channels. Not for navigation.")
                }
            }
            .listStyle(.insetGrouped)
        }
        .confirmationDialog(optionsFor?.name ?? "", isPresented: $showOptions, titleVisibility: .visible, presenting: optionsFor) { s in
            Button("Show this area") { feature.selectArea(s.id, goTo: false) }
            Button("Go to it on the map") { feature.selectArea(s.id, goTo: true) }
            Button("Update (download again)") { feature.updateArea(s.id) }
            Button("Rename") {
                renaming = s
                renameText = s.name
                showRename = true
            }
            Button("Delete", role: .destructive) {
                deleting = s
                showDelete = true
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Rename area", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Save") { if let s = renaming { feature.renameArea(s.id, to: renameText) } }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Delete \(deleting?.name ?? "")?", isPresented: $showDelete) {
            Button("Delete", role: .destructive) { if let s = deleting { feature.deleteArea(s.id) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its saved sea conditions are removed from the phone.")
        }
    }

    private func row(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(Theme.textPrimary)
            Text(detail).font(.caption).foregroundStyle(Theme.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func caption(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(Theme.textMuted)
    }
}

/// A list of choices with a tick on the current one; picking goes back.
private struct ConditionsChoiceList: View {
    let title: String
    let options: [String]
    let selected: Int?
    let onPick: (Int) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(options.indices, id: \.self) { i in
                Button {
                    onPick(i)
                    dismiss()
                } label: {
                    HStack {
                        Text(options[i]).foregroundStyle(Theme.textPrimary)
                        Spacer()
                        if i == selected { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
                    }
                    .contentShape(Rectangle())
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.panelColor)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// "Sea temperature colours": automatic, or a range set by hand (in the chosen unit).
private struct ConditionsTemperatureRangeView: View {
    @ObservedObject var feature: ConditionsFeature
    @Environment(\.dismiss) private var dismiss
    @State private var low = ""
    @State private var high = ""

    var body: some View {
        let unit = feature.app.units.temperature
        List {
            Section {
                Button {
                    feature.applyTemperatureRange(nil)
                    dismiss()
                } label: {
                    HStack {
                        Text("Automatic: stretched to the area").foregroundStyle(Theme.textPrimary)
                        Spacer()
                        if feature.temperatureRange == nil { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
                    }
                }
            }
            Section("Set the range") {
                HStack {
                    Text("Coolest (\(unit.label))")
                    Spacer()
                    TextField("Coolest", text: $low)
                        .keyboardType(.numbersAndPunctuation)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 110)
                }
                HStack {
                    Text("Warmest (\(unit.label))")
                    Spacer()
                    TextField("Warmest", text: $high)
                        .keyboardType(.numbersAndPunctuation)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 110)
                }
                Button("Save") {
                    let a = Double(low.replacingOccurrences(of: ",", with: "."))
                    let b = Double(high.replacingOccurrences(of: ",", with: "."))
                    guard let a, let b, a < b else {
                        feature.app.toast("Enter two temperatures, the coolest first")
                        return
                    }
                    feature.applyTemperatureRange((unit.toCelsius(a), unit.toCelsius(b)))
                    dismiss()
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.panelColor)
        .navigationTitle("Sea temperature colours")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            let start = feature.temperatureRangeStart
            low = String(format: "%.1f", unit.of(start.0))
            high = String(format: "%.1f", unit.of(start.1))
        }
    }
}
