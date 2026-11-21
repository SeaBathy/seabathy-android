package au.opendepth.app.conditions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The main sea forecast with the backup's wind: marked, saved as such, and told from the main forecast. */
class BackupWindTest {
    private val hour = ForecastGrid.HOUR_MS
    private val grid = GeoGrid(doubleArrayOf(-20.0, -19.9), doubleArrayOf(149.0, 149.1))

    private fun forecast(gusts: Boolean, windFromBackup: Boolean = false): ForecastGrid {
        val n = 2 * grid.size
        val fields = buildMap {
            put(ForecastField.WAVE_HEIGHT, FloatArray(n) { 1f })
            put(ForecastField.WIND_U, FloatArray(n) { 5f })
            put(ForecastField.WIND_V, FloatArray(n) { 0f })
            if (gusts) put(ForecastField.GUST, FloatArray(n) { 8f })
        }
        return ForecastGrid(grid, longArrayOf(0, hour), fields, windFromBackup)
    }

    private fun area(f: ForecastGrid, source: Int? = null) =
        if (source == null) ConditionsArea("a", "Reef", -20.0, 149.0, -19.9, 149.1, 99L, f, emptyMap())
        else ConditionsArea("a", "Reef", -20.0, 149.0, -19.9, 149.1, 99L, f, emptyMap(), source = source)

    private fun roundTrip(a: ConditionsArea): ConditionsArea =
        ConditionsStore.read(ByteArrayInputStream(ByteArrayOutputStream().also { ConditionsStore.write(a, it) }.toByteArray()))

    /** [a] as release 1.4.0 wrote it: the same, without the last flag. */
    private fun asOldFile(a: ConditionsArea): ByteArray {
        val packed = ByteArrayOutputStream().also { ConditionsStore.write(a, it) }.toByteArray()
        val plain = GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain, 0, plain.size - 1) } }.toByteArray()
    }

    @Test
    fun `an area follows its forecast's mark unless told otherwise`() {
        assertEquals(ConditionsArea.SOURCE_BACKUP_WIND, area(forecast(gusts = false, windFromBackup = true)).source)
        assertTrue(area(forecast(gusts = false, windFromBackup = true)).windFromBackup)
        assertEquals(ConditionsArea.SOURCE_MAIN, area(forecast(gusts = true)).source)
        val backup = area(forecast(gusts = false), ConditionsArea.SOURCE_BACKUP)
        assertTrue(backup.isBackup && !backup.windFromBackup && backup.wantsMainAgain)
        // With no forecast there's nothing to be the backup's.
        assertFalse(ConditionsArea("a", "a", 0.0, 0.0, 1.0, 1.0, 1L, null, emptyMap(), source = ConditionsArea.SOURCE_BACKUP_WIND).windFromBackup)
    }

    @Test
    fun `the mark survives saving, renaming and laying fresh wind over kept sea values`() {
        val a = area(forecast(gusts = false, windFromBackup = true))
        val back = roundTrip(a)
        assertTrue(back.windFromBackup)
        assertTrue(back.copyWith(name = "Renamed").windFromBackup)
        // A main forecast saved now stays the main one, whatever it holds.
        assertFalse(roundTrip(area(forecast(gusts = false), ConditionsArea.SOURCE_MAIN)).windFromBackup)
        // The usual wind laid over sea values kept from a forecast with the backup's wind: no longer the backup's.
        val renewed = forecast(gusts = true).withSeaFrom(a.forecast!!)
        assertFalse(renewed.windFromBackup)
        assertTrue(forecast(gusts = false, windFromBackup = true).withSeaFrom(a.forecast!!).windFromBackup)
    }

    @Test
    fun `an area saved before the mark is still read, its backup wind told by the missing gusts`() {
        val noGusts = ConditionsStore.read(ByteArrayInputStream(asOldFile(area(forecast(gusts = false), ConditionsArea.SOURCE_MAIN))))
        assertTrue(noGusts.windFromBackup)
        assertEquals(1f, noGusts.forecast!!.fields.getValue(ForecastField.WAVE_HEIGHT)[0], 0f)
        val withGusts = ConditionsStore.read(ByteArrayInputStream(asOldFile(area(forecast(gusts = true), ConditionsArea.SOURCE_MAIN))))
        assertFalse(withGusts.windFromBackup)
        assertFalse(withGusts.isBackup)
        // The backup forecast itself stays the backup.
        val backup = ConditionsStore.read(ByteArrayInputStream(asOldFile(area(forecast(gusts = false), ConditionsArea.SOURCE_BACKUP))))
        assertTrue(backup.isBackup)
        assertFalse(backup.windFromBackup)
    }
}
