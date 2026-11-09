package au.opendepth.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AppOwnedArtifactsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var mbtilesDir: File
    private lateinit var cacheDir: File
    private lateinit var userDir: File
    private lateinit var artifacts: AppOwnedArtifacts

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        mbtilesDir = File(filesDir, "mbtiles").apply { mkdirs() }
        cacheDir = File(filesDir, "geotiff_tile_cache").apply { mkdirs() }
        userDir = temp.newFolder("storage", "emulated", "0", "SeaBathy", "OfflineDownloads")
        artifacts = AppOwnedArtifacts(filesDir, listOf("mbtiles", "geotiff_tile_cache"))
    }

    private fun file(dir: File, name: String, ageMillis: Long = 0L): File =
        File(dir, name).apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - ageMillis)
        }

    // Ownership boundary ----------------------------------------------------

    @Test
    fun `files directly in managed dirs are app-owned`() {
        assertTrue(artifacts.isAppOwned(file(mbtilesDir, "chart.mbtiles").path))
        assertTrue(artifacts.isAppOwned(file(cacheDir, "abc.gdaltiles").path))
    }

    @Test
    fun `user files are never app-owned`() {
        assertFalse(artifacts.isAppOwned(file(userDir, "reef.mbtiles").path))
        assertFalse(artifacts.isAppOwned("content://com.android.externalstorage.documents/document/primary%3Areef.tif"))
    }

    @Test
    fun `other filesDir locations are not app-owned`() {
        assertFalse(artifacts.isAppOwned(file(filesDir, "settings.mbtiles").path))
        val nested = File(mbtilesDir, "nested").apply { mkdirs() }
        assertFalse(artifacts.isAppOwned(file(nested, "deep.mbtiles").path))
        assertFalse(artifacts.isAppOwned(mbtilesDir.path))
    }

    @Test
    fun `dot-dot paths cannot escape the managed dir`() {
        val outside = file(userDir, "reef.mbtiles")
        val sneaky = File(mbtilesDir, "../../storage/emulated/0/SeaBathy/OfflineDownloads/reef.mbtiles").path
        assertFalse(artifacts.isAppOwned(sneaky))
        assertTrue(artifacts.delete(sneaky).isEmpty())
        assertTrue(outside.exists())
    }

    @Test
    fun `a symlink inside a managed dir does not make its target deletable`() {
        val target = file(userDir, "reef.mbtiles")
        val link = File(mbtilesDir, "reef.mbtiles")
        // Windows refuses to create symlinks without Developer Mode; skip rather than fail there.
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: java.nio.file.FileSystemException) {
            org.junit.Assume.assumeNoException(e)
        }
        assertFalse(artifacts.isAppOwned(link.path))
        assertTrue(artifacts.delete(link.path).isEmpty())
        assertTrue(target.exists())
    }

    // Delete ----------------------------------------------------------------

    @Test
    fun `delete removes a gdal cache with its dem cache and sidecars only`() {
        val family = listOf(
            "a.gdaltiles", "a.gdaltiles-journal", "a.gdaltiles.dem", "a.gdaltiles.dem-wal", "a.gdaltiles.dem-shm"
        ).map { file(cacheDir, it) }
        val other = file(cacheDir, "b.gdaltiles")

        val deleted = artifacts.delete(File(cacheDir, "a.gdaltiles").path)

        assertEquals(family.map { it.name }.toSet(), deleted.map { it.name }.toSet())
        family.forEach { assertFalse(it.exists()) }
        assertTrue(other.exists())
    }

    @Test
    fun `delete does not touch similarly named files`() {
        val copy = file(mbtilesDir, "chart.mbtiles")
        val sibling = file(mbtilesDir, "chart-2.mbtiles")
        artifacts.delete(copy.path)
        assertFalse(copy.exists())
        assertTrue(sibling.exists())
    }

    @Test
    fun `delete refuses a real user path`() {
        val user = file(userDir, "reef.mbtiles")
        assertTrue(artifacts.delete(user.path).isEmpty())
        assertTrue(user.exists())
    }

    // Orphan sweep ----------------------------------------------------------

    @Test
    fun `sweep deletes old unreferenced files and keeps referenced ones with their sidecars`() {
        val now = System.currentTimeMillis()
        val keptCache = file(cacheDir, "kept.gdaltiles", ageMillis = 60_000)
        val keptDem = file(cacheDir, "kept.gdaltiles.dem-journal", ageMillis = 60_000)
        val keptCopy = file(mbtilesDir, "kept.mbtiles", ageMillis = 60_000)
        val orphanCache = file(cacheDir, "old.gdaltiles", ageMillis = 60_000)
        val orphanDem = file(cacheDir, "old.gdaltiles.dem", ageMillis = 60_000)
        val orphanCopy = file(mbtilesDir, "removed.mbtiles", ageMillis = 60_000)
        val crashedImport = file(mbtilesDir, ".import-1234.tmp", ageMillis = 60_000)

        val deleted = artifacts.sweepOrphans(
            referencedPaths = listOf(keptCache.path, keptCopy.path),
            olderThanMillis = now - 1_000
        )

        assertEquals(
            setOf(orphanCache, orphanDem, orphanCopy, crashedImport).map { it.name }.toSet(),
            deleted.map { it.name }.toSet()
        )
        listOf(keptCache, keptDem, keptCopy).forEach { assertTrue(it.name, it.exists()) }
    }

    @Test
    fun `sweep leaves files written this session alone`() {
        val processStart = System.currentTimeMillis() - 10_000
        val inFlight = file(mbtilesDir, ".import-5678.tmp", ageMillis = 0)
        val newCache = file(cacheDir, "fresh.gdaltiles", ageMillis = 0)

        val deleted = artifacts.sweepOrphans(emptyList(), olderThanMillis = processStart)

        assertTrue(deleted.isEmpty())
        assertTrue(inFlight.exists())
        assertTrue(newCache.exists())
    }

    @Test
    fun `sweep never looks outside the managed dirs`() {
        val user = file(userDir, "reef.mbtiles", ageMillis = 60_000)
        val elsewhereInFiles = file(filesDir, "waypoints.json", ageMillis = 60_000)
        val nested = File(mbtilesDir, "nested").apply { mkdirs() }
        val nestedFile = file(nested, "deep.mbtiles", ageMillis = 60_000)

        artifacts.sweepOrphans(emptyList(), olderThanMillis = System.currentTimeMillis())

        assertTrue(user.exists())
        assertTrue(elsewhereInFiles.exists())
        assertTrue(nested.exists())
        assertTrue(nestedFile.exists())
    }

    @Test
    fun `sweep covers legacy geotiff dirs with the same reference check`() {
        val legacy = AppOwnedArtifacts(filesDir, listOf("mbtiles", "geotiff_tile_cache", "geotiff", "geotiff_pyramids"))
        val geotiffDir = File(filesDir, "geotiff").apply { mkdirs() }
        val pyramidDir = File(filesDir, "geotiff_pyramids").apply { mkdirs() }
        val stillLoaded = file(geotiffDir, "reef.tif", ageMillis = 60_000)
        val stillLoadedPyramid = file(pyramidDir, "bank.sbdp", ageMillis = 60_000)
        val stillLoadedJournal = file(pyramidDir, "bank.sbdp-journal", ageMillis = 60_000)
        val orphanTif = file(geotiffDir, "gone.tif", ageMillis = 60_000)
        val orphanPyramid = file(pyramidDir, "gone.sbdp", ageMillis = 60_000)

        val deleted = legacy.sweepOrphans(
            referencedPaths = listOf(stillLoaded.path, stillLoadedPyramid.path, "content://some/user/file.tif"),
            olderThanMillis = System.currentTimeMillis()
        )

        assertEquals(setOf("gone.tif", "gone.sbdp"), deleted.map { it.name }.toSet())
        assertFalse(orphanTif.exists())
        assertFalse(orphanPyramid.exists())
        listOf(stillLoaded, stillLoadedPyramid, stillLoadedJournal).forEach { assertTrue(it.name, it.exists()) }
    }

    @Test
    fun `sweep tolerates missing managed dirs`() {
        val bare = AppOwnedArtifacts(temp.newFolder("empty"), listOf("mbtiles", "geotiff_tile_cache"))
        assertTrue(bare.sweepOrphans(emptyList(), System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun `base names group sqlite and dem sidecars`() {
        assertEquals("x.gdaltiles", AppOwnedArtifacts.baseNameOf("x.gdaltiles.dem-journal"))
        assertEquals("x.gdaltiles", AppOwnedArtifacts.baseNameOf("x.gdaltiles.dem"))
        assertEquals("x.gdaltiles", AppOwnedArtifacts.baseNameOf("x.gdaltiles.values"))
        assertEquals("x.gdaltiles", AppOwnedArtifacts.baseNameOf("x.gdaltiles.values-journal"))
        assertEquals("a.mbtiles", AppOwnedArtifacts.baseNameOf("a.mbtiles-wal"))
        assertEquals("a.mbtiles", AppOwnedArtifacts.baseNameOf("a.mbtiles"))
    }
}
