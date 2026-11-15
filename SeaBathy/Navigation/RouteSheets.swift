import SwiftUI

/// The saved-routes list (Android RouteDialogs.showLibrary).
struct SavedRoutesSheet: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        SavedRoutesContent(library: app.navigation.routeLibrary, store: app.navSettings)
    }
}

private struct RenameTarget: Identifiable {
    let id: String
    let name: String
}

private struct SavedRoutesContent: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) var dismiss
    @ObservedObject var library: RouteLibrary
    @ObservedObject var store: NavSettingsStore
    @State var renaming: RenameTarget?
    @State var deleting: SavedRoute?

    var body: some View {
        let settings = store.settings
        let format = settings.format
        SheetScaffold(title: "Saved routes") {
            List {
                Section {
                    HStack(spacing: 8) {
                        Button {
                            saveCurrent()
                        } label: {
                            Text("Save current route").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        Button {
                            dismiss()
                            NavigationFeature.later(0.5) { GpxActions.importGpx(app: app) }
                        } label: {
                            Text("Import GPX").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    .listRowBackground(Color.clear)
                }
                if library.all.isEmpty {
                    Text("No saved routes yet. Plan one on the chart and save it, or import a GPX file.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                        .listRowBackground(Color.clear)
                }
                ForEach(library.all) { route in
                    let plan = RoutePlan(points: route.points, speedKn: settings.cruiseSpeedKn, burnLph: settings.fuelBurnLph)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(route.name).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.textPrimary)
                        Text(summary(route, plan, format)).font(.system(size: 12)).foregroundStyle(Theme.textMuted)
                        HStack(spacing: 6) {
                            small("Go", primary: true) {
                                dismiss()
                                app.navigation.navigateRoute(route)
                            }
                            small("Load") {
                                dismiss()
                                app.navigation.loadRoute(route)
                            }
                            small("Reverse") {
                                library.save(id: route.id, name: route.name, points: route.points.reversed())
                            }
                        }
                        HStack(spacing: 6) {
                            small("Rename") { renaming = RenameTarget(id: route.id, name: route.name) }
                            small("GPX") { app.navigation.shareRouteGpx(route.points, name: route.name) }
                            small("Delete", destructive: true) { deleting = route }
                        }
                    }
                    .padding(.vertical, 6)
                    .listRowBackground(Theme.surfaceRaised)
                }
            }
        }
        .sheet(item: $renaming) { target in
            RouteNameSheet(title: "Rename route", initial: target.name,
                           validate: { name in NavigationFeature.routeNameError(name, library: library, exceptId: target.id) },
                           onName: { name in
                               library.rename(id: target.id, name: name)
                           })
            .presentationDetents([.medium])
        }
        .alert("Delete \(deleting?.name ?? "route")?", isPresented: Binding(
            get: { deleting != nil },
            set: { if !$0 { deleting = nil } })) {
            Button("Delete", role: .destructive) {
                if let route = deleting {
                    library.delete(id: route.id)
                    app.navigation.routeDeleted(route)
                }
                deleting = nil
            }
            Button("Cancel", role: .cancel) { deleting = nil }
        }
    }

    private func summary(_ route: SavedRoute, _ plan: RoutePlan, _ f: NavFormat) -> String {
        var text = "\(route.points.count) points • \(f.distance(plan.totalNm)) • \(f.duration(plan.totalHours))"
        if let fuel = plan.totalFuelLitres { text += " • \(f.fuel(fuel))" }
        return text
    }

    private func saveCurrent() {
        guard app.navigation.planner.points.count >= 2 else {
            app.toast("Plan a route with at least two points first")
            return
        }
        dismiss()
        NavigationFeature.later(0.5) { app.navigation.saveCurrentRoute() }
    }

    private func small(_ title: String, primary: Bool = false, destructive: Bool = false,
                       action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(primary ? Theme.teal : Theme.surface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.border, lineWidth: primary ? 0 : 1))
                .foregroundStyle(primary ? Theme.navy : (destructive ? Theme.red : Theme.textPrimary))
        }
        .buttonStyle(.borderless)
    }
}

/// Asks for a route name; `onName` gets a trimmed name that passed `validate` (Android promptName).
struct RouteNameSheet: View {
    @Environment(\.dismiss) var dismiss
    let title: String
    let initial: String
    let validate: (String) -> String?
    let onName: (String) -> Void
    @State var name = ""
    @State var error: String?
    @State var started = false
    @FocusState var focused: Bool

