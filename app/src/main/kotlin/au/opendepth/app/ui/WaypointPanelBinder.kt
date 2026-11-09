package au.opendepth.app.ui

import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.model.Waypoint
import au.opendepth.app.util.CoordinateFormatter

/** Renders the searchable waypoint list in the side panel. */
class WaypointPanelBinder(
    private val context: Context,
    private val container: LinearLayout,
    private val waypoints: List<Waypoint>,
    private val onGoTo: (Waypoint) -> Unit,
    private val onEdit: (Waypoint) -> Unit
) {
    private val palette = AppTheme.palette

    fun rebuild() {
        container.removeAllViews()

        val search = searchField()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun draw(query: String) {
            list.removeAllViews()
            val matches = waypoints.filter { waypoint ->
                waypoint.name.contains(query, ignoreCase = true) ||
                    waypoint.desc.orEmpty().contains(query, ignoreCase = true)
            }

            if (matches.isEmpty()) {
                list.addView(
                    TextView(context).apply {
                        text = if (waypoints.isEmpty()) {
                            "No waypoints yet"
                        } else {
                            "No matching waypoints"
                        }
                        textSize = 12f
                        setTextColor(palette.secondaryText)
                        setPadding(
                            context.dp(16),
                            context.dp(12),
                            context.dp(16),
                            context.dp(12)
                        )
                    }
                )
                return
            }

            matches.forEach { waypoint -> list.addView(row(waypoint, search)) }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                value: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                value: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = draw(value?.toString().orEmpty())

            override fun afterTextChanged(value: Editable?) = Unit
        })

        container.addView(
            search,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = context.dp(16)
                marginEnd = context.dp(16)
                topMargin = context.dp(4)
                bottomMargin = context.dp(6)
            }
        )
        container.addView(list)
        draw("")
    }

    private fun searchField(): EditText = EditText(context).apply {
        hint = "Search waypoints"
        setSingleLine(true)
        applyInputStyle(palette, UiRadius.CONTROL)
        textSize = 13f
        setPadding(context.dp(12), context.dp(9), context.dp(12), context.dp(9))
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setOnEditorActionListener { view, actionId, _ ->
            val done = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            if (done) hideKeyboard(view)
            done
        }
        setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) hideKeyboard(view)
        }
    }

    private fun row(waypoint: Waypoint, search: EditText): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(8), context.dp(8))

            addView(
                View(context).apply {
                    background = context.circleBackground(
                        parseColorOrDefault(
                            waypoint.color,
                            parseColorOrDefault(Waypoint.DEFAULT_COLOR, palette.destructive)
                        )
                    )
                },
                LinearLayout.LayoutParams(context.dp(12), context.dp(12))
            )

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(10), 0, context.dp(8), 0)
                    addView(
                        TextView(context).apply {
                            text = waypoint.name
                            textSize = 13f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(palette.primaryText)
                        }
                    )
                    addView(
                        TextView(context).apply {
                            val description = waypoint.desc.orEmpty().trim()
                            text = description.ifBlank {
                                CoordinateFormatter.formatDdm(waypoint.lat, waypoint.lng)
                            }
                            textSize = 10f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(palette.secondaryText)
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(
                actionChip("GO", "Go to ${waypoint.name}", palette.accent) {
                    hideKeyboard(search)
                    search.clearFocus()
                    onGoTo(waypoint)
                },
                LinearLayout.LayoutParams(context.dp(42), context.dp(30))
                    .apply { marginEnd = context.dp(5) }
            )

            addView(
                actionChip("EDIT", "Edit ${waypoint.name}", palette.primaryText) {
                    onEdit(waypoint)
                },
                LinearLayout.LayoutParams(context.dp(52), context.dp(30))
            )
        }

    private fun actionChip(
        label: String,
        description: String,
        textColor: Int,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        contentDescription = description
        setTextColor(textColor)
        background = context.roundedBackground(palette.raised, palette.border, 8)
        setOnClickListener { onClick() }
    }

    private fun hideKeyboard(view: View) {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
