package au.opendepth.app.download

/** One fetch attempt: a permanent failure (404, no tile there) isn't retried; a passing one (network, 429, 5xx) is. */
sealed class FetchOutcome {
    class Success(val bytes: ByteArray) : FetchOutcome()
    object Permanent : FetchOutcome()
    class Retryable(val error: Throwable? = null) : FetchOutcome()
}

/**
 * Retries [attempt] up to [maxAttempts] times while it reports
 * [FetchOutcome.Retryable], with a short backoff: big downloads hit passing
 * DNS errors, timeouts, 429s and 5xx. [shouldStop] is checked before each
 * try and wait, so a cancelled download stops at once. [attempt] and
 * [sleep] are passed in for tests.
 */
class RetryingFetcher(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffMillis: (attemptNumber: Int) -> Long = { attemptNumber -> DEFAULT_BACKOFF_MS * attemptNumber },
    private val sleep: (millis: Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val shouldStop: () -> Boolean = { false },
    private val attempt: (url: String) -> FetchOutcome
) {
    fun fetch(url: String): ByteArray? {
        for (attemptNumber in 1..maxAttempts) {
            if (shouldStop()) return null
            when (val outcome = attempt(url)) {
                is FetchOutcome.Success -> return outcome.bytes
                is FetchOutcome.Permanent -> return null
                is FetchOutcome.Retryable -> {
                    if (attemptNumber < maxAttempts && !shouldStop()) sleep(backoffMillis(attemptNumber))
                }
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BACKOFF_MS = 300L
    }
}
