package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.runCancellableUrlTest
import io.nekohasekai.sagernet.bg.proto.runUrlTestBatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UrlTestBatchLifecycleTest {
    private fun CountDownLatch.awaitChecked() {
        check(await(5, TimeUnit.SECONDS)) { "Lifecycle latch timed out" }
    }

    private fun containsFailure(actual: Throwable?, expected: Throwable): Boolean =
        actual != null && (actual === expected ||
            (actual.cause !== actual && containsFailure(actual.cause, expected)) ||
            actual.suppressed.any { containsFailure(it, expected) })

    @Test fun cancellationWaitsForNativeCleanupAndPersistsOnlyCompletedResults() = runBlocking {
        val nativeEntered = CountDownLatch(1)
        val nativeAbort = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val saved = ConcurrentLinkedQueue<List<Int>>()
        val delivered = ConcurrentLinkedQueue<Int>()
        val closed = AtomicInteger()
        val pending = launch(Dispatchers.Default) {
            runUrlTestBatch(
                load = { listOf(1, 2, 3) }, concurrency = 1,
                test = { node ->
                    if (node == 1) 101 else runCancellableUrlTest(
                        cancel = { nativeAbort.countDown() }, initialize = {}, start = {},
                        test = { nativeEntered.countDown(); nativeAbort.awaitChecked(); 202 },
                        close = {
                            cleanupEntered.countDown()
                            cleanupRelease.awaitChecked()
                            closed.incrementAndGet()
                        },
                    )
                },
                save = { results ->
                    assertEquals(1, closed.get())
                    currentCoroutineContext().ensureActive()
                    yield() // Persistence must remain usable after caller cancellation.
                    saved.add(results.toList())
                },
                onResult = { delivered.add(it) },
            )
        }
        try {
            nativeEntered.awaitChecked()
            pending.cancel()
            cleanupEntered.awaitChecked()
            assertFalse("Caller must wait for native close", pending.isCompleted)
            assertTrue("Must not save before native close", saved.isEmpty())
        } finally {
            cleanupRelease.countDown()
            withTimeout(5000) { pending.cancelAndJoin() }
        }
        assertEquals(listOf(101), delivered.toList())
        assertEquals(listOf(listOf(101)), saved.toList())
        assertEquals(1, closed.get())
    }

    @Test fun fixedConcurrencyVisitsEveryNodeExactlyOnce() = runBlocking {
        for ((limit, nodes) in listOf(3 to 17, 0 to 4, 9 to 2)) {
            val expectedParallelism = limit.coerceAtLeast(1).coerceAtMost(nodes)
            val firstWave = CountDownLatch(expectedParallelism)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val visited = ConcurrentLinkedQueue<Int>()
            val delivered = ConcurrentLinkedQueue<Int>()
            val saved = ConcurrentLinkedQueue<List<Int>>()
            val pending = async(Dispatchers.Default) {
                runUrlTestBatch(
                    load = { (1..nodes).toList() }, concurrency = limit,
                    test = { node ->
                        val count = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, count) }
                        try {
                            visited.add(node)
                            firstWave.countDown()
                            release.awaitChecked()
                            node * 10
                        } finally { active.decrementAndGet() }
                    },
                    save = { assertEquals(0, active.get()); saved.add(it.toList()) },
                    onResult = { delivered.add(it) },
                )
            }
            try {
                firstWave.awaitChecked()
                assertEquals(expectedParallelism, active.get())
            } finally { release.countDown() }
            withTimeout(5000) { pending.await() }
            assertEquals(expectedParallelism, maximum.get())
            assertEquals((1..nodes).toList(), visited.sorted())
            assertEquals((1..nodes).map { it * 10 }, delivered.sorted())
            assertEquals(1, saved.size)
            assertEquals(delivered.sorted(), saved.single().sorted())
        }
    }

    @Test fun saveFailureAfterSuccessRemainsObservable() = runBlocking {
        val failure = IllegalStateException("save failed")
        val calls = AtomicInteger()
        val actual = runCatching {
            runUrlTestBatch(load = { listOf(1) }, concurrency = 1, test = { it },
                save = { calls.incrementAndGet(); throw failure })
        }.exceptionOrNull()
        assertTrue(containsFailure(actual, failure))
        assertEquals(1, calls.get())
    }

    @Test fun saveFailureAfterCancellationRemainsObservable() = runBlocking {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val failure = IllegalStateException("save failed after cancellation")
            val completion = AtomicReference<Throwable?>()
            val calls = AtomicInteger()
            val pending = async {
                runUrlTestBatch<Int, Int>(load = { listOf(1) }, concurrency = 1,
                    test = { entered.complete(Unit); awaitCancellation() },
                    save = { assertTrue(it.isEmpty()); calls.incrementAndGet(); throw failure })
            }
            pending.invokeOnCompletion { completion.set(it) }
            withTimeout(5000) { entered.await() }
            pending.cancelAndJoin()
            assertTrue(containsFailure(completion.get(), failure))
            assertEquals(1, calls.get())
        }
    }

    @Test fun loadFailureStillRunsPersistenceAndPreservesBothFailures() = runBlocking {
        val loadFailure = IllegalArgumentException("load failed")
        val saveFailure = IllegalStateException("save failed")
        val calls = AtomicInteger()
        val actual = runCatching {
            runUrlTestBatch<Int, Int>(load = { throw loadFailure }, concurrency = 2,
                test = { error("No loaded nodes may run") },
                save = { assertTrue(it.isEmpty()); calls.incrementAndGet(); throw saveFailure })
        }.exceptionOrNull()
        assertEquals(1, calls.get())
        assertTrue(containsFailure(actual, loadFailure))
        assertTrue(containsFailure(actual, saveFailure))
    }
}
