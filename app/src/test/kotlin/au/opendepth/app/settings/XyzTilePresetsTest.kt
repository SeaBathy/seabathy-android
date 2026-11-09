package au.opendepth.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XyzTilePresetsTest {

    @Test
    fun `every preset is a valid https XYZ template`() {
        XyzTilePresets.ALL.forEach { preset ->
            assertNull(
                "${preset.label} is not a usable template",
                XyzTilePresets.validationError(preset.urlTemplate)
            )
            assertTrue(
                "${preset.label} should be served over https",
                preset.urlTemplate.startsWith("https://")
            )
            assertTrue(
                "${preset.label} needs an attribution string",
                preset.attribution.isNotBlank()
            )
        }
    }

    @Test
    fun `preset ids are unique`() {
        val ids = XyzTilePresets.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `satellite imagery is offered and has a default`() {
        assertTrue(XyzTilePresets.SATELLITE.isNotEmpty())
        assertEquals(
            XyzTilePreset.Category.SATELLITE,
            XyzTilePresets.DEFAULT_SATELLITE.category
        )
    }

    @Test
    fun `presets can be looked up by id`() {
        val first = XyzTilePresets.ALL.first()
        assertNotNull(XyzTilePresets.byId(first.id))
        assertNull(XyzTilePresets.byId("not-a-preset"))
        assertNull(XyzTilePresets.byId(null))
    }

    @Test
    fun `validation rejects blank, non http and incomplete templates`() {
        assertEquals("Enter an XYZ tile URL", XyzTilePresets.validationError("   "))
        assertEquals(
            "URL must start with http:// or https://",
            XyzTilePresets.validationError("ftp://tiles/{z}/{x}/{y}.png")
        )
        assertEquals(
            "URL must contain {z}, {x} and {y}",
            XyzTilePresets.validationError("https://tiles/{z}/{x}.png")
        )
    }

    @Test
    fun `validation accepts a plain template and tolerates whitespace`() {
        assertNull(XyzTilePresets.validationError("  https://a/{z}/{x}/{y}.png  "))
        assertNull(XyzTilePresets.validationError("http://a/{z}/{y}/{x}"))
    }
}
