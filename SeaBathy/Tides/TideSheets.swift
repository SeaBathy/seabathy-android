import SwiftUI

// The tide screens (Android tides/TideDialog.kt): the tide screen, the "where" picker,
// place search, naming a spot, and tide settings.

private let tideDayMs: Int64 = 24 * TideCurve.hourMs
private let tideBlue = Color(hex: "#38BDF8")
private let tideCard = Color(hex: "#0F1A27")

// Small pieces -------------------------------------------------------------------------------

private struct TideCaption: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundStyle(Theme.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private struct TideSectionHeader: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(Theme.textMuted)
            .padding(.top, 14)
            .padding(.bottom, 2)
    }
}

private struct TideButtonLabel: View {
    let title: String
    var systemImage: String? = nil
    var body: some View {
        HStack(spacing: 6) {
            if let systemImage { Image(systemName: systemImage) }
            Text(title)
        }
        .frame(maxWidth: .infinity, minHeight: 34)
    }
}

/// A tappable row with an icon, a title and a detail line (selected rows get the accent border).
private struct TideOptionRow: View {
    let icon: String
    let title: String
    let detail: String
    var selected = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(Theme.teal)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.textPrimary)
                Text(detail).font(.system(size: 13)).foregroundStyle(Theme.textMuted)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .stroke(selected ? Theme.teal : Theme.border, lineWidth: selected ? 2 : 1))
        .contentShape(Rectangle())
    }
}

// The tide screen ------------------------------------------------------------------------------

private struct TideFewerChoice {
    let period: TideSavePeriod
    let current: Int64
    let end: Int64
}

/// The tide screen: now, a chart you can drag along, highs and lows, offline storage.
struct TideMainSheet: View {
    @ObservedObject var feature: TideFeature
    @State private var dayOffset = 0
    @State private var showPeriods = false
    @State private var showFewer = false
    @State private var fewer: TideFewerChoice?
    @State private var confirmDelete = false

