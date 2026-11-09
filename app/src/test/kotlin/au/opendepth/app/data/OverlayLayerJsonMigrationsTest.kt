package au.opendepth.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayerJsonMigrationsTest {

    @Test
    fun `a ramp missing the exaggeration key gets the pre-slider default injected`() {
        val raw = """[{"id":"layer_1","source":"a.tif","ramp":{"paletteId":"rainbow","shallowMetres":-6.0,"deepMetres":-100.0}}]"""

        val migrated = OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw)

        assertTrue(
            "expected the injected default to appear, got: $migrated",
            migrated.contains("\"hillshadeExaggeration\":0.6")
        )
    }

    @Test
    fun `a ramp that already has the key, even at zero, is left exactly alone`() {
        val raw = """[{"id":"layer_1","source":"a.tif","ramp":{"paletteId":"rainbow","hillshadeExaggeration":0.0}}]"""

        val migrated = OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw)

        assertEquals(raw, migrated)
    }

    @Test
    fun `a layer with no ramp at all is left alone`() {
        val raw = """[{"id":"layer_1","source":"a.tif","ramp":null}]"""

        val migrated = OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw)

        assertEquals(raw, migrated)
    }

    @Test
    fun `multiple layers are each patched independently`() {
        val raw = """[
            {"id":"a","source":"a.tif","ramp":{"paletteId":"ocean"}},
            {"id":"b","source":"b.tif","ramp":{"paletteId":"viridis","hillshadeExaggeration":2.5}}
        ]"""

        val migrated = OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw)

        assertTrue(migrated.contains("\"hillshadeExaggeration\":0.6"))
        assertTrue(migrated.contains("\"hillshadeExaggeration\":2.5"))
    }

    @Test
    fun `malformed JSON is returned unchanged rather than thrown`() {
        val raw = "not json at all"

        assertEquals(raw, OverlayLayerJsonMigrations.addMissingHillshadeExaggeration(raw))
    }

    @Test
    fun `an empty layer list is left alone`() {
        assertFalse(OverlayLayerJsonMigrations.addMissingHillshadeExaggeration("[]").isBlank())
        assertEquals("[]", OverlayLayerJsonMigrations.addMissingHillshadeExaggeration("[]"))
    }
}
