import SwiftUI

/// The drawer's searchable waypoint list (Android WaypointPanelBinder).
struct WaypointListSection: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        WaypointListContent(store: app.waypoints)
    }
}

private struct WaypointListContent: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var store: WaypointStore
    @State var query = ""

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        let matches = store.all.filter { w in
            q.isEmpty || w.name.localizedCaseInsensitiveContains(q) || (w.desc ?? "").localizedCaseInsensitiveContains(q)
        }
        VStack(alignment: .leading, spacing: 0) {
            TextField("Search waypoints", text: $query)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .submitLabel(.search)
                .autocorrectionDisabled()
                .padding(.horizontal, 12).padding(.vertical, 9)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.border, lineWidth: 1))
                .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 6)
            if matches.isEmpty {
                Text(store.all.isEmpty ? "No waypoints yet" : "No matching waypoints")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textMuted)
                    .padding(.horizontal, 16).padding(.vertical, 12)
            } else {
                ForEach(matches) { w in row(w) }
            }
        }
    }

    private func row(_ w: Waypoint) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Color(hex: w.color)).frame(width: 12, height: 12)
            VStack(alignment: .leading, spacing: 1) {
                Text(w.name).font(.system(size: 13)).foregroundStyle(Theme.textPrimary).lineLimit(1)
                let desc = (w.desc ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                Text(desc.isEmpty ? CoordinateFormatter.formatDdm(w.lat, w.lng) : desc)
                    .font(.system(size: 10)).foregroundStyle(Theme.textMuted).lineLimit(1)
            }
            Spacer(minLength: 4)
            chip("GO", tint: Theme.teal, label: "Go to \(w.name)") {
                hideKeyboard()
                app.navigation.flyTo(w.lat, w.lng)
                app.sidePanelOpen = false
            }
            chip("EDIT", tint: Theme.textPrimary, label: "Edit \(w.name)") {
                app.navigation.showWaypointEditor(id: w.id)
            }
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
    }

    private func chip(_ title: String, tint: Color, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .frame(width: title == "GO" ? 42 : 52, height: 30)
                .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.border, lineWidth: 1))
                .foregroundStyle(tint)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

/**
 The "New waypoint" sheet (Android NewWaypointSheet). The position is held here
 rather than re-read from the camera on save, so the keyboard or a nudge of the
 map can't move the waypoint.
 */