    var body: some View {
        let now = TideMath.nowMillis()
        let s = feature.settings
        let forecast = feature.forecast
        SheetScaffold(title: "Tides") {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    whereRow(s, forecast, now)
                    status(s, forecast, now)
                    if s.mode == .model { offlineSection(forecast, now) }
                    HStack(spacing: 8) {
                        if s.mode == .model {
                            Button { feature.refreshTapped() } label: { TideButtonLabel(title: "Refresh") }
                                .buttonStyle(.bordered)
                        }
                        NavigationLink { TideSettingsView(feature: feature) } label: { TideButtonLabel(title: "Settings") }
                            .buttonStyle(.bordered)
                    }
                    .padding(.top, 14)
                    TideCaption(text: footer(s, forecast))
                }
                .padding(16)
            }
        }
    }

    private func footer(_ s: TideSettings, _ forecast: TideForecast?) -> String {
        let datum: String = s.showsChartDatum(forecast)
            ? "Heights above the lowest tide (chart datum, LAT), like a tide table. "
            : "Heights from mean sea level — lows read below zero until the lowest tide here is worked out (needs a connection, once). "
        let source = "Modelled tides, worked out from Open-Meteo data (CC BY 4.0, E.U. Copernicus Marine Service). "
        return datum + source + "Not an official tide table. Not for navigation."
    }

    // Where -----------------------------------------------------------------------------------

    private func whereRow(_ s: TideSettings, _ forecast: TideForecast?, _ now: Int64) -> some View {
        let name = feature.place?.name
        return HStack(spacing: 10) {
            Image(systemName: name == nil ? "location.fill" : "mappin.and.ellipse")
                .foregroundStyle(Theme.teal)
            VStack(alignment: .leading, spacing: 2) {
                Text(name ?? "Following you").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.textPrimary)
                Text(whereLine(s, forecast, name, now)).font(.system(size: 12)).foregroundStyle(Theme.textMuted)
            }
            Spacer(minLength: 0)
            Button("Change") { feature.showLocationPicker() }
                .buttonStyle(.bordered)
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.border, lineWidth: 1))
    }

    /// "Tide point 10.8 km NE of Cairns · updated 21:41"
    private func whereLine(_ s: TideSettings, _ forecast: TideForecast?, _ placeName: String?, _ now: Int64) -> String {
        if s.mode == .manual { return "Manual tide from your tide table" }
        if s.mode == .off { return "Tides off" }
        guard let f = forecast else {
            if placeName != nil { return "Saved place" }
            if feature.noPosition { return "Waiting for the GPS" }
            return "Tides where you are, as you travel"
        }
        let here = feature.noFix ? "your last position" : "you"
        let of = placeName ?? here
        let km = f.modelDistanceM / 1000
        let point: String
        if km < 1 {
            point = "Tide point at \(placeName ?? (feature.noFix ? "your last position" : "your position"))"
        } else {
            let dir = TideText.compass(TideGeo.bearing(f.requestLat, f.requestLng, f.modelLat, f.modelLng))
            point = String(format: "Tide point %.1f km %@ of %@", km, dir, of)
        }
        return "\(point) · updated \(feature.format.clock(f.fetchedMillis, now: now))"
    }

    // Now -------------------------------------------------------------------------------------

    @ViewBuilder
    private func status(_ s: TideSettings, _ forecast: TideForecast?, _ now: Int64) -> some View {
        let f = feature.format
        let curve: TideCurve? = (forecast?.curve.covers(now) ?? false) ? forecast?.curve : nil
        if s.mode == .off {
            TideCaption(text: "Tides are off. Turn them on in Settings.")
        } else if s.mode == .manual {
            nowCard(big: f.tideHeight(s.manualHeightM), line: "Manual height", next: nil)
        } else if (feature.loading || feature.saving) && curve == nil {
            Text("Getting tides…")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .padding(.vertical, 16)
        } else if let forecast, let curve {
            let offset = s.shownOffset(forecast)
            let h = (curve.heightAt(now) ?? 0) + offset
            let rate = curve.rateMPerHour(now) ?? 0.0
            let trend = rate > 0.02 ? "▲ Rising \(f.tideRate(rate))" : (rate < -0.02 ? "▼ Falling \(f.tideRate(-rate))" : "■ Slack")
            let next: String? = curve.nextExtremes(from: now, count: 1).first.map {
                "\($0.isHigh ? "High" : "Low") \(f.clock($0.timeMillis, now: now)) · " +
                    "\(f.tideHeight($0.heightM + offset)) · in \(TideText.inHours($0.timeMillis - now))"
            }
            nowCard(big: f.tideHeight(h), line: trend, next: next)
            // With tides saved offline, a failed forecast update (no signal at sea) doesn't matter.
            let savedAhead = (feature.store.savedUntil(feature.key) ?? 0) > now
            if let error = feature.lastError, !savedAhead {
                TideCaption(text: "Couldn't update: \(error)")
            }
            TideChartSection(forecast: forecast, offset: offset, now: now, format: f, dayOffset: $dayOffset)
                .padding(.top, 10)
        } else {
            Text(feature.lastError ?? "No tides downloaded for here yet.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textPrimary)
                .padding(.top, 10)
                .padding(.bottom, 8)
            Button { feature.refreshTapped() } label: { TideButtonLabel(title: "Get tides") }
                .buttonStyle(.borderedProminent)
        }
    }

    private func nowCard(big: String, line: String, next: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("NOW").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.textMuted)
            Text(big).font(.system(size: 34, weight: .bold)).foregroundStyle(tideBlue)
            Text(line).font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.textPrimary)
            if let next {
                Text("Next: \(next)").font(.system(size: 13)).foregroundStyle(Theme.textMuted).padding(.top, 4)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tideCard, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.border, lineWidth: 1))
        .padding(.top, 10)
    }

    // Offline -----------------------------------------------------------------------------------

    @ViewBuilder
    private func offlineSection(_ forecast: TideForecast?, _ now: Int64) -> some View {
        let placeName = feature.place?.name
        let until = feature.store.savedUntil(feature.key)
        let bytes = forecast?.storedBytes ?? 0
        let forecastEnd: Int64? = (until == nil || until! < now) ? forecast?.curve.endMillis : nil
        let line: String = {
            if feature.saving { return "Saving tides… this takes a few seconds." }
            if placeName == nil {
                return "Following you uses the live forecast\(forecastEnd.map { " (to \(TideText.dayMonth($0)))" } ?? ""). " +
                    "Save offline to keep tides for this spot."
            }
            if let until, until >= now { return "Saved offline until \(TideText.dayMonthYear(until)) · \(TideText.size(bytes))" }
            if let until { return "Saved tides ran out on \(TideText.dayMonthYear(until)). Renew to keep them offline." }
            if let forecastEnd { return "Live forecast to \(TideText.dayMonth(forecastEnd)) · \(TideText.size(bytes))" }
            return "Nothing saved yet."
        }()
        let current: Int64? = (until ?? 0) >= now ? until : nil
        let periods = TideSavePeriod.allCases
        let ends = periods.map { TideMath.millis($0.endFrom(Date())) }
        let label = until == nil ? "Save offline" : (until! < now ? "Renew" : "Save longer")
        let primary = until == nil || until! < now

        TideSectionHeader(text: "OFFLINE")
        Text(line)
            .font(.system(size: 14))
            .foregroundStyle(Theme.textPrimary)
            .padding(.leading, 2)
            .padding(.bottom, 6)
            .fixedSize(horizontal: false, vertical: true)
        if !feature.saving {
            HStack(spacing: 8) {
                if primary {
                    Button { showPeriods = true } label: { TideButtonLabel(title: label, systemImage: "arrow.down.circle") }
                        .buttonStyle(.borderedProminent)
                } else {
                    Button { showPeriods = true } label: { TideButtonLabel(title: label, systemImage: "arrow.down.circle") }
                        .buttonStyle(.bordered)
                }
                if bytes > 0 && placeName != nil {
                    Button { confirmDelete = true } label: { TideButtonLabel(title: "Delete", systemImage: "trash") }
                        .buttonStyle(.bordered)
                }
            }
            .confirmationDialog(
                current == nil ? "Keep tides offline for" : "Keep tides offline for (saved to \(TideText.dayMonthYear(current!)) now)",
                isPresented: $showPeriods,
                titleVisibility: .visible
            ) {
                // Each choice shows the day it runs to, and what's saved now, so a
                // shorter pick can't quietly cut saved tides back.
                ForEach(0..<periods.count, id: \.self) { i in
                    Button("\(periods[i].label) · to \(TideText.dayMonthYear(ends[i]))") {
                        if let current, ends[i] < current {
                            let choice = TideFewerChoice(period: periods[i], current: current, end: ends[i])
                            Task { @MainActor in
                                // Let the choice sheet go away before the question appears.
                                try? await Task.sleep(nanoseconds: 350_000_000)
                                fewer = choice
                                showFewer = true
                            }
                        } else {
                            feature.saveOffline(periods[i])
                        }
                    }
                }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Keep fewer tides?", isPresented: $showFewer, presenting: fewer) { choice in
                Button("Keep fewer") { feature.saveOffline(choice.period) }
                Button("Cancel", role: .cancel) {}
            } message: { choice in
                Text("Tides here are saved to \(TideText.dayMonthYear(choice.current)). This keeps them only to \(TideText.dayMonthYear(choice.end)).")
            }
            .alert("Delete saved tides?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) { feature.deleteSavedTides() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("They can be downloaded again any time.")
            }
        }
    }
}

