package au.opendepth.app.map

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The bundled fishing layers as built by tools/fishing_layers: what the app's cards and styles rely on. */
class FishingAssetsTest {

    private fun asset(name: String): File =
        listOf(File("src/main/assets/fishing/$name"), File("app/src/main/assets/fishing/$name")).first { it.exists() }

    private fun features(name: String): List<JsonObject> =
        JsonParser.parseString(asset(name).readText()).asJsonObject.getAsJsonArray("features").map { it.asJsonObject }

    private fun JsonObject.props(): JsonObject = getAsJsonObject("properties")
    private fun JsonObject.text(key: String): String? = props().get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.type(): String = getAsJsonObject("geometry").get("type").asString
    private fun JsonObject.point(): Pair<Double, Double> =
        getAsJsonObject("geometry").getAsJsonArray("coordinates").let { it[0].asDouble to it[1].asDouble }

    private fun km(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val la1 = Math.toRadians(a.second); val la2 = Math.toRadians(b.second)
        val dLa = la2 - la1; val dLo = Math.toRadians(b.first - a.first)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 6371 * 2 * asin(sqrt(h))
    }

    @Test
    fun `wrecks are points round Australia with a position note the card knows`() {
        val wrecks = features("wrecks.geojson")
        assertTrue(wrecks.size in 5000..7000)
        val codes = setOf("p1", "p2", "p3", "f", "nf", "g", "ae", "ch", "chk", null)
        wrecks.forEach { f ->
            assertEquals("Point", f.type())
            val (lng, lat) = f.point()
            assertTrue("$lng,$lat", lng in 95.0..170.0 && lat in -56.0..-8.0)
            assertTrue(f.text("a"), f.text("a") in codes)
            assertTrue(f.text("s") in setOf(null, "W", "S"))
        }
        // All three sources are in, and most wrecks have a name.
        assertTrue(wrecks.count { it.text("s") == "W" } > 200)
        assertTrue(wrecks.count { it.text("s") == "S" } > 500)
        assertTrue(wrecks.count { it.text("n") != null } > wrecks.size * 9 / 10)
    }

    @Test
    fun `a wreck in a state's records isn't also shown from the national one at the same spot`() {
        val wrecks = features("wrecks.geojson")
        fun key(f: JsonObject) = (f.text("n") ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")
        val national = wrecks.filter { it.text("s") == null && key(it).isNotEmpty() }.groupBy(::key)
        val repeats = wrecks.filter { it.text("s") != null && key(it).isNotEmpty() }.count { state ->
            national[key(state)].orEmpty().any { km(it.point(), state.point()) < 0.5 }
        }
        assertEquals(0, repeats)
    }

    @Test
    fun `closures are named areas of a kind the card knows, with their rules`() {
        val closures = features("closures.geojson")
        assertTrue(closures.size > 150)
        val kinds = closures.map { it.text("k") }.toSet()
        assertEquals(setOf("closed", "limits", "anchoring", "habitat"), kinds)
        closures.forEach { f ->
            assertTrue(f.type() == "Polygon" || f.type() == "MultiPolygon")
            assertTrue(!f.text("n").isNullOrBlank())
            assertTrue(!f.text("w").isNullOrBlank())
            assertTrue(f.text("src") in setOf("GBRMPA", "Qld", "WA"))
            assertTrue(f.props().get("sz").asDouble >= 0)
            // MapLibre drops the whole file for one ring of under four points.
            val coordinates = f.getAsJsonObject("geometry").getAsJsonArray("coordinates")
            val polygons: List<JsonArray> = if (f.type() == "Polygon") listOf(coordinates) else coordinates.map { it.asJsonArray }
            polygons.forEach { rings -> rings.forEach { ring -> assertTrue(ring.asJsonArray.size() >= 4) } }
        }
        assertEquals(setOf("GBRMPA", "Qld", "WA"), closures.map { it.text("src") }.toSet())
    }

    @Test
    fun `sites are artificial reefs and shark gear`() {
        val sites = features("sites.geojson")
        val kinds = sites.groupBy { it.text("k") }
        assertEquals(setOf("reef", "reef_area", "drum", "net"), kinds.keys)
        assertTrue(kinds.getValue("drum").size > 300)
        assertTrue(kinds.getValue("reef").all { it.type() == "Point" && !it.text("n").isNullOrBlank() })
        assertTrue(kinds.getValue("net").all { it.type() == "LineString" || it.type() == "Point" })
    }

    @Test
    fun `the bundled fishing layers stay small`() {
        val total = listOf("wrecks.geojson", "sites.geojson", "closures.geojson").sumOf { asset(it).length() }
        assertTrue("$total bytes", total < 3_000_000)
    }
}
