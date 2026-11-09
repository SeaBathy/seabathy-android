package au.opendepth.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointIconTest {

    @Test
    fun `every icon has a unique id`() {
        val ids = WaypointIcon.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `default icon id resolves to the default icon`() {
        assertEquals(WaypointIcon.DEFAULT, WaypointIcon.byId(WaypointIcon.DEFAULT_ICON_ID))
    }

    @Test
    fun `an unrecognised id falls back to the default rather than throwing`() {
        assertEquals(WaypointIcon.DEFAULT, WaypointIcon.byId("not-a-real-icon"))
    }

    @Test
    fun `a null id falls back to the default`() {
        assertEquals(WaypointIcon.DEFAULT, WaypointIcon.byId(null))
    }

    @Test
    fun `the curated set covers the requested marine use cases`() {
        val ids = WaypointIcon.ALL.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("marker", "diamond", "flag", "anchor", "fish", "wreck", "mob")))
    }
}
