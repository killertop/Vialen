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
        check(input.isFile) { "Private input missing" }
        val beans = RawUpdater.parseRaw(input.readText()) ?: error("No imported nodes")
        val index = args.getString("nodeIndex")?.toInt() ?: 0
        check(index in beans.indices)
        val db = SagerDatabase.instance
        val kv = PublicDatabase.kvPairDao
        val keys = listOf(Key.SERVICE_MODE, Key.DIRECT_DNS, Key.REMOTE_DNS,
            Key.BYPASS_LAN, Key.BYPASS_LAN_IN_CORE, Key.PROXY_APPS, Key.BYPASS_MODE,
            Key.INDIVIDUAL, Key.ENABLE_FAKEDNS, Key.APPEND_HTTP_PROXY, Key.ENABLE_DNS_ROUTING)
        val saved = keys.associateWith { key -> kv[key]?.let { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        } }
        var groupId = 0L
        var ruleId = 0L
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        suspend fun awaitState(expected: BaseService.State) {
            repeat(200) { if (connection.service?.state == expected.ordinal) return; delay(100) }
            error("Binder did not reach $expected")
        }
        state.preservingFailure({
            groupId = db.groupDao().createGroup(ProxyGroup(name = "remote-acceptance-${System.nanoTime()}"))
            val profile = ProxyEntity().apply { this.groupId = groupId; putBean(beans[index]) }
            profile.id = db.proxyDao().addProxy(profile)
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
            repeat(2) { round ->
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
                val request = URL("https://cp.cloudflare.com/generate_204").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    request.connectTimeout = 15000; request.readTimeout = 15000
                    request.instanceFollowRedirects = false
                    assertEquals("HTTPS through explicit proxy rule", 204, request.responseCode)
                } finally { request.disconnect() }
                SagerNet.stopService(); awaitState(BaseService.State.Stopped)
                val stopDeadline = System.nanoTime() + 5_000_000_000L
                fun hasVpn() = SagerNet.connectivity.allNetworks.any { network ->
                    SagerNet.connectivity.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                while (hasVpn() && System.nanoTime() < stopDeadline) delay(100)
                assertFalse("VPN network must disappear after stop", hasVpn())
                println("REMOTE_VPN round=$round imported=${beans.size} binder_connected=true active_vpn=true https_204=true binder_stopped=true")
            }
        }, {
            state.cleanupSteps({ VpnConsentTestUi.cleanup() }, { state.stopAndAwait(connection) }, { connection.disconnect(app) }, {
                db.runInTransaction {
                    if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                    if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
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
    }
}