// Chart section ------------------------------------------------------------------------------

private struct TideDayGroup: Identifiable {
    let id: String
    let items: [TideExtreme]
}

/// The day picker, the chart, sun and moon, and the highs and lows.
private struct TideChartSection: View {
    let forecast: TideForecast
    let offset: Double
    let now: Int64
    let format: NavFormat
    @Binding var dayOffset: Int
    @State private var pickingDate = false

    var body: some View {
        let curve = forecast.curve
        let today = TideText.startOfDay(now, 0)
        let lastDay = Int(((curve.endMillis ?? now) - today) / tideDayMs) - 1
        let firstDay = -1
        let day = min(max(dayOffset, firstDay), max(0, lastDay))
        let start = day == 0 ? now - 3 * TideCurve.hourMs : TideText.startOfDay(now, day)
        let dayStart = TideText.startOfDay(now, day)

        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Button { dayOffset = day - 1 } label: {
                    Image(systemName: "chevron.left").frame(width: 40, height: 36)
                }
                .buttonStyle(.bordered)
                .disabled(day <= firstDay)
                .opacity(day > firstDay ? 1 : 0.3)
                .accessibilityLabel("Previous day")

                Button { pickingDate.toggle() } label: {
                    Text(dayLabel(day))
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Choose a date")

                if day != 0 {
                    Button("Today") { dayOffset = 0 }
                        .buttonStyle(.bordered)
                }
                Button { dayOffset = day + 1 } label: {
                    Image(systemName: "chevron.right").frame(width: 40, height: 36)
                }
                .buttonStyle(.bordered)
                .disabled(day >= lastDay)
                .opacity(day < lastDay ? 1 : 0.3)
                .accessibilityLabel("Next day")
            }

            if pickingDate {
                DatePicker(
                    "Choose a date",
                    selection: Binding(
                        get: { TideMath.date(dayStart) },
                        set: { chosen in
                            let cal = Calendar.current
                            let diff = cal.dateComponents([.day], from: TideMath.date(today), to: cal.startOfDay(for: chosen)).day ?? 0
                            dayOffset = min(max(diff, firstDay), max(0, lastDay))
                            pickingDate = false
                        }
                    ),
                    in: TideMath.date(TideText.startOfDay(now, firstDay))...TideMath.date(TideText.startOfDay(now, max(0, lastDay)) + tideDayMs - 1),
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                .labelsHidden()
            }

            TideGraphView(
                curve: curve,
                format: format,
                offsetM: offset,
                nowMillis: now,
                windowStartMillis: start,
                nights: nights(from: start - tideDayMs, to: start + 3 * tideDayMs)
            )
            .id(start)
            .padding(.top, 4)

            TideCaption(text: sunAndMoon(dayStart + tideDayMs / 2)).padding(.top, 6)
            TideCaption(text: "Drag along the chart to see the tide at any time.").padding(.top, 2)

            TideSectionHeader(text: "HIGHS AND LOWS")
            ForEach(groups(curve.extremes(from: dayStart, to: dayStart + 3 * tideDayMs))) { group in
                dayRow(group)
            }
        }
    }