    init(title: String, initial: String, validate: @escaping (String) -> String?, onName: @escaping (String) -> Void) {
        self.title = title
        self.initial = initial
        self.validate = validate
        self.onName = onName
    }

    var body: some View {
        SheetScaffold(title: title, doneLabel: "Cancel") {
            Form {
                Section {
                    TextField("Route name", text: $name)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit { save() }
                    if let error {
                        Text(error).font(.caption).foregroundStyle(Theme.red)
                    }
                }
                Section {
                    Button("Save") { save() }
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            name = initial
            focused = true
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if let message = validate(trimmed) {
            error = message
            return
        }
        dismiss()
        onName(trimmed)
    }
}

/**
 Every leg with distance, bearing, time, arrival and fuel, the totals, and the
 shallowest water per leg against the boat's draft + margin (Android showLegs).
 */
struct RouteLegsSheet: View {
    let title: String
    let plan: RoutePlan
    let speedKn: Double
    let settings: NavSettings
    let variation: (Double, Double) -> Double
    let departureMillis: Int64
    let legDepths: [RouteDepthCheck?]?

    var body: some View {
        let f = settings.format
        let minDepth = settings.minimumDepthM
        SheetScaffold(title: title) {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Departing now at \(f.speed(speedKn))"
                         + (settings.fuelSet ? ", burning \(f.fuel(settings.fuelBurnLph))/h" : "")
                         + ". Depths are survey depths (no tide).")
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                    ForEach(plan.legs, id: \.number) { leg in
                        let depth = legDepths.flatMap { leg.number - 1 < $0.count ? $0[leg.number - 1] : nil }
                        let water = depth.map { -$0.shallowestMetres }
                        let shallow = water.map { $0 < minDepth } ?? false
                        Text(legText(leg, f: f, depth: depth, water: water, shallow: shallow, minDepth: minDepth))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(shallow ? Color(hex: "#FF6B6B") : Theme.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .stroke(shallow ? Color(hex: "#EF4444") : Theme.border, lineWidth: 1))
                    }
                    Text("TOTAL")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.textMuted)
                        .padding(.top, 10)
                    Text(totalText(f: f, minDepth: minDepth))
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textPrimary)
                }
                .padding(16)
            }
        }
    }

    private func legText(_ leg: PlanLeg, f: NavFormat, depth: RouteDepthCheck?, water: Double?, shallow: Bool, minDepth: Double) -> String {
        let trimmedName = (leg.to.name ?? "").trimmingCharacters(in: .whitespaces)
        let name = trimmedName.isEmpty ? "Point \(leg.number + 1)" : trimmedName
        let variationHere = variation(leg.from.lat, leg.from.lng)
        var text = "Leg \(leg.number) → \(name)\n"
        text += "\(f.bearing(leg.bearingTrue, variation: variationHere))   \(f.distance(leg.distanceNm))   \(f.duration(leg.hours))"
        text += "   arrive \(f.clock(plan.arrivalMillis(leg, departureMillis: departureMillis), now: departureMillis))"
        if let fuel = leg.fuelLitres { text += "   \(f.fuel(fuel))" }
        text += "\nTotal \(f.distance(leg.cumulativeNm))"
        if depth == nil && legDepths != nil {
            text += "   •   no survey on this leg"
        } else if let water {
            text += "   •   least depth \(f.depth(water))\(shallow ? "  ⚠ less than \(f.depth(minDepth))" : "")"
        }
        return text
    }

    private func totalText(f: NavFormat, minDepth: Double) -> String {
        var text = "\(plan.legs.count) legs, \(f.distance(plan.totalNm)), \(f.duration(plan.totalHours))"
        if let hours = plan.totalHours {
            text += "\nArrive \(f.clock(departureMillis + Int64(hours * RoutePlan.hourMs), now: departureMillis))"
        }
        if let fuel = plan.totalFuelLitres {
            text += "\nFuel \(f.fuel(fuel))"
            let usable = settings.usableFuelLitres
            if usable > 0 {
                text += fuel > usable ? "  ⚠ more than the usable \(f.fuel(usable))" : ", leaving \(f.fuel(usable - fuel)) plus reserve"
            }
        }
        if let worst = legDepths?.compactMap({ $0 }).max(by: { $0.shallowestMetres < $1.shallowestMetres }) {
            let water = -worst.shallowestMetres
            text += "\nLeast depth on route \(f.depth(water))"
            if water < minDepth { text += "  ⚠ below your \(f.depth(minDepth)) (draft + margin)" }
        }
        return text
    }
}
