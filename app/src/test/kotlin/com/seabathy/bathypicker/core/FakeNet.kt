package com.seabathy.bathypicker.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/** The internet for tests: answers by the start of the address, and remembers what was asked. */
class FakeNet(
    private val texts: Map<String, String> = emptyMap(),
    private val files: Map<String, (String, File) -> Unit> = emptyMap()
) : World.Net {
    val asked = mutableListOf<String>()

    override fun text(url: String, cancel: Http.Cancel?): String {
        synchronized(asked) { asked += url }
        return texts.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw IOException("HTTP 404 from fake")
    }

    override fun save(url: String, file: File, cancel: Http.Cancel?, counter: AtomicLong?) {
        synchronized(asked) { asked += url }
        val make = files.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw IOException("HTTP 503 from fake")
        make(url, file)
        counter?.addAndGet(file.length())
    }

    companion object {
        /**
         * A one-tile float GeoTIFF in EPSG:4326 (up to 256 x 256), as the engine writes them:
         * value(x, y) for each pixel, null for NoData.
         */
        fun tiff(file: File, w: Int, h: Int, west: Double, north: Double, resX: Double, resY: Double, nodata: Float,
                 value: (Int, Int) -> Float?) {
            require(w <= TiffWriter.TILE && h <= TiffWriter.TILE)
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                val tw = TiffWriter(raf.channel, raf.channel, w, h, doubleArrayOf(west, resX, 0.0, north, 0.0, -resY), 4326, nodata)
                val t = TiffWriter.TILE
                val tile = FloatArray(t * t) { i ->
                    val x = i % t
                    val y = i / t
                    if (x < w && y < h) value(x, y) ?: nodata else nodata
                }
                tw.writeTile(0, 0, 0, tile)
                tw.finish()
            }
        }

        /** The numbers after name= in a query, split at commas. */
        fun param(url: String, name: String): List<Double> {
            val m = Regex("[?&]" + Regex.escape(name) + "=([^&]+)").find(url) ?: error("no $name in $url")
            return m.groupValues[1].split(',').map { it.toDouble() }
        }
    }
}
