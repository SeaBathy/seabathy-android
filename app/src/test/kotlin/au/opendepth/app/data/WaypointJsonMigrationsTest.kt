package au.opendepth.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointJsonMigrationsTest {

    @Test
    fun `a waypoint missing the icon key gets the default injected`() {
        val raw = """[{"id":"a","name":"Reef","lat":1.0,"lng":2.0,"color":"#EF4444"}]"""

        val migrated = WaypointJsonMigrations.addMissingIcon(raw)

        assertTrue(migrated.contains("\"icon\":\"marker\""))
    }

    @Test
    fun `a waypoint that already has an icon is left exactly alone`() {
        val raw = """[{"id":"a","name":"Reef","icon":"wreck"}]"""

        assertEquals(raw, WaypointJsonMigrations.addMissingIcon(raw))
    }

    @Test
    fun `multiple waypoints are each patched independently`() {
        val raw = """[
            {"id":"a","name":"One"},
            {"id":"b","name":"Two","icon":"fish"}
        ]"""

        val migrated = WaypointJsonMigrations.addMissingIcon(raw)

        assertTrue(migrated.contains("\"icon\":\"marker\""))
        assertTrue(migrated.contains("\"icon\":\"fish\""))
    }

    @Test
    fun `malformed JSON is returned unchanged rather than thrown`() {
        val raw = "not json"
        assertEquals(raw, WaypointJsonMigrations.addMissingIcon(raw))
    }

    @Test
    fun `an empty list is left alone`() {
        assertEquals("[]", WaypointJsonMigrations.addMissingIcon("[]"))
    }
}
