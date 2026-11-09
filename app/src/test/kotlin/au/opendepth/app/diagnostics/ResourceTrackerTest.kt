package au.opendepth.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResourceTrackerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `snapshot line carries every counter in a fixed, greppable shape`() {
        val line = ResourceTracker.formatSnapshot(
            reason = "periodic",
            javaHeapBytes = 3L * 1024 * 1024,
            javaHeapMaxBytes = 512L * 1024 * 1024,
            nativeHeapBytes = 1536L * 1024,
            gdalDatasets = 1,
            sqliteDatabases = 3,
            serverLayers = 2,
            threads = 57,
            tileCacheBytes = 0,
            tileCacheLiveBytes = 2L * 1024 * 1024,
            tileCacheBudgetBytes = 1024L * 1024 * 1024
        )
        assertEquals(
            "snapshot [periodic] javaHeapMB=3.0/512.0 nativeHeapMB=1.5 gdalDatasets=1 sqliteDbs=3 " +
                "serverLayers=2 threads=57 tileCacheFileMB=0.0 tileCacheLiveMB=2.0 tileCacheBudgetMB=1024.0",
            line
        )
    }

    @Test
    fun `unknown server layer count is shown as a dash, not zero`() {
        val line = ResourceTracker.formatSnapshot("download finished", 0, 0, 0, 0, 0, null, 1, 0, 0, 0)
        assertEquals(true, line.contains("serverLayers=- "))
    }

    @Test
    fun `cache size sums files in nested directories and tolerates a missing one`() {
        val root = temp.newFolder("cache")
        File(root, "a.gdaltiles").writeBytes(ByteArray(100))
        File(root, "a.gdaltiles.dem").writeBytes(ByteArray(50))
        File(root, "sub").mkdirs()
        File(root, "sub/b").writeBytes(ByteArray(7))
        assertEquals(157L, ResourceTracker.directoryBytes(root))
        assertEquals(0L, ResourceTracker.directoryBytes(File(root, "missing")))
    }
}
