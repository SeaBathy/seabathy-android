package au.opendepth.app.nav

import android.content.SharedPreferences

/** Where the ETA and fuel figures take their speed from. */
enum class PlanSpeedSource { CRUISE, GPS }

/**
 * The boat and how navigation behaves. Fuel figures are only shown once a
 * burn rate is set; depth alarms use [draftM] + [safetyMarginM].
 */
data class NavSettings(
    val units: NavUnits = NavUnits(),
    /** Planning speed, and the ETA fallback when GPS has no usable speed. */
    val cruiseSpeedKn: Double = 6.0,
    val speedSource: PlanSpeedSource = PlanSpeedSource.CRUISE,
    /** Fuel used per hour at [cruiseSpeedKn]; 0 = not set. */
    val fuelBurnLph: Double = 0.0,
    val tankLitres: Double = 0.0,
    /** Fuel on board for this trip; 0 = assume a full tank. */
    val fuelOnBoardLitres: Double = 0.0,
    /** Share of the tank kept in reserve and not counted as usable range. */
    val reservePercent: Double = 20.0,
    val draftM: Double = 1.0,
    val safetyMarginM: Double = 1.0,
    /** Within this distance of a route point counts as arriving at it. */
    val arrivalRadiusM: Double = 50.0,
    /** Off-course alarm once the boat is this far off the track line. */
    val xteLimitM: Double = 100.0,
    val autoAdvance: Boolean = true,
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,
    val offCourseAlarm: Boolean = true,
    val arrivalAlarm: Boolean = true,
    val shallowAlarm: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Turn the chart so the boat's course points up while following GPS. */
    val courseUp: Boolean = false,
    /** A dim red filter over the chart, to keep night vision. */
    val nightFilter: Boolean = false
) {
    /** The least water the boat should be in. */
    val minimumDepthM: Double get() = draftM + safetyMarginM

    val fuelSet: Boolean get() = fuelBurnLph > 0.0

    /** Usable fuel for range: on board (or a full tank) less the reserve. */
    val usableFuelLitres: Double
        get() {
            val onBoard = if (fuelOnBoardLitres > 0) fuelOnBoardLitres else tankLitres
            val reserve = tankLitres.coerceAtLeast(onBoard) * reservePercent / 100.0
            return (onBoard - reserve).coerceAtLeast(0.0)
        }

    /** Litres per nautical mile at cruise, or null if fuel is not set. */
    val litresPerNm: Double? get() = if (fuelSet && cruiseSpeedKn > 0) fuelBurnLph / cruiseSpeedKn else null

    /** Range on usable fuel at cruise, in nm, or null if not computable. */
    val rangeNm: Double?
        get() {
            val perNm = litresPerNm ?: return null
            if (usableFuelLitres <= 0) return null
            return usableFuelLitres / perNm
        }

    companion object {
        /** Faster than any boat SeaBathy is for; a bigger number is a typo. */
        const val MAX_CRUISE_KN = 100.0
    }
}

/** Saves [NavSettings] one key per field, so a missing key keeps its default. */
class NavSettingsRepository(private val preferences: SharedPreferences) {

    fun load(): NavSettings {
        val d = NavSettings()
        val p = preferences
        val depth = enumOr(p.getString(K_DEPTH, null), d.units.depth)
        val speed = enumOr(p.getString(K_SPEED, null), d.units.speed)
        val country = java.util.Locale.getDefault().country
        return NavSettings(
            units = NavUnits(
                distance = enumOr(p.getString(K_DISTANCE, null), d.units.distance),
                speed = speed,
                depth = depth,
                fuel = enumOr(p.getString(K_FUEL, null), d.units.fuel),
                bearing = enumOr(p.getString(K_BEARING, null), d.units.bearing),
                temperature = enumOr(p.getString(K_TEMPERATURE, null), TemperatureUnit.defaultFor(country)),
                tide = enumOr(p.getString(K_TIDE, null), HeightUnit.defaultFor(depth, country)),
                waves = enumOr(p.getString(K_WAVES, null), HeightUnit.defaultFor(depth, country)),
                wind = enumOr(p.getString(K_WIND, null), SeaSpeedUnit.like(speed)),
                current = enumOr(p.getString(K_CURRENT, null), SeaSpeedUnit.like(speed))
            ),
            cruiseSpeedKn = double(K_CRUISE, d.cruiseSpeedKn).takeIf { it > 0 && it <= NavSettings.MAX_CRUISE_KN } ?: d.cruiseSpeedKn,
            speedSource = enumOr(p.getString(K_SPEED_SOURCE, null), d.speedSource),
            fuelBurnLph = double(K_BURN, d.fuelBurnLph),
            tankLitres = double(K_TANK, d.tankLitres),
            fuelOnBoardLitres = double(K_ON_BOARD, d.fuelOnBoardLitres),
            reservePercent = double(K_RESERVE, d.reservePercent),
            draftM = double(K_DRAFT, d.draftM),
            safetyMarginM = double(K_SAFETY, d.safetyMarginM),
            arrivalRadiusM = double(K_ARRIVAL, d.arrivalRadiusM),
            xteLimitM = double(K_XTE, d.xteLimitM),
            autoAdvance = p.getBoolean(K_AUTO_ADVANCE, d.autoAdvance),
            alarmSound = p.getBoolean(K_SOUND, d.alarmSound),
            alarmVibrate = p.getBoolean(K_VIBRATE, d.alarmVibrate),
            offCourseAlarm = p.getBoolean(K_OFF_COURSE, d.offCourseAlarm),
            arrivalAlarm = p.getBoolean(K_ARRIVAL_ALARM, d.arrivalAlarm),
            shallowAlarm = p.getBoolean(K_SHALLOW, d.shallowAlarm),
            keepScreenOn = p.getBoolean(K_SCREEN_ON, d.keepScreenOn),
            courseUp = p.getBoolean(K_COURSE_UP, d.courseUp),
            nightFilter = p.getBoolean(K_NIGHT, d.nightFilter)
        )
    }

