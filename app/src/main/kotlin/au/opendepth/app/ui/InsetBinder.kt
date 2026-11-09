package au.opendepth.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Sizes the spacer views that keep content clear of the status bar, the
 * navigation bar and the keyboard.
 *
 * The layout draws edge to edge, so each floating surface carries its own
 * spacer rather than the whole screen being padded.
 */
object InsetBinder {

    private const val SHEET_EXTRA_PADDING_DP = 8

    fun bind(views: MainViews) {
        applyStatusBarInset(views.topBar, views.statusBarSpacer)
        applyStatusBarInset(views.sidePanel, views.panelStatusSpacer)
        applyNavigationBarInset(views.routeCard, views.navBarSpacer)
        applyNavigationBarInset(views.measureCard, views.measureNavSpacer)
        applyKeyboardInset(views.waypointSheet, views.waypointNavSpacer)
    }

    private fun applyStatusBarInset(host: View, spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            spacer.layoutParams.height =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.requestLayout()
            insets
        }
    }

    private fun applyNavigationBarInset(host: View, spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            spacer.layoutParams.height =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.requestLayout()
            insets
        }
    }

    /** The waypoint sheet has to clear the keyboard as well as the nav bar. */
    private fun applyKeyboardInset(host: View, spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            spacer.layoutParams.height =
                maxOf(keyboard, navigation) + view.context.dp(SHEET_EXTRA_PADDING_DP)
            view.requestLayout()
            insets
        }
    }
}
