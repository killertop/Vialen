package io.nekohasekai.sagernet.bg.proto

import java.io.Closeable
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
            try {
                val result = Closeable { close() }.use {
                    currentCoroutineContext().ensureActive()
                    initialize()
                    currentCoroutineContext().ensureActive()
                    start()
                    currentCoroutineContext().ensureActive()
                    test()
                }
                continuation.resume(result)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            } finally {
                cancel()
            }
        }
    }
}