struct NewWaypointSheet: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) var dismiss
    @State var lat: Double
    @State var lng: Double
    @State var name = ""
    @State var desc = ""
    @State var color = Waypoint.defaultColor
    @State var icon = WaypointIcon.defaultIconId
    @State var error: String?
    @State var editingCoordinates = false
    @State var saved = false

    init(lat: Double, lng: Double) {
        _lat = State(initialValue: lat)
        _lng = State(initialValue: lng)
    }

    var body: some View {
        SheetScaffold(title: "New waypoint", doneLabel: "Cancel") {
            Form {
                Section {
                    TextField("Waypoint name", text: $name)
                        .submitLabel(.done)
                        .onSubmit { save() }
                    if let error {
                        Text(error).font(.caption).foregroundStyle(Theme.red)
                    }
                    TextField("Description / notes", text: $desc, axis: .vertical)
                        .lineLimit(2...3)
                }
                Section {
                    WaypointColourPicker(selected: $color)
                    WaypointIconPicker(selected: $icon)
                }
                Section {
                    Button { editingCoordinates = true } label: {
                        Text(CoordinateFormatter.formatDdm(lat, lng))
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Theme.textPrimary)
                    }
                }
                Section {
                    Button("Save waypoint") { save() }
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .sheet(isPresented: $editingCoordinates) {
            CoordinateEditorSheet(title: "Waypoint coordinates", initialLat: lat, initialLng: lng,
                                  positiveLabel: "Use coordinates") { newLat, newLng in
                lat = newLat
                lng = newLng
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func save() {
        // A stray repeat (a second Return) must not add a second waypoint.
        guard !saved else { return }
        if let message = app.navigation.saveNewWaypoint(name: name, description: desc, lat: lat, lng: lng,
                                                       color: color, icon: icon) {
            error = message
            return
        }
        saved = true
        dismiss()
    }
}

/// Edits a waypoint's name, notes and position (Android WaypointEditDialog).
struct WaypointEditSheet: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) var dismiss
    let waypointId: String
    @State var name = ""
    @State var desc = ""
    @State var color = Waypoint.defaultColor
    @State var icon = WaypointIcon.defaultIconId
    @State var lat = 0.0
    @State var lng = 0.0
    @State var loaded = false
    @State var error: String?
    @State var editingCoordinates = false
    @State var confirmingDelete = false

    var body: some View {
        SheetScaffold(title: "Waypoint", doneLabel: "Cancel") {
            Form {
                Section {
                    TextField("Name", text: $name)
                    if let error {
                        Text(error).font(.caption).foregroundStyle(Theme.red)
                    }
                    Button { editingCoordinates = true } label: {
                        Text("\(CoordinateFormatter.formatDdm(lat, lng))   •   EDIT COORDINATES")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Theme.teal)
                    }
                    TextField("Description / notes", text: $desc, axis: .vertical)
                        .lineLimit(2...4)
                }
                Section {
                    WaypointColourPicker(selected: $color)
                    WaypointIconPicker(selected: $icon)
                }
                Section {
                    HStack {
                        Button("Delete", role: .destructive) { confirmingDelete = true }
                            .buttonStyle(.borderless)
                        Spacer()
                        Button("Jump") { jump() }
                            .buttonStyle(.bordered)
                        Button("Save") { save() }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .onAppear(perform: load)
        .sheet(isPresented: $editingCoordinates) {
            CoordinateEditorSheet(title: "Waypoint coordinates", initialLat: lat, initialLng: lng,
                                  positiveLabel: "Save") { newLat, newLng in
                lat = newLat
                lng = newLng
                // Android saves a new position at once.
                if var w = app.waypoints.byId(waypointId) {
                    w.lat = newLat
                    w.lng = newLng
                    app.waypoints.update(w)
                }
            }
            .presentationDetents([.medium, .large])
        }
        .alert("Delete \(name.isEmpty ? "waypoint" : originalName)?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                app.waypoints.remove(id: waypointId)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var originalName: String { app.waypoints.byId(waypointId)?.name ?? name }

    private func load() {
        guard !loaded, let w = app.waypoints.byId(waypointId) else { return }
        loaded = true
        name = w.name
        desc = w.desc ?? ""
        color = w.color
        icon = WaypointIcon.byId(w.icon).id
        lat = w.lat
        lng = w.lng
    }

    private func save() {
        guard var w = app.waypoints.byId(waypointId) else { dismiss(); return }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let newName = trimmed.isEmpty ? w.name : trimmed
        if newName.caseInsensitiveCompare(w.name) != .orderedSame && app.waypoints.isNameTaken(newName, exceptId: w.id) {
            error = "A waypoint called \"\(newName)\" already exists"
            return
        }
        let notes = desc.trimmingCharacters(in: .whitespacesAndNewlines)
        w.name = newName
        w.desc = notes.isEmpty ? nil : notes
        w.color = color
        w.icon = icon
        w.lat = lat
        w.lng = lng
        app.waypoints.update(w)
        dismiss()
    }

    private func jump() {
        dismiss()
        app.navigation.flyTo(lat, lng)
        app.sidePanelOpen = false
    }
}

/// The six waypoint colours (Android colour swatches).
struct WaypointColourPicker: View {
    @Binding var selected: String

    var body: some View {
        HStack(spacing: 10) {
            ForEach(Waypoint.palette, id: \.self) { hex in
                let isSelected = hex.caseInsensitiveCompare(selected) == .orderedSame
                Circle()
                    .fill(Color(hex: hex))
                    .frame(width: 32, height: 32)
                    .overlay(Circle().stroke(Color.white, lineWidth: isSelected ? 3 : 0))
                    .scaleEffect(isSelected ? 1.15 : 1)
                    .onTapGesture { selected = hex }
                    .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
    }
}

/// The waypoint symbols (Android icon picker).
struct WaypointIconPicker: View {
    @Binding var selected: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(WaypointIcon.all) { icon in
                    let isSelected = icon.id == selected
                    Image(systemName: icon.symbol)
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 40, height: 40)
                        .background(isSelected ? Theme.teal : Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.border, lineWidth: 1))
                        .foregroundStyle(isSelected ? Theme.navy : Theme.textPrimary)
                        .onTapGesture { selected = icon.id }
                        .accessibilityLabel(icon.label)
                        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
                }
            }
            .padding(.vertical, 2)
        }
    }
}

/**
 Coordinate entry accepting decimal degrees, degrees with decimal minutes or
 degrees-minutes-seconds, with a live preview (Android CoordinateEditorDialog).
 Closes itself before calling back.
 */
struct CoordinateEditorSheet: View {
    @Environment(\.dismiss) var dismiss
    let title: String
    let initialLat: Double
    let initialLng: Double
    let positiveLabel: String
    var showMoveButton = false
    var onMove: ((Double, Double) -> Void)? = nil
    let onPositive: (Double, Double) -> Void

    @State var text = ""
    @State var modeIndex = 0
    @State var error: String?
    @State var started = false

    init(title: String, initialLat: Double, initialLng: Double, positiveLabel: String,
         showMoveButton: Bool = false, onMove: ((Double, Double) -> Void)? = nil,
         onPositive: @escaping (Double, Double) -> Void) {
        self.title = title
        self.initialLat = initialLat
        self.initialLng = initialLng
        self.positiveLabel = positiveLabel
        self.showMoveButton = showMoveButton
        self.onMove = onMove
        self.onPositive = onPositive
    }

    var body: some View {
        let parsed = CoordinateFormatter.parsePair(text)
        SheetScaffold(title: title, doneLabel: "Cancel") {
            Form {
                Section("FORMAT") {
                    Picker("Format", selection: $modeIndex) {
                        ForEach(0..<CoordinateFormatter.modeLabels.count, id: \.self) { i in
                            Text(CoordinateFormatter.modeLabels[i]).tag(i)
                        }
                    }
                    .pickerStyle(.menu)
                    .onChange(of: modeIndex) { index in
                        let p = CoordinateFormatter.parsePair(text) ?? (initialLat, initialLng)
                        text = CoordinateFormatter.formatPair(p.0, p.1, mode: CoordinateFormatter.modes[index])
                    }
                }
                Section("COORDINATES") {
                    TextField("Paste latitude and longitude", text: $text, axis: .vertical)
                        .font(.system(.body, design: .monospaced))
                        .lineLimit(2...4)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.characters)
                    if let error {
                        Text(error).font(.caption).foregroundStyle(Theme.red)
                    }
                }
                Section("PREVIEW") {
                    Text(parsed.map { CoordinateFormatter.formatDdm($0.0, $0.1) } ?? "Enter valid coordinates")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(parsed == nil ? Theme.red : Theme.textPrimary)
                }
                Section {
                    HStack {
                        Spacer()
                        if showMoveButton {
                            Button("Move map") {
                                guard let p = parsedOrError() else { return }
                                dismiss()
                                onMove?(p.0, p.1)
                            }
                            .buttonStyle(.bordered)
                        }
                        Button(positiveLabel) {
                            guard let p = parsedOrError() else { return }
                            dismiss()
                            onPositive(p.0, p.1)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            text = CoordinateFormatter.formatDdm(initialLat, initialLng)
        }
    }

    private func parsedOrError() -> (Double, Double)? {
        let p = CoordinateFormatter.parsePair(text)
        error = p == nil ? "Use DD, DDM or DMS coordinates" : nil
        return p
    }
}
