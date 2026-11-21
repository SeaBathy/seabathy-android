package au.opendepth.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class NswReefTilesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun feature(id: Int, lng: Double = 151.3, lat: Double = -33.8) =
        """{"type":"Feature","id":$id,"geometry":{"type":"Polygon","coordinates":[[[$lng,$lat],[${lng + 0.001},$lat],[$lng,${lat + 0.001}],[$lng,$lat]]]},"properties":{"OBJECTID":$id}}"""

    private fun page(ids: IntRange, exceeded: Boolean = false) =
        """{"type":"FeatureCollection","features":[${ids.joinToString(",") { feature(it) }}]""" +
            (if (exceeded) ""","exceededTransferLimit":true""" else "") + "}"

    @Test
    fun `tiles cover the area on screen, only along the NSW coast`() {
        val sydney = NswReefTiles.tilesFor(-33.9, 151.2, -33.8, 151.35)
        assertTrue(sydney.isNotEmpty())
        sydney.forEach { t ->
            assertTrue(t.west < 151.35 && t.east > 151.2 && t.south < -33.8 && t.north > -33.9)
        }
        // Queensland and the open Tasman are outside the data: nothing is asked for there.
        assertTrue(NswReefTiles.tilesFor(-27.5, 153.0, -27.3, 153.3).isEmpty())
        assertTrue(NswReefTiles.tilesFor(-34.0, 156.0, -33.0, 157.0).isEmpty())
    }

    @Test
    fun `the tile in the middle of the screen comes first`() {
        val tiles = NswReefTiles.tilesFor(-34.2, 150.9, -33.6, 151.5)
        val cx = (tiles.minOf { it.x } + tiles.maxOf { it.x }) / 2.0
        val cy = (tiles.minOf { it.y } + tiles.maxOf { it.y }) / 2.0
        fun far(t: NswReefTiles.Tile) = (t.x - cx) * (t.x - cx) + (t.y - cy) * (t.y - cy)
        assertTrue(far(tiles.first()) <= 0.5)
        assertEquals(tiles.map(::far).sorted(), tiles.map(::far))
        assertEquals(tiles.size, tiles.distinct().size)
    }

    @Test
    fun `a tile's edges are its web-mercator tile's`() {
        val t = NswReefTiles.Tile(3767, 2457)
        assertEquals(151.083984375, t.west, 1e-9)
        assertEquals(151.171875, t.east, 1e-9)
        assertTrue(t.north > t.south)
        assertEquals("12_3767_2457", t.name)
    }

    @Test
    fun `the query asks for reef outlines only, in the tile, from an offset`() {
        val t = NswReefTiles.Tile(3767, 2457)
        val url = NswReefTiles.queryUrl(t, 1000)
        assertTrue(url.startsWith(NswReefTiles.QUERY + "?"))
        assertTrue(url.contains("where=REEF%3D%27Reef%27"))
        assertTrue(url.contains("geometry=151.083984%2C"))
        assertTrue(url.contains("%2C151.171875%2C"))
        assertTrue(url.contains("resultOffset=1000"))
        assertTrue(url.contains("outSR=4326"))
        assertTrue(url.contains("f=geojson"))
    }

    @Test
    fun `an answer's reefs and whether there are more`() {
        val one = NswReefTiles.parsePage(page(1..3))
        assertEquals(listOf(1L, 2L, 3L), one.reefs.map { it.id })
        assertFalse(one.more)
        assertTrue(NswReefTiles.parsePage(page(1..2, exceeded = true)).more)
        // An id only in the properties is used too; a feature without a shape is skipped.
        val odd = NswReefTiles.parsePage("""{"features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[151,-33]},"properties":{"OBJECTID":9}},{"type":"Feature","id":4,"properties":{}}]}""")
        assertEquals(listOf(9L), odd.reefs.map { it.id })
    }

    @Test(expected = IOException::class)
    fun `an error answer is an error`() {
        NswReefTiles.parsePage("""{"error":{"code":400,"message":"Invalid query"}}""")
    }

    @Test
    fun `a reef across a tile edge is drawn once`() {
        val a = NswReefTiles.parsePage(page(1..3)).reefs
        val b = NswReefTiles.parsePage(page(3..5)).reefs
        val json = NswReefTiles.featureCollection(listOf(a, b))
        assertEquals(5, Regex("\"type\":\"Feature\"").findAll(json).count())
        assertTrue(json.startsWith("""{"type":"FeatureCollection","features":["""))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", NswReefTiles.featureCollection(emptyList()))
    }

    @Test
    fun `a tile is fetched page by page, kept, and read back without fetching again`() {
        val asked = mutableListOf<String>()
        val pages = mapOf(0 to page(1..1000), 1000 to page(1001..1200))
        val store = NswReefStore(temp.root) { url ->
            asked += url
            val offset = Regex("resultOffset=(\\d+)").find(url)!!.groupValues[1].toInt()
            pages.getValue(offset)
        }
        val tile = NswReefTiles.Tile(3767, 2457)
        assertNull(store.cached(tile))
        assertEquals(1200, store.download(tile).size)
        assertEquals(2, asked.size)

        val again = NswReefStore(temp.root) { throw AssertionError("fetched a kept tile") }
        assertEquals(1200, again.cached(tile)?.size)
    }

    @Test
    fun `a broken kept tile is fetched again, not shown`() {
        val tile = NswReefTiles.Tile(3767, 2457)
        val file = temp.newFile(tile.name + ".json.gz")
        file.writeText("not gzip")
        val store = NswReefStore(temp.root) { page(1..2) }
        assertNull(store.cached(tile))
        assertFalse(file.exists())
        assertNotNull(store.download(tile))
        assertEquals(2, store.cached(tile)?.size)
    }

    @Test
    fun `nothing is kept when the service can't be reached`() {
        val tile = NswReefTiles.Tile(3767, 2457)
        val store = NswReefStore(temp.root) { throw IOException("no signal") }
        assertTrue(runCatching { store.download(tile) }.isFailure)
        assertNull(store.cached(tile))
    }
}
