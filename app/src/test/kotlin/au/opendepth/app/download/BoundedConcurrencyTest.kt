package au.opendepth.app.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedConcurrencyTest {

    @Test
    fun `never runs more concurrently than the requested limit`() = runBlocking {
        val limit = 4
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val results = boundedConcurrentMap((1..40).toList(), Dispatchers.Default, limit) { item ->
            val now = current.incrementAndGet()
            peak.updateAndGet { existingPeak -> maxOf(existingPeak, now) }
            delay(20)
            current.decrementAndGet()
            item * 2
        }

        assertEquals((1..40).map { it * 2 }, results)
        assertTrue("peak concurrency was ${peak.get()}, expected <= $limit", peak.get() <= limit)
    }

    @Test
    fun `all items are still processed, in order, when the limit is one at a time`() = runBlocking {
        val results = boundedConcurrentMap((1..10).toList(), Dispatchers.Default, concurrency = 1) { it }

        assertEquals((1..10).toList(), results)
    }

    @Test
    fun `a limit larger than the item count still returns every result in the original order`() = runBlocking {
        val results = boundedConcurrentMap((1..20).toList(), Dispatchers.Default, concurrency = 100) { item ->
            delay((20 - item).toLong())
            item
        }

        assertEquals((1..20).toList(), results)
    }
}
