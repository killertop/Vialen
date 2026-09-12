package io.nekohasekai.sagernet.bg.proto

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Save a stable result snapshot only after every worker, including native cleanup, has exited. */
internal suspend fun <P : Any, R : Any> runUrlTestBatch(
    load: suspend () -> List<P>,
    concurrency: Int,
    test: suspend (P) -> R,
    save: suspend (List<R>) -> Unit,
    onStarted: (Int) -> Unit = {},
    onResult: (R) -> Unit = {},
) {
    val results = ConcurrentLinkedQueue<R>()
    var failure: Throwable? = null
    try {
        val profiles = load()
        currentCoroutineContext().ensureActive()
        onStarted(profiles.size)
        val pending = ConcurrentLinkedQueue(profiles)
        coroutineScope {
            repeat(concurrency.coerceAtLeast(1).coerceAtMost(profiles.size)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val profile = pending.poll() ?: break
                        val result = test(profile)
                        currentCoroutineContext().ensureActive()
                        results.add(result)
                        onResult(result)
                    }
                }
            }
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        // coroutineScope above waits for cancelled children and their Closeable.use cleanup.
        withContext(NonCancellable) {
            try {
                save(results.toList())
            } catch (error: Throwable) {
                val original = failure
                if (original == null) throw error
                if (original is CancellationException && error !is CancellationException) {
                    // A Job can retain its original cancellation cause and discard a recovered
                    // CancellationException. A failed save must remain a real, observable failure.
                    error.addSuppressed(original)
                    throw error
                }
                original.addSuppressed(error)
            }
        }
    }
}
