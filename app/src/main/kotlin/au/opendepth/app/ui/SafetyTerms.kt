package au.opendepth.app.ui

import android.app.Activity
import android.content.SharedPreferences

/**
 * The one agreement that covers the whole app: shown on first launch (and again whenever the
 * wording changes, by [VERSION]), and kept under Settings → Safety and terms. Individual
 * features don't repeat it; only route guidance and man overboard add a line of their own.
 * The same text is on seabathy.com/terms and in SeaBathy Desktop.
 */
object SafetyTerms {
    /** Raise when the wording changes in a way people should agree to again. */
    const val VERSION = 1
    const val URL = "https://seabathy.com/terms"
    private const val KEY = "terms_accepted_version"

    const val SUMMARY = "SeaBathy is not a nautical chart and must not be used for navigation."

    val POINTS = listOf(
        "Depths, tides, forecasts, marine park zones, buoys and names come from free public data. " +
            "They can be wrong, missing or out of date.",
        "Route guidance, alarms and man overboard are aids only. Keep a proper lookout and use official " +
            "charts, notices to mariners and your own judgement.",
        "Marine park zones are a guide: the official zoning map and rules are the law.",
        "In an emergency, call for help first: VHF channel 16, 000, or your EPIRB.",
        "SeaBathy is free software, given as is, with no warranty. You use it at your own risk."
    )

    fun accepted(prefs: SharedPreferences) = prefs.getInt(KEY, 0) >= VERSION

    /** Shows the agreement if it hasn't been accepted yet; it can't be dismissed without a choice. */
    fun showIfNeeded(activity: Activity, prefs: SharedPreferences) {
        if (accepted(prefs) || activity.isFinishing) return
        val card = InfoCard(activity).apply {
            badge = "Before you start"
            title = "Not for navigation"
            subtitle = SUMMARY
            paragraphs += POINTS
            fine = "Full terms: $URL"
            actions += InfoCard.Action("Close app") { activity.finish() }
            actions += InfoCard.Action("I agree", primary = true) {
                prefs.edit().putInt(KEY, VERSION).apply()
            }
        }
        card.show().apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
    }

    /** Settings → Safety and terms: the same text, to read again. */
    fun show(context: android.content.Context) {
        InfoCard(context).apply {
            badge = "Safety and terms"
            title = "Not for navigation"
            subtitle = SUMMARY
            paragraphs += POINTS
            fine = "Full terms: $URL"
            actions += InfoCard.Action("OK", primary = true)
        }.show()
    }
}
