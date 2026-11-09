package au.opendepth.app.download

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FreeMbtilesFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `uses the plain name when it is free`() {
        assertEquals("area.mbtiles", freeMbtilesFile(temp.root, "area").name)
    }

    @Test
    fun `never returns an existing download`() {
        temp.newFile("area.mbtiles")
        temp.newFile("area_2.mbtiles")

        assertEquals("area_3.mbtiles", freeMbtilesFile(temp.root, "area").name)
    }

    @Test
    fun `skips a name whose journal is still present`() {
        temp.newFile("area.mbtiles-journal")

        assertEquals("area_2.mbtiles", freeMbtilesFile(temp.root, "area").name)
    }

    @Test
    fun `skips a name an in-progress download is writing as a part file`() {
        temp.newFile("area.mbtiles.part")

        assertEquals("area_2.mbtiles", freeMbtilesFile(temp.root, "area").name)
    }

    @Test
    fun `part file sits next to its destination`() {
        val destination = java.io.File(temp.root, "area_2.mbtiles")
        assertEquals("area_2.mbtiles.part", partFileFor(destination).name)
        assertEquals(temp.root, partFileFor(destination).parentFile)
    }

    @Test
    fun `stale part files and their journals are removed, finished downloads are not`() {
        temp.newFile("done.mbtiles")
        temp.newFile("done.mbtiles-journal")
        temp.newFile("killed.mbtiles.part")
        temp.newFile("killed.mbtiles.part-journal")
        temp.newFile("notes.part")

        val removed = deleteStaleDownloadParts(temp.root).map { it.name }.sorted()

        assertEquals(listOf("killed.mbtiles.part", "killed.mbtiles.part-journal"), removed)
        assertEquals(
            listOf("done.mbtiles", "done.mbtiles-journal", "notes.part"),
            temp.root.list()!!.sorted()
        )
    }

    @Test
    fun `a missing downloads folder has nothing to sweep`() {
        assertEquals(emptyList<java.io.File>(), deleteStaleDownloadParts(java.io.File(temp.root, "absent")))
    }
}
