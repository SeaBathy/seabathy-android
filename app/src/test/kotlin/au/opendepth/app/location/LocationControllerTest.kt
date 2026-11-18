package au.opendepth.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationControllerTest {
    private fun use(last: Pair<Float, Long>?, accuracy: Float, time: Long) = LocationController.shouldUse(last, accuracy, time)

    @Test
    fun theFirstPositionIsUsedHoweverRough() {
        assertTrue(use(null, 2000f, 0L))
    }

    @Test
    fun aGoodPositionIsAlwaysUsed() {
        assertTrue(use(2000f to 0L, 8f, 1_000L))
        assertTrue(use(5f to 0L, 30f, 1_000L))
    }

    @Test
    fun aRoughPositionNeverReplacesAFreshGoodOne() {
        // GPS said 6 m two seconds ago; a phone-mast position 2 km out arrives.
        assertFalse(use(6f to 0L, 2000f, 2_000L))
        assertFalse(use(6f to 0L, 300f, 29_000L))
    }

    @Test
    fun aRoughPositionIsUsedOnceTheGoodOneIsStale() {
        assertTrue(use(6f to 0L, 2000f, 31_000L))
    }

    @Test
    fun roughPositionsUpdateEachOther() {
        // No GPS at all: mast positions of about the same quality keep the boat moving.
        assertTrue(use(1500f to 0L, 1800f, 3_000L))
        assertFalse(use(400f to 0L, 2000f, 3_000L))
    }
}
