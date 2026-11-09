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

    fun show(title: String, initialText: String): Handle {
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

        val dialog = panels.showPanel(title, body)
        dialog.setCancelable(false)
        return Handle(dialog, label, Handler(Looper.getMainLooper()))
    }
}
