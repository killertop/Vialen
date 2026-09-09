package io.nekohasekai.sagernet

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production TestInstance -> Go HTTP RTT -> actual sing-box SOCKS outbound; no VPN service. */
@RunWith(AndroidJUnit4::class)
class CancellableUrlTestNativeTest {
    @get:Rule val profileState = ProfileSelectionStateRule()

    @Test fun cancelHeldRequestThenRetryInSameProcess() = runBlocking {
        val pid = Process.myPid()
        val db = SagerDatabase.instance
        val nonce = "url-cancel-${System.nanoTime()}"
        val group = ProxyGroup(name = nonce)
        group.id = db.groupDao().createGroup(group)
        profileState.preservingFailure({
            DataStore.directDns = "local"
            DataStore.remoteDns = "local"
            fun profile(port: Int): ProxyEntity = ProxyEntity(groupId = group.id).apply {
                putBean(SOCKSBean().applyDefaultValues().apply {
                    name = nonce; serverAddress = "127.0.0.1"; serverPort = port
                })
                id = db.proxyDao().addProxy(this)
            }
            HeldLoopbackSocksFixture(nonce).use { held ->
                val pending = async(Dispatchers.Default) {
                    TestInstance(profile(held.port), "http://198.18.0.254/$nonce/held", 10000).doTest()
                }
                try {
                    held.awaitReady() // Actual complete HTTP headers, not merely TCP accept.
                    val started = System.nanoTime()
                    withTimeout(2000) { pending.cancelAndJoin() }
                    val cancelMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue("Cancellation waited for URL timeout: $cancelMs ms", cancelMs < 2000)
                    assertTrue(pending.isCancelled)
                    // The fixture's independent reader must observe peer closure BEFORE
                    // its response gate is opened. Fixture shutdown cannot satisfy this oracle.
                    withTimeout(2000) {
                        while (held.diagnosticSnapshot().none {
                            it.contains("held_peer_read=-1 before_release=true") ||
                                it.contains("held_peer_read_error=java.net.SocketException before_release=true")
                        }) delay(10)
                    }
                    assertEquals(1, held.heldRequests.get())
                    assertEquals(0, held.freshRequests.get())
                    assertTrue("Fixture errors: ${held.errors}", held.errors.isEmpty())
                    Log.i("CancellableUrlTest", "cancel_ms=$cancelMs peer_closed_before_release=true requests=1 pid=$pid")
                } finally {
                    pending.cancelAndJoin()
                    held.releaseHeld(discard = true)
                }
            } // Fixture close also joins all workers and requires executor termination.
            LoopbackSocksFixture(nonce).use { healthy ->
                val latency = withTimeout(5000) {
                    TestInstance(profile(healthy.port), "http://198.18.0.254/$nonce", 4000).doTest()
                }
                assertTrue("Negative RTT: $latency", latency >= 0)
                assertEquals("Original RTT test must send two requests", 2, healthy.requests.get())
                withTimeout(2000) {
                    while (!healthy.diagnosticSnapshot().first().contains("active=0 ")) delay(10)
                }
                assertTrue(healthy.diagnosticSnapshot().first().contains("failed=0 "))
                assertEquals(pid, Process.myPid())
                Log.i("CancellableUrlTest", "retry_rtt_ms=$latency requests=2 active=0 same_pid=$pid")
            }
        }, {
            db.runInTransaction {
                db.proxyDao().deleteByGroup(group.id)
                db.groupDao().deleteById(group.id)
            }
        })
    }
}
