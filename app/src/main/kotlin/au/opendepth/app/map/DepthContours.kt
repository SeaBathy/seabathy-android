package au.opendepth.app.map

import au.opendepth.app.geotiff.DepthGrid
import au.opendepth.app.model.ContourSettings
import au.opendepth.app.model.GeoBounds
import au.opendepth.app.nav.DepthUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Depth contour lines on the chart, for depth layers: lines of equal depth
 * with the depth written along them, as the web app and the 3D view have.
 * They are worked out on the phone, from the depths under the part of the
 * chart on screen, each time the chart comes to rest.
 *
 * Everything here is sums on plain arrays (the map side is
 * [DepthContourLayers]), so it is unit-tested on the JVM.
 */
object DepthContours {

    /** Where a spacing in the depth unit is asked for: none picked, so one to suit the depths on screen. */
    const val AUTO = 0

    /**
     * The spacings a person can pick: round numbers of the depth unit chosen
     * in Boat & navigation, over about the same depths in each (a metre or
     * so to a hundred). Auto picks one to suit the depths on screen.
     */
    fun choices(unit: DepthUnit): List<Int> = when (unit) {
        DepthUnit.METRES -> listOf(1, 2, 5, 10, 20, 50, 100)
        DepthUnit.FEET -> listOf(2, 5, 10, 20, 50, 100, 200, 500)
        DepthUnit.FATHOMS -> listOf(1, 2, 5, 10, 20, 50)
    }

    /** A spacing as it is listed and shown: "5 ft". */
    fun spacingLabel(every: Int, unit: DepthUnit): String = "$every ${unit.label}"

    /** What is kept for a spacing of [every] in [unit]: its metres. See [ContourSettings.everyMetres]. */
    fun metresFor(every: Int, unit: DepthUnit): Double = every / unit.perMetre

    /**
     * The spacing in [unit] that a kept spacing of [everyMetres] stands for,
     * or [AUTO] for none. One picked in [unit] comes back as it was picked
     * (1.524 m is 5 ft). One picked in another unit, or saved when spacings
     * were whole metres, is not a round number of this one: it reads as the
     * closest of [choices] in proportion (5 m, 16.4 ft, is 20 ft), so the
     * lines stay about as far apart and are still labelled in round numbers.
     */
    fun pickedIn(everyMetres: Double, unit: DepthUnit): Int {
        if (!(everyMetres > 0.0) || !everyMetres.isFinite()) return AUTO
        val inUnit = everyMetres * unit.perMetre
        return choices(unit).minByOrNull { abs(ln(it / inUnit)) } ?: AUTO
    }

