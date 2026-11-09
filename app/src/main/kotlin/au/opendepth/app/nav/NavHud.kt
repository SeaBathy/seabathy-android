package au.opendepth.app.nav

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.content.res.ColorStateList
import android.widget.ImageButton
import au.opendepth.app.R
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.ui.AppTheme
import au.opendepth.app.ui.applySecondaryStyle
import au.opendepth.app.ui.dp
import au.opendepth.app.ui.dpF
import kotlin.math.abs
import kotlin.math.roundToInt

/** Extra readings shown with a [NavState]. */
data class NavExtras(
    /** Water under the boat now, metres (survey depth plus tide), or null if unknown. */
    val depthNowM: Double?,
    /** Shallowest water just ahead on the current course, metres, or null. */
    val depthAheadM: Double?,
    val tideM: Double?,
    val variation: Double,
    /** Red banner text for an active alarm, or null. */
    val alarmText: String?
)

/**
 * The navigation display: where the boat is heading, the plotter numbers,
 * and the lane. Tapping the header folds it down to the essentials.
 */
class NavHud(
    private val context: Context,
    private val onPrevious: () -> Unit,
    private val onSkip: () -> Unit,
    /** Toggles alarm sound; returns whether alarms are now muted. */
    private val onMute: () -> Boolean,
    private val onStop: () -> Unit
) {
    private val palette = AppTheme.palette
    private var compact = false

    private val title = text(16f, bold = true)
    private val progress = text(12f, muted = true)
    private val banner = text(14f, bold = true).apply {
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        background = rounded(0xFFB91C1C.toInt())
        visibility = View.GONE
    }
    private val lane = LaneView(context)
    private val cells = List(12) { Cell() }
    private val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val muteButton: ImageButton = button(R.drawable.ic_volume_on, "Mute alarms") { setMuted(onMute()) }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xF0111827.toInt(), palette.border)
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        elevation = context.dpF(6f)
        visibility = View.GONE
        isClickable = true // swallow taps so the map underneath doesn't get them
    }

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { compact = !compact; applyCompact() }
        }
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        names.addView(title)
        names.addView(progress)
        header.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button(R.drawable.ic_chevron_left, "Previous point", onPrevious))
        header.addView(button(R.drawable.ic_chevron_right, "Next point", onSkip))
        header.addView(muteButton)
        header.addView(button(R.drawable.ic_stop, "Stop navigating", onStop))
        view.addView(header)
        view.addView(banner, matchWrap(topDp = 6))

        view.addView(row(cells[0], cells[1], cells[2]), matchWrap(topDp = 6))
        view.addView(lane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(64)).apply {
            topMargin = context.dp(4)
        })
        details.addView(row(cells[3], cells[4], cells[5]), matchWrap(topDp = 4))
        details.addView(row(cells[6], cells[7], cells[8]), matchWrap(topDp = 4))
        details.addView(row(cells[9], cells[10], cells[11]), matchWrap(topDp = 4))
        view.addView(details)
    }

    fun hide() {
        view.visibility = View.GONE
    }

    fun show(state: NavState, extras: NavExtras, settings: NavSettings) {
        val f = NavFormat(settings.units)
        view.visibility = View.VISIBLE
        title.text = if (state.finished) "Arrived: ${state.targetLabel}" else "→ ${state.targetLabel}"
        progress.text = "Point ${state.targetIndex + 1} of ${state.pointCount}" + if (compact) "  ·  tap for more" else ""

        banner.text = extras.alarmText.orEmpty()
        banner.visibility = if (extras.alarmText == null) View.GONE else View.VISIBLE

        val side = when {
            abs(state.xteM) < 1 -> ""
            state.xteM > 0 -> " R"
            else -> " L"
        }
        val steer = state.steerDegrees?.let {
            val d = abs(it).roundToInt()
            when {
                d < 2 -> "heading for it"
                it > 0 -> "to waypoint ▶ $d°"
                else -> "to waypoint ◀ $d°"
            }
        } ?: "no course yet"

        cells[0].set("DTW", f.distance(state.dtwNm))
        cells[1].set("BTW", f.bearing(state.btwTrue, extras.variation))
        cells[2].set("XTE", f.offset(state.xteM) + side, warn = state.offCourse, sub = steer)
        cells[3].set("SOG", f.speed(state.sogKn))
        cells[4].set("COG", f.bearing(state.cogTrue, extras.variation))
        cells[5].set("VMG", f.speed(state.vmgKn))
        cells[6].set("TTG", f.duration(state.ttgNextHours), sub = "ETA ${f.clock(state.etaNextMillis)}")
        cells[7].set("TO END", f.distance(state.remainingNm), sub = "ETA ${f.clock(state.etaEndMillis)}")
        val fuel = state.fuelToEndLitres
        cells[8].set("FUEL TO END", f.fuel(fuel), warn = fuel != null && settings.usableFuelLitres > 0 && fuel > settings.usableFuelLitres)
        val minDepth = settings.minimumDepthM
        cells[9].set(
            "DEPTH",
            f.depth(extras.depthNowM),
            warn = extras.depthNowM != null && extras.depthNowM < minDepth,
            sub = "need ${f.depth(minDepth)}"
        )
        cells[10].set(
            "AHEAD",
            f.depth(extras.depthAheadM),
            warn = extras.depthAheadM != null && extras.depthAheadM < minDepth
        )
        cells[11].set("TIDE", extras.tideM?.let { String.format(java.util.Locale.US, "%.2f m", it) } ?: "—")

        lane.limitM = settings.xteLimitM
        lane.xteM = state.xteM
        lane.steerDegrees = state.steerDegrees
        applyCompact()
    }

    private fun applyCompact() {
        details.visibility = if (compact) View.GONE else View.VISIBLE
    }

    private inner class Cell {
        val label = text(10f, muted = true)
        val value = text(19f, bold = true, mono = true)
        val sub = text(10f, muted = true).apply { visibility = View.GONE }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
            background = rounded(0x33000000)
            addView(label)
            addView(value)
            addView(sub)
        }

        fun set(name: String, text: String, warn: Boolean = false, sub: String? = null) {
            label.text = name
            value.text = text
            value.setTextColor(if (warn) 0xFFFF6B6B.toInt() else palette.primaryText)
            this.sub.text = sub.orEmpty()
            this.sub.visibility = if (sub == null) View.GONE else View.VISIBLE
        }
    }

    private fun row(vararg cells: Cell): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        cells.forEachIndexed { i, cell ->
            addView(cell.box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = context.dp(4)
            })
        }
    }

    private fun text(sizeSp: Float, bold: Boolean = false, muted: Boolean = false, mono: Boolean = false) =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(if (muted) palette.secondaryText else palette.primaryText)
            typeface = when {
                mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            maxLines = 2
        }

    /** Shows whether alarms are muted on the speaker button. */
    fun setMuted(muted: Boolean) {
        muteButton.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        muteButton.imageTintList = ColorStateList.valueOf(if (muted) 0xFFFF6B6B.toInt() else palette.primaryText)
        muteButton.contentDescription = if (muted) "Alarms muted — tap to turn on" else "Mute alarms"
    }

    private fun button(icon: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(palette.primaryText)
            contentDescription = description
            background = context.roundedBackgroundCompat()
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(40)).apply { marginStart = context.dp(4) }
        }

    private fun Context.roundedBackgroundCompat() = rounded(palette.raised, palette.border)

    private fun matchWrap(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = context.dp(topDp) }

    private fun rounded(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = context.dpF(10f)
        setColor(fill)
        if (stroke != null) setStroke(context.dp(1), stroke)
    }
}
