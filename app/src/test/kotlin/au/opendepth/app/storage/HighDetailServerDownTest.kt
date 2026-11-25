package au.opendepth.app.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** When IMAS's servers fail, high detail falls back to the worldwide depths; other failures are shown as they are. */
class HighDetailServerDownTest {

    @Test
    fun imasRefusingOrFailingCountsAsDown() {
        assertTrue(HighDetailDepths.serverDown(IOException("HTTP 403 from data.imas.utas.edu.au")))
        assertTrue(HighDetailDepths.serverDown(IOException("HTTP 404 from data.imas.utas.edu.au")))
        assertTrue(HighDetailDepths.serverDown(IOException("Couldn't read 01_shallow_bathy.tif: HTTP 500 from geoserver.imas.utas.edu.au")))
        assertTrue(HighDetailDepths.serverDown(IOException("Can't reach data.imas.utas.edu.au. Check the phone's internet connection.")))
        // Wrapped, as the engine's worker threads pass it on.
        assertTrue(HighDetailDepths.serverDown(RuntimeException("build failed", IOException("HTTP 503 from data.imas.utas.edu.au"))))
    }

    @Test
    fun otherFailuresAreNotTreatedAsImasDown() {
        assertFalse(HighDetailDepths.serverDown(IOException("No high-detail depths for this area.")))
        assertFalse(HighDetailDepths.serverDown(IOException("HTTP 403 from ausseabed-public-bathymetry.s3.ap-southeast-2.amazonaws.com")))
        assertFalse(HighDetailDepths.serverDown(IOException("The phone ran out of memory. Zoom in to a smaller area and try again.")))
        assertFalse(HighDetailDepths.serverDown(IOException("HTTP 416 from data.imas.utas.edu.au")))
    }
}
