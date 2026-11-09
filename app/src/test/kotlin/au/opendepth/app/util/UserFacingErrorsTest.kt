package au.opendepth.app.util

import au.opendepth.app.geotiff.GeoTiffReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class UserFacingErrorsTest {

    private val fallback = "Could not import this file"

    @Test
    fun `app-authored IOException messages pass through`() {
        assertEquals(
            "The selected TIFF file is empty",
            UserFacingErrors.describe(IOException("The selected TIFF file is empty"), fallback)
        )
    }

    @Test
    fun `app-declared IOException subclasses pass through`() {
        assertEquals(
            "Unsupported compression",
            UserFacingErrors.describe(GeoTiffReader.UnsupportedGeoTiffException("Unsupported compression"), fallback)
        )
    }

    @Test
    fun `blank messages use the fallback`() {
        assertEquals(fallback, UserFacingErrors.describe(IOException(" "), fallback))
        assertEquals(fallback, UserFacingErrors.describe(IOException(), fallback))
    }

    @Test
    fun `network failures never show host names or stack wording`() {
        val message = UserFacingErrors.describe(
            UnknownHostException("Unable to resolve host \"tile.example.com\": No address associated with hostname"),
            fallback
        )
        assertFalse(message.contains("tile.example.com"))
        assertNotEquals(fallback, message)
        assertEquals(message, UserFacingErrors.describe(ConnectException("ECONNREFUSED"), fallback))
    }

    @Test
    fun `permission denials hide the content uri`() {
        val message = UserFacingErrors.describe(
            SecurityException("Permission Denial: reading content://com.android.providers/doc/123 requires grant"),
            fallback
        )
        assertFalse(message.contains("content://"))
        assertNotEquals(fallback, message)
    }

    @Test
    fun `missing files get a plain explanation`() {
        val message = UserFacingErrors.describe(FileNotFoundException("/storage/x.mbtiles: open failed: ENOENT"), fallback)
        assertFalse(message.contains("ENOENT"))
        // FileNotFoundException is an IOException: its own branch must win over pass-through.
        assertNotEquals(fallback, message)
    }

    @Test
    fun `platform errno failures never pass through`() {
        assertEquals(
            fallback,
            UserFacingErrors.describe(IOException("read failed: EIO (I/O error)"), fallback)
        )
    }

    @Test
    fun `out of storage space gets a plain explanation`() {
        val message = UserFacingErrors.describe(IOException("write failed: ENOSPC (No space left on device)"), fallback)
        assertFalse(message.contains("ENOSPC"))
        assertNotEquals(fallback, message)
    }

    @Test
    fun `runtime exceptions use the fallback`() {
        assertEquals(fallback, UserFacingErrors.describe(IllegalStateException("index 3 out of bounds"), fallback))
        assertEquals(fallback, UserFacingErrors.describe(NullPointerException(), fallback))
    }
}
