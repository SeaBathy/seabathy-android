package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.BitmapFactory
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import au.opendepth.app.R
import au.opendepth.app.model.Waypoint
import au.opendepth.app.model.WaypointIcon
import au.opendepth.app.model.WaypointPhoto
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import au.opendepth.app.util.CoordinateFormatter

/**
 * Edits a waypoint's name, position, notes, colour, symbol and photos. A plain dialog like
 * [PanelDialogs.showPanel], not a themed AlertDialog. The fields scroll when the screen is too
 * short for them (a small phone, or on its side); the buttons stay in view.
 */
class WaypointEditDialog(
    private val context: Context,
    private val panels: PanelDialogs,
    private val coordinateEditor: CoordinateEditorDialog,
    /** Where waypoint photos are kept. */
    private val photosDir: File
) {
    private val palette = AppTheme.palette

    /**
     * [isNameTaken] reports whether another waypoint already uses a name, so
     * a rename is held to the same uniqueness rule as creating one.
     */
    fun show(
        waypoint: Waypoint,
        isNameTaken: (String) -> Boolean,
        onChanged: () -> Unit,
        onJumpTo: (Waypoint) -> Unit,
        onDelete: (Waypoint) -> Unit,
        /** Opens the camera for a photo of [Waypoint]; null hides the button. */
        onAddPhoto: ((Waypoint) -> Unit)? = null
    ) {
        val dialog = Dialog(context, R.style.Theme_SeaBathy_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(context.dp(ROOT_PADDING_DP), context.dp(ROOT_PADDING_DP), context.dp(ROOT_PADDING_DP), context.dp(ROOT_PADDING_DP))
        }

        // The buttons sit under the fields, outside the part that scrolls.
        val frame = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(palette.panel, palette.border, UiRadius.CARD)
            setPadding(context.dp(CARD_PADDING_DP), context.dp(16), context.dp(CARD_PADDING_DP), context.dp(16))
        }
        root.addView(frame)

        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(
            ScrollView(context).apply {
                isFillViewport = false
                addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            // No taller than the fields; shorter, and scrolling, when the screen is.
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        card.addView(
            TextView(context).apply {
                text = "Waypoint"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
            }
        )

        val nameInput = panels.input {
            setText(waypoint.name)
            hint = "Name"
            setSingleLine(true)
            selectAll()
        }
        card.addView(
            nameInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(14) }
        )

        val coordinates = TextView(context).apply {
            text = coordinateLabel(waypoint)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.accent)
            setPadding(0, context.dp(10), 0, context.dp(10))
            setOnClickListener {
                coordinateEditor.show(
                    title = "Waypoint coordinates",
                    initialLat = waypoint.lat,
                    initialLng = waypoint.lng,
                    positiveLabel = "Save"
                ) { latitude, longitude ->
                    waypoint.lat = latitude
                    waypoint.lng = longitude
                    text = coordinateLabel(waypoint)
                    onChanged()
                }
            }
        }
        card.addView(coordinates)

        val descriptionInput = panels.input {
            setText(waypoint.desc.orEmpty())
            hint = "Description / notes"
            minLines = 2
            maxLines = 4
        }
        card.addView(descriptionInput)

        // Colour and symbol, as in the new-waypoint sheet. They change with Save, like the name,
        // and only when one was tapped: a colour or symbol this build doesn't offer is kept.
        var pickedColor: String? = null
        var pickedIcon: String? = null
        val rowWidth = (context.resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt() -
            2 * context.dp(ROOT_PADDING_DP + CARD_PADDING_DP)

        card.addView(panels.sectionHeader("COLOUR"))
        val colourRow = swatchRow()
        val colourSwatches = WaypointSwatches.addColours(
            colourRow,
            WaypointSwatches.fitScale(
                rowWidth, Waypoint.PALETTE.size,
                context.dp(WaypointSwatches.COLOUR_SIZE_DP + 2 * WaypointSwatches.COLOUR_MARGIN_DP)
            )
        )
        colourSwatches.forEachIndexed { index, swatch ->
            swatch.setOnClickListener {
                pickedColor = Waypoint.PALETTE[index]
                WaypointSwatches.showColour(colourSwatches, pickedColor)
            }
        }
        WaypointSwatches.showColour(colourSwatches, waypoint.color)
        card.addView(colourRow)

        card.addView(panels.sectionHeader("SYMBOL"))
        val iconRow = swatchRow()
        val iconSwatches = WaypointSwatches.addIcons(
            iconRow,
            WaypointSwatches.fitScale(
                rowWidth, WaypointIcon.ALL.size,
                context.dp(WaypointSwatches.ICON_SIZE_DP + 2 * WaypointSwatches.ICON_MARGIN_DP)
            )
        )
        iconSwatches.forEachIndexed { index, swatch ->
            swatch.setOnClickListener {
                pickedIcon = WaypointIcon.ALL[index].id
                WaypointSwatches.showIcon(iconSwatches, pickedIcon)
            }
        }
        WaypointSwatches.showIcon(iconSwatches, waypoint.icon)
        card.addView(iconRow)

        // Photos, each with the conditions when it was taken.
        val photos = waypoint.photos.orEmpty()
        if (photos.isNotEmpty() || onAddPhoto != null) {
            card.addView(panels.sectionHeader("PHOTOS"))
            val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (photo in photos.asReversed()) {
                strip.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "Photo taken ${stamp(photo.takenMillis)}"
                    setImageBitmap(decode(File(photosDir, photo.file), context.dp(THUMB_DP)))
                    setOnClickListener { showPhoto(waypoint, photo, onChanged) { dialog.dismiss() } }
                }, LinearLayout.LayoutParams(context.dp(THUMB_DP), context.dp(THUMB_DP)).apply { marginEnd = context.dp(6) })
            }
            if (photos.isNotEmpty()) {
                card.addView(HorizontalScrollView(context).apply { addView(strip) })
            }
            if (onAddPhoto != null) {
                card.addView(Button(context).apply {
                    text = "📷  Take a photo"
                    applySecondaryStyle(palette)
                    contentDescription = "Take a photo of this waypoint, saved with the tide and sea conditions"
                    setOnClickListener {
                        // Keep what was typed and chosen before the camera opens.
                        waypoint.desc = descriptionInput.text.toString().trim().takeIf { it.isNotBlank() }
                        pickedColor?.let { waypoint.color = it }
                        pickedIcon?.let { waypoint.icon = it }
                        onChanged()
                        dialog.dismiss()
                        onAddPhoto(waypoint)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
            }
            if (photos.isEmpty()) {
                card.addView(panels.caption("Saved with the time, tide, wind, waves, swell, sea temperature, sun and moon.", topPaddingDp = 4))
            }
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(18), 0, 0)
        }

        buttonRow.addView(
            Button(context).apply {
                text = "Delete"
                isAllCaps = false
                setTextColor(palette.destructive)
                background = ColorDrawable(Color.TRANSPARENT)
                setOnClickListener {
                    panels.confirm(
                        title = "Delete ${waypoint.name}?",
                        confirmLabel = "Delete"
                    ) {
                        dialog.dismiss()
                        onDelete(waypoint)
                    }
                }
            }
        )

        buttonRow.addView(
            View(context),
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        buttonRow.addView(
            Button(context).apply {
                text = "Jump"
                applySecondaryStyle(palette)
                setOnClickListener {
                    dialog.dismiss()
                    onJumpTo(waypoint)
                }
            },
            spacedParams()
        )

        buttonRow.addView(
            Button(context).apply {
                text = "Save"
                applyPrimaryStyle(palette)
                setOnClickListener {
                    val name = nameInput.text.toString().trim().ifBlank { waypoint.name }
                    if (!name.equals(waypoint.name, ignoreCase = true) && isNameTaken(name)) {
                        nameInput.error = "A waypoint called \"$name\" already exists"
                        return@setOnClickListener
                    }
                    waypoint.name = name
                    waypoint.desc = descriptionInput.text.toString().trim().takeIf { it.isNotBlank() }
                    pickedColor?.let { waypoint.color = it }
                    pickedIcon?.let { waypoint.icon = it }
                    onChanged()
                    dialog.dismiss()
                }
            },
            spacedParams()
        )

        frame.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = DIM_AMOUNT
            }
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.show()
    }

    /** A photo full width, with the conditions it was saved with. */
    private fun showPhoto(waypoint: Waypoint, photo: WaypointPhoto, onChanged: () -> Unit, closeEditor: () -> Unit) {
        val body = panels.body()
        val file = File(photosDir, photo.file)
        body.addView(ImageView(context).apply {
            adjustViewBounds = true
            contentDescription = "Photo of ${waypoint.name}"
            setImageBitmap(decode(file, context.resources.displayMetrics.widthPixels))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(panels.sectionHeader(stamp(photo.takenMillis).uppercase(Locale.getDefault())))
        body.addView(panels.caption(
            // Gson leaves a missing list null, whatever the type says.
            @Suppress("USELESS_CAST")
            (photo.conditions as List<String>?).orEmpty().let { lines ->
                if (lines.isEmpty()) "No conditions were saved with this photo." else lines.joinToString(separator = "\n")
            },
            topPaddingDp = 0
        ))
        lateinit var view: Dialog
        panels.addRow(body, panels.row("Delete this photo", destructive = true) {
            panels.confirm("Delete this photo?", "The conditions saved with it go too.", confirmLabel = "Delete") {
                waypoint.photos?.remove(photo)
                if (waypoint.photos?.isEmpty() == true) waypoint.photos = null
                file.delete()
                onChanged()
                view.dismiss()
                closeEditor()
            }
        })
        view = panels.showPanel(waypoint.name, body)
    }

    /** A JPEG scaled down to about [px] across, upright, or null if it can't be read. */
    private fun decode(file: File, px: Int): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun stamp(millis: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

    /** A row of colour or symbol choices, from the left like the fields above it. */
    private fun swatchRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun spacedParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = context.dp(8) }

    private fun coordinateLabel(waypoint: Waypoint): String =
        "${CoordinateFormatter.format(waypoint.lat, waypoint.lng)}   •   EDIT COORDINATES"

    private companion object {
        const val DIM_AMOUNT = 0.72f
        const val WIDTH_FRACTION = 0.96f
        const val THUMB_DP = 72
        const val ROOT_PADDING_DP = 12
        const val CARD_PADDING_DP = 20
    }
}
