package au.opendepth.app.conditions

import kotlin.math.ceil

/**
 * A piece of water in a map tile: its outline (rings as x0, y0, x1, y1… in tile units, the first
 * the outside and the rest holes) and whether the map calls it ocean.
 */
class Water(val ocean: Boolean, val rings: List<ShortArray>)

/**
 * Which water of the map tiles is the sea. The map draws the open sea as "ocean" and ends it at
 * a straight line across each harbour, bay and river mouth; what lies inside is drawn as water
 * of its own. Kept to the ocean alone, the sea conditions stopped at that line: nothing in
 * Sydney Harbour, Botany Bay or any estuary. Here water that touches the ocean, or touches water
 * that does, is sea too; a lake or dam that doesn't stays out.
 *
 * Each tile's water is laid out on a grid ([SIDE] cells a side, about 40 m) to see what touches
 * what. Water that runs on into the next tile is followed one tile further, from that tile's
 * edge ([seeds]). Pure, unit-tested.
 */
object SeaWaters {
    const val SIDE = 1024
    private const val EXTENT = 4096f
    private const val OCEAN = 255
    /** Pieces told apart in one tile (the biggest); smaller ones beyond that are never sea. */
    private const val MOST = 254

    /** A tile's water on the grid: each piece's number, which numbers touch (pairs), and the number lying along each edge (north, east, south, west). */
    class Layout(val pieces: List<Water>, val numbers: IntArray, val touching: IntArray, val edges: Array<ByteArray>)

    fun layout(pieces: List<Water>): Layout {
        val numbers = IntArray(pieces.size)
        val others = pieces.indices.filter { !pieces[it].ocean }.sortedByDescending { boxArea(pieces[it]) }
        others.forEachIndexed { rank, i -> numbers[i] = if (rank < MOST) rank + 1 else 0 }
        pieces.indices.forEach { if (pieces[it].ocean) numbers[it] = OCEAN }
        val cells = ByteArray(SIDE * SIDE)
        // The ocean last: where outlines overlap, it stands.
        for (i in others) if (numbers[i] != 0) fill(cells, pieces[i].rings, numbers[i].toByte())
        for (i in pieces.indices) if (pieces[i].ocean) fill(cells, pieces[i].rings, OCEAN.toByte())
        val touch = BooleanArray(256 * 256)
        if (others.isNotEmpty()) {
            for (y in 0 until SIDE) {
                val row = y * SIDE
                for (x in 0 until SIDE) {
                    val a = cells[row + x].toInt() and 0xFF
                    if (a == 0) continue
                    // To the right and below, across a gap of one cell too: outlines a hair apart still touch.
                    if (x + 1 < SIDE) {
                        var b = cells[row + x + 1].toInt() and 0xFF
                        if (b == 0 && x + 2 < SIDE) b = cells[row + x + 2].toInt() and 0xFF
                        if (b != 0 && b != a) touch[a * 256 + b] = true
                    }
                    if (y + 1 < SIDE) {
                        var b = cells[row + SIDE + x].toInt() and 0xFF
                        if (b == 0 && y + 2 < SIDE) b = cells[row + 2 * SIDE + x].toInt() and 0xFF
                        if (b != 0 && b != a) touch[a * 256 + b] = true
                    }
                }
            }
        }
        val pairs = ArrayList<Int>()
        for (a in 1..255) for (b in 1..255) if (touch[a * 256 + b] || touch[b * 256 + a]) {
            pairs += a
            pairs += b
        }
        val edges = arrayOf(
            ByteArray(SIDE) { cells[it] },
            ByteArray(SIDE) { cells[it * SIDE + SIDE - 1] },
            ByteArray(SIDE) { cells[(SIDE - 1) * SIDE + it] },
            ByteArray(SIDE) { cells[it * SIDE] }
        )
        return Layout(pieces, numbers, pairs.toIntArray(), edges)
    }

