package io.nekohasekai.sagernet.bg.proto

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun runCancellableUrlTest(
    cancel: () -> Unit,
    initialize: suspend () -> Unit,
    start: () -> Unit,
    test: () -> Int,
    close: () -> Unit,
): Int = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        // Only the worker closes resources: cancellation must not race startup.
        continuation.invokeOnCancellation { cancel() }
        launch(Dispatchers.IO) {
            var cleanupFailure: Exception? = null
            try {
                val result = Closeable {
                    try {
                        close()
                    } catch (failure: Exception) {
                        cleanupFailure = failure
                        throw failure
                    }
                }.use {
                    currentCoroutineContext().ensureActive()
                    initialize()
                    currentCoroutineContext().ensureActive()
                    start()
                    currentCoroutineContext().ensureActive()
                    test()
                }
                continuation.resume(result)
            } catch (e: Exception) {
                val cleanup = cleanupFailure
                if (cleanup != null && cleanup !is CancellationException) {
                    // A cancelled continuation discards resume failures. Keep cleanup
                    // failures in the structured worker so they remain observable.
                    // use() already suppresses close failure on an ordinary body error.
                    throw if (e is CancellationException) cleanup else e
                }
                continuation.resumeWithException(e)
            } finally {
                cancel()
            }
        }
    }
}
