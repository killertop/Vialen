package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Process-owned stop state. A timeout is recoverable only through the original observer. */
internal class VpnStopGate {
    private class Pending(val generation: Any, val dispose: () -> Unit) {
        var disposalAttempted = false
        fun disposeOnce() {
            if (disposalAttempted) return
            disposalAttempted = true
            dispose()
        }
    }

    private val lock = Any()
    private var fatal = false
    private var pending: Pending? = null

    fun checkCanStart() = synchronized(lock) {
        check(!fatal) { "VPN cleanup failed. Restart the app before reconnecting." }
        check(pending == null) { "Waiting for the system to remove the previous VPN. Try connecting again after cleanup completes." }
    }

    fun markFailed() = synchronized(lock) { fatal = true }

    fun waitForRemoval(
        scope: CoroutineScope,
        generation: Any,
        awaitRemoval: suspend () -> Unit,
        dispose: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Job {
        val work = synchronized(lock) {
            check(!fatal && pending == null) { "VPN stop gate already has an unresolved stop" }
            Pending(generation, dispose).also { pending = it }
        }
        // Publish before launch; the process scope survives destruction of a Service instance.
        var waitingFailure: Throwable? = null
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                awaitRemoval()
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                waitingFailure = error
            }
        }
        job.invokeOnCompletion { completionError ->
            var error = completionError ?: waitingFailure
            if (error == null) {
                try {
                    completeRemoval(work.generation)
                } catch (disposalError: Throwable) {
                    error = disposalError
                }
            }
            val failure = error
            if (failure != null) {
                synchronized(lock) {
                    fatal = true
                    if (pending === work) {
                        try {
                            work.disposeOnce()
                        } catch (disposalError: Throwable) {
                            if (disposalError !== failure) failure.addSuppressed(disposalError)
                        }
                        pending = null
                    }
                }
                onFailure(failure)
            }
        }
        return job
    }

    /** Identity guard also protects against delayed completion from a previous generation. */
    internal fun completeRemoval(generation: Any) = synchronized(lock) {
        val work = pending ?: return@synchronized
        if (work.generation !== generation) return@synchronized
        try {
            work.disposeOnce()
        } catch (error: Throwable) {
            fatal = true
            throw error
        }
        pending = null
        // Never reset fatal: a concurrent cleanup failure must survive late LOST.
    }
}
