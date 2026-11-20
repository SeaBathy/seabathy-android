package au.opendepth.app.storage

import au.opendepth.app.storage.StorageUsage.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageUsageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dataDir: File
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var sharedDir: File

    @Before
    fun setUp() {
        dataDir = temp.newFolder("data")
        filesDir = File(dataDir, "files").apply { mkdirs() }
        cacheDir = File(dataDir, "cache").apply { mkdirs() }
        sharedDir = temp.newFolder("shared")
    }

    /** A file of [bytes] bytes, last written [ageMillis] ago. */
    private fun file(dir: File, name: String, bytes: Int, ageMillis: Long = 0L): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(System.currentTimeMillis() - ageMillis)
        }
    }

    private fun places(offlineMapsDir: File = File(sharedDir, "OfflineDownloads"), otherRoots: List<File> = listOf(dataDir)) =
        StorageUsage.Places(filesDir, cacheDir, offlineMapsDir, mapCacheDir = filesDir, otherRoots = otherRoots)

    // Grouping --------------------------------------------------------------

    @Test
    fun `each folder is counted in its own row`() {
        file(File(filesDir, "mbtiles"), "chart.mbtiles", 100)
        file(File(filesDir, "surveys"), "survey.tif", 200)
        file(File(filesDir, "seabed"), "Seabed 27S 153E.tif", 50)
        file(File(filesDir, "geotiff_tile_cache"), "survey.gdaltiles", 70)
        file(File(filesDir, "conditions"), "area.bin", 30)
        file(File(filesDir, "conditions/coast"), "1_2.sea", 5)
        file(File(filesDir, "tides"), "follow.json", 20)
        file(File(filesDir, "photos"), "a.jpg", 40)
        file(File(filesDir, "tracks"), "t.csv", 10)
        file(File(sharedDir, "OfflineDownloads"), "map.mbtiles", 500)

        val report = StorageUsage.measure(places())

        assertEquals(350L, report[Group.CHARTS])
        assertEquals(70L, report[Group.SURVEY_TILES])
        assertEquals(500L, report[Group.OFFLINE_MAPS])
        assertEquals(55L, report[Group.CONDITIONS])
        assertEquals(40L, report[Group.PHOTOS])
        assertEquals(10L, report[Group.TRACKS])
        assertEquals(0L, report[Group.TEMPORARY])
        assertEquals(0L, report[Group.OTHER])
        assertEquals(1025L, report.totalBytes)
    }

    @Test
    fun `the cache folder and the map's own tile cache are temporary files`() {
        file(cacheDir, "geotiff-import-1.tif", 13)
        file(File(cacheDir, "terrain-tiles"), "abc.0", 11)
        file(filesDir, "mbgl-offline.db", 60)
        file(filesDir, "mbgl-offline.db-journal", 3)

        val report = StorageUsage.measure(places())

        assertEquals(87L, report[Group.TEMPORARY])
        assertEquals(0L, report[Group.OTHER])
    }

    @Test
    fun `everything else the app keeps is counted once, as other files`() {
        file(File(filesDir, "logs"), "seabathy-debug.log", 7)
        file(File(filesDir, "proj"), "proj.db", 9)
        file(filesDir, "bathypicker_surveys.json", 4)
        file(File(dataDir, "shared_prefs"), "od.xml", 17)
        file(File(filesDir, "photos"), "a.jpg", 40)
        file(cacheDir, "left.tmp", 13)

        val report = StorageUsage.measure(places())

        // The files folder is inside the private folder: neither it, nor the photos and the
        // cache inside them, may be counted a second time.
        assertEquals(37L, report[Group.OTHER])
        assertEquals(40L, report[Group.PHOTOS])
        assertEquals(13L, report[Group.TEMPORARY])
        assertEquals(90L, report.totalBytes)
    }

    @Test
    fun `offline maps kept inside the app's shared folder are not counted twice`() {
        // The Play build before Android 11: Android/data/<app>/files/OfflineDownloads.
        val external = File(sharedDir, "Android/data/app")
        val offline = File(external, "files/OfflineDownloads")
        file(offline, "map.mbtiles", 500)
        file(File(external, "files"), "stray.txt", 6)

        val report = StorageUsage.measure(places(offlineMapsDir = offline, otherRoots = listOf(dataDir, external)))

        assertEquals(500L, report[Group.OFFLINE_MAPS])
        assertEquals(6L, report[Group.OTHER])
    }

    @Test
    fun `missing folders count as nothing`() {
        val report = StorageUsage.measure(places(offlineMapsDir = File(sharedDir, "not-there")))

        assertEquals(0L, report.totalBytes)
        Group.entries.forEach { assertEquals(0L, report[it]) }
    }

    @Test
    fun `a link is not followed`() {
        val outside = temp.newFolder("installed-app")
        file(outside, "libgdal.so", 900)
        try {
            Files.createSymbolicLink(File(dataDir, "lib").toPath(), outside.toPath())
        } catch (e: Exception) {
            // Not every system lets a test make links.
            org.junit.Assume.assumeNoException(e)
        }

        assertEquals(0L, StorageUsage.measure(places()).totalBytes)
    }

    @Test
    fun `free space is the room left where the app's files are`() {
        // Not compared exactly: anything else writing to the disk moves it between two looks.
        assertTrue(StorageUsage.measure(places()).freeBytes > 0L)
    }

    // Clearing --------------------------------------------------------------

    @Test
    fun `clearing deletes only working files from before this run`() {
        val hour = 3_600_000L
        val old = file(cacheDir, "geotiff-import-1.tif", 13, ageMillis = 2 * hour)
        val oldNested = file(File(cacheDir, "work"), "bp-1-c.tmp", 8, ageMillis = 2 * hour)
        val inUse = file(cacheDir, "geotiff-import-2.tif", 30)
        val started = System.currentTimeMillis() - hour

        val freed = StorageUsage.clearOldCacheFiles(places(), started, leave = emptySet())

        assertEquals(21L, freed)
        assertFalse(old.exists())
        assertFalse(oldNested.exists())
        assertTrue("a file written during this run may be in use", inUse.exists())
        assertTrue("folders stay", File(cacheDir, "work").isDirectory)
    }

    @Test
    fun `clearing leaves the caches that have their own owner`() {
        val hour = 3_600_000L
        val tile = file(File(cacheDir, "terrain-tiles"), "abc.0", 11, ageMillis = 2 * hour)
        val web = file(File(cacheDir, "WebView/Default"), "index", 5, ageMillis = 2 * hour)
        val old = file(cacheDir, "left.tmp", 3, ageMillis = 2 * hour)

        val freed = StorageUsage.clearOldCacheFiles(
            places(), System.currentTimeMillis() - hour, leave = setOf("terrain-tiles", "WebView")
        )

        assertEquals(3L, freed)
        assertTrue(tile.exists())
        assertTrue(web.exists())
        assertFalse(old.exists())
    }

    @Test
    fun `clearing touches nothing outside the cache folder`() {
        val hour = 3_600_000L
        val photo = file(File(filesDir, "photos"), "a.jpg", 40, ageMillis = 5 * hour)
        val chart = file(File(filesDir, "mbtiles"), "chart.mbtiles", 100, ageMillis = 5 * hour)
        val tide = file(File(filesDir, "tides"), "follow.json", 20, ageMillis = 5 * hour)
        val offline = file(File(sharedDir, "OfflineDownloads"), "map.mbtiles", 500, ageMillis = 5 * hour)
        val mapCache = file(filesDir, "mbgl-offline.db", 60, ageMillis = 5 * hour)

        val freed = StorageUsage.clearOldCacheFiles(places(), System.currentTimeMillis() - hour, leave = emptySet())

        assertEquals(0L, freed)
        listOf(photo, chart, tide, offline, mapCache).forEach { assertTrue(it.name, it.exists()) }
    }
}
