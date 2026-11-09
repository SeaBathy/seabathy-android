package au.opendepth.app.util

import au.opendepth.app.diagnostics.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens a resource on the IO dispatcher and closes it if the caller was
 * cancelled meanwhile: `withContext` would drop the result, leaking an open
 * GDAL dataset or database.
 */
suspend fun <T : Any> openOnIoClosingOnCancel(open: () -> T, close: (T) -> Unit): T {
    val opened = AtomicReference<T?>(null)
    try {
        return withContext(Dispatchers.IO) { open().also { opened.set(it) } }
    } catch (cancelled: CancellationException) {
        opened.getAndSet(null)?.let { resource ->
            DiagnosticLog.log("CancellableOpen", "cancelled mid-open; closing ${resource.javaClass.simpleName}")
            runCatching { close(resource) }
        }
        throw cancelled
    }
}

/**
 * [runCatching] that lets cancellation through, so a cancelled operation
 * stops instead of being reported to the user as a failure.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
