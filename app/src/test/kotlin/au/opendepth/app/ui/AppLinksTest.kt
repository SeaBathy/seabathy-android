package au.opendepth.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {

    @Test
    fun `the Play build shares its Play listing, the GitHub build the website`() {
        assertEquals("https://play.google.com/store/apps/details?id=com.seabathy.app", AppLinks.shareUrl(true, "com.seabathy.app"))
        assertEquals("https://seabathy.com", AppLinks.shareUrl(false, "au.opendepth.app"))
    }

    @Test
    fun `rating opens the Play Store app on the listing`() {
        assertEquals("market://details?id=com.seabathy.app", AppLinks.marketUri("com.seabathy.app"))
    }

    @Test
    fun `the shared line is short and ends with the link`() {
        val text = AppLinks.shareText("https://seabathy.com")
        assertTrue(text.endsWith(" https://seabathy.com"))
        assertTrue(text.length < 160)
    }
}
