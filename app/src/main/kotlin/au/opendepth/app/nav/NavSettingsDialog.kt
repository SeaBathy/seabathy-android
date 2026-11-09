package au.opendepth.app.nav

import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.PanelDialogs
import au.opendepth.app.ui.dp
import java.util.Locale

/**
 * Boat profile, units, arrival and alarm settings. Values are entered in
 * the units chosen at the top and stored in metric/knots.
 */
class NavSettingsDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    fun show(initial: NavSettings, onSave: (NavSettings) -> Unit) {
        var units = initial.units
        val body = panels.body()

        // Units ---------------------------------------------------------------
        body.addView(panels.sectionHeader("UNITS"))
        lateinit var rebuildLabels: () -> Unit
        fun choiceRow(title: String, current: () -> String, labels: List<String>, selected: () -> Int, onPick: (Int) -> Unit) {
            lateinit var row: TextView
            row = panels.row(title, current()) {
                panels.showChoicePanel(title, labels, selected()) { index ->
                    onPick(index)
                    row.text = "$title\n${current()}"
                    rebuildLabels()
                }
            }
            panels.addRow(body, row)
        }
        choiceRow("Distance", { units.distance.label }, listOf("Nautical miles (nm)", "Kilometres (km)", "Statute miles (mi)"),
            { units.distance.ordinal }) { units = units.copy(distance = DistanceUnit.entries[it]) }
        choiceRow("Speed", { units.speed.label }, listOf("Knots (kn)", "km/h", "mph"),
            { units.speed.ordinal }) { units = units.copy(speed = SpeedUnit.entries[it]) }
        choiceRow("Depth", { units.depth.label }, listOf("Metres (m)", "Feet (ft)", "Fathoms (fm)"),
            { units.depth.ordinal }) { units = units.copy(depth = DepthUnit.entries[it]) }
        choiceRow("Fuel", { units.fuel.label }, listOf("Litres (L)", "US gallons", "UK gallons"),
            { units.fuel.ordinal }) { units = units.copy(fuel = FuelUnit.entries[it]) }
        choiceRow("Bearings", { if (units.bearing == BearingReference.TRUE) "True (°T)" else "Magnetic (°M)" },
            listOf("True (°T)", "Magnetic (°M)"), { units.bearing.ordinal }) {
            units = units.copy(bearing = BearingReference.entries[it])
        }

        // Tides and sea conditions --------------------------------------------
        body.addView(panels.sectionHeader("TIDES AND SEA CONDITIONS"))
        val heightLabels = listOf("Metres (m)", "Feet (ft)")
        val currentLabels = listOf("Knots (kn)", "km/h", "mph", "Metres per second (m/s)")
        choiceRow("Tide height", { units.tide.label }, heightLabels,
            { units.tide.ordinal }) { units = units.copy(tide = HeightUnit.entries[it]) }
        choiceRow("Wind", { units.wind.label }, currentLabels + "Beaufort (Bft)",
            { units.wind.ordinal }) { units = units.copy(wind = SeaSpeedUnit.entries[it]) }
        choiceRow("Waves and swell", { units.waves.label }, heightLabels,
            { units.waves.ordinal }) { units = units.copy(waves = HeightUnit.entries[it]) }
        choiceRow("Current", { units.current.label }, currentLabels,
            { units.current.ordinal }) { units = units.copy(current = SeaSpeedUnit.entries[it]) }
        choiceRow("Sea temperature", { units.temperature.label }, listOf("Celsius (°C)", "Fahrenheit (°F)"),
            { units.temperature.ordinal }) { units = units.copy(temperature = TemperatureUnit.entries[it]) }

