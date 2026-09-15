package io.nekohasekai.sagernet.ui.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Enqueue on the event thread; suspension of one operation cannot reorder later events. */
class OrderedWorkQueue(scope: CoroutineScope, onFailure: (Throwable) -> Unit) {
    private val latest = HashMap<Any, suspend () -> Unit>()
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
        worker.invokeOnCompletion { work.cancel(); synchronized(latest) { latest.clear() } }
    }
    /** Coalesce only replaceable reads. Ordinary writes retain every queue entry. */
    fun submitLatest(key: Any, operation: suspend () -> Unit) {
        synchronized(latest) {
            val alreadyQueued = latest.put(key, operation) != null
            if (!alreadyQueued) try {
                submit {
                    val next = synchronized(latest) { latest.remove(key) }
                    next?.invoke()
                }
            } catch (error: Exception) {
                latest.remove(key)
                throw error
            }
        }
    }

    fun cancelLatest(key: Any) { synchronized(latest) { latest.remove(key) } }

    fun submit(operation: suspend () -> Unit) {
        check(work.trySend(operation).isSuccess)
    }
}
