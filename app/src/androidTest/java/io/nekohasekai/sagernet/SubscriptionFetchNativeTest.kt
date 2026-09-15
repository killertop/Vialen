package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.group.SubscriptionFetch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Production coroutine -> gomobile -> Go HTTP. No VPN, external network, or preference writes. */
@RunWith(AndroidJUnit4::class)
class SubscriptionFetchNativeTest {
    @get:Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule()

    private fun requireIdleFixture() {
        check(!DataStore.serviceState.started) { "Subscription fetch fixture requires an idle service" }
        // Refuse an existing SOCKS listener rather than changing the user's configured port.
        ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", DataStore.mixedPort)) }
    }

    private class HeldResponse(private val oversized: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 8000 }
        private val accepted = AtomicReference<Socket?>()
        private val headers = CountDownLatch(1)
        private val disconnected = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val closing = AtomicBoolean(false)
        val peerClosedBeforeCleanup = AtomicBoolean(false)
        val url = "http://127.0.0.1:${listener.localPort}/synthetic-subscription"
        private val worker = thread(name = "subscription-fetch-fixture", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 40_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(input.readLine() == "GET /synthetic-subscription HTTP/1.1")
                    var bytes = 0
                    while (true) {
                        val line = input.readLine() ?: error("Incomplete request headers")
                        bytes += line.length
                        check(bytes <= 64 * 1024) { "Unexpected request headers" }
                        if (line.isEmpty()) break
                    }
                    val size = if (oversized) 16 * 1024 * 1024 + 1 else 100
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    headers.countDown()
                    // No body is released by this fixture. Only client closure can finish this read.
                    check(input.read() == -1) { "Expected peer EOF" }
                    peerClosedBeforeCleanup.set(!closing.get())
                    disconnected.countDown()
                }
            } catch (error: Throwable) {
                if (!closing.get()) failure.set(error)
                headers.countDown()
                disconnected.countDown()
            } finally { accepted.set(null) }
        }

        suspend fun awaitHeaders() = withContext(Dispatchers.IO) {
            check(headers.await(8, TimeUnit.SECONDS)) { "Request never reached local HTTP fixture" }
            failure.get()?.let { throw AssertionError("Fixture failed", it) }
        }

        suspend fun assertPeerClosed() = withContext(Dispatchers.IO) {
            check(disconnected.await(3, TimeUnit.SECONDS)) { "Server did not observe client closure" }
            failure.get()?.let { throw AssertionError("Fixture failed", it) }
            assertTrue("Closure must precede fixture cleanup", peerClosedBeforeCleanup.get())
        }

        override fun close() {
            closing.set(true)
            listener.close()
            accepted.get()?.close()
            worker.join(5000)
            check(!worker.isAlive) { "Fixture worker did not exit" }
        }
    }

    @Test fun cancelHeldBodyJoinsNativeChildAndClosesPeer() = runBlocking {
        requireIdleFixture()
        HeldResponse().use { fixture ->
            val returned = AtomicBoolean(false)
            val fetch = async(Dispatchers.Default) {
                try { SubscriptionFetch.fetch(fixture.url, "Vialen-Synthetic-Test") }
                finally { returned.set(true) }
            }
            try {
                fixture.awaitHeaders()
                assertFalse(fetch.isCompleted)
                val started = System.nanoTime()
                withTimeout(4000) { fetch.cancelAndJoin() }
                assertTrue(fetch.isCancelled)
                assertTrue("Production fetch scope must finish its native IO child", returned.get())
                assertTrue(fetch.children.none())
                fixture.assertPeerClosed()
                println("SUBSCRIPTION_FETCH_CANCEL join_ms=${(System.nanoTime() - started) / 1_000_000} peer_eof_before_cleanup=true")
            } finally { fetch.cancelAndJoin() }
        }
    }

    @Test fun declaredOversizeIsRejectedWithoutWaitingForBody() = runBlocking {
        requireIdleFixture()
        HeldResponse(oversized = true).use { fixture ->
            val result = withTimeout(8000) {
                runCatching { SubscriptionFetch.fetch(fixture.url, "Vialen-Synthetic-Test") }
            }
            assertTrue("16 MiB + 1 response must be rejected", result.isFailure)
            assertEquals("TOO_LARGE", (result.exceptionOrNull() as io.nekohasekai.sagernet.group.SubscriptionFailure).code)
            fixture.assertPeerClosed()
        }
    }

    @Test fun productionThirtySecondTimeoutIncludesHeldBody() = runBlocking {
        requireIdleFixture()
        HeldResponse().use { fixture ->
            val started = System.nanoTime()
            val fetch = async(Dispatchers.Default) {
                runCatching { SubscriptionFetch.fetch(fixture.url, "Vialen-Synthetic-Test") }
            }
            try {
                fixture.awaitHeaders()
                val result = withTimeout(38_000) { fetch.await() }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue("Native deadline must reject held body", result.isFailure)
                assertTrue("Must observe production 30s deadline, not an early transport failure: $elapsed", elapsed in 28_000..37_999)
                assertEquals("TIMEOUT", (result.exceptionOrNull() as io.nekohasekai.sagernet.group.SubscriptionFailure).code)
                assertTrue(fetch.children.none())
                fixture.assertPeerClosed()
                println("SUBSCRIPTION_FETCH_TIMEOUT elapsed_ms=$elapsed peer_eof_before_cleanup=true")
            } finally { fetch.cancelAndJoin() }
        }
    }
}