        // Boat ----------------------------------------------------------------
        body.addView(panels.sectionHeader("BOAT"))
        val cruise = field(body, "Cruise speed") { units.speed.label }
        cruise.set(initial.cruiseSpeedKn * units.speed.perKnot)
        var speedSource = initial.speedSource
        lateinit var sourceRow: TextView
        sourceRow = panels.row("ETAs from", speedSourceLabel(speedSource)) {
            panels.showChoicePanel(
                "ETAs from",
                listOf("Cruise speed", "GPS speed when moving"),
                speedSource.ordinal
            ) {
                speedSource = PlanSpeedSource.entries[it]
                sourceRow.text = "ETAs from\n${speedSourceLabel(speedSource)}"
            }
        }
        panels.addRow(body, sourceRow)
        val burn = field(body, "Fuel burn per hour") { "${units.fuel.label}/h" }
        burn.set(initial.fuelBurnLph * units.fuel.perLitre)
        val tank = field(body, "Tank capacity") { units.fuel.label }
        tank.set(initial.tankLitres * units.fuel.perLitre)
        val onBoard = field(body, "Fuel on board") { units.fuel.label }
        onBoard.set(initial.fuelOnBoardLitres * units.fuel.perLitre)
        body.addView(panels.caption("Fuel burn 0 turns the fuel figures off. Fuel on board 0 counts as a full tank.", topPaddingDp = 0))
        val reserve = field(body, "Reserve") { "% of tank" }
        reserve.set(initial.reservePercent)
        val economy = panels.caption("", topPaddingDp = 0)
        body.addView(economy)
        val draft = field(body, "Draft") { units.depth.label }
        draft.set(initial.draftM * units.depth.perMetre)
        val safety = field(body, "Safety margin under keel") { units.depth.label }
        safety.set(initial.safetyMarginM * units.depth.perMetre)

        // Navigation -----------------------------------------------------------
        body.addView(panels.sectionHeader("NAVIGATION"))
        val arrival = field(body, "Arrival radius") { smallLabel(units) }
        arrival.set(initial.arrivalRadiusM * smallFactor(units))
        val xte = field(body, "Off-course limit") { smallLabel(units) }
        xte.set(initial.xteLimitM * smallFactor(units))
        val autoAdvance = check(body, "Go to the next point on arrival", initial.autoAdvance)

        // Alarms ----------------------------------------------------------------
        body.addView(panels.sectionHeader("ALARMS"))
        val arrivalAlarm = check(body, "Arriving at a point", initial.arrivalAlarm)
        val offCourseAlarm = check(body, "Off course", initial.offCourseAlarm)
        val shallowAlarm = check(body, "Shallow water (draft + margin)", initial.shallowAlarm)
        val sound = check(body, "Sound", initial.alarmSound)
        val vibrate = check(body, "Vibrate", initial.alarmVibrate)
        val screenOn = check(body, "Keep screen on while navigating", initial.keepScreenOn)

        // Display ---------------------------------------------------------------
        body.addView(panels.sectionHeader("DISPLAY"))
        val courseUp = check(body, "Course up (chart turns with the boat)", initial.courseUp)
        val night = check(body, "Night red filter", initial.nightFilter)
        body.addView(
            panels.caption(
                "Alarms work while SeaBathy is open. Shallow checks use your surveys plus the tide.",
                topPaddingDp = 4
            )
        )

        fun read(previousUnits: NavUnits): NavSettings = NavSettings(
            units = units,
            cruiseSpeedKn = cruise.value() / previousUnits.speed.perKnot,
            speedSource = speedSource,
            fuelBurnLph = burn.value() / previousUnits.fuel.perLitre,
            tankLitres = tank.value() / previousUnits.fuel.perLitre,
            fuelOnBoardLitres = onBoard.value() / previousUnits.fuel.perLitre,
            reservePercent = reserve.value().coerceIn(0.0, 90.0),
            draftM = draft.value() / previousUnits.depth.perMetre,
            safetyMarginM = safety.value() / previousUnits.depth.perMetre,
            arrivalRadiusM = (arrival.value() / smallFactor(previousUnits)).coerceAtLeast(5.0),
            xteLimitM = (xte.value() / smallFactor(previousUnits)).coerceAtLeast(5.0),
            autoAdvance = autoAdvance.isChecked,
            alarmSound = sound.isChecked,
            alarmVibrate = vibrate.isChecked,
            offCourseAlarm = offCourseAlarm.isChecked,
            arrivalAlarm = arrivalAlarm.isChecked,
            shallowAlarm = shallowAlarm.isChecked,
            keepScreenOn = screenOn.isChecked,
            courseUp = courseUp.isChecked,
            nightFilter = night.isChecked
        )

