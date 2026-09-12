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
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
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

    @Test fun importedRustProfilesCarryTunTrafficAcrossReconnectAndSwitch() = runPipeline(false)

    @Test fun nativeBinaryRuleSetCarriesTunTrafficAcrossReconnectAndSwitch() = runPipeline(true)

    @Test fun manualRemoteBootstrapCarriesTunTrafficWithoutInitialNetworkDownload() = runPipeline(true, true)

    private fun runPipeline(nativeRuleSet: Boolean, bootstrap: Boolean = false) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SagerNet>()
        assertNull("Grant VPN consent before this explicit lifecycle test", VpnService.prepare(app))
        check(!DataStore.serviceState.started) { "An existing VPN is running; refusing to interrupt it" }
        check(SagerNet.connectivity.allNetworks.none { network ->
            SagerNet.connectivity.getNetworkCapabilities(network)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }) { "Refusing to replace an existing VPN" }
        val oldMode = DataStore.serviceMode; val oldProxy = DataStore.selectedProxy
        val oldDirect = DataStore.directDns; val oldRemote = DataStore.remoteDns
        val oldBypass = DataStore.bypassLan; val oldCoreBypass = DataStore.bypassLanInCore
        val oldApps = DataStore.proxyApps; val oldFake = DataStore.enableFakeDns
        val oldHttp = DataStore.appendHttpProxy
        val oldIndividual = DataStore.individual; val oldBypassMode = DataStore.bypass
        val kv = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao
        val hadIndividual = kv[Key.INDIVIDUAL] != null
        val hadBypassMode = kv[Key.BYPASS_MODE] != null
        val db = SagerDatabase.instance
        var groupId = 0L; var ruleId = 0L; var fallbackRuleId = 0L
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        suspend fun awaitState(expected: BaseService.State) {
            repeat(150) {
                if (connection.service?.state == expected.ordinal) return
                delay(100)
            }
            error("VPN state did not reach $expected; binder=${connection.service?.state}")
        }
        val nonce = "rust-${System.nanoTime()}"
        val remoteRef = RouteRuleSet(nonce, "https://127.0.0.1:1/$nonce.srs")
        val nativeFile = if (bootstrap) RuleSetDownloads.file(app.filesDir, remoteRef) else java.io.File(app.filesDir, "rule-sets/$nonce.srs")
        profileState.preservingFailure({
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            DataStore.proxyApps = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("restrict_test_apps") == "true"; DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            if (DataStore.proxyApps) { DataStore.individual = app.packageName; DataStore.bypass = false }
            LoopbackSocksFixture(nonce).use { first -> LoopbackSocksFixture(nonce).use { second ->
                LoopbackHttpFixture().use { server ->
                    val universal = SOCKSBean().apply {
                        initializeDefaultValues(); name = "RustVPN_B"; serverAddress = "127.0.0.1"; serverPort = second.port
                    }.toUniversalLink()
                    server.reply.set(LoopbackHttpFixture.Reply(body = "socks5://127.0.0.1:${first.port}#RustVPN_A\n$universal"))
                    val sub = SubscriptionBean().apply { initializeDefaultValues(); link = "http://127.0.0.1:${server.port}/subscription"; deduplication = true; forceResolve = false }
                    val group = ProxyGroup(name = nonce, type = GroupType.SUBSCRIPTION, subscription = sub)
                    group.id = db.groupDao().createGroup(group); groupId = group.id
                    RawUpdater.doUpdate(group, sub, null, false)
                }
                val profiles = db.proxyDao().getByGroup(groupId)
                assertEquals(2, profiles.size)
                val route = RuleEntity(name = nonce, userOrder = Long.MIN_VALUE, enabled = true, outbound = 0)
                if (nativeRuleSet) {
                    nativeFile.parentFile!!.mkdirs()
                    // sing-box SRS v5: one destination predicate, 198.18.0.254/32.
                    nativeFile.writeBytes(android.util.Base64.decode("U1JTBXjaYmRgY2SAAEaWY0IM/8DEfwZAAAAA//8cPgS9", android.util.Base64.DEFAULT))
                    libcore.Libcore.validateRuleSet(nativeFile.absolutePath, "binary")
                    route.ruleSets = RouteRuleSet.encode(listOf(if (bootstrap) remoteRef else RouteRuleSet(nonce, "rule-sets/${nativeFile.name}")))
                    // An unrelated native destination must be ORed with the SRS, not ANDed.
                    route.ip = "192.0.2.1/32"
                } else route.ip = "198.18.0.254/32"
                ruleId = db.rulesDao().createRule(route)
                if (nativeRuleSet) fallbackRuleId = db.rulesDao().createRule(RuleEntity(
                    name = "$nonce-fallback", userOrder = Long.MIN_VALUE + 1, enabled = true,
                    ip = "198.18.0.254/32", outbound = -2))
                app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                connection.connect(app, object : SagerConnection.Callback {
                    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
                    override fun onServiceConnected(service: ISagerNetService) {}
                })
                fun diagnostic(stage: Int, event: String, failure: Throwable? = null) {
                    try {
                        val connectivity = SagerNet.connectivity
                        val network = connectivity.activeNetwork
                        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
                        val link = network?.let { connectivity.getLinkProperties(it) }
                        println("RUST_VPN_DIAGNOSTIC ns=${System.nanoTime()} stage=$stage event=$event selected=${DataStore.selectedProxy} binder=${connection.service?.state} network=${network?.networkHandle} vpn=${capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} interface=${link?.interfaceName} underlying=${SagerNet.underlyingNetwork?.networkHandle} failure=${failure?.javaClass?.name}")
                        first.diagnosticSnapshot().forEach { println("RUST_VPN_FIXTURE stage=$stage event=$event endpoint=first $it") }
                        second.diagnosticSnapshot().forEach { println("RUST_VPN_FIXTURE stage=$stage event=$event endpoint=second $it") }
                    } catch (diagnosticError: Throwable) {
                        // Snapshot failures cannot replace the test's original result.
                        println("RUST_VPN_DIAGNOSTIC stage=$stage event=$event snapshot_error=${diagnosticError.javaClass.name}")
                    }
                }
                repeat(3) { stage ->
                    DataStore.selectedProxy = profiles[if (stage == 2) 1 else 0].id
                    diagnostic(stage, "before_start")
                    SagerNet.startService(); awaitState(BaseService.State.Connected)
                    diagnostic(stage, "connected")
                    // NO_PROXY disables explicit HTTP proxying: success must traverse TUN.
                    val request = URL("http://198.18.0.254/$nonce").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    diagnostic(stage, "before_http")
                    var requestFailure: Throwable? = null
                    val response = try {
                        request.connectTimeout = 5000; request.readTimeout = 5000
                        request.inputStream.bufferedReader().use { it.readText() }
                    } catch (error: Throwable) {
                        requestFailure = error
                        diagnostic(stage, "http_failure", error)
                        throw error
                    } finally {
                        try { request.disconnect() } catch (cleanupError: Throwable) {
                            val original = requestFailure
                            if (original != null) original.addSuppressed(cleanupError) else throw cleanupError
                        }
                    }
                    diagnostic(stage, "http_success")
                    assertEquals("RUST_VPN_E2E_$nonce", response)
                    assertEquals(if (stage == 0) 1 else 2, first.requests.get())
                    assertEquals(if (stage == 2) 1 else 0, second.requests.get())
                    SagerNet.stopService(); awaitState(BaseService.State.Stopped)
                    diagnostic(stage, "stopped")
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
                DataStore.individual = oldIndividual; DataStore.bypass = oldBypassMode
                if (!hadIndividual) kv.delete(Key.INDIVIDUAL)
                if (!hadBypassMode) kv.delete(Key.BYPASS_MODE)
                db.runInTransaction {
                    if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                    if (fallbackRuleId != 0L) db.rulesDao().deleteById(fallbackRuleId)
                    if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
                }
                check(!nativeFile.exists() || nativeFile.delete()) { "Cannot remove owned SRS fixture" }
            })
        })
        assertNull(db.groupDao().getById(groupId))
        assertNull(db.rulesDao().getById(ruleId))
        if (fallbackRuleId != 0L) assertNull(db.rulesDao().getById(fallbackRuleId))
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
