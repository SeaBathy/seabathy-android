package au.opendepth.app.map

import au.opendepth.app.geotiff.DepthGrid
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.nav.DepthUnit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DepthContoursTest {

    // ---------------------------------------------------------------- the interval

    @Test
    fun `auto picks an interval that gives about sixteen lines at most`() {
        assertEquals(1, DepthContours.stepFor(-12.0, 0.0))
        assertEquals(2, DepthContours.stepFor(-20.0, 0.0))
        assertEquals(10, DepthContours.stepFor(-100.0, 0.0))
        assertEquals(50, DepthContours.stepFor(-830.0, -40.0))
        assertEquals(500, DepthContours.stepFor(-5200.0, 0.0))
    }

    @Test
    fun `auto has an answer for a flat seabed, the deepest ocean, and a range no sea has`() {
        assertEquals(1, DepthContours.stepFor(-8.0, -8.0))
        assertEquals(1, DepthContours.stepFor(-3.0, -8.0))
        assertEquals(1000, DepthContours.stepFor(-11000.0, 0.0, DepthContours.AUTO))
        assertEquals(2000, DepthContours.stepFor(-500_000.0, 0.0))
    }

    @Test
    fun `a picked interval is used, unless it would make too many lines`() {
        assertEquals(5, DepthContours.stepFor(-100.0, 0.0, picked = 5))
        assertEquals(100, DepthContours.stepFor(-30.0, 0.0, picked = 100))
        // A line every metre over 100 m is 100 lines: the next interval with 60 or fewer is used.
        assertEquals(2, DepthContours.stepFor(-100.0, 0.0, picked = 1))
        assertEquals(20, DepthContours.stepFor(-1000.0, 0.0, picked = 1))
    }

    // ---------------------------------------------------------------- the interval in the depth unit

    @Test
    fun `the spacings offered are round numbers of the unit chosen`() {
        assertEquals(listOf(1, 2, 5, 10, 20, 50, 100), DepthContours.choices(DepthUnit.METRES))
        assertEquals(listOf(2, 5, 10, 20, 50, 100, 200, 500), DepthContours.choices(DepthUnit.FEET))
        assertEquals(listOf(1, 2, 5, 10, 20, 50), DepthContours.choices(DepthUnit.FATHOMS))

        assertEquals("5 m", DepthContours.spacingLabel(5, DepthUnit.METRES))
        assertEquals("5 ft", DepthContours.spacingLabel(5, DepthUnit.FEET))
        assertEquals("10 fm", DepthContours.spacingLabel(10, DepthUnit.FATHOMS))
    }

    @Test
    fun `a spacing is kept in metres and read back as it was picked`() {
        for (unit in DepthUnit.values()) {
            for (every in DepthContours.choices(unit)) {
                assertEquals("$every ${unit.label}", every, DepthContours.pickedIn(DepthContours.metresFor(every, unit), unit))
            }
        }
        assertEquals(5.0, DepthContours.metresFor(5, DepthUnit.METRES), 0.0)
        assertEquals(1.524, DepthContours.metresFor(5, DepthUnit.FEET), 0.0005)
        assertEquals(18.288, DepthContours.metresFor(10, DepthUnit.FATHOMS), 0.0005)
    }

    @Test
    fun `no spacing kept, or one that makes no sense, is Auto`() {
        for (unit in DepthUnit.values()) {
            for (kept in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertEquals(DepthContours.AUTO, DepthContours.pickedIn(kept, unit))
            }
        }
    }

    @Test
    fun `a spacing picked in another unit reads as the closest round number of this one`() {
        // Whole metres, as layers were saved before the spacing followed the unit, with feet chosen now.
        assertEquals(listOf(5, 5, 20, 50, 50, 200, 500), listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).map { DepthContours.pickedIn(it, DepthUnit.FEET) })
        // And with fathoms.
        assertEquals(listOf(1, 1, 2, 5, 10, 20, 50), listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).map { DepthContours.pickedIn(it, DepthUnit.FATHOMS) })
        // Picked in feet, with metres chosen now: 5 ft is 1.5 m, 20 ft is 6.1 m, 500 ft is 152 m.
        fun feet(every: Int) = DepthContours.pickedIn(DepthContours.metresFor(every, DepthUnit.FEET), DepthUnit.METRES)
        assertEquals(listOf(1, 2, 2, 5, 20, 20, 50, 100), DepthContours.choices(DepthUnit.FEET).map { feet(it) })
    }

    @Test
    fun `auto picks round numbers of the unit chosen`() {
        // 30 m of water is 98 ft, or 16.4 fathoms.
        assertEquals(2, DepthContours.stepFor(-30.0, 0.0, unit = DepthUnit.METRES))
        assertEquals(10, DepthContours.stepFor(-30.0, 0.0, unit = DepthUnit.FEET))
        assertEquals(2, DepthContours.stepFor(-30.0, 0.0, unit = DepthUnit.FATHOMS))
        // Four metres over a flat is 13 ft: a line every foot.
        assertEquals(1, DepthContours.stepFor(-4.0, 0.0, unit = DepthUnit.FEET))
        // The deepest ocean, 36 000 ft or 6000 fathoms, and a range no sea has.
        assertEquals(5000, DepthContours.stepFor(-11000.0, 0.0, unit = DepthUnit.FEET))
        assertEquals(500, DepthContours.stepFor(-11000.0, 0.0, unit = DepthUnit.FATHOMS))
        assertEquals(5000, DepthContours.stepFor(-500_000.0, 0.0, unit = DepthUnit.FEET))
        assertEquals(1000, DepthContours.stepFor(-500_000.0, 0.0, unit = DepthUnit.FATHOMS))
    }

    @Test
    fun `a picked spacing in feet is used, or gives way to a wider round number of feet`() {
        assertEquals(5, DepthContours.stepFor(-30.0, 0.0, picked = 5, unit = DepthUnit.FEET))
        // 100 m is 328 ft: a line every 2 ft would be 164 of them, every 5 ft still 66.
        assertEquals(10, DepthContours.stepFor(-100.0, 0.0, picked = 2, unit = DepthUnit.FEET))
        assertEquals(10, DepthContours.stepFor(-1000.0, 0.0, picked = 1, unit = DepthUnit.FATHOMS))
    }

    // ---------------------------------------------------------------- the label

    @Test
    fun `a line is labelled with the round number it stands for, without its sign`() {
        assertEquals("10 m", DepthContours.labelFor(-2, 5, DepthUnit.METRES))
        assertEquals("0 m", DepthContours.labelFor(0, 5, DepthUnit.METRES))
        assertEquals("15 ft", DepthContours.labelFor(-3, 5, DepthUnit.FEET))
        assertEquals("100 fm", DepthContours.labelFor(-10, 10, DepthUnit.FATHOMS))
    }

    @Test
    fun `a height above the water is marked as one`() {
        assertEquals("+5 m", DepthContours.labelFor(1, 5, DepthUnit.METRES))
    }

    // ---------------------------------------------------------------- tracing

    /** A cone-shaped hole: the depth at each cell is its distance, in cells, from the middle. */
    private fun cone(size: Int): FloatArray {
        val middle = (size - 1) / 2.0
        return FloatArray(size * size) { i -> -hypot(i % size - middle, i / size - middle).toFloat() }
    }

    private fun DoubleArray.isClosed() = size >= 4 && this[0] == this[size - 2] && this[1] == this[size - 1]

    @Test
    fun `a cone's 10 m line is one closed ring of 10 cells' radius`() {
        val levels = DepthContours.trace(cone(101), 101, 101, 10.0, top = 0.0)
        val ten = levels.single { it.level == -10.0 }

        assertEquals(1, ten.lines.size)
        val ring = ten.lines.single()
        assertTrue("the ring ends where it began", ring.isClosed())
        assertTrue("a ring of ${ring.size / 2} points", ring.size / 2 > 40)
        for (i in ring.indices step 2) {
            assertEquals(10.0, hypot(ring[i] - 50.0, ring[i + 1] - 50.0), 0.05)
        }
    }

    @Test
    fun `a ring is joined all the way round, one point after the next`() {
        val ring = DepthContours.trace(cone(101), 101, 101, 10.0, top = 0.0).single { it.level == -30.0 }.lines.single()

        assertTrue(ring.isClosed())
        // About eight crossings for every cell of radius.
        assertTrue("a ring of ${ring.size / 2} points", ring.size / 2 in 200..300)
        // Neighbours along the line are in the same cell: never more than a cell apart either way.
        for (i in 0 until ring.size - 2 step 2) {
            assertTrue(abs(ring[i + 2] - ring[i]) <= 1.0 + 1e-9 && abs(ring[i + 3] - ring[i + 1]) <= 1.0 + 1e-9)
        }
    }

    @Test
    fun `there is a line for every multiple of the interval between the deepest and the top`() {
        val levels = DepthContours.trace(cone(101), 101, 101, 10.0, top = 0.0)

        // The corners are 70.7 cells from the middle; nothing is above the surface.
        assertEquals(listOf(-70.0, -60.0, -50.0, -40.0, -30.0, -20.0, -10.0, 0.0), levels.map { it.level })
    }

    @Test
    fun `a line that runs off the depths is left open at the edge`() {
        val sixty = DepthContours.trace(cone(101), 101, 101, 10.0, top = 0.0).single { it.level == -60.0 }

        // A ring of 60 cells' radius is cut by all four sides: an arc across each corner.
        assertEquals(4, sixty.lines.size)
        sixty.lines.forEach { arc ->
            assertFalse(arc.isClosed())
            val ends = listOf(arc[0], arc[1], arc[arc.size - 2], arc[arc.size - 1])
            assertTrue("ends on the edge", ends.count { it == 0.0 || it == 100.0 } >= 2)
        }
    }

    @Test
    fun `no line crosses cells with no depth`() {
        // A seabed a metre deeper with every cell east, known only as far as column 6.
        val cols = 12
        val rows = 5
        val values = FloatArray(cols * rows) { i -> if (i % cols <= 6) -(i % cols) - 0.5f else Float.NaN }
        val levels = DepthContours.trace(values, cols, rows, 1.0)

        assertEquals(listOf(-6.0, -5.0, -4.0, -3.0, -2.0, -1.0), levels.map { it.level })
        levels.forEach { level ->
            val line = level.lines.single()
            assertEquals(rows, line.size / 2)
            // Half way between the two columns either side of it, top to bottom.
            for (i in line.indices step 2) assertEquals(-level.level - 0.5, line[i], 1e-6)
            assertEquals(setOf(0.0, (rows - 1).toDouble()), setOf(line[1], line[line.size - 1]))
        }
    }

    @Test
    fun `a saddle joins the pairs of sides the middle of the cell says`() {
        // Two shallow corners across from each other, two deep: one cell, level -5.
        val shallowMiddle = floatArrayOf(-2f, -8f, -8f, -2f)
        val lines = DepthContours.trace(shallowMiddle, 2, 2, 5.0, top = 0.0).single().lines

        assertEquals(2, lines.size)
        lines.forEach { assertEquals(2, it.size / 2) }
    }

    @Test
    fun `a flat seabed, or an interval of nothing, has no lines`() {
        assertTrue(DepthContours.trace(FloatArray(25) { -7f }, 5, 5, 1.0).isEmpty())
        assertTrue(DepthContours.trace(cone(11), 11, 11, 0.0).isEmpty())
        assertTrue(DepthContours.trace(FloatArray(25) { Float.NaN }, 5, 5, 1.0).isEmpty())
    }

    @Test
    fun `the working memory is left clean for the next run`() {
        val scratch = DepthContours.Scratch()
        val first = DepthContours.trace(cone(41), 41, 41, 5.0, 0.0, scratch)
        val again = DepthContours.trace(cone(41), 41, 41, 5.0, 0.0, scratch)

        assertEquals(first.map { it.level }, again.map { it.level })
        first.zip(again).forEach { (a, b) ->
            assertEquals(a.lines.size, b.lines.size)
            a.lines.zip(b.lines).forEach { (x, y) -> assertTrue(x.contentEquals(y)) }
        }
    }

    @Test
    fun `a line closing within a cell or two is a speck`() {
        // One cell a metre shallower than the flat seabed round it.
        val values = FloatArray(81) { -5.5f }.also { it[4 * 9 + 4] = -4.5f }
        val bump = DepthContours.trace(values, 9, 9, 1.0).single().lines.single()

        assertTrue(bump.isClosed())
        assertTrue(DepthContours.isSpeck(bump))
        assertFalse(DepthContours.isSpeck(doubleArrayOf(3.0, 3.0, 5.0, 3.5, 3.0, 3.0)))
    }

    // ---------------------------------------------------------------- the depths under the screen

    private val bounds = GeoBounds(west = 150.0, south = -21.5, east = 152.0, north = -20.0)

    /** A grid over [bounds] whose depth at each cell comes from [depth]. */
    private fun depthGrid(cols: Int, rows: Int, depth: (col: Int, row: Int) -> Float): DepthGrid {
        val values = FloatArray(cols * rows) { depth(it % cols, it / cols) }
        return DepthGrid(bounds, cols, rows, values, values.max().toDouble(), values.min().toDouble(), values.size)
    }

    @Test
    fun `a whole grid in view is thinned to about 700 samples a side`() {
        val whole = DepthContours.gridFromCells(depthGrid(2000, 1500) { col, row -> -(col + row).toFloat() }, bounds)!!

        // Every fourth cell: every second would be 1000 across.
        assertEquals(500, whole.cols)
        assertEquals(375, whole.rows)
        assertEquals(-4f, whole.values[1], 0f)
        assertEquals(-4f, whole.values[whole.cols], 0f)
        // A cell's depth belongs to its middle.
        val perCol = 2.0 / 2000
        assertEquals(150.0 + 0.5 * perCol, whole.placement.lng(0.0), 1e-9)
        assertEquals(150.0 + 4.5 * perCol, whole.placement.lng(1.0), 1e-9)
        assertEquals(-20.0 - 0.5 * 1.5 / 1500, whole.placement.lat(0.0), 1e-9)
        assertEquals(37.25, whole.placement.col(whole.placement.lng(37.25)), 1e-6)
        assertEquals(11.5, whole.placement.row(whole.placement.lat(11.5)), 1e-6)
    }

    @Test
    fun `zoomed in, every cell under the screen is used, with a margin, and small moves find the same window`() {
        val grid = depthGrid(2000, 1500) { col, row -> (col + 10_000 * row).toFloat() }
        val part = GeoBounds(west = 150.9, south = -20.8, east = 151.0, north = -20.7)
        val window = DepthContours.gridFromCells(grid, part)!!

        // The screen is columns 900 to 1000 and rows 700 to 800; a quarter of it more each side, out to whole blocks of 32.
        assertEquals((672 * 10_000 + 864).toFloat(), window.values[0], 0f)
        assertEquals(1056 - 864 + 1, window.cols)
        assertEquals(832 - 672 + 1, window.rows)
        assertEquals((672 * 10_000 + 865).toFloat(), window.values[1], 0f)

        val nudged = GeoBounds(west = 150.902, south = -20.798, east = 151.002, north = -20.698)
        assertEquals(window.key, DepthContours.gridFromCells(grid, nudged)!!.key)
        val elsewhere = GeoBounds(west = 151.4, south = -20.8, east = 151.5, north = -20.7)
        assertFalse(window.key == DepthContours.gridFromCells(grid, elsewhere)!!.key)
    }

    @Test
    fun `less than two cells of a grid under the screen is nothing to trace`() {
        val grid = depthGrid(4, 1) { col, _ -> -col.toFloat() }
        assertNull(DepthContours.gridFromCells(grid, bounds))
    }

    @Test
    fun `tiles sit side by side, a missing one left empty, each sample at the middle of its pixel`() {
        val tiles = DepthContours.Tiles(z = 10, x0 = 5, x1 = 6, y0 = 7, y1 = 7)
        val grid = DepthContours.gridFromTiles(tiles, 4) { x, _ -> if (x == 5) FloatArray(16) { 500f + it } else null }!!

        assertEquals(8, grid.cols)
        assertEquals(4, grid.rows)
        assertEquals(listOf(500f, 501f, 502f, 503f), grid.values.slice(0..3))
        assertEquals(listOf(504f, 505f, 506f, 507f), grid.values.slice(8..11))
        assertTrue(grid.values.slice(4..7).all { it.isNaN() })

        assertEquals(DepthContours.tileLng(5 + 0.5 / 4, 10), grid.placement.lng(0.0), 1e-12)
        assertEquals(DepthContours.tileLng(6 + 2.5 / 4, 10), grid.placement.lng(6.0), 1e-12)
        assertEquals(DepthContours.tileLat(7 + 3.5 / 4, 10), grid.placement.lat(3.0), 1e-12)
        assertEquals(2.25, grid.placement.col(grid.placement.lng(2.25)), 1e-6)
        assertEquals(1.75, grid.placement.row(grid.placement.lat(1.75)), 1e-6)
    }

    @Test
    fun `big tiles are thinned evenly across their edges`() {
        val tiles = DepthContours.Tiles(z = 12, x0 = 100, x1 = 101, y0 = 40, y1 = 41)
        val grid = DepthContours.gridFromTiles(tiles, 512) { x, y -> FloatArray(512 * 512) { (x - 100) * 1_000_000f + (y - 40) * 2_000_000f + it } }!!

        // 1024 pixels across: every second one.
        assertEquals(512, grid.cols)
        assertEquals(512, grid.rows)
        assertEquals(2f, grid.values[1], 0f)
        assertEquals(2f * 512, grid.values[grid.cols], 0f)
        // The 256th sample is the second tile's first pixel, in both directions.
        assertEquals(1_000_000f, grid.values[256], 0f)
        assertEquals(1_000_002f, grid.values[257], 0f)
        assertEquals(2_000_000f, grid.values[256 * grid.cols], 0f)
    }

    @Test
    fun `the tiles under the screen are the ones the layer is drawn with, unless that is too many`() {
        val phone = GeoBounds(west = 150.90, south = -20.76, east = 150.94, north = -20.70)
        val shown = DepthContours.tilesUnder(phone, 14)
        assertEquals(14, shown.z)
        assertTrue(shown.count in 1..40)
        // The tile holding the north-west corner of the screen, and the one holding the south-east.
        assertEquals(DepthContours.lngToTileX(150.90, 14).toInt(), shown.x0)
        assertEquals(DepthContours.latToTileY(-20.70, 14).toInt(), shown.y0)
        assertEquals(DepthContours.lngToTileX(150.94, 14).toInt(), shown.x1)
        assertEquals(DepthContours.latToTileY(-20.76, 14).toInt(), shown.y1)

        val wide = GeoBounds(west = 150.0, south = -21.0, east = 151.0, north = -20.0)
        val coarser = DepthContours.tilesUnder(wide, 14)
        assertTrue(coarser.z < 14)
        assertTrue(coarser.count <= 40)
    }

    @Test
    fun `the depths on screen are the water there, not land and not what is off screen`() {
        // Deep in the west, shallow with an island in the east.
        val grid = DepthContours.gridFromCells(depthGrid(200, 100) { col, _ -> if (col < 100) -50f else if (col < 150) -5f else 3f }, bounds)!!

        assertEquals(-50.0 to 0.0, DepthContours.depthsWithin(grid, bounds))
        assertEquals(-50.0 to -50.0, DepthContours.depthsWithin(grid, GeoBounds(150.1, -21.0, 150.5, -20.5)))
        assertEquals(-5.0 to -5.0, DepthContours.depthsWithin(grid, GeoBounds(151.1, -21.0, 151.4, -20.5)))
        // All land: nothing between the deepest and the surface, so no lines.
        val island = DepthContours.depthsWithin(grid, GeoBounds(151.7, -21.0, 151.9, -20.5))!!
        assertTrue(island.second <= island.first)

        val empty = DepthContours.gridFromCells(depthGrid(50, 50) { _, _ -> Float.NaN }, bounds)!!
        assertNull(DepthContours.depthsWithin(empty, bounds))
    }

    // ---------------------------------------------------------------- as map data

    private fun features(json: String): List<JsonObject> =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("features").map { it.asJsonObject }

    private fun JsonObject.label(): String = getAsJsonObject("properties").get("label").asString

    private fun JsonObject.coordinates(): List<Pair<Double, Double>> =
        getAsJsonObject("geometry").getAsJsonArray("coordinates").map { it.asJsonArray[0].asDouble to it.asJsonArray[1].asDouble }

    @Test
    fun `lines are drawn for depths only, never on land, each where its depth lies`() {
        // 20 m deep at the west edge, a metre shallower with every cell east, rising to 19 m of land.
        val grid = DepthContours.gridFromCells(depthGrid(40, 10) { col, _ -> col - 20f }, bounds)!!
        val lines = features(DepthContours.geoJson(grid, 10, DepthUnit.METRES))

        assertEquals(listOf("10 m", "0 m"), lines.map { it.label() })
        assertEquals(listOf(10, 0), lines.map { it.getAsJsonObject("properties").get("depth").asInt })
        assertEquals("LineString", lines[0].getAsJsonObject("geometry").get("type").asString)
        // The 10 m depth is at column 10, whose middle is ten and a half cells from the west edge.
        val tenMetres = lines[0].coordinates()
        assertEquals(10, tenMetres.size)
        tenMetres.forEach { (lng, lat) ->
            assertEquals(150.0 + 10.5 * 2.0 / 40, lng, 1e-6)
            assertTrue(lat < -20.0 && lat > -21.5)
        }
        assertEquals(-20.0 - 0.5 * 1.5 / 10, tenMetres.maxOf { it.second }, 1e-6)
    }

    @Test
    fun `every fifth line is marked as a heavier one`() {
        val grid = DepthContours.gridFromCells(depthGrid(140, 6) { col, _ -> -col.toFloat() - 0.5f }, bounds)!!
        val major = features(DepthContours.geoJson(grid, 10, DepthUnit.METRES))
            .associate { it.label() to it.getAsJsonObject("properties").get("major").asBoolean }

        assertEquals(setOf("50 m", "100 m"), major.filterValues { it }.keys)
        assertEquals(13, major.size)
    }

    @Test
    fun `with feet or fathoms chosen the lines are at round numbers of them, and labelled so`() {
        // 20.5 m (67 ft, 11 fathoms) deep at the west edge, a metre shallower with every cell east.
        val grid = DepthContours.gridFromCells(depthGrid(40, 10) { col, _ -> col - 20.5f }, bounds)!!

        val feet = features(DepthContours.geoJson(grid, 10, DepthUnit.FEET))
        assertEquals(listOf("60 ft", "50 ft", "40 ft", "30 ft", "20 ft", "10 ft", "0 ft"), feet.map { it.label() })
        assertEquals(
            listOf("65 ft", "60 ft", "55 ft", "50 ft", "45 ft", "40 ft", "35 ft", "30 ft", "25 ft", "20 ft", "15 ft", "10 ft", "5 ft", "0 ft"),
            features(DepthContours.geoJson(grid, 5, DepthUnit.FEET)).map { it.label() }
        )
        assertEquals(
            listOf("10 fm", "5 fm", "0 fm"),
            features(DepthContours.geoJson(grid, 5, DepthUnit.FATHOMS)).map { it.label() }
        )

        // Each line lies where the seabed is that many feet deep: 30 ft is 9.144 m, between columns 11 and 12.
        val thirty = feet.single { it.label() == "30 ft" }
        thirty.coordinates().forEach { (lng, _) -> assertEquals(150.0 + (20.5 - 9.144 + 0.5) * 2.0 / 40, lng, 1e-5) }
        // Its depth is kept in metres too.
        assertEquals(9.14, thirty.getAsJsonObject("properties").get("depth").asDouble, 0.005)
        assertEquals(0.0, feet.last().getAsJsonObject("properties").get("depth").asDouble, 0.0)
    }

    @Test
    fun `every fifth line in feet is a heavier one, counted in feet`() {
        val grid = DepthContours.gridFromCells(depthGrid(40, 10) { col, _ -> col - 20.5f }, bounds)!!
        val major = features(DepthContours.geoJson(grid, 5, DepthUnit.FEET))
            .associate { it.label() to it.getAsJsonObject("properties").get("major").asBoolean }

        assertEquals(setOf("50 ft", "25 ft", "0 ft"), major.filterValues { it }.keys)
    }

    @Test
    fun `a seabed that falls away past the screen widens a spacing in feet to a round number of feet`() {
        // 10 m deeper with every cell east, down to 3000 m, near 10 000 ft: a line every 5 ft would be 2000 of them.
        val grid = DepthContours.gridFromCells(depthGrid(300, 6) { col, _ -> -10f * col - 5f }, bounds)!!
        val labels = features(DepthContours.geoJson(grid, 5, DepthUnit.FEET)).map { it.label() }

        // Every 50 ft is the first with 240 or fewer.
        assertEquals("9800 ft", labels.first())
        assertEquals("50 ft", labels.last())
        assertEquals(196, labels.size)
    }

    @Test
    fun `a seabed that falls away past the screen widens the interval rather than drawing thousands of lines`() {
        // 10 m deeper with every cell east, down to 3000 m: a line every metre would be 3000 of them.
        val grid = DepthContours.gridFromCells(depthGrid(300, 6) { col, _ -> -10f * col - 5f }, bounds)!!
        val depths = features(DepthContours.geoJson(grid, 1, DepthUnit.METRES))
            .map { it.getAsJsonObject("properties").get("depth").asInt }

        assertEquals((2980 downTo 20 step 20).toList(), depths)
    }

    @Test
    fun `specks are left off the chart`() {
        // A flat seabed with one cell a metre shallower, and a real step down across the middle.
        val grid = DepthContours.gridFromCells(
            depthGrid(40, 40) { col, row -> if (col == 10 && row == 10) -4.5f else if (col < 20) -5.5f else -7.5f },
            bounds
        )!!
        val lines = features(DepthContours.geoJson(grid, 1, DepthUnit.METRES))

        // The step is the 6 m and 7 m lines; the bump's 5 m ring is not drawn.
        assertEquals(listOf("7 m", "6 m"), lines.map { it.label() })
    }

    @Test
    fun `a ring comes out as a ring on the chart`() {
        val size = 101
        val middle = 50.0
        val grid = DepthContours.gridFromCells(depthGrid(size, size) { col, row -> -hypot(col - middle, row - middle).toFloat() }, bounds)!!
        val ring = features(DepthContours.geoJson(grid, 10, DepthUnit.METRES)).single { it.label() == "10 m" }.coordinates()

        assertEquals(ring.first(), ring.last())
        // Ten cells' radius, in degrees each way, round the middle of the middle cell.
        val perCol = 2.0 / size
        val perRow = 1.5 / size
        val centreLng = 150.0 + 50.5 * perCol
        val centreLat = -20.0 - 50.5 * perRow
        ring.forEach { (lng, lat) ->
            assertEquals(10.0, hypot((lng - centreLng) / perCol, (lat - centreLat) / perRow), 0.06)
        }
    }

    @Test
    fun `nothing to draw is still map data`() {
        val flat = DepthContours.gridFromCells(depthGrid(20, 20) { _, _ -> -9f }, bounds)!!

        assertTrue(features(DepthContours.geoJson(flat, 1, DepthUnit.METRES)).isEmpty())
        assertTrue(features(DepthContours.EMPTY).isEmpty())
    }
}
