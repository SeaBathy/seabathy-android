package au.opendepth.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetEdgeDetectorTest {
    @Test
    fun reportsOnlyOfflineToOnlineTransitions() {
        val edge = InternetEdgeDetector(initial = false)
        assertFalse(edge.update(false))
        assertTrue(edge.update(true))
        assertFalse(edge.update(true))
        assertFalse(edge.update(false))
        assertTrue(edge.update(true))
    }

    @Test
    fun startingOnlineDoesNotReportRegain() {
        val edge = InternetEdgeDetector(initial = true)
        assertFalse(edge.update(true))
    }
}
