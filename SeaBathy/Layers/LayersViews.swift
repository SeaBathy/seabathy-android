import SwiftUI

// The drawer sections and the sheets of the layers feature (Android LayerPanelBinder,
// BasemapSelector, XyzTileDialog, CustomBasemapDialog, DataSourceDialog,
// DownloadZoneDialog, ImportProgressDialog), with the Android wording.

// MARK: - Drawer: map layers

/// The drawer's map layer list, top of the stack first.
struct LayerListSection: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        LayerListContent(feature: app.layers)
    }
}

private struct LayerListContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var feature: LayersFeature

    var body: some View {
        if feature.layers.isEmpty && feature.unavailable.isEmpty {
            Text("No map layers loaded yet")
                .font(.caption)
                .foregroundStyle(Theme.textMuted)
        }
        ForEach(feature.layers) { layer in
            LayerRow(feature: feature, layer: layer)
        }
        // Listed last: they sit below the loaded layers in the saved stack.
        ForEach(feature.unavailable) { layer in
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(layer.name).font(.subheadline).lineLimit(1).foregroundStyle(Theme.textMuted)
                    Text("Couldn't load — will try again next launch").font(.caption2).foregroundStyle(Theme.textMuted)
                }
                Spacer()
                LayersChip(label: "REMOVE", accessibility: "Remove \(layer.name)", destructive: true) {
                    confirmRemove(layer)
                }
            }
        }
    }

    private func confirmRemove(_ layer: OverlayLayer) {
        app.confirm("Remove \(layer.name)?", "Remove this layer from the map? Your original file is not deleted.",
                    confirm: "Remove", destructive: true) {
            feature.remove(layer.id)
        }
    }
}

private struct LayerRow: View {
    @EnvironmentObject var app: AppModel
    let feature: LayersFeature
    let layer: OverlayLayer

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button { feature.showLayer(layer) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(layer.name).font(.subheadline).lineLimit(1).foregroundStyle(Theme.textPrimary)
                        Text(subtitle).font(.caption2).lineLimit(1).foregroundStyle(Theme.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Go to \(layer.name) on the map")

                Toggle("", isOn: Binding(get: { layer.visible }, set: { feature.setVisible(layer.id, $0) }))
                    .labelsHidden()
                    .tint(Theme.teal)
                    .accessibilityLabel("Show \(layer.name)")
            }

            Slider(value: Binding(get: { layer.sanitisedOpacity },
                                  set: { feature.setOpacity(layer.id, $0, persistNow: false) }),
                   in: 0...1, step: 1.0 / Double(LayerPanelFormat.opacitySteps), onEditingChanged: { editing in
                // Saved once the drag finishes, not on every step.
                if !editing, let current = feature.layers.first(where: { $0.id == layer.id }) {
                    feature.setOpacity(layer.id, current.opacity, persistNow: true)
                }
            })
            .tint(Theme.teal)
            .accessibilityLabel("Opacity for \(layer.name)")

            HStack(spacing: 5) {
                LayersChip(label: "▲", accessibility: "Move \(layer.name) up") { feature.move(layer.id, 1) }
                LayersChip(label: "▼", accessibility: "Move \(layer.name) down") { feature.move(layer.id, -1) }
                LayersChip(label: "BOTTOM", accessibility: "Send \(layer.name) to the bottom") { feature.sendToBottom(layer.id) }
                Spacer(minLength: 4)
                LayersChip(label: "REMOVE", accessibility: "Remove \(layer.name)", destructive: true) {
                    let id = layer.id
                    app.confirm("Remove \(layer.name)?", "Remove this layer from the map? Your original file is not deleted.",
                                confirm: "Remove", destructive: true) {
                        feature.remove(id)
                    }
                }
            }

            HStack(spacing: 5) {
                LayersChip(label: "RENAME", accessibility: "Rename \(layer.name)") { feature.showRename(layer) }
                if feature.hasDepth(layer.id) {
                    LayersChip(label: "COLOURS", accessibility: "Depth colours for \(layer.name)", wide: true) {
                        feature.showDepthColours(layerId: layer.id)
                    }
                    LayersChip(label: "3D VIEW", accessibility: "Show \(layer.name) in 3D", wide: true) {
                        feature.open3d(layer)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var subtitle: String {
        let opacity = LayerPanelFormat.formatOpacity(layer.sanitisedOpacity)
        switch layer.kind {
        case .mbtiles:
            return "MBTiles • \(opacity)"
        case .geotiff:
            if feature.hasDepth(layer.id), let ramp = layer.ramp {
                return "Depth GeoTIFF • \(Int(ramp.shallowMetres.rounded())) to \(Int(ramp.deepMetres.rounded())) m • \(opacity)"
            }
            return "Image GeoTIFF • \(opacity)"
        case .xyz:
            return "Online tiles • \(opacity)"
        }
    }
}

/// A small labelled control on a layer or source row.
private struct LayersChip: View {
    let label: String
    let accessibility: String
    var destructive = false
    var wide = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .padding(.horizontal, 8)
                .frame(minWidth: 38, maxWidth: wide ? .infinity : nil, minHeight: 28)
                .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.border, lineWidth: 0.5))
                .foregroundStyle(destructive ? Theme.red : Theme.textPrimary)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(accessibility)
    }
}

// MARK: - Drawer: basemap

/// The basemap choice at the foot of the drawer. "Custom" always opens its picker.
struct BasemapPickerSection: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        BasemapPickerContent(basemap: app.basemap)
    }
}

