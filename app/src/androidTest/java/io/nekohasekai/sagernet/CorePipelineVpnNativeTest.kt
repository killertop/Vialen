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
import java.io.IOException
import java.net.SocketException
import java.net.Socket
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Requires pre-granted VPN consent. Uses only local synthetic endpoints. */
@RunWith(AndroidJUnit4::class)
class CorePipelineVpnNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE)
    val foreground = BenchmarkForegroundRule(requireRetainedHost = false)
    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()

    @Test fun importedCoreProfilesCarryTunTrafficAcrossReconnectAndSwitch() = runPipeline(false)

    @Test fun selectorReloadKeepsTunForSelectionAndRebuildsForPlatformChanges() = runPipeline(false, reload = true)

    @Test fun confirmedShortcutsStartAndStopRealTun() = runPipeline(false, shortcuts = true)

    @Test fun nativeBinaryRuleSetCarriesTunTrafficAcrossReconnectAndSwitch() = runPipeline(true)

    @Test fun manualRemoteBootstrapCarriesTunTrafficWithoutInitialNetworkDownload() = runPipeline(true, true)

    @Test fun ruleSetAndDestinationMustBothMatchBeforeTunTrafficIsForwarded() =
        runPipeline(true, mismatchFirst = true)

    private fun runPipeline(nativeRuleSet: Boolean, bootstrap: Boolean = false, mismatchFirst: Boolean = false, reload: Boolean = false, shortcuts: Boolean = false) = runBlocking {
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
        var groupId = 0L; var ruleId = 0L; var fallbackRuleId = 0L; var healthRuleId = 0L
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        suspend fun awaitState(expected: BaseService.State) {
            repeat(150) {
                if (connection.service?.state == expected.ordinal) return
                delay(100)
            }
            error("VPN state did not reach $expected; binder=${connection.service?.state}")
        }
        suspend fun confirmShortcut(start: Boolean) {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val target = if (start) io.nekohasekai.sagernet.ui.QuickEnableShortcut::class.java
                else io.nekohasekai.sagernet.ui.QuickDisableShortcut::class.java
            val bindDeadline = System.nanoTime() + 5_000_000_000L
            while (connection.service == null && System.nanoTime() < bindDeadline) delay(50)
            val before = checkNotNull(connection.service).state
            app.startActivity(Intent(app, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = System.nanoTime() + 5_000_000_000L
            var confirmed = false
            while (!confirmed && System.nanoTime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString() == app.packageName) {
                    val button = root.findAccessibilityNodeInfosByViewId("android:id/button1").singleOrNull()
                    if (button != null && button.isEnabled) {
                        assertEquals("Shortcut acted before confirmation", before, connection.service?.state)
                        check(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        confirmed = true
                    }
                }
                if (!confirmed) delay(50)
            }
            check(confirmed) { "Shortcut confirmation did not appear" }
        }
        val nonce = "core-${System.nanoTime()}"
        val remoteRef = RouteRuleSet(nonce, "https://127.0.0.1:1/$nonce.srs")
        val nativeFile = if (bootstrap) RuleSetDownloads.file(app.filesDir, remoteRef) else java.io.File(app.filesDir, "rule-sets/$nonce.srs")
        profileState.preservingFailure({
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            DataStore.proxyApps = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("restrict_test_apps") == "true"; DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            if (DataStore.proxyApps) { DataStore.individual = isolatedAppRoutingSelection(); DataStore.bypass = false }
            LoopbackSocksFixture(nonce, setOf("198.18.0.254", "198.18.0.253")).use { first -> LoopbackSocksFixture(nonce).use { second ->
                LoopbackHttpFixture().use { server ->
                    val secondLink = "socks5://127.0.0.1:${second.port}#CoreVPN_B"
                    server.reply.set(LoopbackHttpFixture.Reply(body = "socks5://127.0.0.1:${first.port}#CoreVPN_A\n$secondLink"))
                    val sub = SubscriptionBean().apply { initializeDefaultValues(); link = "http://127.0.0.1:${server.port}/subscription"; deduplication = true; forceResolve = false }
                    val group = ProxyGroup(name = nonce, type = GroupType.SUBSCRIPTION, subscription = sub, isSelector = reload)
                    group.id = db.groupDao().createGroup(group); groupId = group.id
                    RawUpdater.doUpdate(group, sub, null, false)
                }
                val profiles = db.proxyDao().getByGroup(groupId)
                assertEquals(2, profiles.size)
                val route = RuleEntity(name = nonce, userOrder = Long.MIN_VALUE + 1, enabled = true, outbound = 0)
                if (nativeRuleSet) {
                    nativeFile.parentFile!!.mkdirs()
                    // sing-box SRS v5: one destination predicate, 198.18.0.254/32.
                    nativeFile.writeBytes(android.util.Base64.decode("U1JTBXjaYmRgY2SAAEaWY0IM/8DEfwZAAAAA//8cPgS9", android.util.Base64.DEFAULT))
                    libcore.Libcore.validateRuleSet(nativeFile.absolutePath, "binary")
                    route.ruleSets = RouteRuleSet.encode(listOf(if (bootstrap) remoteRef else RouteRuleSet(nonce, "rule-sets/${nativeFile.name}")))
                    // Rule-set references and explicit destination IP are separate AND conditions.
                    route.ip = if (mismatchFirst) "192.0.2.1/32" else "198.18.0.254/32"
                } else route.ip = "198.18.0.254/32"
                ruleId = db.rulesDao().createRule(route)
                if (nativeRuleSet) fallbackRuleId = db.rulesDao().createRule(RuleEntity(
                    name = "$nonce-fallback", userOrder = Long.MIN_VALUE + 2, enabled = true,
                    ip = "198.18.0.254/32", outbound = -2))
                if (mismatchFirst) healthRuleId = db.rulesDao().createRule(RuleEntity(
                    name = "$nonce-health", userOrder = Long.MIN_VALUE, enabled = true,
                    ip = "198.18.0.253/32", outbound = 0))
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
                        println("CORE_VPN_DIAGNOSTIC ns=${System.nanoTime()} stage=$stage event=$event selected=${DataStore.selectedProxy} binder=${connection.service?.state} network=${network?.networkHandle} vpn=${capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} interface=${link?.interfaceName} underlying=${SagerNet.underlyingNetwork?.networkHandle} failure=${failure?.javaClass?.name}")
                        first.diagnosticSnapshot().forEach { println("CORE_VPN_FIXTURE stage=$stage event=$event endpoint=first $it") }
                        second.diagnosticSnapshot().forEach { println("CORE_VPN_FIXTURE stage=$stage event=$event endpoint=second $it") }
                    } catch (diagnosticError: Throwable) {
                        // Snapshot failures cannot replace the test's original result.
                        println("CORE_VPN_DIAGNOSTIC stage=$stage event=$event snapshot_error=${diagnosticError.javaClass.name}")
                    }
                }
                fun requestThroughTun(stage: Int, host: String = "198.18.0.254"): String {
                    // NO_PROXY disables explicit HTTP proxying: success must traverse TUN.
                    val request = URL("http://$host/$nonce").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    diagnostic(stage, "before_http")
                    var requestFailure: Throwable? = null
                    return try {
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
                }
                if (mismatchFirst) {
                    check(nativeRuleSet)
                    DataStore.selectedProxy = profiles.first().id
                    SagerNet.startService(); awaitState(BaseService.State.Connected)
                    diagnostic(-1, "mismatched_conditions_connected")
                    // Prove this exact VPN session can forward TUN traffic before and after rejection.
                    assertEquals("RUST_VPN_E2E_$nonce", requestThroughTun(-1, "198.18.0.253"))
                    val acceptedBefore = first.acceptedConnections
                    val secondAcceptedBefore = second.acceptedConnections
                    val requestsBefore = first.requests.get()
                    // Read the TCP stream directly: HttpURLConnection wraps clean EOF in a generic
                    // IOException and may retry, obscuring whether the core closed the connection.
                    var firstByte: Int? = null
                    val rejection = try {
                        Socket(Proxy.NO_PROXY).use { socket ->
                            socket.soTimeout = 5000
                            socket.connect(InetSocketAddress("198.18.0.254", 80), 5000)
                            socket.getOutputStream().apply {
                                write("GET /$nonce HTTP/1.1\r\nHost: 198.18.0.254\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                flush()
                            }
                            firstByte = socket.getInputStream().read()
                        }
                        null
                    } catch (error: IOException) { error }
                    diagnostic(-1, "negative_tcp_result_byte_$firstByte", rejection)
                    val acceptedAfterNegative = first.acceptedConnections
                    val secondAcceptedAfterNegative = second.acceptedConnections
                    val requestsAfterNegative = first.requests.get()
                    // Complete the same-session control before judging any negative outcome.
                    assertEquals("RUST_VPN_E2E_$nonce", requestThroughTun(-1, "198.18.0.253"))
                    val message = rejection?.message.orEmpty().lowercase(java.util.Locale.ROOT)
                    val tcpRefusal = rejection is SocketException &&
                        (message.contains("connection reset") || message.contains("connection refused"))
                    assertTrue("Expected EOF without response bytes or a TCP refusal/reset; byte=$firstByte error=$rejection",
                        (rejection == null && firstByte == -1) || tcpRefusal)
                    assertEquals("Rejected traffic must not open a SOCKS connection", acceptedBefore, acceptedAfterNegative)
                    assertEquals("Rejected traffic must not open a second SOCKS connection", secondAcceptedBefore, secondAcceptedAfterNegative)
                    assertEquals("Rejected traffic must not add a proxy request", requestsBefore, requestsAfterNegative)
                    assertEquals(acceptedBefore + 1, first.acceptedConnections)
                    assertEquals(requestsBefore + 1, first.requests.get())
                    assertEquals(secondAcceptedBefore, second.acceptedConnections)
                    assertEquals(0, second.requests.get())
                    SagerNet.stopService(); awaitState(BaseService.State.Stopped)
                    val storedRule = requireNotNull(db.rulesDao().getById(ruleId))
                    val unchangedSets = storedRule.ruleSets
                    storedRule.ip = "198.18.0.254/32"
                    db.rulesDao().updateRule(storedRule)
                    val correctedRule = requireNotNull(db.rulesDao().getById(ruleId))
                    assertEquals(unchangedSets, correctedRule.ruleSets)
                    assertEquals("198.18.0.254/32", correctedRule.ip)
                    diagnostic(-1, "conditions_corrected_vpn_stopped")
                    // The following stages rebuild VPN, then reconnect and switch the same profiles.
                }
                if (reload) {
                    fun vpnHandle(): Long? = SagerNet.connectivity.allNetworks.firstOrNull {
                        SagerNet.connectivity.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                    }?.networkHandle
                    DataStore.selectedProxy = profiles.first().id
                    SagerNet.startService(); awaitState(BaseService.State.Connected)
                    assertEquals("RUST_VPN_E2E_$nonce", requestThroughTun(10))
                    val originalHandle = checkNotNull(vpnHandle())
                    DataStore.selectedProxy = profiles.last().id
                    SagerNet.reloadService()
                    val selectionDeadline = System.nanoTime() + 5_000_000_000L
                    while (connection.service?.profileName != profiles.last().displayName() && System.nanoTime() < selectionDeadline) delay(50)
                    assertEquals(profiles.last().displayName(), connection.service?.profileName)
                    assertEquals(originalHandle, vpnHandle())
                    assertEquals("RUST_VPN_E2E_$nonce", requestThroughTun(11))
                    assertEquals(1, second.requests.get())
                    DataStore.appendHttpProxy = true
                    SagerNet.reloadService()
                    val rebuildDeadline = System.nanoTime() + 15_000_000_000L
                    while ((vpnHandle() == null || vpnHandle() == originalHandle || connection.service?.state != BaseService.State.Connected.ordinal) && System.nanoTime() < rebuildDeadline) delay(50)
                    assertEquals(BaseService.State.Connected.ordinal, connection.service?.state)
                    val rebuiltHandle = checkNotNull(vpnHandle())
                    assertNotEquals(originalHandle, rebuiltHandle)
                    assertEquals("RUST_VPN_E2E_$nonce", requestThroughTun(12))
                    val stored = checkNotNull(db.rulesDao().getById(ruleId))
                    stored.domains = "regexp:["
                    db.rulesDao().updateRule(stored)
                    SagerNet.reloadService()
                    delay(1_000)
                    assertEquals(BaseService.State.Connected.ordinal, connection.service?.state)
                    assertEquals(rebuiltHandle, vpnHandle())
                    assertEquals("RUST_VPN_E2E_$nonce", requestThroughTun(13))
                    SagerNet.stopService(); awaitState(BaseService.State.Stopped)
                    println("TUN_RELOAD selection_same_tun=true platform_new_tun=true invalid_candidate_kept_tun=true http_payloads=4 stopped=true")
                } else repeat(3) { stage ->
                    DataStore.selectedProxy = profiles[if (stage == 2) 1 else 0].id
                    diagnostic(stage, "before_start")
                    if (shortcuts) confirmShortcut(true) else SagerNet.startService()
                    awaitState(BaseService.State.Connected)
                    diagnostic(stage, "connected")
                    val response = requestThroughTun(stage)
                    diagnostic(stage, "http_success")
                    assertEquals("RUST_VPN_E2E_$nonce", response)
                    assertEquals((if (stage == 0) 1 else 2) + (if (mismatchFirst) 2 else 0), first.requests.get())
                    assertEquals(if (stage == 2) 1 else 0, second.requests.get())
                    if (shortcuts) confirmShortcut(false) else SagerNet.stopService()
                    awaitState(BaseService.State.Stopped)
                    diagnostic(stage, "stopped")
                    println("CORE_VPN_E2E stage=$stage binder_connected=true tun_payload=true binder_stopped=true confirmed_shortcuts=$shortcuts")
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
                    if (healthRuleId != 0L) db.rulesDao().deleteById(healthRuleId)
                    if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
                }
                check(!nativeFile.exists() || nativeFile.delete()) { "Cannot remove owned SRS fixture" }
            })
        })
        assertNull(db.groupDao().getById(groupId))
        assertNull(db.rulesDao().getById(ruleId))
        if (fallbackRuleId != 0L) assertNull(db.rulesDao().getById(fallbackRuleId))
        if (healthRuleId != 0L) assertNull(db.rulesDao().getById(healthRuleId))
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
