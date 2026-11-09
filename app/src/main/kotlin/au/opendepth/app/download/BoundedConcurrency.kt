package au.opendepth.app.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Maps [items] with [transform] on [dispatcher], at most [concurrency] at a
 * time, so a big download doesn't flood DNS or the connection pool. Results
 * keep the order of [items].
 */
internal suspend fun <T, R> boundedConcurrentMap(
    items: List<T>,
    dispatcher: CoroutineDispatcher,
    concurrency: Int,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    val gate = Semaphore(concurrency)
    items.map { item ->
        async(dispatcher) { gate.withPermit { transform(item) } }
    }.awaitAll()
}
