package io.nekohasekai.sagernet.ui.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Enqueue on the event thread; suspension of one operation cannot reorder later events. */
class OrderedWorkQueue(scope: CoroutineScope, onFailure: (Throwable) -> Unit) {
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        val worker = scope.launch {
            for (operation in work) {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(error)
                }
            }
        }
        // If the owner is cancelled, reject future work instead of silently accumulating it.
        worker.invokeOnCompletion { work.cancel() }
    }
    fun submit(operation: suspend () -> Unit) {
        check(work.trySend(operation).isSuccess)
    }
}