    private func dayLabel(_ offset: Int) -> String {
        switch offset {
        case 0: return "Today"
        case 1: return "Tomorrow"
        case -1: return "Yesterday"
        default: return TideText.string(TideText.startOfDay(now, offset), "EEE d MMM yyyy")
        }
    }

    /// "Sunrise 05:41 · Sunset 18:22 · Full moon, spring tides" for the day around `noon`.
    private func sunAndMoon(_ noon: Int64) -> String {
        var sun = ""
        if let t = TideSunMoon.sunTimes(noon, lat: forecast.requestLat, lng: forecast.requestLng) {
            sun = "Sunrise \(TideText.hourMinute(t.rise)) · Sunset \(TideText.hourMinute(t.set)) · "
        }
        let moon = TideSunMoon.phaseName(noon)
        if let spring = TideSunMoon.springOrNeap(noon) { return sun + "\(moon), \(spring)" }
        return sun + moon
    }

    /// Sunset-to-sunrise spans between `from` and `to`, for shading the chart.
    private func nights(from: Int64, to: Int64) -> [(Int64, Int64)] {
        var suns: [(rise: Int64, set: Int64)] = []
        var d = TideText.startOfDay(from, 0) + tideDayMs / 2
        while d < to + tideDayMs {
            if let t = TideSunMoon.sunTimes(d, lat: forecast.requestLat, lng: forecast.requestLng) { suns.append(t) }
            d += tideDayMs
        }
        guard suns.count >= 2 else { return [] }
        return (0..<(suns.count - 1)).map { (suns[$0].set, suns[$0 + 1].rise) }
    }

