package au.opendepth.app.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

/** Slides the layers panel in and out from the left edge. */
class SidePanel(private val views: MainViews) {

    val isOpen: Boolean
        get() = views.panelScrim.visibility == View.VISIBLE

    fun bind(onClosed: () -> Unit = {}) {
        views.btnMenu.setOnClickListener { open() }
        views.btnPanelClose.setOnClickListener {
            close()
            onClosed()
        }
        views.panelScrim.setOnClickListener {
            close()
            onClosed()
        }
    }

    fun open() {
        views.panelScrim.visibility = View.VISIBLE
        views.panelScrim.alpha = 0f
        views.sidePanel.animate()
            .translationX(0f)
            .setDuration(OPEN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        views.panelScrim.animate().alpha(1f).setDuration(OPEN_MS).start()
    }

    fun close() {
        val offscreen = -views.sidePanel.context.dp(WIDTH_DP).toFloat()
        views.sidePanel.animate()
            .translationX(offscreen)
            .setDuration(CLOSE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        views.panelScrim.animate()
            .alpha(0f)
            .setDuration(CLOSE_MS)
            .withEndAction { views.panelScrim.visibility = View.GONE }
            .start()
    }

    private companion object {
        /** Matches the panel width declared in the layout. */
        const val WIDTH_DP = 300
        const val OPEN_MS = 250L
        const val CLOSE_MS = 220L
    }
}
