package io.nekohasekai.sagernet

import android.content.Intent
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Requires pre-granted VPN consent. Uses only local synthetic endpoints. */
@RunWith(AndroidJUnit4::class)
class RustPipelineVpnNativeTest {
    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()

    @Test fun importedRustProfilesCarryTunTrafficAcrossReconnectAndSwitch() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SagerNet>()
        assertNull("Grant VPN consent before this explicit lifecycle test", VpnService.prepare(app))
        check(!DataStore.serviceState.started) { "An existing VPN is running; refusing to interrupt it" }
        val oldMode = DataStore.serviceMode; val oldProxy = DataStore.selectedProxy
        val oldDirect = DataStore.directDns; val oldRemote = DataStore.remoteDns
        val oldBypass = DataStore.bypassLan; val oldCoreBypass = DataStore.bypassLanInCore
        val oldApps = DataStore.proxyApps; val oldFake = DataStore.enableFakeDns
        val oldHttp = DataStore.appendHttpProxy
        val db = SagerDatabase.instance
        var groupId = 0L; var ruleId = 0L
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        suspend fun awaitState(expected: BaseService.State) {
            repeat(150) {
                if (connection.service?.state == expected.ordinal) return
                delay(100)
            }
            error("VPN state did not reach $expected; binder=${connection.service?.state}")
        }
        val nonce = "rust-${System.nanoTime()}"
        profileState.preservingFailure({
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            DataStore.proxyApps = false; DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            LoopbackSocksFixture(nonce).use { first -> LoopbackSocksFixture(nonce).use { second ->
                LoopbackHttpFixture().use { server ->
                    server.reply.set(LoopbackHttpFixture.Reply(body = "socks5://127.0.0.1:${first.port}#RustVPN_A\nsocks5://127.0.0.1:${second.port}#RustVPN_B"))
                    val sub = SubscriptionBean().apply { initializeDefaultValues(); link = "http://127.0.0.1:${server.port}/subscription"; deduplication = true; forceResolve = false }
                    val group = ProxyGroup(name = nonce, type = GroupType.SUBSCRIPTION, subscription = sub)
                    group.id = db.groupDao().createGroup(group); groupId = group.id
                    RawUpdater.doUpdate(group, sub, null, false)
                }
                val profiles = db.proxyDao().getByGroup(groupId)
                assertEquals(2, profiles.size)
                ruleId = db.rulesDao().createRule(RuleEntity(name = nonce, userOrder = Long.MIN_VALUE, enabled = true, ip = "198.18.0.254/32", outbound = 0))
                app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                connection.connect(app, object : SagerConnection.Callback {
                    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
                    override fun onServiceConnected(service: ISagerNetService) {}
                })
                repeat(3) { stage ->
                    DataStore.selectedProxy = profiles[if (stage == 2) 1 else 0].id
                    SagerNet.startService(); awaitState(BaseService.State.Connected)
                    // NO_PROXY disables explicit HTTP proxying: success must traverse TUN.
                    val request = URL("http://198.18.0.254/$nonce").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    val response = try {
                        request.connectTimeout = 5000; request.readTimeout = 5000
                        request.inputStream.bufferedReader().use { it.readText() }
                    } finally { request.disconnect() }
                    assertEquals("RUST_VPN_E2E_$nonce", response)
                    assertEquals(if (stage == 0) 1 else 2, first.requests.get())
                    assertEquals(if (stage == 2) 1 else 0, second.requests.get())
                    SagerNet.stopService(); awaitState(BaseService.State.Stopped)
                    println("RUST_VPN_E2E stage=$stage binder_connected=true tun_payload=true binder_stopped=true")
                }
            } }
        }, {
            profileState.cleanupSteps({
                profileState.stopAndAwait(connection)
            }, {
                connection.disconnect(app)
            }, {
                DataStore.serviceMode = oldMode; DataStore.selectedProxy = oldProxy
                DataStore.directDns = oldDirect; DataStore.remoteDns = oldRemote
                DataStore.bypassLan = oldBypass; DataStore.bypassLanInCore = oldCoreBypass
                DataStore.proxyApps = oldApps; DataStore.enableFakeDns = oldFake; DataStore.appendHttpProxy = oldHttp
                db.runInTransaction {
                    if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                    if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
                }
            })
        })
        assertNull(db.groupDao().getById(groupId))
        assertNull(db.rulesDao().getById(ruleId))
        assertEquals(oldMode, DataStore.serviceMode)
        assertEquals(oldProxy, DataStore.selectedProxy)
        assertEquals(oldBypass, DataStore.bypassLan)
        assertEquals(oldCoreBypass, DataStore.bypassLanInCore)
        assertEquals(oldApps, DataStore.proxyApps)
        assertEquals(oldFake, DataStore.enableFakeDns)
        assertEquals(oldHttp, DataStore.appendHttpProxy)
        assertEquals(oldDirect, DataStore.directDns)
        assertEquals(oldRemote, DataStore.remoteDns)
    }
}