    private func groups(_ extremes: [TideExtreme]) -> [TideDayGroup] {
        let f = TideText.formatter("EEEE d MMM")
        var out: [TideDayGroup] = []
        for e in extremes {
            let day = f.string(from: TideMath.date(e.timeMillis))
            if let last = out.last, last.id == day {
                out[out.count - 1] = TideDayGroup(id: day, items: last.items + [e])
            } else {
                out.append(TideDayGroup(id: day, items: [e]))
            }
        }
        return out
    }

    private func dayRow(_ group: TideDayGroup) -> some View {
        let items = Array(group.items.prefix(4))
        return VStack(alignment: .leading, spacing: 4) {
            Text(group.id)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .padding(.leading, 2)
                .padding(.top, 6)
            HStack(spacing: 6) {
                ForEach(0..<items.count, id: \.self) { i in
                    cell(items[i])
                }
                // Same cell width on days with fewer than four tides.
                ForEach(0..<(4 - items.count), id: \.self) { _ in
                    Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                }
            }
        }
    }

    private func cell(_ e: TideExtreme) -> some View {
        VStack(spacing: 2) {
            Text(e.isHigh ? "▲ High" : "▼ Low").font(.system(size: 11)).foregroundStyle(Theme.textMuted)
            Text(TideText.hourMinute(e.timeMillis)).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.textPrimary)
            Text(format.tideHeight(e.heightM + offset)).font(.system(size: 12)).foregroundStyle(Theme.textPrimary)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(e.isHigh ? tideBlue : Theme.border, lineWidth: 1))
        .opacity(e.timeMillis < now ? 0.45 : 1)
    }
}

// Where picker --------------------------------------------------------------------------------

/// Where tides come from: follow the boat, a saved place, or a new one.
struct TideLocationSheet: View {
    @ObservedObject var feature: TideFeature
    @State private var removing: TidePlace?
    @State private var confirmRemove = false

    var body: some View {
        let selectedId = feature.settings.placeId
        let now = TideMath.nowMillis()
        SheetScaffold(title: "Tides for") {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    TideCaption(text: "Tides use the nearest ocean point to where you choose.")
                    Button { feature.followMe() } label: {
                        TideOptionRow(icon: "location.fill", title: "Follow me", detail: "Where the boat is, as you travel", selected: selectedId == nil)
                    }
                    .buttonStyle(.plain)
                    Button { feature.startMapPick() } label: {
                        TideOptionRow(icon: "map", title: "Pick on the map", detail: "A harbour, reef or ramp")
                    }
                    .buttonStyle(.plain)
                    NavigationLink { TideSearchView(feature: feature) } label: {
                        TideOptionRow(icon: "magnifyingglass", title: "Search a place", detail: "A town or harbour by name")
                    }
                    .buttonStyle(.plain)

                    if !feature.places.all.isEmpty {
                        TideSectionHeader(text: "SAVED PLACES")
                        ForEach(feature.places.all) { place in
                            HStack(spacing: 6) {
                                Button { feature.choose(place) } label: {
                                    TideOptionRow(icon: "mappin.and.ellipse", title: place.name,
                                                  detail: detail(feature.store.savedUntil(place.id), now),
                                                  selected: place.id == selectedId)
                                }
                                .buttonStyle(.plain)
                                Button {
                                    removing = place
                                    confirmRemove = true
                                } label: {
                                    Image(systemName: "trash").frame(width: 40, height: 48)
                                }
                                .buttonStyle(.bordered)
                                .accessibilityLabel("Remove \(place.name)")
                            }
                        }
                    }
                }
                .padding(16)
            }
            .alert(removing.map { "Remove \($0.name)?" } ?? "Remove?", isPresented: $confirmRemove, presenting: removing) { place in
                Button("Remove", role: .destructive) { feature.deletePlace(place) }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("Its saved tides are deleted too.")
            }
        }
    }

    private func detail(_ until: Int64?, _ now: Int64) -> String {
        guard let until else { return "Live forecast only" }
        return until >= now ? "Offline until \(TideText.dayMonthYear(until))" : "Offline tides ran out \(TideText.dayMonthYear(until))"
    }
}

