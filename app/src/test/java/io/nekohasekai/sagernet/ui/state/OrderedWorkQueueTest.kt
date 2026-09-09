package io.nekohasekai.sagernet.ui.state

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OrderedWorkQueueTest {
    @Test fun suspendedWriteCannotOvertakeResetOrLastToggle() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val values = mutableMapOf(1L to false, 2L to false)
            val queue = OrderedWorkQueue(scope) { finished.completeExceptionally(it) }
            queue.submit { entered.complete(Unit); release.await(); values[1L] = true }
            entered.await()
            queue.submit { values[1L] = false }
            queue.submit { values[1L] = true }
            queue.submit { assertTrue(values.getValue(1L)); assertFalse(values.getValue(2L)); values.clear() }
            queue.submit { values[3L] = false }
            queue.submit { assertEquals(mapOf(3L to false), values); finished.complete(Unit) }
            release.complete(Unit)
            withTimeout(5000) { finished.await() }
        } finally { scope.cancel() }
    }

    @Test fun failureDoesNotDiscardSubsequentUserIntent() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val failed = CompletableDeferred<Throwable>()
            val done = CompletableDeferred<Unit>()
            val queue = OrderedWorkQueue(scope) { failed.complete(it) }
            queue.submit { error("storage unavailable") }
            queue.submit { done.complete(Unit) }
            withTimeout(5000) { done.await() }
            assertEquals("storage unavailable", failed.await().message)
        } finally { scope.cancel() }
    }
    @Test fun cancellationTerminatesTheQueueAndRejectsNewWork() = runBlocking<Unit> {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        var failures = 0
        val queue = OrderedWorkQueue(scope) { failures++ }
        queue.submit { entered.complete(Unit); awaitCancellation() }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, failures)
        assertThrows(IllegalStateException::class.java) { queue.submit { error("must not run") } }
    }

}
