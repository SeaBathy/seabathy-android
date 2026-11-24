package au.opendepth.app.ui

/**
 * Links for Settings → About: the guide, sharing SeaBathy, and rating it on Google Play.
 * Plain links and Android's own share sheet: no extra library, no tracking, no permission.
 */
object AppLinks {
    const val GUIDE_URL = "https://seabathy.com/guide"

    /** Where a shared link points in the GitHub build: the website, which has every download. */
    const val WEBSITE_URL = "https://seabathy.com"

    /** The Google Play listing of [packageName], for sharing and when the Play Store app can't open. */
    fun storeWebUrl(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"

    /** Opens the Play Store app on [packageName]'s listing. */
    fun marketUri(packageName: String): String = "market://details?id=$packageName"

    /** The link to share: the Play listing in the Google Play build, else the website. */
    fun shareUrl(playBuild: Boolean, packageName: String): String =
        if (playBuild) storeWebUrl(packageName) else WEBSITE_URL

    /** The short line sent with the link. */
    fun shareText(link: String): String =
        "SeaBathy: free 3D seabed and depth maps, tides and sea conditions, with no ads and no account. $link"
}
