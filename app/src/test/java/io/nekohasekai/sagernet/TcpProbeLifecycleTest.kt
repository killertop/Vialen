package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TcpProbeLifecycleTest {
    @Test fun noNetworkIsNotAnUnavailableNode() = runBlocking {
        val result = tcpProbe(1, "snapshot", null, "example.test", 443)
        assertEquals(-1, result.status)
        assertEquals("当前无可用网络，请联网后重试", result.error)
    }
    @Test fun dnsFailureDoesNotOpenSocket() = runBlocking {
        var opened = false
        try {
            runTcpProbe({ throw UnknownHostException() }, { opened = true; Socket() }, 443)
            fail("DNS must fail")
        } catch (_: UnknownHostException) { }
        assertFalse(opened)
    }
    @Test fun cancellationClosesSocketAndJoinsWorker() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val connection = object : Socket() {
            override fun bind(bindpoint: SocketAddress?) {}
            override fun setSoTimeout(timeout: Int) {}
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                entered.countDown()
                try { closed.await(5, TimeUnit.SECONDS) } finally { exited.countDown() }
            }
            override fun close() { closed.countDown() }
        }
        val job = launch(Dispatchers.Default) { runTcpProbe({ "127.0.0.1" }, { connection }, 443) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        withTimeout(3000) { job.cancelAndJoin() }
        assertEquals(0L, closed.count); assertEquals(0L, exited.count)
    }
}