    /** Which numbers are sea: the ocean, [seeds] (water running in from next door), and whatever touches them, piece to piece. */
    fun joined(layout: Layout, seeds: BooleanArray? = null): BooleanArray {
        val sea = BooleanArray(256)
        val queue = ArrayDeque<Int>()
        fun add(number: Int) {
            if (number != 0 && !sea[number]) {
                sea[number] = true
                queue.addLast(number)
            }
        }
        if (layout.numbers.any { it == OCEAN }) add(OCEAN)
        seeds?.forEachIndexed { number, on -> if (on) add(number) }
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            var k = 0
            while (k + 1 < layout.touching.size) {
                if (layout.touching[k] == a) add(layout.touching[k + 1])
                k += 2
            }
        }
        return sea
    }

    /** Where the sea lies along each edge (north, east, south, west). */
    fun edgeSea(layout: Layout, sea: BooleanArray): Array<BooleanArray> =
        Array(4) { side -> BooleanArray(SIDE) { i -> sea[layout.edges[side][i].toInt() and 0xFF] } }

    /**
     * The pieces that carry on sea from the tiles next door: [facing] is, for each of this tile's
     * edges (north, east, south, west), the sea along the neighbour's edge that meets it.
     */
    fun seeds(layout: Layout, facing: Array<BooleanArray?>): BooleanArray {
        val seeds = BooleanArray(256)
        for (side in 0 until 4) {
            val theirs = facing[side] ?: continue
            val mine = layout.edges[side]
            for (i in 0 until SIDE) {
                val number = mine[i].toInt() and 0xFF
                if (number == 0 || seeds[number]) continue
                if (theirs[i] || (i > 0 && theirs[i - 1]) || (i + 1 < SIDE && theirs[i + 1])) seeds[number] = true
            }
        }
        return seeds
    }

    /** The outlines of all the tile's sea. */
    fun rings(layout: Layout, sea: BooleanArray): List<ShortArray> =
        layout.pieces.indices.filter { layout.pieces[it].ocean || sea[layout.numbers[it]] }.flatMap { layout.pieces[it].rings }

    private fun boxArea(w: Water): Long {
        val outer = w.rings.firstOrNull() ?: return 0L
        var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE
        var i = 0
        while (i + 1 < outer.size) {
            x0 = minOf(x0, outer[i].toInt()); x1 = maxOf(x1, outer[i].toInt())
            y0 = minOf(y0, outer[i + 1].toInt()); y1 = maxOf(y1, outer[i + 1].toInt())
            i += 2
        }
        return (x1 - x0).toLong() * (y1 - y0)
    }

    /** Each place an outline of [rings] crosses the middle of a grid row. */
    private inline fun crossings(rings: List<ShortArray>, visit: (row: Int, x: Float) -> Unit) {
        val scale = SIDE / EXTENT
        for (r in rings) {
            val n = r.size / 2
            if (n < 3) continue
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val xa = r[i * 2] * scale
                val ya = r[i * 2 + 1] * scale
                val xb = r[j * 2] * scale
                val yb = r[j * 2 + 1] * scale
                if (ya == yb) continue
                val top = minOf(ya, yb)
                val bottom = maxOf(ya, yb)
                // The rows whose middle lies from top (in) to bottom (out).
                var row = ceil(top - 0.5f).toInt().coerceAtLeast(0)
                val last = (ceil(bottom - 0.5f).toInt() - 1).coerceAtMost(SIDE - 1)
                while (row <= last) {
                    visit(row, xa + (row + 0.5f - ya) * (xb - xa) / (yb - ya))
                    row++
                }
            }
        }
    }

    /** Marks the cells inside a piece (its outside ring less its holes) with [number]. */
    private fun fill(cells: ByteArray, rings: List<ShortArray>, number: Byte) {
        val starts = IntArray(SIDE + 1)
        crossings(rings) { row, _ -> starts[row + 1]++ }
        for (y in 0 until SIDE) starts[y + 1] += starts[y]
        val xs = FloatArray(starts[SIDE])
        val at = starts.copyOf(SIDE)
        crossings(rings) { row, x -> xs[at[row]++] = x }
        for (y in 0 until SIDE) {
            val from = starts[y]
            val to = starts[y + 1]
            if (to - from < 2) continue
            java.util.Arrays.sort(xs, from, to)
            var k = from
            while (k + 1 < to) {
                val first = ceil(xs[k] - 0.5f).toInt().coerceAtLeast(0)
                val last = (ceil(xs[k + 1] - 0.5f).toInt() - 1).coerceAtMost(SIDE - 1)
                if (last >= first) java.util.Arrays.fill(cells, y * SIDE + first, y * SIDE + last + 1, number)
                k += 2
            }
        }
    }
}
