import SwiftUI

/**
 Boat profile, units, arrival and alarm settings (Android NavSettingsDialog,
 "Boat & navigation"). Values are entered in the units chosen at the top and
 stored in metric/knots. Saved however the sheet is closed, as on Android.
 */
struct NavSettingsSheet: View {
    @ObservedObject var store: NavSettingsStore

    @State private var units: NavUnits
    /// The units the number fields currently hold their values in.
    @State private var shownUnits: NavUnits
    @State private var cruise = ""
    @State private var burn = ""
    @State private var tank = ""
    @State private var onBoard = ""
    @State private var reserve = ""
    @State private var draft = ""
    @State private var safety = ""
    @State private var arrival = ""
    @State private var xte = ""
    @State private var speedSource: PlanSpeedSource
    @State private var autoAdvance: Bool
    @State private var arrivalAlarm: Bool
    @State private var offCourseAlarm: Bool
    @State private var shallowAlarm: Bool
    @State private var sound: Bool
    @State private var vibrate: Bool
    @State private var screenOn: Bool
    @State private var courseUp: Bool
    @State private var night: Bool
    @State private var started = false

    init(store: NavSettingsStore) {
        self.store = store
        let s = store.settings
        _units = State(initialValue: s.units)
        _shownUnits = State(initialValue: s.units)
        _speedSource = State(initialValue: s.speedSource)
        _autoAdvance = State(initialValue: s.autoAdvance)
        _arrivalAlarm = State(initialValue: s.arrivalAlarm)
        _offCourseAlarm = State(initialValue: s.offCourseAlarm)
        _shallowAlarm = State(initialValue: s.shallowAlarm)
        _sound = State(initialValue: s.alarmSound)
        _vibrate = State(initialValue: s.alarmVibrate)
        _screenOn = State(initialValue: s.keepScreenOn)
        _courseUp = State(initialValue: s.courseUp)
        _night = State(initialValue: s.nightFilter)
    }

    var body: some View {
        SheetScaffold(title: "Boat & navigation") {
            Form {
                Section("UNITS") {
                    choice("Distance", selection: $units.distance, options: DistanceUnit.allCases,
                           labels: ["Nautical miles (nm)", "Kilometres (km)", "Statute miles (mi)"])
                    choice("Speed", selection: $units.speed, options: SpeedUnit.allCases,
                           labels: ["Knots (kn)", "km/h", "mph"])
                    choice("Depth", selection: $units.depth, options: DepthUnit.allCases,
                           labels: ["Metres (m)", "Feet (ft)", "Fathoms (fm)"])
                    choice("Fuel", selection: $units.fuel, options: FuelUnit.allCases,
                           labels: ["Litres (L)", "US gallons", "UK gallons"])
                    choice("Bearings", selection: $units.bearing, options: BearingReference.allCases,
                           labels: ["True (°T)", "Magnetic (°M)"])
                }
                Section("TIDES AND SEA CONDITIONS") {
                    choice("Tide height", selection: $units.tide, options: HeightUnit.allCases,
                           labels: ["Metres (m)", "Feet (ft)"])
                    choice("Wind", selection: $units.wind, options: SeaSpeedUnit.allCases,
                           labels: ["Knots (kn)", "km/h", "mph", "Metres per second (m/s)", "Beaufort (Bft)"])
                    choice("Waves and swell", selection: $units.waves, options: HeightUnit.allCases,
                           labels: ["Metres (m)", "Feet (ft)"])
                    choice("Current", selection: $units.current,
                           options: [SeaSpeedUnit.knots, .kmh, .mph, .metresPerSecond],
                           labels: ["Knots (kn)", "km/h", "mph", "Metres per second (m/s)"])
                    choice("Sea temperature", selection: $units.temperature, options: TemperatureUnit.allCases,
                           labels: ["Celsius (°C)", "Fahrenheit (°F)"])
                }
                Section {
                    field("Cruise speed", units.speed.label, $cruise)
                    Picker("ETAs from", selection: $speedSource) {
                        Text("Cruise speed").tag(PlanSpeedSource.cruise)
                        Text("GPS speed when moving").tag(PlanSpeedSource.gps)
                    }
                    .pickerStyle(.menu)
                    field("Fuel burn per hour", "\(units.fuel.label)/h", $burn)
                    field("Tank capacity", units.fuel.label, $tank)
                    field("Fuel on board", units.fuel.label, $onBoard)
                    caption("Fuel burn 0 turns the fuel figures off. Fuel on board 0 counts as a full tank.")
                    field("Reserve", "% of tank", $reserve)
                    caption(economyText)
                    field("Draft", units.depth.label, $draft)
                    field("Safety margin under keel", units.depth.label, $safety)
                } header: { Text("BOAT") }
                Section("NAVIGATION") {
                    field("Arrival radius", Self.smallLabel(units), $arrival)
                    field("Off-course limit", Self.smallLabel(units), $xte)
                    Toggle("Go to the next point on arrival", isOn: $autoAdvance)
                }
                Section("ALARMS") {
                    Toggle("Arriving at a point", isOn: $arrivalAlarm)
                    Toggle("Off course", isOn: $offCourseAlarm)
                    Toggle("Shallow water (draft + margin)", isOn: $shallowAlarm)
                    Toggle("Sound", isOn: $sound)
                    Toggle("Vibrate", isOn: $vibrate)
                    Toggle("Keep screen on while navigating", isOn: $screenOn)
                }
                Section {
                    Toggle("Course up (chart turns with the boat)", isOn: $courseUp)
                    Toggle("Night red filter", isOn: $night)
                } header: {
                    Text("DISPLAY")
                } footer: {
                    Text("Alarms work while SeaBathy is open, and with the screen off while navigating. Shallow checks use your surveys.")
                }
            }
            .tint(Theme.teal)
        }
        .onAppear(perform: fill)
        .onChange(of: units) { newUnits in convertFields(to: newUnits) }
        .onDisappear { store.settings = read(shownUnits) }
    }

