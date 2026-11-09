package au.opendepth.app.download

import au.opendepth.app.model.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSamplerTest {

    private val bounds = GeoBounds(west = 151.0, south = -34.0, east = 151.5, north = -33.5)

    @Test
    fun `averages real fetched sizes rather than a flat assumption`() {
        val sampler = TileSampler { ByteArray(30_000) }

        val result = sampler.sample("https://example.com/{z}/{x}/{y}.jpg", bounds, 8..12)

        assertEquals(30_000L, result.averageBytes)
        assertTrue(result.sampledSizes.isNotEmpty())
        assertTrue(result.sampledSizes.all { it == 30_000 })
    }

    @Test
    fun `skips failed samples rather than counting them as zero-byte tiles`() {
        var call = 0
        val sampler = TileSampler { call++; if (call % 2 == 0) null else ByteArray(10_000) }

        val result = sampler.sample("https://example.com/{z}/{x}/{y}.jpg", bounds, 8..12)

        assertTrue(result.sampledSizes.isNotEmpty())
        assertTrue(result.sampledSizes.all { it == 10_000 })
        assertEquals(10_000L, result.averageBytes)
    }

    @Test
    fun `returns a null average when every sample fails, not zero`() {
        val sampler = TileSampler { null }

        val result = sampler.sample("https://example.com/{z}/{x}/{y}.jpg", bounds, 8..12)

        assertTrue(result.sampledSizes.isEmpty())
        assertNull(result.averageBytes)
    }

    @Test
    fun `samples span the whole requested zoom range, not just the first zoom`() {
        // Template shape "https://example.com/{z}/{x}/{y}.jpg" -> after
        // substitution, split('/') puts the zoom at a fixed index.
        val requestedZooms = mutableSetOf<Int>()
        val sampler = TileSampler { url -> requestedZooms.add(url.split("/")[3].toInt()); ByteArray(1) }

        sampler.sample("https://example.com/{z}/{x}/{y}.jpg", bounds, 8..12)

        assertEquals((8..12).toSet(), requestedZooms)
    }

    @Test
    fun `spreadPicks samples across the whole list, not just the first N`() {
        val candidates = (0 until 100).toList()

        val picks = TileSampler.spreadPicks(candidates, 5)

        assertEquals(listOf(0, 20, 40, 60, 80), picks)
    }

    @Test
    fun `spreadPicks never asks for more picks than there are candidates`() {
        val candidates = listOf("a", "b", "c")

        val picks = TileSampler.spreadPicks(candidates, 8)

        assertEquals(3, picks.size)
    }

    @Test
    fun `urlFor substitutes tokens regardless of their order in the template`() {
        val url = TileSampler.urlFor("https://tiles.example/{z}/{y}/{x}.jpg", z = 10, x = 5, y = 7)

        assertEquals("https://tiles.example/10/7/5.jpg", url)
    }
}
