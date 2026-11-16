package au.opendepth.app.ui

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Stays up while a file imports, so a long import doesn't look frozen.
 * [Handle] updates move to the main thread themselves.
 */
class ImportProgressDialog(private val context: Context, private val panels: PanelDialogs) {

    class Handle internal constructor(
        private val dialog: Dialog,
        private val label: TextView,
        private val mainHandler: Handler
    ) {
        fun update(text: String) {
            mainHandler.post { label.text = text }
        }

        fun dismiss() {
            mainHandler.post { runCatching { dialog.dismiss() } }
        }
    }

    /** [onCancel], when given, adds a Cancel button that calls it (the caller then dismisses). */
    fun show(title: String, initialText: String, onCancel: (() -> Unit)? = null): Handle {
        val body = panels.body()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ProgressBar(context).apply { isIndeterminate = true })
        val label = TextView(context).apply {
            textSize = 14f
            text = initialText
            setPadding(context.dp(12), 0, 0, 0)
        }
        row.addView(label)
        body.addView(row)
        if (onCancel != null) {
            body.addView(android.widget.Button(context).apply {
                text = "Cancel"
                isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    label.text = "Stopping…"
                    onCancel()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = context.dp(12)
            })
        }

        val dialog = panels.showPanel(title, body)
        dialog.setCancelable(false)
        // A long build with a Cancel button: keep the screen on so the phone doesn't sleep mid-way.
        if (onCancel != null) dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return Handle(dialog, label, Handler(Looper.getMainLooper()))
    }
}
