package au.opendepth.app.backup

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppBackupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun phoneBackupRoundTrips() {
        val from = MemoryPrefs()
        from.edit().putString("wpts", """[{"id":"a","name":"Reef"}]""").putBoolean("flag", true).putInt("n", 7)
            .putLong("l", 9_000_000_000L).putFloat("f", 0.5f).putStringSet("set", setOf("x", "y"))
            .putString("overlay_layers_v2", "[local files]").putString(AppBackup.KEY_FOLDER, "content://x").commit()
        val oldFiles = temp.newFolder("old")
        File(oldFiles, "tracks").apply { mkdirs() }.resolve("t1.csv").writeText("1,2,3\n")
        File(oldFiles, "photos").apply { mkdirs() }.resolve("p.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val bytes = ByteArrayOutputStream().also { AppBackup.write(from, oldFiles, it, "test", 1_000L) }.toByteArray()

        val newFiles = temp.newFolder("new")
        File(newFiles, "tracks").apply { mkdirs() }.resolve("stale.csv").writeText("old")
        val summary = AppBackup.stage(ByteArrayInputStream(bytes), newFiles)!!
        assertEquals(1, summary.waypoints)
        assertEquals(1, summary.tracks)
        assertEquals(1, summary.photos)

        val to = MemoryPrefs()
        to.edit().putString("overlay_layers_v2", "[this phone]").putString("gone", "x").commit()
        assertTrue(AppBackup.applyPending(to, newFiles))
        assertEquals("""[{"id":"a","name":"Reef"}]""", to.getString("wpts", null))
        assertEquals(true, to.getBoolean("flag", false))
        assertEquals(7, to.getInt("n", 0))
        assertEquals(9_000_000_000L, to.getLong("l", 0))
        assertEquals(0.5f, to.getFloat("f", 0f))
        assertEquals(setOf("x", "y"), to.getStringSet("set", null))
        // This phone's own layer list and folder stay; the backup's are never written.
        assertEquals("[this phone]", to.getString("overlay_layers_v2", null))
        assertNull(to.getString(AppBackup.KEY_FOLDER, null))
        assertNull(to.getString("gone", null))
        assertTrue(File(newFiles, "tracks/t1.csv").exists())
        assertFalse(File(newFiles, "tracks/stale.csv").exists())
        assertTrue(File(newFiles, "photos/p.jpg").exists())
        assertFalse(AppBackup.applyPending(to, newFiles))
    }

    @Test
    fun desktopBackupAddsWaypoints() {
        val bytes = zip(
            AppBackup.MANIFEST to """{"format":1,"app":"desktop","created":5,"portable":{"wpts":"[{\"id\":\"a\"},{\"id\":\"b\"}]","saved_routes":"[]"}}"""
        )
        val files = temp.newFolder("d")
        assertEquals(2, AppBackup.stage(ByteArrayInputStream(bytes), files)!!.waypoints)
        val prefs = MemoryPrefs()
        prefs.edit().putString("wpts", """[{"id":"a","name":"mine"}]""").putString("other", "kept").commit()
        assertTrue(AppBackup.applyPending(prefs, files))
        assertEquals("""[{"id":"a","name":"mine"},{"id":"b"}]""", prefs.getString("wpts", null))
        assertEquals("kept", prefs.getString("other", null))
    }

    @Test
    fun rejectsOtherFilesAndUnsafePaths() {
        val files = temp.newFolder("x")
        assertNull(AppBackup.stage(ByteArrayInputStream(zip("hello.txt" to "hi")), files))
        val bytes = zip(
            AppBackup.MANIFEST to """{"format":1,"app":"android","created":1,"settings":{}}""",
            "tracks/../../evil.csv" to "x",
            "other/a.txt" to "x"
        )
        AppBackup.stage(ByteArrayInputStream(bytes), files)!!
        AppBackup.applyPending(MemoryPrefs(), files)
        assertFalse(File(files.parentFile, "evil.csv").exists())
        assertFalse(File(files, "other/a.txt").exists())
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    /** In-memory [SharedPreferences] covering every type. */
    private class MemoryPrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in map
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false
            override fun putString(k: String, v: String?) = apply { puts[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { puts[k] = v?.toSet() }
            override fun putInt(k: String, v: Int) = apply { puts[k] = v }
            override fun putLong(k: String, v: Long) = apply { puts[k] = v }
            override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
            override fun remove(k: String) = apply { removes += k }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                map.putAll(puts)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
