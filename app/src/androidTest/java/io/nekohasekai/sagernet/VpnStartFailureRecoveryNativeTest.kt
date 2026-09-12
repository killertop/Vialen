package io.nekohasekai.sagernet

import android.content.Intent
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit, pre-authorized VPN test. Never kills or restarts the background process. */
@RunWith(AndroidJUnit4::class)
class VpnStartFailureRecoveryNativeTest {
    @get:Rule val profileState = ProfileSelectionStateRule()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    @Test fun occupiedMixedPortCanRetryInSameBackgroundProcess() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SagerNet>()
        assertNull("VPN consent must be pre-granted", VpnService.prepare(app))
        check(DataStore.serviceState in listOf(BaseService.State.Idle, BaseService.State.Stopped)) {
            "Refusing to interrupt an existing VPN"
        }
        val initialServices = shell("dumpsys activity services ${app.packageName}")
        check(initialServices.contains("ACTIVITY MANAGER SERVICES"))
        check(listOf("startRequested=true", "fgRequired=true", "isForeground=true").none { initialServices.contains(it) }) {
            "Existing started service; refusing to interrupt it"
        }
        val keys = listOf(Key.MIXED_PORT, Key.ALLOW_ACCESS, Key.BYPASS_LAN,
            Key.BYPASS_LAN_IN_CORE, Key.PROXY_APPS, Key.ENABLE_FAKEDNS, Key.APPEND_HTTP_PROXY)
        val publicDb = PublicDatabase.instance
        val kv = publicDb.keyValuePairDao()
        val raw = linkedMapOf<String, KeyValuePair?>()
        publicDb.runInTransaction {
            keys.forEach { key -> raw[key] = kv[key]?.let { row ->
                KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
            } }
        }
        val db = SagerDatabase.instance
        var groupId = 0L
        var ruleId = 0L
        var blocker: ServerSocket? = null
        var fixture: LoopbackSocksFixture? = null
        var connected = false
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        val events = CopyOnWriteArrayList<Pair<BaseService.State, String?>>()
        suspend fun awaitCondition(label: String, condition: () -> Boolean) {
            repeat(200) { if (condition()) return; delay(100) }
            error("Timeout: $label; binder=${connection.service?.state}; callbacks=$events")
        }
        fun backgroundPid(): String {
            val pid = shell("pidof ${app.packageName}:bg").trim()
            check(pid.matches(Regex("[0-9]+"))) { "Expected one live background process: $pid" }
            return pid
        }
        val nonce = "start-recovery-${System.nanoTime()}"
        profileState.preservingFailure({
            blocker = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            val port = checkNotNull(blocker).localPort
            fixture = LoopbackSocksFixture(nonce)
            val endpoint = checkNotNull(fixture)
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.mixedPort = port; DataStore.allowAccess = false
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            DataStore.proxyApps = false; DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            groupId = db.groupDao().createGroup(ProxyGroup(name = nonce))
            val bean = SOCKSBean().apply {
                initializeDefaultValues(); name = nonce; serverAddress = "127.0.0.1"; serverPort = endpoint.port
            }
            val profile = ProxyEntity(groupId = groupId, userOrder = 1).apply { putBean(bean) }
            DataStore.selectedProxy = db.proxyDao().addProxy(profile)
            ruleId = db.rulesDao().createRule(RuleEntity(name = nonce, userOrder = Long.MIN_VALUE,
                enabled = true, ip = "198.18.0.254/32", outbound = 0))
            app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            connected = true // Disconnect even if connect partially fails.
            connection.connect(app, object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                    events.add(state to msg)
                }
                override fun onServiceConnected(service: ISagerNetService) {}
            })
            awaitCondition("binder ready") { connection.service != null }
            val pid = backgroundPid()
            events.clear()
            SagerNet.startService()
            awaitCondition("port-conflict Connecting then Stopped") {
                val snapshot = events.toList()
                val start = snapshot.indexOfFirst { it.first == BaseService.State.Connecting }
                start >= 0 && snapshot.drop(start + 1).any { it.first == BaseService.State.Stopped } &&
                    connection.service?.state == BaseService.State.Stopped.ordinal
            }
            val failure = events.last { it.first == BaseService.State.Stopped }.second.orEmpty()
            assertTrue("Expected actual port conflict, got: $failure", failure.contains("address already in use", ignoreCase = true))
            assertFalse("Must not poison stop gate: $failure", failure.contains("cleanup could not be confirmed", ignoreCase = true))
            assertEquals(pid, backgroundPid())
            assertEquals(0, endpoint.requests.get())
            blocker?.close(); blocker = null
            events.clear()
            SagerNet.startService()
            awaitCondition("retry Connected") { connection.service?.state == BaseService.State.Connected.ordinal }
            assertEquals("Retry must use the same background process", pid, backgroundPid())
            assertTrue(events.any { it.first == BaseService.State.Connecting })
            val active = checkNotNull(SagerNet.connectivity.activeNetwork)
            assertTrue(checkNotNull(SagerNet.connectivity.getNetworkCapabilities(active)).hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            assertFalse(checkNotNull(SagerNet.connectivity.getLinkProperties(active)).interfaceName.isNullOrBlank())
            val request = URL("http://198.18.0.254/$nonce").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            var requestFailure: Throwable? = null
            val response = try {
                request.connectTimeout = 5000; request.readTimeout = 5000
                request.inputStream.bufferedReader().use { it.readText() }
            } catch (error: Throwable) { requestFailure = error; throw error }
            finally {
                try { request.disconnect() } catch (error: Throwable) {
                    val original = requestFailure; if (original == null) throw error else original.addSuppressed(error)
                }
            }
            assertEquals("RUST_VPN_E2E_$nonce", response)
            assertEquals(1, endpoint.requests.get())
            SagerNet.stopService()
            awaitCondition("clean stop") {
                connection.service?.state == BaseService.State.Stopped.ordinal &&
                    events.any { it.first == BaseService.State.Stopped }
            }
            awaitCondition("owned VPN network removed") {
                SagerNet.connectivity.getNetworkCapabilities(active) == null
            }
            assertEquals(pid, backgroundPid())
            assertTrue(events.filter { it.first == BaseService.State.Stopped }.all { it.second.isNullOrBlank() })
            println("VPN_START_RECOVERY port_conflict=true same_bg_pid=$pid retry_connected=true tun_nonce=true clean_stop=true")
        }, {
            profileState.cleanupSteps({
                if (connected) profileState.stopAndAwait(connection)
            }, {
                if (connected) connection.disconnect(app)
            }, {
                blocker?.close()
            }, {
                fixture?.close()
            }, {
                db.runInTransaction {
                    if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                    if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
                }
                if (ruleId != 0L) assertNull(db.rulesDao().getById(ruleId))
                if (groupId != 0L) {
                    assertNull(db.groupDao().getById(groupId)); assertEquals(0L, db.proxyDao().countByGroup(groupId))
                }
            }, {
                publicDb.runInTransaction { raw.forEach { (key, row) -> if (row == null) kv.delete(key) else kv.put(row) } }
            }, {
                raw.forEach { (key, expected) ->
                    val actual = kv[key]
                    check(if (expected == null) actual == null else actual != null && actual.valueType == expected.valueType && actual.value.contentEquals(expected.value)) {
                        "Raw setting restoration failed: $key"
                    }
                }
                println("VPN_START_RECOVERY owned_data_removed=true extra_raw_settings_restored=true")
            })
        })
    }
}