    /** Every spacing drawn, as numbers of the depth unit: Auto's, and the wider ones a picked spacing gives way to. */
    private val STEPS = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000)

    /** The widest spacing drawn in [unit]: wide enough for the deepest ocean. */
    private fun widest(unit: DepthUnit): Int = when (unit) {
        DepthUnit.METRES -> 2000
        DepthUnit.FEET -> 5000
        DepthUnit.FATHOMS -> 1000
    }

    /** Auto: about this many lines at most across the depths on screen. */
    private const val AUTO_LINES = 16

    /** Never more lines than this, whatever interval is picked: zoomed far out, a wider one is used. */
    private const val MAX_LINES = 60

    /**
     * Never more lines than this through the whole grid traced, which reaches
     * past the screen: where the seabed falls away out there, the interval
     * that suits the screen is widened rather than tracing thousands of lines.
     */
    private const val MAX_GRID_LINES = 240

    /** The most points drawn for one layer; a seabed rough enough to need more gets a wider interval. */
    private const val MAX_POINTS = 150_000

    /** The most levels traced through one grid, on screen or off it: beyond this the interval makes no sense for the depths. */
    private const val MAX_LEVELS = 4_000

    /** The most map tiles of depths read for one screen; beyond that a coarser zoom is used. */
    private const val MAX_TILES = 40

    /** The most samples along one side of the grid traced for one screen, give or take a block. */
    private const val MAX_SIDE = 700

    /**
     * A whole grid's window starts and ends on multiples of this many
     * samples, so small moves of the chart find the same window, and the
     * same lines, again.
     */
    private const val BLOCK = 32

    /** How far past the screen a whole grid's window reaches, as a share of the screen, so lines run off its edges. */
    private const val MARGIN = 0.25

    const val EMPTY: String = MapLayerIds.EMPTY_GEOJSON

    /**
     * The spacing to draw, as a number of [unit], for depths from [min] to
     * [max] metres: the one [picked] (a number of the unit too), or a round
     * number of the unit to suit that range; wider when it would make too
     * many lines.
     */
    fun stepFor(min: Double, max: Double, picked: Int = AUTO, unit: DepthUnit = DepthUnit.METRES): Int {
        val range = max(0.0, max - min) * unit.perMetre
        val widest = widest(unit)
        var step = if (picked > 0) picked else STEPS.firstOrNull { range / it <= AUTO_LINES }?.coerceAtMost(widest) ?: widest
        while (range / step > MAX_LINES) {
            step = widerThan(step, unit) ?: break
        }
        return step
    }

    private fun widerThan(step: Int, unit: DepthUnit): Int? = STEPS.firstOrNull { it > step && it <= widest(unit) }

    /**
     * A contour's depth as it is written on the chart: line number [count]
     * from the surface (negative below it) of a spacing of [every] in
     * [unit]. So exactly the round number the line stands for, never its
     * metres turned back into the unit.
     */
    fun labelFor(count: Long, every: Int, unit: DepthUnit): String {
        val value = count * every
        return "${if (value > 0) "+" else ""}${abs(value)} ${unit.label}"
    }

    // ---------------------------------------------------------------- tracing

    /** The lines of one depth: each a run of x, y pairs in cells; a closed line ends where it began. */
    class Level(val level: Double, val lines: List<DoubleArray>)

    /**
     * Working memory for [trace], kept between runs so a new screenful does
     * not allocate megabytes again. One run at a time may use it.
     */
    class Scratch {
        internal var size = 0
        internal var link = IntArray(0)
        internal var seen = ByteArray(0)

        /** Lets the memory go, when no layer has lines on. */
        fun release() {
            size = 0
            link = IntArray(0)
            seen = ByteArray(0)
        }
    }

    /**
     * The contour lines of a grid of depths ([cols] to a row, NaN where there
     * are none), one set for each multiple of [step] up to [top], by marching
     * squares, with the pieces joined into continuous lines.
     */
    fun trace(
        values: FloatArray,
        cols: Int,
        rows: Int,
        step: Double,
        top: Double = Double.POSITIVE_INFINITY,
        scratch: Scratch = Scratch()
    ): List<Level> {
        val cells = cols * rows
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (i in 0 until cells) {
            val v = values[i]
            if (v.isNaN()) continue
            if (v < min) min = v.toDouble()
            if (v > max) max = v.toDouble()
        }
        if (!(max > min) || !(step > 0.0)) return emptyList()
        // A level L is crossed where some depths are below it and some at or above: min < L <= max.
        val firstLevel = floor(min / step) + 1
        val lastLevel = min(floor(max / step), floor(top / step))
        if (lastLevel < firstLevel || lastLevel - firstLevel >= MAX_LEVELS) return emptyList()
        val first = firstLevel.toInt()
        val last = lastLevel.toInt()
        val segments = Array(last - first + 1) { IntList() }

        // An edge between two cells of a row is numbered by its left cell; one between two rows, by
        // its upper cell, after all of those.
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val i = r * cols + c
                val tl = values[i].toDouble()
                val tr = values[i + 1].toDouble()
                val bl = values[i + cols].toDouble()
                val br = values[i + cols + 1].toDouble()
                if (tl.isNaN() || tr.isNaN() || bl.isNaN() || br.isNaN()) continue
                val lo = min(min(tl, tr), min(bl, br))
                val hi = max(max(tl, tr), max(bl, br))
                val k0 = max(first, floor(lo / step).toInt() + 1)
                val k1 = min(last, floor(hi / step).toInt())
                if (k1 < k0) continue
                val edgeTop = i
                val edgeBottom = i + cols
                val edgeLeft = cells + i
                val edgeRight = cells + i + 1
                for (k in k0..k1) {
                    val level = k * step
                    val into = segments[k - first]
                    val kind = (if (tl >= level) 8 else 0) or (if (tr >= level) 4 else 0) or
                        (if (br >= level) 2 else 0) or (if (bl >= level) 1 else 0)
                    when (kind) {
                        1, 14 -> into.add(edgeLeft, edgeBottom)
                        2, 13 -> into.add(edgeBottom, edgeRight)
                        3, 12 -> into.add(edgeLeft, edgeRight)
                        4, 11 -> into.add(edgeTop, edgeRight)
                        6, 9 -> into.add(edgeTop, edgeBottom)
                        7, 8 -> into.add(edgeTop, edgeLeft)
                        // Two corners across from each other: the middle of the cell says which pair is joined.
                        5 -> if ((tl + tr + bl + br) / 4 >= level) {
                            into.add(edgeTop, edgeLeft)
                            into.add(edgeBottom, edgeRight)
                        } else {
                            into.add(edgeTop, edgeRight)
                            into.add(edgeLeft, edgeBottom)
                        }
                        10 -> if ((tl + tr + bl + br) / 4 >= level) {
                            into.add(edgeTop, edgeRight)
                            into.add(edgeLeft, edgeBottom)
                        } else {
                            into.add(edgeTop, edgeLeft)
                            into.add(edgeBottom, edgeRight)
                        }
                    }
                }
            }
        }

        if (scratch.size != cells) {
            scratch.size = cells
            scratch.link = IntArray(cells * 4) { -1 }
            scratch.seen = ByteArray(cells * 2)
        }
        val link = scratch.link
        val seen = scratch.seen
        val out = ArrayList<Level>()
        for (n in segments.indices) {
            val pairs = segments[n]
            if (pairs.size == 0) continue
            val level = (first + n) * step
            // Each piece joins two edges; an edge has at most two pieces meeting on it.
            for (s in 0 until pairs.size step 2) {
                val a = pairs[s]
                val b = pairs[s + 1]
                if (link[a * 2] == -1) link[a * 2] = b else link[a * 2 + 1] = b
                if (link[b * 2] == -1) link[b * 2] = a else link[b * 2 + 1] = a
            }
            val lines = ArrayList<DoubleArray>()
            fun walk(start: Int) {
                val line = DoubleList()
                var before = -1
                var at = start
                while (at != -1 && seen[at].toInt() == 0) {
                    seen[at] = 1
                    // Where the level crosses this edge, between the depths at its two ends.
                    if (at < cells) {
                        val row = at / cols
                        val a = values[at]
                        line.add(at - row * cols + (level - a) / (values[at + 1] - a), row.toDouble())
                    } else {
                        val i = at - cells
                        val row = i / cols
                        val a = values[i]
                        line.add((i - row * cols).toDouble(), row + (level - a) / (values[i + cols] - a))
                    }
                    val next = if (link[at * 2] != before) link[at * 2] else link[at * 2 + 1]
                    before = at
                    at = next
                }
                // Back at the start: a closed line.
                if (at == start && line.size > 4) line.add(line[0], line[1])
                if (line.size > 2) lines.add(line.toArray())
            }
            // Lines with ends first (they stop at the edge of the depths), then the closed ones.
            for (s in 0 until pairs.size) if (seen[pairs[s]].toInt() == 0 && link[pairs[s] * 2 + 1] == -1) walk(pairs[s])
            for (s in 0 until pairs.size) if (seen[pairs[s]].toInt() == 0) walk(pairs[s])
            for (s in 0 until pairs.size) {
                val edge = pairs[s]
                link[edge * 2] = -1
                link[edge * 2 + 1] = -1
                seen[edge] = 0
            }
            out.add(Level(level, lines))
        }
        return out
    }

    /** True for a line that stays within a cell and a half each way: a speck, not a shape of the seabed. */
    fun isSpeck(line: DoubleArray): Boolean {
        var x0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (i in line.indices step 2) {
            val x = line[i]
            val y = line[i + 1]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        return x1 - x0 < 1.5 && y1 - y0 < 1.5
    }

    // ---------------------------------------------------------------- the depths under the screen

    /** Where a [Grid]'s samples lie: column and row numbers to degrees, and back. */
    interface Placement {
        fun lng(x: Double): Double
        fun lat(y: Double): Double
        fun col(lng: Double): Double
        fun row(lat: Double): Double
    }

    /**
     * The depths lines are traced from: [cols] samples to a row, north row
     * first, NaN where there are none. [key] tells one grid from another, so
     * the same depths are not traced twice.
     */
    class Grid(val values: FloatArray, val cols: Int, val rows: Int, val key: String, val placement: Placement)

    /**
     * The part of a whole depth grid under [part] of the chart (and a margin
     * round it), every cell or every second, fourth... so that it stays
     * about [MAX_SIDE] samples a side at most. Null when that is less than
     * two cells each way.
     */
    fun gridFromCells(grid: DepthGrid, part: GeoBounds): Grid? {
        val bounds = grid.bounds
        val lngSpan = bounds.east - bounds.west
        val latSpan = bounds.north - bounds.south
        if (!(lngSpan > 0.0) || !(latSpan > 0.0)) return null
        val marginX = (part.east - part.west) * MARGIN
        val marginY = (part.north - part.south) * MARGIN
        // A cell's depth belongs to its middle: cell c is at (c + 0.5) cells from the west edge.
        fun column(lng: Double) = (lng - bounds.west) / lngSpan * grid.cols - 0.5
        fun line(lat: Double) = (bounds.north - lat) / latSpan * grid.rows - 0.5
        var c0 = floor(column(part.west - marginX)).toInt().coerceIn(0, grid.cols - 1)
        var c1 = ceil(column(part.east + marginX)).toInt().coerceIn(0, grid.cols - 1)
        var r0 = floor(line(part.north + marginY)).toInt().coerceIn(0, grid.rows - 1)
        var r1 = ceil(line(part.south - marginY)).toInt().coerceIn(0, grid.rows - 1)
        if (c1 <= c0 || r1 <= r0) return null

        var stride = 1
        while (max(c1 - c0, r1 - r0) / stride + 1 > MAX_SIDE - 2 * BLOCK) stride *= 2
        val block = stride * BLOCK
        c0 = c0 / block * block
        r0 = r0 / block * block
        c1 = min(grid.cols - 1, (c1 + block - 1) / block * block)
        r1 = min(grid.rows - 1, (r1 + block - 1) / block * block)
        val cols = (c1 - c0) / stride + 1
        val rows = (r1 - r0) / stride + 1
        if (cols < 2 || rows < 2) return null

        val values = FloatArray(cols * rows)
        for (y in 0 until rows) {
            val from = (r0 + y * stride) * grid.cols + c0
            for (x in 0 until cols) values[y * cols + x] = grid.values[from + x * stride]
        }
        val west = bounds.west
        val north = bounds.north
        val perCol = lngSpan / grid.cols
        val perRow = latSpan / grid.rows
        val placement = object : Placement {
            override fun lng(x: Double) = west + (c0 + x * stride + 0.5) * perCol
            override fun lat(y: Double) = north - (r0 + y * stride + 0.5) * perRow
            override fun col(lng: Double) = ((lng - west) / perCol - 0.5 - c0) / stride
            override fun row(lat: Double) = ((north - lat) / perRow - 0.5 - r0) / stride
        }
        return Grid(values, cols, rows, "cells/$c0/$r0/$cols/$rows/$stride", placement)
    }

    /** The map tiles covering part of the chart at zoom [z]: inclusive ranges, y counted from the north. */
    class Tiles(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val count: Int get() = (x1 - x0 + 1) * (y1 - y0 + 1)
    }

    /**
     * The tiles of depths under [part] of the chart: at [zoom], the zoom the
     * layer's own tiles are drawn at, or a coarser one when that would be
     * more than [MAX_TILES] of them.
     */
    fun tilesUnder(part: GeoBounds, zoom: Int): Tiles {
        var z = zoom.coerceAtLeast(0)
        var tiles = tilesAt(part, z)
        while (z > 0 && tiles.count > MAX_TILES) {
            z -= 1
            tiles = tilesAt(part, z)
        }
        return tiles
    }

    private fun tilesAt(part: GeoBounds, z: Int): Tiles {
        val last = (1 shl z) - 1
        return Tiles(
            z,
            x0 = floor(lngToTileX(part.west, z)).toInt().coerceIn(0, last),
            x1 = floor(lngToTileX(part.east, z) - 1e-9).toInt().coerceIn(0, last),
            y0 = floor(latToTileY(part.north, z)).toInt().coerceIn(0, last),
            y1 = floor(latToTileY(part.south, z) - 1e-9).toInt().coerceIn(0, last)
        )
    }

    /**
     * The depths of [tiles] as one grid, the tiles side by side. Each tile is
     * [size] samples a side as [tile] gives it (null for a tile with no
     * depths); every sample is used, or every second, third... so the grid
     * stays [MAX_SIDE] a side at most. Samples are evenly spaced across the
     * tiles' edges, so the lines meet where the tiles do.
     */
    fun gridFromTiles(tiles: Tiles, size: Int, tile: (x: Int, y: Int) -> FloatArray?): Grid? {
        if (size < 2) return null
        val across = (tiles.x1 - tiles.x0 + 1) * size
        val down = (tiles.y1 - tiles.y0 + 1) * size
        val stride = max(1, ceil(max(across, down) / MAX_SIDE.toDouble()).toInt())
        val cols = (across - 1) / stride + 1
        val rows = (down - 1) / stride + 1
        if (cols < 2 || rows < 2) return null
        val values = FloatArray(cols * rows) { Float.NaN }
        for (ty in tiles.y0..tiles.y1) {
            for (tx in tiles.x0..tiles.x1) {
                val depths = tile(tx, ty) ?: continue
                if (depths.size != size * size) return null
                // This tile's first pixel, counted across all the tiles, and the grid samples that fall in it.
                val left = (tx - tiles.x0) * size
                val topEdge = (ty - tiles.y0) * size
                var y = (topEdge + stride - 1) / stride
                while (y < rows && y * stride < topEdge + size) {
                    val from = (y * stride - topEdge) * size - left
                    var x = (left + stride - 1) / stride
                    while (x < cols && x * stride < left + size) {
                        values[y * cols + x] = depths[from + x * stride]
                        x++
                    }
                    y++
                }
            }
        }
        val z = tiles.z
        val x0 = tiles.x0
        val y0 = tiles.y0
        // A tile's sample is the depth at the middle of its pixel.
        val placement = object : Placement {
            override fun lng(x: Double) = tileLng(x0 + (x * stride + 0.5) / size, z)
            override fun lat(y: Double) = tileLat(y0 + (y * stride + 0.5) / size, z)
            override fun col(lng: Double) = ((lngToTileX(lng, z) - x0) * size - 0.5) / stride
            override fun row(lat: Double) = ((latToTileY(lat, z) - y0) * size - 0.5) / stride
        }
        return Grid(values, cols, rows, tilesKey(tiles, size), placement)
    }

    /** The [Grid.key] of the grid [gridFromTiles] makes of [tiles]: known before any tile is read. */
    fun tilesKey(tiles: Tiles, size: Int): String = "tiles/${tiles.z}/${tiles.x0}-${tiles.x1}/${tiles.y0}-${tiles.y1}/$size"

    /**
     * The deepest and shallowest water in the part of [grid] under [part] of
     * the chart, in metres, or null with no depths there. Depths only: land
     * in a survey gets no lines, and does not widen the interval.
     */
    fun depthsWithin(grid: Grid, part: GeoBounds): Pair<Double, Double>? {
        val c0 = floor(grid.placement.col(part.west)).toInt().coerceIn(0, grid.cols - 1)
        val c1 = ceil(grid.placement.col(part.east)).toInt().coerceIn(0, grid.cols - 1)
        val r0 = floor(grid.placement.row(part.north)).toInt().coerceIn(0, grid.rows - 1)
        val r1 = ceil(grid.placement.row(part.south)).toInt().coerceIn(0, grid.rows - 1)
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (r in r0..r1) {
            for (c in c0..c1) {
                val v = grid.values[r * grid.cols + c]
                if (v.isNaN()) continue
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        if (min > max) return null
        return min.toDouble() to min(max.toDouble(), 0.0)
    }

    // ---------------------------------------------------------------- as map data

    /**
     * The contour lines of [grid], one every [step] of [unit] (5 with feet
     * is a line every 5 ft; wider if that would be more points than a phone
     * should draw), as GeoJSON lines with the depth to write on them, and
     * their depth in metres. Depths only, so none above the surface; specks
     * are left out.
     */
    fun geoJson(grid: Grid, step: Int, unit: DepthUnit, scratch: Scratch = Scratch()): String {
        val perMetre = unit.perMetre
        var every = step.coerceAtLeast(1)
        var deepest = 0f
        for (v in grid.values) if (v < deepest) deepest = v
        while (-deepest * perMetre / every > MAX_GRID_LINES) {
            every = widerThan(every, unit) ?: break
        }
        var levels = trace(grid.values, grid.cols, grid.rows, every / perMetre, 0.0, scratch)
        while (levels.sumOf { level -> level.lines.sumOf { it.size / 2 } } > MAX_POINTS) {
            every = widerThan(every, unit) ?: break
            levels = trace(grid.values, grid.cols, grid.rows, every / perMetre, 0.0, scratch)
        }
        val json = StringBuilder(1 shl 16)
        json.append("""{"type":"FeatureCollection","features":[""")
        var any = false
        for (level in levels) {
            // Which line this is, counted from the surface: its depth is that many spacings.
            val count = Math.round(level.level * perMetre / every)
            // Every fifth line is heavier, as in the 3D view.
            val major = count % 5 == 0L
            val properties = """{"label":"${labelFor(count, every, unit)}","major":$major,"depth":${metresText(-level.level)}}"""
            for (line in level.lines) {
                if (line.size < 6 || isSpeck(line)) continue
                if (any) json.append(',')
                any = true
                json.append("""{"type":"Feature","properties":""").append(properties)
                    .append(""","geometry":{"type":"LineString","coordinates":[""")
                for (i in line.indices step 2) {
                    if (i > 0) json.append(',')
                    json.append('[')
                    appendDegrees(json, grid.placement.lng(line[i]))
                    json.append(',')
                    appendDegrees(json, grid.placement.lat(line[i + 1]))
                    json.append(']')
                }
                json.append("]}}")
            }
        }
        json.append("]}")
        return json.toString()
    }

    /** A depth in metres as a JSON number: whole where it is, else to the centimetre. */
    private fun metresText(metres: Double): String {
        val whole = Math.round(metres)
        return if (abs(metres - whole) < 1e-6) whole.toString() else String.format(Locale.US, "%.2f", metres)
    }

    /** Degrees to seven places (about a centimetre), without the cost of formatting each one. */
    private fun appendDegrees(json: StringBuilder, degrees: Double) {
        var scaled = Math.round(degrees * 1e7)
        if (scaled < 0) {
            json.append('-')
            scaled = -scaled
        }
        json.append(scaled / 10_000_000).append('.')
        val fraction = scaled % 10_000_000
        var divisor = 1_000_000L
        while (divisor > 0) {
            json.append('0' + ((fraction / divisor) % 10).toInt())
            divisor /= 10
        }
    }

    // ---------------------------------------------------------------- Web Mercator tile sums

    private const val MAX_LAT = 85.0511287798066

    internal fun tileLng(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    internal fun tileLat(y: Double, z: Int): Double =
        Math.toDegrees(atan(sinh(PI - 2 * PI * y / (1 shl z))))

    internal fun lngToTileX(lng: Double, z: Int): Double = (lng + 180.0) / 360.0 * (1 shl z)

    internal fun latToTileY(lat: Double, z: Int): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * (1 shl z)
    }

    // ---------------------------------------------------------------- growing arrays

    /** A list of ints without a box for each: a level's pieces, two edge numbers apiece. */
    private class IntList {
        private var items = IntArray(64)
        var size = 0
            private set

        fun add(a: Int, b: Int) {
            if (size + 2 > items.size) items = items.copyOf(items.size * 2)
            items[size++] = a
            items[size++] = b
        }

        operator fun get(index: Int): Int = items[index]
    }

    /** Likewise for a line's points, x then y. */
    private class DoubleList {
        private var items = DoubleArray(32)
        var size = 0
            private set

        fun add(x: Double, y: Double) {
            if (size + 2 > items.size) items = items.copyOf(items.size * 2)
            items[size++] = x
            items[size++] = y
        }

        operator fun get(index: Int): Double = items[index]

        fun toArray(): DoubleArray = items.copyOf(size)
    }
}