private struct BasemapPickerContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var basemap: BasemapController

    var body: some View {
        ForEach(0..<BasemapController.options.count, id: \.self) { index in
            let option = BasemapController.options[index]
            Button {
                if option.type == BasemapType.custom {
                    app.layers.showCustomBasemap()
                } else if option.type != basemap.currentType {
                    basemap.select(option.type)
                }
            } label: {
                HStack {
                    Text(option.label).foregroundStyle(Theme.textPrimary)
                    Spacer()
                    if option.type == basemap.currentType {
                        Image(systemName: "checkmark").foregroundStyle(Theme.teal)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
        }
    }
}

// MARK: - Tile presets

private struct LayersPresetRow: View {
    let preset: XyzTilePreset
    let actionLabel: String
    let onChoose: () -> Void

    var body: some View {
        Button(action: onChoose) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(preset.label).font(.subheadline.weight(.bold)).foregroundStyle(Theme.textPrimary)
                    Text(preset.description).font(.caption).foregroundStyle(Theme.textMuted)
                    Text(preset.category.label).font(.caption2).foregroundStyle(Theme.teal)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(actionLabel)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Theme.teal, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .foregroundStyle(Theme.navy)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
    }
}

private struct LayersLinkFields: View {
    @Binding var url: String
    @Binding var attribution: String
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Tile URL template").font(.caption).foregroundStyle(Theme.textMuted)
            TextField("https://example.com/tiles/{z}/{x}/{y}.png", text: $url, axis: .vertical)
                .lineLimit(2...4)
                .font(.system(.footnote, design: .monospaced))
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        VStack(alignment: .leading, spacing: 4) {
            Text("Attribution").font(.caption).foregroundStyle(Theme.textMuted)
            TextField("Provider credit shown on the map", text: $attribution, axis: .vertical)
                .lineLimit(2...4)
        }
        if let error {
            Text(error).font(.caption).foregroundStyle(Theme.red)
        }
    }
}

// MARK: - Online map layers

/// Ready-made tile layers, or your own tile link as a layer or as the basemap.
struct LayersXyzSheet: View {
    @EnvironmentObject var app: AppModel
    @State private var url = ""
    @State private var attribution = ""
    @State private var error: String?
    @State private var loaded = false
    @State private var confirmClear = false

    var body: some View {
        SheetScaffold(title: "Online map layers") {
            Form {
                Section {
                    ForEach(XyzTilePresets.all) { preset in
                        LayersPresetRow(preset: preset, actionLabel: "ADD") {
                            app.dismissSheet()
                            app.layers.addXyzPreset(preset)
                        }
                    }
                } header: {
                    Text("ADD A LAYER")
                } footer: {
                    Text("Adds it to your layers, with the same show/hide and opacity as your own maps.")
                }

                Section {
                    LayersLinkFields(url: $url, attribution: $attribution, error: error)
                    Button("Add as a layer") {
                        guard let link = validated() else { return }
                        app.dismissSheet()
                        app.layers.addXyz(name: "Custom tiles", urlTemplate: link, attribution: trimmedAttribution)
                        app.toast("Added Custom tiles")
                    }
                    .fontWeight(.semibold)
                    Button("Use as the basemap") {
                        guard let link = validated() else { return }
                        app.dismissSheet()
                        app.layers.useAsBasemap(url: link, attribution: trimmedAttribution)
                        app.toast("Custom basemap saved")
                    }
                    Button("Clear the custom basemap", role: .destructive) { confirmClear = true }
                } header: {
                    Text("YOUR OWN TILE LINK")
                } footer: {
                    Text("A link with {z}, {x} and {y} in it. Check the provider's licence allows this.")
                }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            url = app.basemap.customUrl
            attribution = app.basemap.customAttribution
        }
        .alert("Clear the custom basemap?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) {
                url = ""
                attribution = ""
                error = nil
                app.basemap.clearCustomBasemap()
                app.toast("Custom basemap cleared")
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its link and credit are removed.")
        }
    }

    private var trimmedAttribution: String { attribution.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func validated() -> String? {
        let link = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let message = XyzTilePresets.validationError(link) {
            error = message
            return nil
        }
        error = nil
        return link
    }
}

// MARK: - Custom basemap

/// Shown when "Custom" is picked as the basemap: a ready-made source or your own link.
struct LayersCustomBasemapSheet: View {
    @EnvironmentObject var app: AppModel
    @State private var url = ""
    @State private var attribution = ""
    @State private var error: String?

    var body: some View {
        SheetScaffold(title: "Custom basemap") {
            Form {
                Section {
                    ForEach(XyzTilePresets.all) { preset in
                        LayersPresetRow(preset: preset, actionLabel: "USE") {
                            app.dismissSheet()
                            app.layers.useAsBasemap(url: preset.urlTemplate, attribution: preset.attribution)
                        }
                    }
                } header: {
                    Text("READY-MADE")
                } footer: {
                    Text("Its credit is shown on the map for you.")
                }

                Section {
                    LayersLinkFields(url: $url, attribution: $attribution, error: error)
                    Button("Use as the basemap") {
                        let link = url.trimmingCharacters(in: .whitespacesAndNewlines)
                        if let message = XyzTilePresets.validationError(link) {
                            error = message
                            return
                        }
                        app.dismissSheet()
                        app.layers.useAsBasemap(url: link, attribution: attribution.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                    .fontWeight(.semibold)
                } header: {
                    Text("YOUR OWN TILE LINK")
                } footer: {
                    Text("A link with {z}, {x} and {y} in it. Check the provider's licence allows this.")
                }
            }
        }
    }
}

// MARK: - Offline maps (data sources)

/// Add or remove tile sources, and download the area on screen from one.
struct LayersDataSourceSheet: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        LayersDataSourceContent(feature: app.layers)
    }
}

private struct LayersDataSourceContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var feature: LayersFeature
    @State private var name = ""
    @State private var url = ""
    @State private var attribution = ""
    @State private var nameError: String?
    @State private var urlError: String?
    @State private var removing: DataSource?

    var body: some View {
        SheetScaffold(title: "Offline maps") {
            Form {
                if let download = feature.download {
                    Section {
                        Button {
                            feature.showDownloadProgress()
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Downloading \(download.sourceName)").foregroundStyle(Theme.textPrimary)
                                ProgressView(value: download.fraction).tint(Theme.teal)
                                Text(download.text).font(.caption).foregroundStyle(Theme.textMuted)
                            }
                        }
                    }
                }

                Section("MAP SOURCES") {
                    if feature.dataSources.all.isEmpty {
                        Text("None yet: add one below.").font(.caption).foregroundStyle(Theme.textMuted)
                    }
                    ForEach(feature.dataSources.all) { source in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(source.name).font(.subheadline)
                            Text(source.urlTemplate).font(.caption2).lineLimit(1).truncationMode(.middle)
                                .foregroundStyle(Theme.textMuted)
                            if !source.attribution.isEmpty {
                                Text(source.attribution).font(.caption2).lineLimit(1).foregroundStyle(Theme.textMuted)
                            }
                            HStack {
                                LayersChip(label: "DOWNLOAD", accessibility: "Download \(source.name) for the area on screen") {
                                    feature.beginDownload(source)
                                }
                                Spacer()
                                if !source.builtIn {
                                    LayersChip(label: "REMOVE", accessibility: "Remove \(source.name)", destructive: true) {
                                        removing = source
                                    }
                                }
                            }
                            .padding(.top, 4)
                        }
                        .padding(.vertical, 2)
                    }
                }

                Section {
                    TextField("Source name", text: $name)
                    if let nameError { Text(nameError).font(.caption).foregroundStyle(Theme.red) }
                    TextField("https://example.com/{z}/{x}/{y}.png", text: $url)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if let urlError { Text(urlError).font(.caption).foregroundStyle(Theme.red) }
                    TextField("Credit shown on the map (optional)", text: $attribution)
                    Button("Add source") { add() }
                } header: {
                    Text("ADD A SOURCE")
                } footer: {
                    Text("A map or satellite tile link with {z}, {x} and {y} in it. Check its licence allows saving tiles offline.")
                }
            }
        }
        .alert("Remove \(removing?.name ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
               presenting: removing) { source in
            Button("Remove", role: .destructive) { feature.removeDataSource(source) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Only the link is removed; maps already downloaded stay.")
        }
    }

    private func add() {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        nameError = nil
        urlError = nil
        if n.isEmpty {
            nameError = "Give this source a name"
            return
        }
        if !DataSourceStore.isPlausibleTileTemplate(u) {
            urlError = "A full http(s) link with {z}, {x} and {y}"
            return
        }
        feature.addDataSource(name: n, urlTemplate: u, attribution: attribution.trimmingCharacters(in: .whitespacesAndNewlines))
        name = ""
        url = ""
        attribution = ""
    }
}

// MARK: - Download an offline map

/// Pick zooms, then "Assess" (nothing is fetched before that), then "Download".
/// Changing the zooms after assessing turns Download off until assessed again.
struct LayersDownloadZoneSheet: View {
    @EnvironmentObject var app: AppModel
    let source: DataSource
    let bounds: GeoBounds
    @State private var minText: String
    @State private var maxText: String
    @State private var status = "Tap Assess to see how big the download is. Nothing is downloaded yet."
    @State private var statusIsError = false
    @State private var assessing = false
    @State private var assessLabel = "Assess"
    @State private var assessedRange: ClosedRange<Int>?

    init(source: DataSource, bounds: GeoBounds, defaultMinZoom: Int, defaultMaxZoom: Int) {
        self.source = source
        self.bounds = bounds
        _minText = State(initialValue: String(defaultMinZoom))
        _maxText = State(initialValue: String(defaultMaxZoom))
    }

    var body: some View {
        SheetScaffold(title: "Download an offline map") {
            Form {
                Section("AREA") {
                    Text("The area on screen. To change it, close this, move the map and open it again.\n\n"
                         + "\(source.name)\n"
                         + "West \(coord(bounds.west))   East \(coord(bounds.east))\n"
                         + "South \(coord(bounds.south))   North \(coord(bounds.north))")
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                }
                Section {
                    HStack {
                        TextField("Min zoom", text: $minText).keyboardType(.numberPad)
                        TextField("Max zoom", text: $maxText).keyboardType(.numberPad)
                    }
                } header: {
                    Text("ZOOM RANGE")
                } footer: {
                    Text("A higher max zoom has more detail, but many more tiles.")
                }
                Section("SIZE ESTIMATE") {
                    Text(status).font(.footnote).foregroundStyle(statusIsError ? Theme.red : Theme.textMuted)
                    HStack(spacing: 8) {
                        Button(assessLabel) { assess() }
                            .buttonStyle(.bordered)
                            .disabled(assessing)
                            .frame(maxWidth: .infinity)
                        Button("Download") { confirm() }
                            .buttonStyle(.borderedProminent)
                            .disabled(assessedRange == nil)
                            .opacity(assessedRange == nil ? 0.4 : 1)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .onChange(of: minText) { _ in rangeEdited() }
        .onChange(of: maxText) { _ in rangeEdited() }
    }

    private func coord(_ value: Double) -> String { String(format: "%.4f", value) }

    private func readZoomRange() -> ClosedRange<Int>? {
        guard let lo = Int(minText.trimmingCharacters(in: .whitespaces)),
              let hi = Int(maxText.trimmingCharacters(in: .whitespaces)),
              lo >= 0, hi <= LayersTiles.maxSaneZoom, lo <= hi else { return nil }
        return lo...hi
    }

    private func rangeEdited() {
        assessedRange = nil
        statusIsError = false
        status = "Zoom range changed — tap Assess again."
    }

    private func assess() {
        guard let range = readZoomRange() else {
            statusIsError = true
            status = "Zooms run 0–\(LayersTiles.maxSaneZoom), min no higher than max."
            return
        }
        assessing = true
        assessLabel = "Assessing…"
        statusIsError = false
        status = "Checking a few tiles…"
        let source = self.source, bounds = self.bounds
        Task {
            let result = await app.layers.assess(source, bounds: bounds, zoomRange: range)
            assessing = false
            if result.ok {
                // The fields changed while sampling: this estimate is for a range no longer shown.
                if readZoomRange() != range {
                    assessLabel = "Assess"
                    return
                }
                assessedRange = range
                assessLabel = "Re-assess"
                statusIsError = false
                status = result.message
            } else {
                assessLabel = "Assess"
                statusIsError = true
                status = result.message
                assessedRange = nil
            }
        }
    }

    private func confirm() {
        guard let range = assessedRange, range == readZoomRange() else { return }
        app.layers.startDownload(source, bounds: bounds, zoomRange: range)
    }
}

/// Progress of the running offline download.
struct LayersDownloadProgressSheet: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        LayersDownloadProgressContent(feature: app.layers)
    }
}

private struct LayersDownloadProgressContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var feature: LayersFeature

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let download = feature.download {
                Text("Downloading \(download.sourceName)").font(.headline)
                ProgressView(value: download.fraction).tint(Theme.teal)
                Text(download.text).font(.footnote).foregroundStyle(Theme.textMuted)
                HStack {
                    Button("Cancel", role: .destructive) { feature.cancelDownload() }
                        .buttonStyle(.bordered)
                    Spacer()
                    Button("Hide") { app.dismissSheet() }
                        .buttonStyle(.bordered)
                }
            } else {
                Text("No download running").foregroundStyle(Theme.textMuted)
                Button("Close") { app.dismissSheet() }.buttonStyle(.bordered)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.panelColor)
        .foregroundStyle(Theme.textPrimary)
        .tint(Theme.teal)
        .preferredColorScheme(.dark)
    }
}

// MARK: - Small sheets

/// Stays up while a file imports, so a long import doesn't look frozen.
struct LayersProgressSheet: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        LayersProgressContent(feature: app.layers)
    }
}

