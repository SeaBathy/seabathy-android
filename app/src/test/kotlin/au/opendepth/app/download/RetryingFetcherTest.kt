package au.opendepth.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryingFetcherTest {

    @Test
    fun `succeeds immediately without retrying when the first attempt succeeds`() {
        var attempts = 0
        val fetcher = RetryingFetcher(sleep = {}) {
            attempts++
            FetchOutcome.Success("ok".toByteArray())
        }

        val result = fetcher.fetch("https://example.com/tile")

        assertEquals(1, attempts)
        assertEquals("ok", result?.toString(Charsets.UTF_8))
    }

    @Test
    fun `recovers from a transient failure that succeeds on a later attempt`() {
        var attempts = 0
        val fetcher = RetryingFetcher(sleep = {}) {
            attempts++
            if (attempts < 3) FetchOutcome.Retryable() else FetchOutcome.Success("recovered".toByteArray())
        }

        val result = fetcher.fetch("https://example.com/tile")

        assertEquals(3, attempts)
        assertEquals("recovered", result?.toString(Charsets.UTF_8))
    }

    @Test
    fun `gives up after maxAttempts consecutive transient failures`() {
        var attempts = 0
        val fetcher = RetryingFetcher(maxAttempts = 3, sleep = {}) {
            attempts++
            FetchOutcome.Retryable()
        }

        val result = fetcher.fetch("https://example.com/tile")

        assertEquals(3, attempts)
        assertNull(result)
    }

    @Test
    fun `does not retry a permanent failure`() {
        var attempts = 0
        val fetcher = RetryingFetcher(sleep = {}) {
            attempts++
            FetchOutcome.Permanent
        }

        val result = fetcher.fetch("https://example.com/tile")

        assertEquals(1, attempts)
        assertNull(result)
    }

    @Test
    fun `backs off between attempts but not after the final one`() {
        val sleeps = mutableListOf<Long>()
        val fetcher = RetryingFetcher(
            maxAttempts = 3,
            backoffMillis = { attemptNumber -> attemptNumber * 100L },
            sleep = { millis -> sleeps.add(millis) }
        ) { FetchOutcome.Retryable() }

        fetcher.fetch("https://example.com/tile")

        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun `stops retrying and skips the backoff as soon as it is told to stop`() {
        var attempts = 0
        var sleeps = 0
        var stop = false
        val fetcher = RetryingFetcher(maxAttempts = 5, sleep = { sleeps++ }, shouldStop = { stop }) {
            attempts++
            stop = true // cancelled while this attempt was in flight
            FetchOutcome.Retryable()
        }

        assertNull(fetcher.fetch("https://example.com/tile"))
        assertEquals(1, attempts)
        assertEquals(0, sleeps)
    }

    @Test
    fun `makes no attempt at all once already stopped`() {
        var attempts = 0
        val fetcher = RetryingFetcher(sleep = {}, shouldStop = { true }) {
            attempts++
            FetchOutcome.Success(ByteArray(1))
        }

        assertNull(fetcher.fetch("https://example.com/tile"))
        assertEquals(0, attempts)
    }
}