        // Fields hold numbers in the units shown; switching units converts them.
        var shownUnits = units
        fun updateEconomy() {
            val s = read(shownUnits)
            val f = NavFormat(s.units)
            val perNm = s.litresPerNm
            economy.text = if (perNm == null) {
                "Set a fuel burn to see economy and range."
            } else {
                val perDistance = perNm / s.units.distance.perNm * s.units.fuel.perLitre
                val range = s.rangeNm
                String.format(
                    Locale.US,
                    "%.2f %s per %s at cruise.%s",
                    perDistance,
                    s.units.fuel.label,
                    s.units.distance.label,
                    range?.let { "  Range ${f.distance(it)}." }.orEmpty()
                )
            }
        }
        rebuildLabels = {
            val before = read(shownUnits)
            shownUnits = units
            cruise.set(before.cruiseSpeedKn * units.speed.perKnot)
            burn.set(before.fuelBurnLph * units.fuel.perLitre)
            tank.set(before.tankLitres * units.fuel.perLitre)
            onBoard.set(before.fuelOnBoardLitres * units.fuel.perLitre)
            draft.set(before.draftM * units.depth.perMetre)
            safety.set(before.safetyMarginM * units.depth.perMetre)
            arrival.set(before.arrivalRadiusM * smallFactor(units))
            xte.set(before.xteLimitM * smallFactor(units))
            listOf(cruise, burn, tank, onBoard, draft, safety, arrival, xte).forEach { it.relabel() }
            updateEconomy()
        }
        listOf(cruise, burn, tank, onBoard, reserve).forEach { it.onChange { updateEconomy() } }
        updateEconomy()

        // Saved however the panel is closed: the panel is long, and a
        // Save button at the bottom was easy to miss.
        panels.showPanel("Boat & navigation", body, onDismiss = { onSave(read(shownUnits)) })
    }

    private fun speedSourceLabel(source: PlanSpeedSource) =
        if (source == PlanSpeedSource.CRUISE) "Cruise speed" else "GPS speed when moving"

    /** Arrival and lane sizes use metres, or feet with miles. */
    private fun smallFactor(units: NavUnits) =
        if (units.distance == DistanceUnit.STATUTE_MILES) NavFormat.FEET_PER_METRE else 1.0

    private fun smallLabel(units: NavUnits) = if (units.distance == DistanceUnit.STATUTE_MILES) "ft" else "m"

    /** A labelled number field whose unit label can change. */
    private inner class NumberField(private val caption: TextView, val input: EditText, private val title: String, private val unit: () -> String) {
        fun set(value: Double) {
            input.setText(if (value == 0.0) "0" else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.'))
        }

        fun value(): Double = input.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        fun relabel() { caption.text = "$title (${unit()})" }
        fun onChange(action: () -> Unit) {
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = action()
            })
        }
    }

    private fun field(body: LinearLayout, title: String, unit: () -> String): NumberField {
        val caption = panels.caption("$title (${unit()})", topPaddingDp = 2)
        body.addView(caption)
        val input = panels.input {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        panels.addRow(body, input)
        return NumberField(caption, input, title, unit)
    }

    private fun check(body: LinearLayout, label: String, checked: Boolean): CheckBox =
        CheckBox(context).apply {
            text = label
            isChecked = checked
            textSize = 14f
            setTextColor(palette.primaryText)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            minHeight = context.dp(44)
        }.also { panels.addRow(body, it, bottomMarginDp = 2) }
}