    // Fields ---------------------------------------------------------------------------

    private func choice<T: Hashable>(_ title: String, selection: Binding<T>, options: [T], labels: [String]) -> some View {
        Picker(title, selection: selection) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                Text(index < labels.count ? labels[index] : "\(option)").tag(option)
            }
        }
        .pickerStyle(.menu)
    }

    private func field(_ title: String, _ unit: String, _ text: Binding<String>) -> some View {
        HStack {
            Text("\(title) (\(unit))")
            Spacer()
            TextField("0", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 100)
        }
    }

    private func caption(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(Theme.textMuted)
    }

    /// Arrival and lane sizes use metres, or feet with miles.
    static func smallFactor(_ u: NavUnits) -> Double { u.distance == .statuteMiles ? NavFormat.feetPerMetre : 1 }
    static func smallLabel(_ u: NavUnits) -> String { u.distance == .statuteMiles ? "ft" : "m" }

    static func show(_ value: Double) -> String {
        if value == 0 { return "0" }
        var s = String(format: "%.2f", value)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    private static func value(_ text: String) -> Double {
        guard let v = Double(text.replacingOccurrences(of: ",", with: ".")), v.isFinite, v >= 0 else { return 0 }
        return v
    }

    private func fill() {
        guard !started else { return }
        started = true
        setFields(store.settings, in: units)
    }

    private func setFields(_ s: NavSettings, in u: NavUnits) {
        cruise = Self.show(s.cruiseSpeedKn * u.speed.perKnot)
        burn = Self.show(s.fuelBurnLph * u.fuel.perLitre)
        tank = Self.show(s.tankLitres * u.fuel.perLitre)
        onBoard = Self.show(s.fuelOnBoardLitres * u.fuel.perLitre)
        reserve = Self.show(s.reservePercent)
        draft = Self.show(s.draftM * u.depth.perMetre)
        safety = Self.show(s.safetyMarginM * u.depth.perMetre)
        arrival = Self.show(s.arrivalRadiusM * Self.smallFactor(u))
        xte = Self.show(s.xteLimitM * Self.smallFactor(u))
    }

    /// Fields hold numbers in the units shown; switching units converts them.
    private func convertFields(to newUnits: NavUnits) {
        let before = read(shownUnits)
        shownUnits = newUnits
        setFields(before, in: newUnits)
    }

    private func read(_ previous: NavUnits) -> NavSettings {
        var s = store.settings
        s.units = units
        let cruiseKn = Self.value(cruise) / previous.speed.perKnot
        // An empty or out-of-range cruise speed keeps the old one rather than zero.
        if cruiseKn > 0 && cruiseKn <= NavSettings.maxCruiseKn { s.cruiseSpeedKn = cruiseKn }
        s.speedSource = speedSource
        s.fuelBurnLph = Self.value(burn) / previous.fuel.perLitre
        s.tankLitres = Self.value(tank) / previous.fuel.perLitre
        s.fuelOnBoardLitres = Self.value(onBoard) / previous.fuel.perLitre
        s.reservePercent = min(max(Self.value(reserve), 0), 90)
        s.draftM = Self.value(draft) / previous.depth.perMetre
        s.safetyMarginM = Self.value(safety) / previous.depth.perMetre
        s.arrivalRadiusM = max(Self.value(arrival) / Self.smallFactor(previous), 5)
        s.xteLimitM = max(Self.value(xte) / Self.smallFactor(previous), 5)
        s.autoAdvance = autoAdvance
        s.alarmSound = sound
        s.alarmVibrate = vibrate
        s.offCourseAlarm = offCourseAlarm
        s.arrivalAlarm = arrivalAlarm
        s.shallowAlarm = shallowAlarm
        s.keepScreenOn = screenOn
        s.courseUp = courseUp
        s.nightFilter = night
        return s
    }

    private var economyText: String {
        let s = read(shownUnits)
        guard let perNm = s.litresPerNm else { return "Set a fuel burn to see economy and range." }
        let perDistance = perNm / s.units.distance.perNm * s.units.fuel.perLitre
        let range = s.rangeNm.map { "  Range \(s.format.distance($0))." } ?? ""
        return String(format: "%.2f %@ per %@ at cruise.%@", perDistance, s.units.fuel.label, s.units.distance.label, range)
    }
}
