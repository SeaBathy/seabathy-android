package au.opendepth.app.data

import android.content.SharedPreferences
import au.opendepth.app.model.DataSource
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [DataSourceStore] against a real [DataSourceRepository] and a
 * real [Gson] -- only [SharedPreferences] itself is faked (a plain
 * in-memory map; this app has no Robolectric dependency to provide a real
 * one), so this is a genuine persistence round-trip, not a mocked-out unit
 * in isolation.
 */
class DataSourceStoreTest {

    private lateinit var preferences: FakePreferences
    private lateinit var store: DataSourceStore

    @Before
    fun setUp() {
        preferences = FakePreferences()
        store = DataSourceStore(DataSourceRepository(preferences, Gson()))
    }

    @Test
    fun `a fresh store is seeded with the built-in default preset`() {
        store.load()

        assertEquals(1, store.all.size)
        assertEquals(DataSource.DEFAULT_PRESET, store.all.first())
    }

    @Test
    fun `an added source is present after reloading from a fresh store instance`() {
        store.load()
        store.add("My Tiles", "https://example.com/{z}/{x}/{y}.png", "(c) Example")

        val reloaded = DataSourceStore(DataSourceRepository(preferences, Gson()))
        reloaded.load()

        assertEquals(2, reloaded.all.size)
        val added = reloaded.all.first { !it.builtIn }
        assertEquals("My Tiles", added.name)
        assertEquals("https://example.com/{z}/{x}/{y}.png", added.urlTemplate)
        assertEquals("(c) Example", added.attribution)
    }

    @Test
    fun `a user-added source can be removed`() {
        store.load()
        val added = store.add("Temp", "https://example.com/{z}/{x}/{y}.png", "")

        val removed = store.remove(added.id)

        assertTrue(removed)
        assertTrue(store.all.none { it.id == added.id })
    }

    @Test
    fun `the built-in preset cannot be removed`() {
        store.load()

        val removed = store.remove(DataSource.DEFAULT_PRESET.id)

        assertFalse(removed)
        assertTrue(store.all.any { it.id == DataSource.DEFAULT_PRESET.id })
    }

    @Test
    fun `removing an unknown id is a no-op that reports false`() {
        store.load()

        assertFalse(store.remove("not-a-real-id"))
    }

    /** A minimal in-memory [SharedPreferences]; only the string get/edit path this repository uses is implemented. */
    private class FakePreferences : SharedPreferences {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pendingPuts = mutableMapOf<String, String?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pendingPuts[requireNotNull(key)] = value
                return this
            }

            override fun apply() {
                pendingPuts.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                throw UnsupportedOperationException("not used by DataSourceRepository")

            override fun putInt(key: String?, value: Int) =
                throw UnsupportedOperationException("not used by DataSourceRepository")

            override fun putLong(key: String?, value: Long) =
                throw UnsupportedOperationException("not used by DataSourceRepository")

            override fun putFloat(key: String?, value: Float) =
                throw UnsupportedOperationException("not used by DataSourceRepository")

            override fun putBoolean(key: String?, value: Boolean) =
                throw UnsupportedOperationException("not used by DataSourceRepository")

            override fun remove(key: String?): SharedPreferences.Editor {
                pendingPuts[requireNotNull(key)] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                throw UnsupportedOperationException("not used by DataSourceRepository")
            }
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException("not used by DataSourceRepository")

        override fun getInt(key: String?, defValue: Int) =
            throw UnsupportedOperationException("not used by DataSourceRepository")

        override fun getLong(key: String?, defValue: Long) =
            throw UnsupportedOperationException("not used by DataSourceRepository")

        override fun getFloat(key: String?, defValue: Float) =
            throw UnsupportedOperationException("not used by DataSourceRepository")

        override fun getBoolean(key: String?, defValue: Boolean) =
            throw UnsupportedOperationException("not used by DataSourceRepository")

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }
}