    fun save(s: NavSettings) {
        preferences.edit()
            .putString(K_DISTANCE, s.units.distance.name)
            .putString(K_SPEED, s.units.speed.name)
            .putString(K_DEPTH, s.units.depth.name)
            .putString(K_FUEL, s.units.fuel.name)
            .putString(K_BEARING, s.units.bearing.name)
            .putString(K_TEMPERATURE, s.units.temperature.name)
            .putString(K_TIDE, s.units.tide.name)
            .putString(K_WAVES, s.units.waves.name)
            .putString(K_WIND, s.units.wind.name)
            .putString(K_CURRENT, s.units.current.name)
            .putString(K_CRUISE, s.cruiseSpeedKn.toString())
            .putString(K_SPEED_SOURCE, s.speedSource.name)
            .putString(K_BURN, s.fuelBurnLph.toString())
            .putString(K_TANK, s.tankLitres.toString())
            .putString(K_ON_BOARD, s.fuelOnBoardLitres.toString())
            .putString(K_RESERVE, s.reservePercent.toString())
            .putString(K_DRAFT, s.draftM.toString())
            .putString(K_SAFETY, s.safetyMarginM.toString())
            .putString(K_ARRIVAL, s.arrivalRadiusM.toString())
            .putString(K_XTE, s.xteLimitM.toString())
            .putBoolean(K_AUTO_ADVANCE, s.autoAdvance)
            .putBoolean(K_SOUND, s.alarmSound)
            .putBoolean(K_VIBRATE, s.alarmVibrate)
            .putBoolean(K_OFF_COURSE, s.offCourseAlarm)
            .putBoolean(K_ARRIVAL_ALARM, s.arrivalAlarm)
            .putBoolean(K_SHALLOW, s.shallowAlarm)
            .putBoolean(K_SCREEN_ON, s.keepScreenOn)
            .putBoolean(K_COURSE_UP, s.courseUp)
            .putBoolean(K_NIGHT, s.nightFilter)
            .apply()
    }

    private fun double(key: String, default: Double): Double =
        preferences.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: default

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val K_DISTANCE = "nav_unit_distance"
        const val K_SPEED = "nav_unit_speed"
        const val K_DEPTH = "nav_unit_depth"
        const val K_FUEL = "nav_unit_fuel"
        const val K_BEARING = "nav_bearing_ref"
        const val K_TEMPERATURE = "nav_unit_temperature"
        const val K_TIDE = "nav_unit_tide"
        const val K_WAVES = "nav_unit_waves"
        const val K_WIND = "nav_unit_wind"
        const val K_CURRENT = "nav_unit_current"
        const val K_CRUISE = "nav_cruise_kn"
        const val K_SPEED_SOURCE = "nav_speed_source"
        const val K_BURN = "nav_burn_lph"
        const val K_TANK = "nav_tank_l"
        const val K_ON_BOARD = "nav_on_board_l"
        const val K_RESERVE = "nav_reserve_pct"
        const val K_DRAFT = "nav_draft_m"
        const val K_SAFETY = "nav_safety_m"
        const val K_ARRIVAL = "nav_arrival_m"
        const val K_XTE = "nav_xte_m"
        const val K_AUTO_ADVANCE = "nav_auto_advance"
        const val K_SOUND = "nav_alarm_sound"
        const val K_VIBRATE = "nav_alarm_vibrate"
        const val K_OFF_COURSE = "nav_alarm_off_course"
        const val K_ARRIVAL_ALARM = "nav_alarm_arrival"
        const val K_SHALLOW = "nav_alarm_shallow"
        const val K_SCREEN_ON = "nav_keep_screen_on"
        const val K_COURSE_UP = "nav_course_up"
        const val K_NIGHT = "nav_night_filter"
    }
}