/// Place search: type a name, pick from the results.
private struct TideSearchView: View {
    @ObservedObject var feature: TideFeature
    @State private var query = ""
    @State private var status = ""
    @State private var results: [TidePlaceResult] = []
    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                TextField("Town, harbour or bay", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .focused($focused)
                    .onSubmit { run() }
                if !status.isEmpty { TideCaption(text: status) }
                ForEach(results) { r in
                    Button { feature.chooseSearchResult(r) } label: {
                        TideOptionRow(icon: "mappin.and.ellipse", title: r.name,
                                      detail: r.detail.isEmpty ? String(format: "%.3f, %.3f", r.lat, r.lng) : r.detail)
                    }
                    .buttonStyle(.plain)
                }
                Button { run() } label: { TideButtonLabel(title: "Search") }
                    .buttonStyle(.borderedProminent)
            }
            .padding(16)
        }
        .background(Theme.panelColor)
        .navigationTitle("Search a place")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { focused = true }
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.count < 2 {
            status = "Type at least two letters."
            return
        }
        status = "Searching…"
        results = []
        Task {
            do {
                let found = try await feature.store.search(q)
                results = found
                status = found.isEmpty ? "Nothing found for \"\(q)\"." : "Tap one to save it."
            } catch {
                status = TideText.message(error, fallback: "Search failed")
            }
        }
    }
}

/// Asks for a name for a new place.
struct TideNameSheet: View {
    let suggested: String
    let fromOpenStreetMap: Bool
    let onName: (String) -> Void
    @State private var name: String
    @FocusState private var focused: Bool

    init(suggested: String, fromOpenStreetMap: Bool, onName: @escaping (String) -> Void) {
        self.suggested = suggested
        self.fromOpenStreetMap = fromOpenStreetMap
        self.onName = onName
        _name = State(initialValue: suggested)
    }

    var body: some View {
        SheetScaffold(title: "Name this spot", doneLabel: "Cancel") {
            VStack(alignment: .leading, spacing: 8) {
                TextField("", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .focused($focused)
                    .submitLabel(.done)
                    .onSubmit { save() }
                if fromOpenStreetMap {
                    TideCaption(text: "Suggested name from © OpenStreetMap contributors (ODbL).")
                }
                Button { save() } label: { TideButtonLabel(title: "Save") }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 6)
                Spacer()
            }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .onAppear { focused = true }
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        onName(trimmed.isEmpty ? suggested : trimmed)
    }
}

// Settings ---------------------------------------------------------------------------------------

/// Tide settings, saved when leaving the screen (as Android saves on dismiss).
private struct TideSettingsView: View {
    @ObservedObject var feature: TideFeature
    private let original: TideSettings
    private let unit: HeightUnit
    private let autoDatum: Double?
    private let datumShown: String
    private let manualShown: String
    @State private var mode: TideMode
    @State private var datum: TideDatum
    @State private var datumText: String
    @State private var manualText: String
    @State private var correct: Bool
    @State private var chip: Bool