private struct LayersProgressContent: View {
    @ObservedObject var feature: LayersFeature

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(feature.progressTitle).font(.headline)
            HStack(spacing: 12) {
                ProgressView()
                Text(feature.progressText).font(.subheadline)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.panelColor)
        .foregroundStyle(Theme.textPrimary)
        .preferredColorScheme(.dark)
        .interactiveDismissDisabled()
    }
}

struct LayersRenameSheet: View {
    @EnvironmentObject var app: AppModel
    let layerId: String
    @State private var name: String

    init(layerId: String, name: String) {
        self.layerId = layerId
        _name = State(initialValue: name)
    }

    var body: some View {
        SheetScaffold(title: "Rename layer", doneLabel: "Cancel") {
            Form {
                Section {
                    TextField("Layer name", text: $name)
                    Button("Save") {
                        app.layers.rename(layerId, name)
                        app.dismissSheet()
                    }
                    .fontWeight(.semibold)
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

/// A list to pick one item from (Android PanelDialogs.showChoicePanel).
struct LayersChoiceSheet: View {
    @EnvironmentObject var app: AppModel
    let title: String
    let items: [(String, String)]
    let onChoose: (String) -> Void

    var body: some View {
        SheetScaffold(title: title, doneLabel: "Cancel") {
            List {
                ForEach(0..<items.count, id: \.self) { index in
                    Button(items[index].1) { onChoose(items[index].0) }
                        .foregroundStyle(Theme.textPrimary)
                }
            }
        }
    }
}
