import SwiftUI
import UIKit

/**
 The "Depth colours" sheet (Android ui/DepthRampDialog.kt): palette, depth range,
 exact values, slope shading and presets for one depth layer. Each change goes to
 `onChange`, which recolours the layer; the file is never reread. Depths are shown in
 metres, negative below sea level, as on Android.
 */
struct DepthRampSheet: View {
    private let units: NavUnits
    @StateObject private var model: DepthRampSheetModel
    @FocusState private var focus: DepthRampField?

    init(layerName: String, ramp: DepthRampSettings, source: DepthLayerSource?, units: NavUnits,
         onChange: @escaping (DepthRampSettings) -> Void) {
        self.units = units
        _model = StateObject(wrappedValue: DepthRampSheetModel(layerName: layerName, ramp: ramp, source: source,
                                                               onChange: onChange))
    }

    var body: some View {
        SheetScaffold(title: "Depth colours") {
            Form {
                Section(header: Text("LAYER")) {
                    Text(model.layerCaption)
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                }
                .listRowBackground(Theme.surface)

                Section(header: Text("PALETTE PREVIEW")) {
                    VStack(spacing: 4) {
                        legend(model.legend)
                            .frame(height: 30)
                        HStack {
                            Text(DepthRampFormat.depth(model.ramp.shallowMetres))
                            Spacer()
                            Text(DepthRampFormat.depth(model.ramp.deepMetres))
                        }
                        .font(.system(size: 10))
                        .foregroundStyle(Theme.textMuted)
                    }
                    .padding(.vertical, 4)
                }
                .listRowBackground(Theme.surface)

                Section(header: Text("DEPTH RANGE")) {
                    if let coverage = model.coverageText {
                        Text(coverage)
                            .font(.footnote)
                            .foregroundStyle(model.coverageWarning ? Theme.red : Theme.textMuted)
                    }
                    sliderBlock(title: "Shallow end", value: DepthRampFormat.depth(model.ramp.shallowMetres),
                                binding: model.shallowBinding, range: model.lowerBound...model.upperBound)
                    sliderBlock(title: "Deep end", value: DepthRampFormat.depth(model.ramp.deepMetres),
                                binding: model.deepBinding, range: model.lowerBound...model.upperBound)
                }
                .listRowBackground(Theme.surface)

                Section(header: Text("EXACT VALUES")) {
                    HStack(spacing: 8) {
                        TextField("Shallow, e.g. -5", text: $model.shallowText)
                            .focused($focus, equals: .shallow)
                            .onChange(of: model.shallowText) { text in
                                if focus == .shallow { model.typedShallow(text) }
                            }
                        TextField("Deep, e.g. -15", text: $model.deepText)
                            .focused($focus, equals: .deep)
                            .onChange(of: model.deepText) { text in
                                if focus == .deep { model.typedDeep(text) }
                            }
                    }
                    .font(.system(.body, design: .monospaced))
                    .keyboardType(.numbersAndPunctuation)
                    .submitLabel(.done)
                    .onSubmit { focus = nil }
                }
                .listRowBackground(Theme.surface)

                Section(header: Text("HILLSHADE")) {
                    Text("How strongly slopes and channels are shaded. 0 is flat.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                    sliderBlock(title: nil, value: DepthRampFormat.exaggeration(model.ramp.hillshadeExaggeration),
                                binding: model.hillshadeBinding,
                                range: DepthRampSettings.minHillshadeExaggeration...DepthRampSettings.maxHillshadeExaggeration)
                    TextField("Exaggeration", text: $model.hillshadeText)
                        .focused($focus, equals: .hillshade)
                        .font(.system(.body, design: .monospaced))
                        .keyboardType(.decimalPad)
                        .onSubmit { focus = nil }
                }
                .listRowBackground(Theme.surface)

                Section(header: Text("COLOURS")) {
                    NavigationLink {
                        DepthPaletteList(selectedId: model.ramp.paletteId, invert: model.ramp.invert) { id in
                            model.choosePalette(id)
                        }
                    } label: {
                        valueRow("Colour palette", DepthPalette.byId(model.ramp.paletteId).label)
                    }
                    Button { model.toggleInvert() } label: {
                        valueRow("Reverse ramp direction", model.ramp.invert ? "On" : "Off")
                    }
                    Button { model.toggleOutside() } label: {
                        valueRow("Outside the range", DepthRampFormat.outsideLabel(model.ramp.showOutside))
                    }
                }
                .listRowBackground(Theme.surface)

                Section(header: Text("PRESETS")) {
                    ForEach(DepthRampPreset.all) { preset in
                        Button(preset.label) { model.applyPreset(preset) }
                            .foregroundStyle(Theme.teal)
                    }
                }
                .listRowBackground(Theme.surface)
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focus = nil }
                }
            }
        }
        .onChange(of: focus) { newFocus in model.focusMoved(to: newFocus) }
        .task { await model.loadHistogram() }
    }

    @ViewBuilder
    private func legend(_ image: UIImage?) -> some View {
        if let image {
            Image(uiImage: image)
                .resizable()
                .interpolation(.medium)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(Theme.border, lineWidth: 1))
        } else {
            RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Theme.surfaceRaised)
        }
    }

    private func sliderBlock(title: String?, value: String, binding: Binding<Double>,
                             range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let title {
                Text(title).font(.footnote).foregroundStyle(Theme.textMuted)
            }
            Text(value)
                .font(.system(size: 15, design: .monospaced))
                .foregroundStyle(Theme.teal)
            Slider(value: binding, in: range, onEditingChanged: { editing in
                model.dragging(editing)
            })
            .tint(Theme.teal)
        }
        .padding(.vertical, 2)
    }

    private func valueRow(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(Theme.textPrimary)
            Text(value).font(.footnote).foregroundStyle(Theme.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

fileprivate enum DepthRampField: Hashable { case shallow, deep, hillshade }

/// The palette choice list, each with a preview strip.
fileprivate struct DepthPaletteList: View {
    let selectedId: String
    let invert: Bool
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(DepthPalette.all) { palette in
                Button {
                    onPick(palette.id)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(palette.label).foregroundStyle(Theme.textPrimary)
                            if let strip = DepthImageRenderer.legendImage(
                                ramp: DepthRampSettings(paletteId: palette.id, invert: invert), width: 256, height: 12) {
                                Image(uiImage: strip)
                                    .resizable()
                                    .frame(height: 12)
                                    .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))
                            }
                        }
                        if palette.id == selectedId {
                            Image(systemName: "checkmark").foregroundStyle(Theme.teal)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .listRowBackground(Theme.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.panelColor)
        .navigationTitle("Colour palette")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A named shallow/deep pair offered as a one-tap range.
struct DepthRampPreset: Identifiable {
    let label: String
    let build: (_ dataShallowest: Double, _ dataDeepest: Double, _ histogram: DepthHistogram?) -> (Double, Double)
    var id: String { label }

    static let all: [DepthRampPreset] = [
        DepthRampPreset(label: "Fit to this layer's depths") { shallowest, deepest, histogram in
            let r = DepthRampFit.fitRange(shallowestMetres: shallowest, deepestMetres: deepest, histogram: histogram)
            return (r.shallow, r.deep)
        },
        DepthRampPreset(label: "Reef and shallows: -1 m to -15 m") { _, _, _ in (-1.0, -15.0) },
        DepthRampPreset(label: "Inshore detail: -10 m to -30 m") { _, _, _ in (-10.0, -30.0) },
        DepthRampPreset(label: "Coastal: -6 m to -100 m") { _, _, _ in (-6.0, -100.0) },
        DepthRampPreset(label: "Offshore: -20 m to -500 m") { _, _, _ in (-20.0, -500.0) },
    ]
}

/// Number formatting shared by the sheet and its tests (same wording as Android).
enum DepthRampFormat {
    /// "-12.5 m" below sea level, "+3.0 m" for land above it.
    static func depth(_ metres: Double) -> String {
        if metres < 0 { return String(format: "%.1f m", metres) }
        if metres > 0 { return String(format: "+%.1f m", metres) }
        return "0.0 m"
    }

    /// A typed level: negative below sea level, positive above it.
    static func parse(_ text: String) -> Double? {
        let cleaned = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let v = Double(cleaned), v.isFinite else { return nil }
        return v
    }

    static func plain(_ metres: Double) -> String {
        let rounded = metres.rounded()
        return abs(metres - rounded) < 0.05 ? String(Int(rounded)) : String(format: "%.1f", metres)
    }

    static func exaggeration(_ value: Double) -> String { String(format: "%.1fx", value) }

    static func outsideLabel(_ show: Bool) -> String {
        show ? "Shown in the end colours" : "Hidden — only the range is drawn"
    }

    /// Under this share of a layer in range, warn that it will look empty.
    static let nearlyNone = 0.05

    /// How much of the layer a range shows, and whether to warn.
    static func coverage(_ histogram: DepthHistogram, ramp: DepthRampSettings) -> (text: String, warning: Bool) {
        let share = histogram.fractionWithin(ramp.shallowMetres, ramp.deepMetres)
        let pct = share * 100
        let percent = (pct > 0 && pct < 1) ? "under 1" : String(format: "%.0f", pct)
        if ramp.showOutside {
            return ("\(percent)% of this layer is in this range; the rest is shown in the end colours.", false)
        }
        if share < nearlyNone {
            return ("Only \(percent)% of this layer is in this range, so it will look almost empty. Depths outside are hidden.", true)
        }
        return ("Shows \(percent)% of this layer. Depths outside the range are hidden.", false)
    }
}

@MainActor
fileprivate final class DepthRampSheetModel: ObservableObject {
    @Published private(set) var ramp: DepthRampSettings
    @Published private(set) var legend: UIImage? = nil
    @Published private(set) var histogram: DepthHistogram? = nil
    @Published var shallowText = ""
    @Published var deepText = ""
    @Published var hillshadeText = ""

    let layerCaption: String
    let lowerBound: Double
    let upperBound: Double
    private let dataShallowest: Double
    private let dataDeepest: Double
    private let source: DepthLayerSource?
    private let onChange: (DepthRampSettings) -> Void
    private var isDragging = false
    private var pending: DispatchWorkItem?
    private var lastPublished: DepthRampSettings?
    private var previousFocus: DepthRampField?

    private static let recolourDelay = 0.09
    private static let sliderPadding = 5.0
    private static let minBand = 1.0

    init(layerName: String, ramp: DepthRampSettings, source: DepthLayerSource?,
         onChange: @escaping (DepthRampSettings) -> Void) {
        let start = ramp.normalised()
        self.ramp = start
        self.source = source
        self.onChange = onChange
        var shallowest = source?.shallowestMetres ?? start.shallowMetres
        var deepest = source?.deepestMetres ?? start.deepMetres
        if !shallowest.isFinite || !deepest.isFinite {
            shallowest = start.shallowMetres
            deepest = start.deepMetres
        }
        dataShallowest = shallowest
        dataDeepest = deepest
        // Headroom beyond the data so the range can be clipped as well as widened.
        let dataShallow = shallowest.rounded(.up)
        let dataDeep = deepest.rounded(.down)
        let padding = max(Self.sliderPadding, (dataShallow - dataDeep) * 0.25)
        upperBound = dataShallow + padding
        lowerBound = dataDeep - padding
        layerCaption = "\(layerName)\nData range \(DepthRampFormat.depth(shallowest)) to \(DepthRampFormat.depth(deepest)). "
            + "Depths below sea level are negative (-10 = 10 m deep); land above it is +."
        legend = DepthImageRenderer.legendImage(ramp: start)
        syncInputs()
        lastPublished = start
    }

    var coverageText: String? {
        guard let histogram else { return nil }
        return DepthRampFormat.coverage(histogram, ramp: ramp).text
    }

    var coverageWarning: Bool {
        guard let histogram else { return false }
        return DepthRampFormat.coverage(histogram, ramp: ramp).warning
    }

    func loadHistogram() async {
        guard let source, histogram == nil else { return }
        let found = await Task.detached(priority: .userInitiated) { source.histogram() }.value
        histogram = found
    }

    // Sliders ---------------------------------------------------------------

    private func clampToSlider(_ v: Double) -> Double { min(max(v, lowerBound), upperBound) }
    private func tenth(_ v: Double) -> Double { (v * 10).rounded() / 10 }

    var shallowBinding: Binding<Double> {
        Binding(get: { [unowned self] in self.clampToSlider(self.ramp.shallowMetres) },
                set: { [unowned self] value in
                    // The ends never meet or cross: an empty band hid the whole layer.
                    let shallow = max(self.tenth(value), self.ramp.deepMetres + Self.minBand)
                    var next = self.ramp
                    next.shallowMetres = shallow
                    self.apply(next, syncInputs: true, coalesce: self.isDragging)
                })
    }

    var deepBinding: Binding<Double> {
        Binding(get: { [unowned self] in self.clampToSlider(self.ramp.deepMetres) },
                set: { [unowned self] value in
                    let deep = min(self.tenth(value), self.ramp.shallowMetres - Self.minBand)
                    var next = self.ramp
                    next.deepMetres = deep
                    self.apply(next, syncInputs: true, coalesce: self.isDragging)
                })
    }

    var hillshadeBinding: Binding<Double> {
        Binding(get: { [unowned self] in self.ramp.hillshadeExaggeration },
                set: { [unowned self] value in
                    var next = self.ramp
                    next.hillshadeExaggeration = DepthRampSettings.coerceHillshadeExaggeration(self.tenth(value))
                    self.apply(next, syncInputs: true, coalesce: self.isDragging)
                })
    }

    func dragging(_ editing: Bool) {
        isDragging = editing
        if !editing { publish(ramp, coalesce: false) }
    }

    // Text fields -------------------------------------------------------------

    /// Each parseable edit applies as it is typed, so closing the sheet straight after typing keeps it.
    func typedShallow(_ text: String) {
        guard let v = DepthRampFormat.parse(text) else { return }
        var next = ramp
        next.shallowMetres = v
        apply(next, syncInputs: false, coalesce: true)
    }

    func typedDeep(_ text: String) {
        guard let v = DepthRampFormat.parse(text) else { return }
        var next = ramp
        next.deepMetres = v
        apply(next, syncInputs: false, coalesce: true)
    }

    func focusMoved(to newFocus: DepthRampField?) {
        let left = previousFocus
        previousFocus = newFocus
        guard let left, left != newFocus else { return }
        var next = ramp
        switch left {
        case .shallow:
            if let v = DepthRampFormat.parse(shallowText) { next.shallowMetres = v }
        case .deep:
            if let v = DepthRampFormat.parse(deepText) { next.deepMetres = v }
        case .hillshade:
            if let v = DepthRampFormat.parse(hillshadeText) {
                next.hillshadeExaggeration = DepthRampSettings.coerceHillshadeExaggeration(v)
            }
        }
        apply(next, syncInputs: true, coalesce: false)
    }

    // Choices -----------------------------------------------------------------

    func choosePalette(_ id: String) {
        var next = ramp
        next.paletteId = id
        apply(next, syncInputs: false, coalesce: false)
    }

    func toggleInvert() {
        var next = ramp
        next.invert.toggle()
        apply(next, syncInputs: false, coalesce: false)
    }

    func toggleOutside() {
        var next = ramp
        next.showOutside.toggle()
        apply(next, syncInputs: false, coalesce: false)
    }

    func applyPreset(_ preset: DepthRampPreset) {
        let range = preset.build(dataShallowest, dataDeepest, histogram)
        var next = ramp
        next.shallowMetres = range.0
        next.deepMetres = range.1
        apply(next, syncInputs: true, coalesce: false)
    }

    // Publishing --------------------------------------------------------------

    private func apply(_ next: DepthRampSettings, syncInputs sync: Bool, coalesce: Bool) {
        let normalised = next.normalised()
        if normalised.paletteId != ramp.paletteId || normalised.invert != ramp.invert || legend == nil {
            legend = DepthImageRenderer.legendImage(ramp: normalised)
        }
        ramp = normalised
        if sync { syncInputs() }
        publish(normalised, coalesce: coalesce)
    }

    private func syncInputs() {
        shallowText = DepthRampFormat.plain(ramp.shallowMetres)
        deepText = DepthRampFormat.plain(ramp.deepMetres)
        hillshadeText = DepthRampFormat.plain(ramp.hillshadeExaggeration)
    }

    /// Repainting a big grid costs real work, so drags coalesce into one repaint per
    /// short interval while discrete choices apply immediately.
    private func publish(_ settings: DepthRampSettings, coalesce: Bool) {
        pending?.cancel()
        pending = nil
        if coalesce {
            let work = DispatchWorkItem { [weak self] in self?.send(settings) }
            pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.recolourDelay, execute: work)
        } else {
            send(settings)
        }
    }

    private func send(_ settings: DepthRampSettings) {
        if settings == lastPublished { return }
        lastPublished = settings
        onChange(settings)
    }
}
