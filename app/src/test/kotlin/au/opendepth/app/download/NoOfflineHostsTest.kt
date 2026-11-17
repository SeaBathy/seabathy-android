package au.opendepth.app.download

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoOfflineHostsTest {
    @Test
    fun blocksServersThatForbidOfflineCopies() {
        assertNotNull(OfflineTileDownloadController.noOfflineReason("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertNotNull(OfflineTileDownloadController.noOfflineReason("https://a.tile.opentopomap.org/{z}/{x}/{y}.png"))
    }

    @Test
    fun allowsOtherServers() {
        assertNull(OfflineTileDownloadController.noOfflineReason("https://gibs.earthdata.nasa.gov/wmts/{z}/{y}/{x}.jpg"))
        assertNull(OfflineTileDownloadController.noOfflineReason("https://tiles.openfreemap.org/{z}/{x}/{y}.pbf"))
        assertNull(OfflineTileDownloadController.noOfflineReason("not a url"))
    }
}
