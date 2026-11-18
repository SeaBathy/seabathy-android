package au.opendepth.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineFolderTest {

    @Test
    fun `a build that can be given all files access keeps the shared folder`() {
        assertEquals(OfflineFolder.SHARED, offlineFolderFor(mayHaveAllFilesAccess = true, sdk = 36))
        assertEquals(OfflineFolder.SHARED, offlineFolderFor(mayHaveAllFilesAccess = true, sdk = 26))
    }

    @Test
    fun `a build without it saves to Download from Android 11`() {
        assertEquals(OfflineFolder.DOWNLOADS, offlineFolderFor(mayHaveAllFilesAccess = false, sdk = 30))
        assertEquals(OfflineFolder.DOWNLOADS, offlineFolderFor(mayHaveAllFilesAccess = false, sdk = 36))
    }

    @Test
    fun `and to its own folder before Android 11`() {
        assertEquals(OfflineFolder.APP, offlineFolderFor(mayHaveAllFilesAccess = false, sdk = 29))
        assertEquals(OfflineFolder.APP, offlineFolderFor(mayHaveAllFilesAccess = false, sdk = 26))
    }
}
