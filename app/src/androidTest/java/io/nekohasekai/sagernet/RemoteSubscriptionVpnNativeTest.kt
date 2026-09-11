package io.nekohasekai.sagernet

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
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Explicit real-device gate. Private subscription input is never packaged or logged. */
@RunWith(AndroidJUnit4::class)
class RemoteSubscriptionVpnNativeTest {
    @get:org.junit.Rule val state = ProfileSelectionStateRule()

    @Test fun importedRemoteNodeCarriesHttpsAcrossReconnect() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit remote VPN opt-in required", args.getString("vialenRemoteVpn") == "true")
        val app = ApplicationProvider.getApplicationContext<SagerNet>()
        check(!DataStore.serviceState.started) { "Existing service must be idle" }
        check(SagerNet.connectivity.allNetworks.none { network ->
            SagerNet.connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }) { "Refusing to replace an existing VPN" }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        val oldConsent = shell("cmd appops get ${app.packageName} ACTIVATE_VPN")
            .substringAfter("ACTIVATE_VPN: ", "default").substringBefore(';').trim()
        val input = File(app.filesDir, "remote-acceptance-private.json")
        val urlInput = File(app.filesDir, "remote-acceptance-private.url")
        val useRemoteUrl = args.getString("vialenRemoteUrl") == "true"
        check(if (useRemoteUrl) urlInput.isFile else input.isFile) { "Private input missing" }
        val beans = if (useRemoteUrl) null else RawUpdater.parseRaw(input.readText()) ?: error("No imported nodes")
        val index = args.getString("nodeIndex")?.toInt() ?: 0
        val rounds = args.getString("remoteRounds")?.toInt() ?: 2
        require(rounds in 1..10) { "Remote rounds must be bounded to 1..10" }
        if (beans != null) check(index in beans.indices)
        val db = SagerDatabase.instance
        val kv = PublicDatabase.kvPairDao
        val keys = listOf(Key.SERVICE_MODE, Key.DIRECT_DNS, Key.REMOTE_DNS,
            Key.BYPASS_LAN, Key.BYPASS_LAN_IN_CORE, Key.PROXY_APPS, Key.BYPASS_MODE,
            Key.INDIVIDUAL, Key.ENABLE_FAKEDNS, Key.APPEND_HTTP_PROXY, Key.ENABLE_DNS_ROUTING)
        val saved = keys.associateWith { key -> kv[key]?.let { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        } }
        var groupId = 0L
        var refreshGroupId = 0L
        var ruleId = 0L
        val requestTimes = mutableListOf<Long>()
        val refreshCounts = mutableListOf<Int>()
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        suspend fun awaitState(expected: BaseService.State) {
            repeat(200) { if (connection.service?.state == expected.ordinal) return; delay(100) }
            error("Binder did not reach $expected")
        }
        state.preservingFailure({
            val group = ProxyGroup(name = "remote-acceptance-${System.nanoTime()}")
            if (useRemoteUrl) {
                val subscription = SubscriptionBean().apply {
                    initializeDefaultValues()
                    link = urlInput.readText().trim()
                    check(link.startsWith("https://")) { "HTTPS subscription required" }
                    deduplication = true; forceResolve = false
                }
                group.type = GroupType.SUBSCRIPTION; group.subscription = subscription
            }
            groupId = db.groupDao().createGroup(group); group.id = groupId
            val profile = if (useRemoteUrl) {
                repeat(2) { refresh ->
                    RawUpdater.doUpdate(group, group.subscription!!, null, false)
                    check(db.proxyDao().getByGroup(groupId).isNotEmpty()) { "Empty remote refresh" }
                    println("REMOTE_REFRESH round=$refresh count=${db.proxyDao().getByGroup(groupId).size}")
                }
                val imported = db.proxyDao().getByGroup(groupId)
                check(index in imported.indices)
                imported[index]
            } else {
                ProxyEntity().apply { this.groupId = groupId; putBean(beans!![index]) }.also {
                    it.id = db.proxyDao().addProxy(it)
                }
            }
            val importedCount = if (useRemoteUrl) db.proxyDao().getByGroup(groupId).size else beans!!.size
            ruleId = db.rulesDao().createRule(RuleEntity(name = "remote-acceptance", enabled = true,
                userOrder = Long.MIN_VALUE, domains = "full:cp.cloudflare.com", outbound = profile.id))
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.selectedProxy = profile.id
            DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.enableDnsRouting = false
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            DataStore.proxyApps = true; DataStore.bypass = false
            DataStore.individual = app.packageName
            DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            VpnConsentTestUi.launchMainResumed()
            connection.connect(app, object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
                override fun onServiceConnected(service: ISagerNetService) {}
            })
            repeat(rounds) { round ->
                val needsConsent = VpnService.prepare(app) != null
                SagerNet.startService()
                if (needsConsent) {
                    val owner = VpnConsentTestUi.awaitFreshDialog()
                    VpnConsentTestUi.clickButton("android:id/button1")
                    VpnConsentTestUi.awaitDismissed(owner)
                }
                awaitState(BaseService.State.Connected)
                var vpn = false
                repeat(100) {
                    val network = SagerNet.connectivity.activeNetwork
                    if (network != null && SagerNet.connectivity.getNetworkCapabilities(network)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) vpn = true
                    if (!vpn) delay(100)
                }
                assertTrue("Active network must be VPN", vpn)
                if (round == 0 && args.getString("refreshThroughProxy") == "true") {
                    check(urlInput.isFile) { "Private URL input missing" }
                    val subscription = SubscriptionBean().apply {
                        initializeDefaultValues(); link = urlInput.readText().trim()
                        check(link.startsWith("https://")) { "HTTPS subscription required" }
                        deduplication = true; forceResolve = false
                    }
                    val refreshGroup = ProxyGroup(name = "remote-refresh-${System.nanoTime()}",
                        type = GroupType.SUBSCRIPTION, subscription = subscription)
                    refreshGroupId = db.groupDao().createGroup(refreshGroup)
                    refreshGroup.id = refreshGroupId
                    repeat(2) {
                        RawUpdater.doUpdate(refreshGroup, subscription, null, false)
                        val count = db.proxyDao().getByGroup(refreshGroupId).size
                        check(count > 0) { "Empty proxy-assisted refresh" }
                        refreshCounts += count
                    }
                }
                val request = URL("https://cp.cloudflare.com/generate_204").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                val requestStart = System.nanoTime()
                var requestMs = 0L
                try {
                    request.connectTimeout = 15000; request.readTimeout = 15000
                    request.instanceFollowRedirects = false
                    assertEquals("HTTPS through explicit proxy rule", 204, request.responseCode)
                    requestMs = (System.nanoTime() - requestStart) / 1_000_000
                    requestTimes += requestMs
                } finally { request.disconnect() }
                SagerNet.stopService(); awaitState(BaseService.State.Stopped)
                val stopDeadline = System.nanoTime() + 5_000_000_000L
                fun hasVpn() = SagerNet.connectivity.allNetworks.any { network ->
                    SagerNet.connectivity.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                while (hasVpn() && System.nanoTime() < stopDeadline) delay(100)
                assertFalse("VPN network must disappear after stop", hasVpn())
                println("REMOTE_VPN round=$round imported=$importedCount binder_connected=true active_vpn=true https_204=true binder_stopped=true request_ms=$requestMs")
            }
        }, {
            state.cleanupSteps({ VpnConsentTestUi.cleanup() }, { state.stopAndAwait(connection) }, { connection.disconnect(app) }, {
                db.runInTransaction {
                    if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                    if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
                    if (refreshGroupId != 0L) { db.proxyDao().deleteByGroup(refreshGroupId); db.groupDao().deleteById(refreshGroupId) }
                }
                saved.forEach { (key, row) -> if (row == null) kv.delete(key) else kv.put(row) }
            }, {
                shell("cmd appops set ${app.packageName} ACTIVATE_VPN $oldConsent")
            })
        })
        assertNull(db.groupDao().getById(groupId))
        assertNull(db.rulesDao().getById(ruleId))
        saved.forEach { (key, row) ->
            val actual = kv[key]
            if (row == null) assertNull("Restored $key", actual) else {
                assertNotNull("Restored $key", actual)
                assertEquals(row.valueType, actual!!.valueType)
                assertArrayEquals(row.value, actual.value)
            }
        }
        InstrumentationRegistry.getInstrumentation().addResults(android.os.Bundle().apply {
            putString("remote_request_ms", requestTimes.joinToString(","))
            putString("proxy_refresh_counts", refreshCounts.joinToString(","))
        })
    }
}
