package au.opendepth.app.tides

import android.content.SharedPreferences
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidePlaceStoreTest {

    private val prefs = StringPrefs()

    @Test
    fun `places are saved, kept unique by name and survive a reload`() {
        val store = TidePlaceStore(prefs, Gson())
        val reef = store.add("Reef", -18.6, 147.2)
        val again = store.add("reef", -18.7, 147.3)
        assertEquals("reef (2)", again.name)

        store.rename(reef.id, "Outer reef")
        val reloaded = TidePlaceStore(prefs, Gson())
        assertEquals(listOf("Outer reef", "reef (2)"), reloaded.all.map { it.name })
        assertEquals(-18.6, reloaded.byId(reef.id)!!.lat, 1e-9)

        reloaded.delete(reef.id)
        assertNull(TidePlaceStore(prefs, Gson()).byId(reef.id))
    }

    @Test
    fun `a blank name gets a default`() {
        assertEquals("Tide spot", TidePlaceStore(prefs, Gson()).add("  ", 0.0, 0.0).name)
    }

    @Test
    fun `unreadable saved data starts empty instead of crashing`() {
        prefs.values["tide_places"] = "{not json"
        assertEquals(0, TidePlaceStore(prefs, Gson()).all.size)
    }

    /** Just the string get/put this store uses. */
    private class StringPrefs : SharedPreferences {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String?, defValue: String?) = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { if (value == null) values.remove(key!!) else values[key!!] = value }
            override fun apply() = Unit
            override fun commit() = true
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { values.remove(key!!) }
            override fun clear() = apply { values.clear() }
        }
        override fun getAll(): MutableMap<String, *> = values
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
