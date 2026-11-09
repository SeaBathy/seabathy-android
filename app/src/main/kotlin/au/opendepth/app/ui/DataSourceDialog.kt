package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import au.opendepth.app.model.DataSource

/** The "Offline maps" screen: add or remove tile sources, and download the area on screen from one. */
class DataSourceDialog(
    private val context: Context,
    private val panels: PanelDialogs
) {
    private val palette = AppTheme.palette

    data class Callbacks(
        val sources: () -> List<DataSource>,
        val onAdd: (name: String, urlTemplate: String, attribution: String) -> Unit,
        val onRemove: (DataSource) -> Unit,
        val onDownload: (DataSource) -> Unit
    )

    fun show(callbacks: Callbacks) {
        val body = panels.body()
        lateinit var dialog: Dialog

        fun rebuild() {
            body.removeAllViews()

            body.addView(panels.sectionHeader("MAP SOURCES"))
            val sources = callbacks.sources()
            if (sources.isEmpty()) {
                body.addView(
                    panels.caption("None yet: add one below.", topPaddingDp = 0)
                )
            } else {
                sources.forEach { source ->
                    body.addView(
                        sourceRow(source, callbacks, ::rebuild) { dialog.dismiss() },
                        rowParams()
                    )
                }
            }

            body.addView(panels.sectionHeader("ADD A SOURCE"))
            body.addView(
                panels.caption(
                    "A map or satellite tile link with {z}, {x} and {y} in it. " +
                        "Check its licence allows saving tiles offline.",
                    topPaddingDp = 0
                )
            )

            val nameInput = panels.input { hint = "Source name" }
            panels.addRow(body, nameInput)

            val urlInput = panels.input {
                hint = "https://example.com/{z}/{x}/{y}.png"
                inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
            panels.addRow(body, urlInput)

            val attributionInput = panels.input {
                hint = "Credit shown on the map (optional)"
                setSingleLine(true)
            }
            panels.addRow(body, attributionInput)

            panels.addRow(
                body,
                Button(context).apply {
                    text = "Add source"
                    applySecondaryStyle(palette)
                    setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        val urlTemplate = urlInput.text.toString().trim()
                        val attribution = attributionInput.text.toString().trim()
                        if (name.isBlank()) {
                            nameInput.error = "Give this source a name"
                            return@setOnClickListener
                        }
                        if (!isPlausibleTileTemplate(urlTemplate)) {
                            urlInput.error = "A full http(s) link with {z}, {x} and {y}"
                            return@setOnClickListener
                        }
                        callbacks.onAdd(name, urlTemplate, attribution)
                        rebuild()
                    }
                }
            )
        }

        rebuild()
        dialog = panels.showPanel("Offline maps", body)
    }

    private fun sourceRow(
        source: DataSource,
        callbacks: Callbacks,
        rebuild: () -> Unit,
        dismiss: () -> Unit
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.roundedBackground(palette.surface, palette.border, UiRadius.ROW)
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))

        addView(
            TextView(context).apply {
                text = source.name
                textSize = 13f
                setTextColor(palette.primaryText)
            }
        )
        addView(
            TextView(context).apply {
                text = source.urlTemplate
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(palette.secondaryText)
                setPadding(0, context.dp(2), 0, if (source.attribution.isBlank()) context.dp(8) else 0)
            }
        )
        if (source.attribution.isNotBlank()) {
            addView(
                TextView(context).apply {
                    text = source.attribution
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(palette.secondaryText)
                    setPadding(0, 0, 0, context.dp(8))
                }
            )
        }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(chip("DOWNLOAD", "Download ${source.name} for the area on screen") {
                    // The estimate/confirm/progress prompts this triggers
                    // read more naturally over the map than stacked on top
                    // of this still-open source list.
                    dismiss()
                    callbacks.onDownload(source)
                })

                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

                if (!source.builtIn) {
                    addView(chip("REMOVE", "Remove ${source.name}", destructive = true) {
                        panels.confirm(
                            title = "Remove ${source.name}?",
                            message = "Only the link is removed; maps already downloaded stay.",
                            confirmLabel = "Remove"
                        ) {
                            callbacks.onRemove(source)
                            rebuild()
                        }
                    })
                }
            }
        )
    }

    private fun chip(
        label: String,
        description: String,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        contentDescription = description
        minHeight = context.dp(28)
        setTextColor(if (destructive) palette.destructive else palette.primaryText)
        background = context.roundedBackground(palette.raised, palette.border, 8)
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = context.dp(5) }
        setOnClickListener { onClick() }
    }

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = context.dp(8) }

    /** Deliberately shallow — see the "ADD A SOURCE" caption for why a deeper check is not this dialog's job. */
    private fun isPlausibleTileTemplate(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) &&
            listOf("{z}", "{x}", "{y}").all { it in url }
}
