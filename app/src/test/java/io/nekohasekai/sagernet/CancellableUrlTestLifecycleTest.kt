package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.runCancellableUrlTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancellableUrlTestLifecycleTest {
    private fun assertObservableFailure(expected: Throwable, actual: Throwable?) {
        assertNotNull(actual)
        assertEquals(expected.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stacktrace recovery (-ea / debug mode) can copy an exception,
        // retaining the original as its cause. Require the actual sentinel to remain reachable.
        assertTrue("Original failure must remain observable in the cause chain",
            generateSequence(actual) { it.cause }.take(16).any { it === expected })
    }

    private fun cancellationAt(stage: Int) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val started = AtomicInteger()
        val tested = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            runCancellableUrlTest(
                cancel = { if (stage == 2) release.countDown() },
                initialize = { if (stage == 0) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } },
                start = { started.incrementAndGet(); if (stage == 1) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } },
                test = { tested.incrementAndGet(); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); 10 },
                close = { closed.incrementAndGet() },
            )
        }
        check(entered.await(3, TimeUnit.SECONDS))
        job.cancel()
        if (stage != 2) {
            assertEquals(0, closed.get())
            assertFalse(job.isCompleted)
            release.countDown()
        }
        withTimeout(3000) { job.join() }
        assertEquals(1, closed.get())
        assertEquals(if (stage == 0) 0 else 1, started.get())
        assertEquals(if (stage == 2) 1 else 0, tested.get())
    }
    @Test fun cancellationDuringInitialization() { cancellationAt(0) }
    @Test fun cancellationDuringStartup() { cancellationAt(1) }
    @Test fun cancellationDuringNetwork() { cancellationAt(2) }
    @Test fun failedStartupClosesOnceAndPreservesSuppressedError() = runBlocking {
        val failure = IllegalStateException("start")
        val cleanup = IllegalStateException("close")
        val closed = AtomicInteger()
        try {
            runCancellableUrlTest({}, {}, { throw failure }, { error("must not test") }, { closed.incrementAndGet(); throw cleanup })
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertObservableFailure(failure, e)
            assertTrue(failure.suppressed.contains(cleanup))
        }
        assertEquals(1, closed.get())
    }
    @Test fun successClosesBeforeReturning() = runBlocking {
        val closed = AtomicInteger()
        assertEquals(12, runCancellableUrlTest({}, {}, {}, { 12 }, { closed.incrementAndGet() }))
        assertEquals(1, closed.get())
    }
    @Test fun alreadyCancelledDoesNotInitialize() = runBlocking {
        val initialized = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            runCancellableUrlTest({}, { initialized.incrementAndGet() }, {}, { 0 }, {})
        }
        job.cancelAndJoin()
        assertEquals(0, initialized.get())
    }
    @Test fun closeFailureAfterCallerCancellationRemainsObservable() = runBlocking {
        supervisorScope {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cleanup = IllegalStateException("close after cancellation")
            val completion = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val closed = AtomicInteger()
            val pending = async(Dispatchers.Default) {
                runCancellableUrlTest(
                    cancel = { release.countDown() }, initialize = {}, start = {},
                    test = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); 10 },
                    close = { closed.incrementAndGet(); throw cleanup },
                )
            }
            pending.invokeOnCompletion { completion.set(it) }
            check(entered.await(3, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertEquals(1, closed.get())
            assertObservableFailure(cleanup, completion.get())
        }
    }

    @Test fun cancellationExceptionCannotHideSuppressedCleanupFailure() = runBlocking {
        supervisorScope {
            val cleanup = IllegalStateException("cleanup")
            val pending = async {
                runCancellableUrlTest({}, {}, {}, { throw CancellationException("body") }, { throw cleanup })
            }
            try {
                pending.await()
                fail("Expected cleanup failure")
            } catch (failure: IllegalStateException) {
                assertObservableFailure(cleanup, failure)
            }
        }
    }

    @Test fun requestAbortErrorWithSuccessfulCleanupRemainsCancellation() = runBlocking {
        supervisorScope {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completion = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val closed = AtomicInteger()
            val pending = async(Dispatchers.Default) {
                runCancellableUrlTest(
                    cancel = { release.countDown() }, initialize = {}, start = {},
                    test = {
                        entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                        throw IllegalStateException("Go context canceled")
                    },
                    close = { closed.incrementAndGet() },
                )
            }
            pending.invokeOnCompletion { completion.set(it) }
            check(entered.await(3, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertEquals(1, closed.get())
            assertTrue(completion.get() is CancellationException)
            assertTrue(completion.get().suppressed.isEmpty())
        }
    }

}