    init(feature: TideFeature) {
        self.feature = feature
        let s = feature.settings
        let u = feature.format.units.tide
        original = s
        unit = u
        autoDatum = feature.forecast?.lowestTideM.map { -$0 }
        let datumValue = s.chartDatumAuto ? "" : String(format: "%.2f", u.of(s.chartDatumBelowMslM))
        let manualValue = String(format: "%.2f", u.of(s.manualHeightM))
        datumShown = datumValue
        manualShown = manualValue
        _mode = State(initialValue: s.mode)
        _datum = State(initialValue: s.datum)
        _datumText = State(initialValue: datumValue)
        _manualText = State(initialValue: manualValue)
        _correct = State(initialValue: s.correctDepths)
        _chip = State(initialValue: s.showChip)
    }

    var body: some View {
        Form {
            Section {
                Picker("Tides from", selection: $mode) {
                    ForEach([TideMode.model, .manual, .off], id: \.self) { m in
                        Text(modeLabel(m)).tag(m)
                    }
                }
                Picker("Survey depths are from", selection: $datum) {
                    ForEach(TideDatum.allCases, id: \.self) { d in
                        Text(datumLabel(d)).tag(d)
                    }
                }
            }
            Section {
                TextField(autoDatum.map { String(format: "Auto: %.2f here", unit.of($0)) } ?? "Auto", text: $datumText)
                    .keyboardType(.decimalPad)
            } header: {
                Text("Lowest tide (chart datum) below mean sea level, \(unit.label)")
            } footer: {
                Text("Leave empty to work it out from the tide model. Type your tide table's value (0–\(unit == .feet ? "32" : "10") \(unit.label)) to use that instead.")
            }
            Section {
                TextField("", text: $manualText)
                    .keyboardType(.numbersAndPunctuation)
            } header: {
                Text("Manual tide height, from a tide table (\(unit.label))")
            }
            Section {
                Toggle("Add the tide to depths and alarms", isOn: $correct)
                Toggle("Show the tide on the chart", isOn: $chip)
            }
        }
        .tint(Theme.teal)
        .navigationTitle("Tide settings")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { save() }
    }

    /// Left as shown keeps the exact value: rounding through another unit would slowly change it.
    private func typedMetres(_ text: String, _ shown: String, _ original: Double) -> Double? {
        if text == shown && !text.trimmingCharacters(in: .whitespaces).isEmpty { return original }
        let clean = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        return Double(clean).map { unit.toMetres($0) }
    }

    private func save() {
        // Anything empty or out of reason (a slip of the finger) means auto / unchanged.
        let typedDatum = typedMetres(datumText, datumShown, original.chartDatumBelowMslM).flatMap { TideSettings.validChartDatum($0) ? $0 : nil }
        let typedManual = typedMetres(manualText, manualShown, original.manualHeightM).flatMap { TideSettings.validTideHeight($0) ? $0 : nil }
        var s = feature.settings
        s.mode = mode
        s.datum = datum
        s.chartDatumAuto = typedDatum == nil
        s.chartDatumBelowMslM = typedDatum ?? original.chartDatumBelowMslM
        s.manualHeightM = typedManual ?? original.manualHeightM
        s.showChip = chip
        s.correctDepths = correct
        feature.applySettings(s)
    }

    private func modeLabel(_ mode: TideMode) -> String {
        switch mode {
        case .model: return "Forecast (free, works offline once saved)"
        case .manual: return "Manual height"
        case .off: return "Off"
        }
    }

    private func datumLabel(_ datum: TideDatum) -> String {
        switch datum {
        case .meanSeaLevel: return "Mean sea level"
        case .chartDatum: return "Chart datum (lowest tide, LAT)"
        }
    }
}
