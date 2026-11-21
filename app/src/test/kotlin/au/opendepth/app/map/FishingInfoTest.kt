package au.opendepth.app.map

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingInfoTest {

    private fun props(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun MapInfo.row(label: String) = rows.firstOrNull { it.label == label }?.value

    @Test
    fun `a wreck card says what is known and that positions may be approximate`() {
        val info = FishingInfo.wreck(props("""{"n":"Admella","y":1859,"t":"Screw steamer","l":"Carpenters Rocks","a":"f","s":"S","also":"A"}"""))
        assertEquals("Shipwreck", info.badge)
        assertEquals("Admella", info.title)
        assertEquals("Lost 1859 · Screw steamer", info.subtitle)
        assertEquals("Carpenters Rocks", info.row("Where"))
        assertEquals("From the wreck site, which has been found", info.row("Position"))
        assertEquals("SA Department for Environment and Water, and the national database", info.row("Record"))
        assertTrue(info.fine!!.contains("Positions are often approximate"))
        assertTrue(info.fine!!.contains(FishingInfo.WRECKS_CREDIT))
    }

    @Test
    fun `a wreck with little known still gets a card`() {
        val info = FishingInfo.wreck(props("""{"a":"p1"}"""))
        assertEquals("Unnamed wreck", info.title)
        assertNull(info.subtitle)
        assertEquals("Rough: to about 10 km", info.row("Position"))
        assertEquals("Australasian Underwater Cultural Heritage Database", info.row("Record"))
        assertNull(info.row("Depth"))
        assertEquals("Aircraft wreck", FishingInfo.wreck(props("""{"n":"Catalina","air":1,"s":"W"}""")).badge)
    }

    @Test
    fun `every position code reads plainly`() {
        listOf("p1", "p2", "p3", "f", "nf", "g", "ae", "ch", "chk").forEach { code ->
            assertTrue(code, !FishingInfo.positionNote(code).isNullOrBlank())
        }
        assertNull(FishingInfo.positionNote(null))
        assertNull(FishingInfo.positionNote("zz"))
    }

    @Test
    fun `wreck depths as a range in metres`() {
        assertEquals("20 m", FishingInfo.depthText(20.0, 20.0))
        assertEquals("12 to 18.5 m", FishingInfo.depthText(12.0, 18.5))
        assertEquals("18 m", FishingInfo.depthText(0.0, 18.0))
        assertNull(FishingInfo.depthText(0.0, 0.0))
        assertNull(FishingInfo.depthText(null, null))
        assertEquals("Depth", FishingInfo.wreck(props("""{"n":"Omeo","d0":3,"d1":6,"s":"W"}""")).rows.first { it.value == "3 to 6 m" }.label)
    }

    @Test
    fun `a closure card says what kind of area it is and to check the rules`() {
        val info = FishingInfo.closure(props("""{"n":"HMAS Swan Wreck Site","k":"closed","w":"No fishing at this wreck site.","o":"Wreck Site Order 1998","src":"WA","sz":0.5}"""))
        assertEquals("Closed area", info.badge)
        assertEquals("HMAS Swan Wreck Site", info.title)
        assertEquals("Western Australia", info.subtitle)
        assertEquals("No fishing at this wreck site.", info.row("Rules"))
        assertEquals("Wreck Site Order 1998", info.row("Under"))
        assertTrue(info.paragraphs.single().contains("check the current rules"))
        assertTrue(info.paragraphs.single().contains("not the law"))
        assertEquals(FishingInfo.CLOSURES_CREDIT, info.fine)
    }

    @Test
    fun `each closure kind has its own badge`() {
        assertEquals("Closed area", FishingInfo.closureBadge("closed").first)
        assertEquals("Fishing limits", FishingInfo.closureBadge("limits").first)
        assertEquals("No anchoring", FishingInfo.closureBadge("anchoring").first)
        assertEquals("Fish habitat", FishingInfo.closureBadge("habitat").first)
        assertEquals("Fishing limits", FishingInfo.closureBadge(null).first)
        val fha = FishingInfo.closure(props("""{"n":"Kippa-Ring Fish Habitat Area","k":"habitat","w":"Fish Habitat Area.","o":"FHA-014","src":"Qld"}"""))
        assertEquals("FHA-014", fha.row("Reference"))
        assertEquals("Queensland", fha.subtitle)
    }

    @Test
    fun `a closure named on a marine park zone's card`() {
        val row = FishingInfo.alsoHere(props("""{"n":"Raine Island Reef (11-243)","k":"closed","w":"Restricted access.","src":"GBRMPA"}"""))
        assertEquals("Also here", row.label)
        assertEquals("Closed area: Raine Island Reef (11-243). Check the current rules.", row.value)
        assertEquals("Fishing limits. Check the current rules.", FishingInfo.alsoHere(props("""{"k":"limits"}""")).value)
    }

    @Test
    fun `shark gear and reefs`() {
        val drum = FishingInfo.sharkGear(props("""{"k":"drum","n":"Drumline 6","b":"Narrowneck Beach","ar":"Gold Coast"}"""))
        assertEquals("Shark control", drum.badge)
        assertEquals("Drumline 6", drum.title)
        assertEquals("Narrowneck Beach · Gold Coast", drum.subtitle)
        assertTrue(drum.paragraphs.single().contains("Keep clear"))
        assertTrue(drum.fine!!.contains("April 2024"))
        assertEquals("Shark net", FishingInfo.sharkGear(props("""{"k":"net"}""")).title)

        val reef = FishingInfo.reef(props("""{"k":"reef","n":"West Peel","l":"Moreton Bay Marine Park","src":"Q","ha":50}"""))
        assertEquals("West Peel", reef.title)
        assertEquals("50 ha", reef.row("Size"))
        assertTrue(reef.paragraphs.single().contains("check the current rules"))
        val narrowneck = FishingInfo.reef(props("""{"k":"reef","n":"Narrowneck Artificial Reef","l":"Gold Coast","src":"G","yb":2000}"""))
        assertEquals("2000", narrowneck.row("Built"))
        assertTrue(narrowneck.paragraphs.single().contains("protect the beach"))
    }

    @Test
    fun `every fishing layer has a credit naming its licence`() {
        assertEquals(5, FishingInfo.ALL_CREDITS.size)
        FishingInfo.ALL_CREDITS.forEach { credit ->
            assertTrue(credit, credit.isNotBlank())
            assertTrue(credit, credit.contains("CC BY"))
        }
        assertEquals(FishingInfo.NSW_REEF_CREDIT, FishingInfo.nswReef().fine)
    }
}
