package au.opendepth.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** The only check on a path arriving through MainActivity's exported import-offer intent. */
class IsOfflineDownloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var downloads: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        downloads = temp.newFolder("SeaBathy", "OfflineDownloads")
        outside = temp.newFolder("elsewhere")
    }

    private fun isDownload(path: String) = isOfflineDownload(path, downloads)

    @Test
    fun `an mbtiles file directly in the downloads folder is accepted`() {
        assertTrue(isDownload(File(downloads, "eox_-18.3_147.7_20261004.mbtiles").path))
        assertTrue(isDownload(File(downloads, "chart.MBTILES").path))
    }

    @Test
    fun `other extensions are rejected`() {
        assertFalse(isDownload(File(downloads, "notes.txt").path))
        assertFalse(isDownload(File(downloads, "chart.mbtiles-journal").path))
    }

    @Test
    fun `nested folders are rejected`() {
        val nested = File(downloads, "sub").apply { mkdirs() }
        assertFalse(isDownload(File(nested, "chart.mbtiles").path))
    }

    @Test
    fun `dot-dot traversal out of the folder is rejected`() {
        assertFalse(isDownload(File(downloads, "../../elsewhere/chart.mbtiles").path))
    }

    @Test
    fun `a symlink in the folder pointing outside is rejected`() {
        val target = File(outside, "private.mbtiles").apply { writeText("x") }
        val link = File(downloads, "link.mbtiles")
        // Windows refuses to create symlinks without Developer Mode; skip rather than fail there.
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: java.nio.file.FileSystemException) {
            org.junit.Assume.assumeNoException(e)
        }
        assertFalse(isDownload(link.path))
    }

    @Test
    fun `files elsewhere are rejected`() {
        assertFalse(isDownload(File(outside, "chart.mbtiles").path))
        assertFalse(isDownload(""))
    }
}
