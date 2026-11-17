package au.opendepth.app.download

import au.opendepth.app.BuildConfig
import au.opendepth.app.diagnostics.DiagnosticLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches one tile URL, retrying passing failures. The size check and the
 * download service each make their own.
 */
class TileFetchClient {

    private val lazyClient = lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // A redirect may upgrade an http:// source to https, but never
            // downgrade an https:// one to cleartext.
            .addNetworkInterceptor { chain ->
                if (chain.call().request().url.isHttps && !chain.request().url.isHttps) {
                    throw IOException("Refused a redirect from https to plain http")
                }
                chain.proceed(chain.request())
            }
            .build()
    }
    private val client: OkHttpClient by lazyClient

    /** Set by [cancelInFlight]; every fetch after that gives up immediately. */
    @Volatile
    private var cancelled = false

    private val retryingFetcher = RetryingFetcher(shouldStop = { cancelled || refusal != null }, attempt = ::attemptFetch)

    // Polite downloading ------------------------------------------------------
    // A steady pace (about TARGET_PER_SECOND tiles a second), a pause whenever the server says
    // "slow down" (429/503, for as long as its Retry-After asks), and a stop when it keeps refusing.

    /** Why the server stopped this download (blocked, or rate-limiting hard); null while it's going fine. */
    @Volatile
    var refusal: String? = null
        private set

    private val paceLock = Object()
    private var nextStartAt = 0L
    private var pausedUntil = 0L
    private var gapMs = BASE_GAP_MS
    private var refusalsInARow = 0
    private var throttlesInARow = 0
    private var calmSuccesses = 0

    /** Waits for this request's turn: the pace, and any pause the server asked for. */
    private fun waitForTurn() {
        val wait: Long
        synchronized(paceLock) {
            val now = System.currentTimeMillis()
            val start = maxOf(now, nextStartAt, pausedUntil)
            nextStartAt = start + gapMs
            wait = start - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    private fun onSuccess() = synchronized(paceLock) {
        refusalsInARow = 0
        throttlesInARow = 0
        // Back up to full pace gradually after the server asked to slow down.
        if (gapMs > BASE_GAP_MS && ++calmSuccesses >= CALM_STEP) {
            calmSuccesses = 0
            gapMs = maxOf(BASE_GAP_MS, gapMs * 4 / 5)
        }
    }

    /** 429 or 503: pause as asked (or 2, 4, 8 ... s), halve the pace; stop if it keeps saying so. */
    private fun onSlowDown(retryAfterSeconds: Long?) = synchronized(paceLock) {
        throttlesInARow++
        calmSuccesses = 0
        val pauseMs = (retryAfterSeconds?.times(1000) ?: (1000L shl throttlesInARow.coerceAtMost(6)))
            .coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
        pausedUntil = maxOf(pausedUntil, System.currentTimeMillis() + pauseMs)
        gapMs = (gapMs * 2).coerceAtMost(MAX_GAP_MS)
        DiagnosticLog.log(TAG, "server asked to slow down: pausing ${pauseMs / 1000} s, then ${1000 / gapMs} tiles a second")
        if (throttlesInARow >= GIVE_UP_AFTER) refusal = RATE_LIMITED
    }

    /** 401 or 403: the server won't serve these tiles to this app (blocked, or a key is needed). */
    private fun onRefused() = synchronized(paceLock) {
        if (++refusalsInARow >= GIVE_UP_AFTER) refusal = BLOCKED
    }

    /** Clears the pace and any refusal, for a new assess or download. */
    fun resetPoliteness() = synchronized(paceLock) {
        refusal = null
        nextStartAt = 0L
        pausedUntil = 0L
        gapMs = BASE_GAP_MS
        refusalsInARow = 0
        throttlesInARow = 0
        calmSuccesses = 0
    }

    /**
     * Stops a cancelled download at once: aborts every call in flight and makes
     * later fetches return null until [resetCancellation]. Coroutine
     * cancellation alone can't interrupt a blocking OkHttp call.
     */
    fun cancelInFlight() {
        cancelled = true
        if (lazyClient.isInitialized()) client.dispatcher.cancelAll()
    }

    /** Re-arms this client for a new run after [cancelInFlight]. */
    fun resetCancellation() {
        cancelled = false
    }

    /** A single tile's raw bytes, retried a few times for transient failures, or null once genuinely given up on. */
    fun fetchTile(urlTemplate: String, z: Int, x: Int, y: Int): ByteArray? {
        val url = TileSampler.urlFor(urlTemplate, z, x, y)
        val data = retryingFetcher.fetch(url)
        if (data == null) logFailure("tile fetch failed (after retries): z=$z x=$x y=$y url=${redactUrl(url)}")
        return data
    }

    /** Plain `(url) -> bytes?` shape [TileSampler] expects. */
    fun fetch(url: String): ByteArray? = retryingFetcher.fetch(url)

    /**
     * A 404 or other client error is [FetchOutcome.Permanent] (no tile there).
     * 429, 5xx and any exception (DNS, timeout, reset) are
     * [FetchOutcome.Retryable].
     */
    private fun attemptFetch(url: String): FetchOutcome {
        waitForTurn()
        if (cancelled || refusal != null) return FetchOutcome.Permanent
        return try {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", "SeaBathy/${BuildConfig.VERSION_NAME} (Android map download)").build()
            ).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body ?: return FetchOutcome.Retryable()
                        // Bounded read: a misbehaving custom source must not be able to exhaust memory.
                        val source = body.source()
                        if (body.contentLength() > MAX_TILE_BYTES || source.request(MAX_TILE_BYTES + 1)) {
                            DiagnosticLog.log(TAG, "tile over ${MAX_TILE_BYTES / (1024 * 1024)}MB, rejected: url=${redactUrl(url)}")
                            FetchOutcome.Permanent
                        } else {
                            onSuccess()
                            FetchOutcome.Success(source.buffer.readByteArray())
                        }
                    }
                    response.code == 429 || response.code == 503 -> {
                        onSlowDown(response.header("Retry-After")?.trim()?.toLongOrNull())
                        logFailure("server asked to slow down: url=${redactUrl(url)} status=${response.code}")
                        FetchOutcome.Retryable()
                    }
                    response.code == 401 || response.code == 403 -> {
                        onRefused()
                        logFailure("refused: url=${redactUrl(url)} status=${response.code}")
                        FetchOutcome.Permanent
                    }
                    response.code in RETRYABLE_HTTP_CODES -> {
                        logFailure("retryable http status: url=${redactUrl(url)} status=${response.code}")
                        FetchOutcome.Retryable()
                    }
                    else -> {
                        logFailure("permanent failure: url=${redactUrl(url)} status=${response.code}")
                        FetchOutcome.Permanent
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            // OkHttp rejected the URL itself: retrying cannot help.
            DiagnosticLog.log(TAG, "invalid tile url: ${redactUrl(url)}")
            FetchOutcome.Permanent
        } catch (error: Exception) {
            logFailure("fetch error (retryable): url=${redactUrl(url)}", error)
            FetchOutcome.Retryable(error)
        }
    }

    /**
     * Per-tile failures are logged for the first [MAX_LOGGED_FAILURES] only
     * (stack traces for the first few): a large area over open ocean or with
     * no signal fails thousands of times, which would roll the 4 MB log over
     * and erase everything before it. TileDownloadRunner logs the totals.
     */
    private fun logFailure(message: String, error: Throwable? = null) {
        val count = loggedFailures.incrementAndGet()
        when {
            count > MAX_LOGGED_FAILURES -> return
            error != null && count <= MAX_LOGGED_STACK_TRACES -> DiagnosticLog.logError(TAG, message, error)
            error != null -> DiagnosticLog.log(TAG, "$message: ${error.javaClass.simpleName}: ${error.message}")
            else -> DiagnosticLog.log(TAG, message)
        }
        if (count == MAX_LOGGED_FAILURES) DiagnosticLog.log(TAG, "further per-tile failures are not logged")
    }

    private val loggedFailures = AtomicInteger(0)

    /** Starts a fresh failure-logging budget; call at the start of each assess or download. */
    fun resetLogBudget() = loggedFailures.set(0)

    companion object {
        /** About 20 tiles a second at most: quick, but never a flood. */
        private const val BASE_GAP_MS = 50L
        private const val MAX_GAP_MS = 1000L
        private const val MIN_PAUSE_MS = 2_000L
        private const val MAX_PAUSE_MS = 120_000L
        private const val CALM_STEP = 100
        /** Refusals (or slow-downs) in a row before the download stops. */
        private const val GIVE_UP_AFTER = 8

        const val BLOCKED = "This map's server is refusing downloads: it may block apps, or need your own key. Nothing was saved."
        const val RATE_LIMITED = "This map's server kept asking SeaBathy to slow down, so the download stopped. Nothing was saved."
        /** What to try instead, shown with either reason. */
        const val TRY_A_KEY = "For offline maps, try a map service that gives you your own free key, then add its tile link " +
            "(with your key in it) under Online and offline maps → Online map layers → Your own tile link."

        private const val MAX_LOGGED_FAILURES = 50
        private const val MAX_LOGGED_STACK_TRACES = 3
        private const val TAG = "OfflineTileDownload"
        /** HTTP statuses worth retrying: 429 (rate limited) and 5xx (server-side, likely transient) — a normal 4xx (404 chief among them) means "no tile here," not "try again." */
        private val RETRYABLE_HTTP_CODES = setOf(500, 502, 504)
        /** Far beyond any real 256px raster tile. */
        private const val MAX_TILE_BYTES = 8L * 1024 * 1024
    }
}

/** A URL safe for the shareable debug log: no query string or user-info, where custom sources often carry API keys. */
internal fun redactUrl(url: String): String {
    val withoutQuery = url.substringBefore('?').substringBefore('#')
    val scheme = withoutQuery.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty()) return withoutQuery
    val rest = withoutQuery.removePrefix("$scheme://")
    val authority = rest.substringBefore('/')
    val path = rest.removePrefix(authority)
    val redacted = if (url.length != withoutQuery.length) "$path?…" else path
    return "$scheme://${authority.substringAfterLast('@')}$redacted"
}
