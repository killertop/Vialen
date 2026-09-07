package io.nekohasekai.sagernet

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
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
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit destructive-to-connectivity emulator test. Never invoked by the ordinary lifecycle class. */
@RunWith(AndroidJUnit4::class)
class ControlledNetworkHandoverNativeTest {
    @get:org.junit.Rule val profileState = ProfileSelectionStateRule()
    @Test fun resetDisabledPreservesHeldRequest() = runBlocking { runCase(false) }
    @Test fun resetEnabledClosesHeldRequest() = runBlocking { runCase(true) }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Deadline waiting for $label" }; delay(50) }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private suspend fun runCase(reset: Boolean) {
        check(InstrumentationRegistry.getArguments().getString("allow_network_toggle") == "true") { "Requires explicit allow_network_toggle=true" }
        check(Build.FINGERPRINT.let { it.contains("generic") || it.contains("emulator") || it.contains("sdk_gphone") }) { "Emulator fingerprint required: ${Build.FINGERPRINT}" }
        val app = ApplicationProvider.getApplicationContext<SagerNet>()
        assertNull("Pre-grant VPN consent", VpnService.prepare(app))
        check(!DataStore.serviceState.started) { "Existing service must be stopped" }
        val cm = SagerNet.connectivity
        val oldWifi = shell("settings get global wifi_on")
        val oldData = shell("settings get global mobile_data")
        check(oldWifi in listOf("0", "1", "2") && oldData in listOf("0", "1")) { "Unsupported radio snapshot wifi=$oldWifi data=$oldData" }
        val wifiWasOn = oldWifi != "0"
        val keys = (ProfileSelectionStateRule.keys + listOf(Key.NETWORK_CHANGE_RESET_CONNECTIONS,
            Key.BYPASS_LAN, Key.BYPASS_LAN_IN_CORE, Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL, Key.ENABLE_FAKEDNS, Key.APPEND_HTTP_PROXY)).distinct()
        val pdb = PublicDatabase.instance
        val dao = pdb.keyValuePairDao()
        val original = linkedMapOf<String, KeyValuePair?>()
        pdb.runInTransaction { keys.forEach { key -> original[key] = dao[key]?.let { row -> KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() } } } }
        val db = SagerDatabase.instance
        var groupId = 0L; var ruleId = 0L
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        val observerKey = Any()
        var observerStarted = false; var cellularRequested = false; var radioTouched = false
        val cellCallback = object : ConnectivityManager.NetworkCallback() {}
        val observedDefault = AtomicReference<Network?>()
        fun networkHas(network: Network?, transport: Int): Boolean = network != null &&
            cm.getNetworkCapabilities(network)?.let { it.hasTransport(transport) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true &&
            !cm.getLinkProperties(network)?.interfaceName.isNullOrEmpty()
        fun physical(transport: Int) = cm.allNetworks.any { networkHas(it, transport) }
        fun current(transport: Int): Boolean = networkHas(observedDefault.get(), transport) && SagerNet.underlyingNetwork == observedDefault.get()
        val fixture = HeldLoopbackSocksFixture("handover-${System.nanoTime()}")
        val requestRef = AtomicReference<Socket?>()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "held-http").apply { isDaemon = true } }
        fun request(fresh: Boolean): String {
            val socket = Socket(Proxy.NO_PROXY)
            if (!fresh) requestRef.set(socket)
            var failure: Throwable? = null
            try {
                socket.soTimeout = 30000
                socket.connect(InetSocketAddress("198.18.0.254", 80), 10000)
                val path = "/${fixture.nonce}/${if (fresh) "fresh" else "held"}"
                socket.getOutputStream().apply {
                    write("GET $path HTTP/1.1\r\nHost: 198.18.0.254\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
                val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                val split = response.indexOf("\r\n\r\n")
                // A reset EOF before a complete response is I/O failure, never HTTP success.
                if (split < 0) throw java.io.EOFException("Incomplete controlled HTTP response")
                val headers = response.substring(0, split).split("\r\n")
                check(headers.first() == "HTTP/1.1 200 OK")
                val body = response.substring(split + 4)
                check(headers.contains("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}"))
                return body
            } catch (error: Throwable) { failure = error; throw error }
            finally { try { socket.close() } catch (error: Throwable) { if (failure == null) throw error else failure.addSuppressed(error) } }
        }
        profileState.preservingFailure({
            radioTouched = true
            shell("svc wifi enable")
            if (oldData == "0") shell("svc data enable")
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cellCallback)
            cellularRequested = true
            awaitCondition("WiFi and cellular available") { physical(NetworkCapabilities.TRANSPORT_WIFI) && physical(NetworkCapabilities.TRANSPORT_CELLULAR) }
            DefaultNetworkListener.start(observerKey) { network ->
                observedDefault.set(network)
                println("CONTROLLED_HANDOVER ns=${System.nanoTime()} reset=$reset event=default network=$network interface=${network?.let(cm::getLinkProperties)?.interfaceName} wifi=${networkHas(network, NetworkCapabilities.TRANSPORT_WIFI)} cell=${networkHas(network, NetworkCapabilities.TRANSPORT_CELLULAR)}")
            }
            observerStarted = true
            awaitCondition("initial default WiFi") { networkHas(observedDefault.get(), NetworkCapabilities.TRANSPORT_WIFI) }
            DataStore.networkChangeResetConnections = reset
            DataStore.serviceMode = Key.MODE_VPN; DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            // Keep unrelated system validation traffic outside this strict local fixture.
            DataStore.proxyApps = true; DataStore.bypass = false; DataStore.individual = app.packageName
            DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            val group = ProxyGroup(name = fixture.nonce)
            group.id = db.groupDao().createGroup(group); groupId = group.id
            val row = ProxyEntity(groupId = groupId).apply { putBean(SOCKSBean().applyDefaultValues().apply { name = fixture.nonce; serverAddress = "127.0.0.1"; serverPort = fixture.port }) }
            row.id = db.proxyDao().addProxy(row)
            ruleId = db.rulesDao().createRule(RuleEntity(name = fixture.nonce, userOrder = Long.MIN_VALUE, enabled = true, ip = "198.18.0.254/32", outbound = 0))
            DataStore.selectedProxy = row.id
            app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            connection.connect(app, object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
                override fun onServiceConnected(service: ISagerNetService) {}
            })
            SagerNet.startService()
            awaitCondition("VPN Connected") { connection.service?.state == BaseService.State.Connected.ordinal }
            awaitCondition("VPN underlying WiFi") { current(NetworkCapabilities.TRANSPORT_WIFI) }
            val initial = observedDefault.get()!!
            val initialInterface = cm.getLinkProperties(initial)!!.interfaceName
            val held = executor.submit<String> { request(false) }
            fixture.awaitReady()
            check(!held.isDone) { "Held request completed before handover" }
            println("CONTROLLED_HANDOVER ns=${System.nanoTime()} reset=$reset event=toggle_begin network=$initial interface=$initialInterface")
            shell("svc wifi disable")
            awaitCondition("actual WiFi to cellular handover") {
                val next = observedDefault.get()
                current(NetworkCapabilities.TRANSPORT_CELLULAR) && next != initial && cm.getLinkProperties(next)?.interfaceName != initialInterface
            }
            check(DefaultNetworkListener.get() == observedDefault.get()) { "Underlying changed again during listener barrier" }
            println("CONTROLLED_HANDOVER ns=${System.nanoTime()} reset=$reset event=handover_observed network=${observedDefault.get()}")
            if (reset) {
                // A timeout (including SocketTimeoutException) is a failure, never reset evidence.
                val error = try { held.get(12, TimeUnit.SECONDS); error("Reset did not close held request") }
                    catch (error: ExecutionException) { error.cause ?: error }
                check(error is IOException && error !is java.io.InterruptedIOException) { "Expected non-timeout IOException before response release, got $error" }
                println("CONTROLLED_HANDOVER ns=${System.nanoTime()} reset=true event=held_failed_before_release type=${error.javaClass.name}")
                fixture.releaseHeld(discard = true)
            } else {
                check(!held.isDone) { "Reset-disabled held request completed before release" }
                fixture.releaseHeld()
                assertEquals(fixture.body(false), held.get(12, TimeUnit.SECONDS))
            }
            assertEquals(1, fixture.heldRequests.get())
            assertEquals(fixture.body(true), request(true))
            assertEquals(1, fixture.freshRequests.get())
            check(fixture.errors.isEmpty()) { "Fixture errors: ${fixture.errors}" }
            println("CONTROLLED_HANDOVER reset=$reset old_connection=${if (reset) "closed_before_release" else "preserved"} new_request_exact_nonce=true")
        }, {
            withContext(NonCancellable) {
                // Clear and restore interruption so it cannot prevent radio/DB cleanup.
                val interrupted = Thread.interrupted()
                try { profileState.cleanupSteps({
                    fixture.close()
                }, {
                    requestRef.get()?.close()
                }, {
                    executor.shutdownNow()
                    check(executor.awaitTermination(3, TimeUnit.SECONDS)) { "HTTP worker not terminated" }
                }, {
                    profileState.stopAndAwait(connection)
                }, {
                    connection.disconnect(app)
                }, {
                    if (observerStarted) check(DefaultNetworkListener.stop(observerKey))
                }, {
                    if (cellularRequested) cm.unregisterNetworkCallback(cellCallback)
                }, {
                    if (radioTouched) {
                        shell("svc wifi ${if (wifiWasOn) "enable" else "disable"}")
                        awaitCondition("original WiFi transport state") { physical(NetworkCapabilities.TRANSPORT_WIFI) == wifiWasOn }
                        // svc may normalize wifi_on=2; preserve the exact pre-test persisted value too.
                        if (shell("settings get global wifi_on") != oldWifi) shell("settings put global wifi_on $oldWifi")
                        check(shell("settings get global wifi_on") == oldWifi)
                        println("CONTROLLED_HANDOVER reset=$reset wifi_restored=true raw=$oldWifi")
                    }
                }, {
                    if (radioTouched) {
                        if (oldData == "0") {
                            shell("svc data disable")
                            awaitCondition("cellular internet removed") { !physical(NetworkCapabilities.TRANSPORT_CELLULAR) }
                        }
                        check(shell("settings get global mobile_data") == oldData)
                        println("CONTROLLED_HANDOVER reset=$reset mobile_data_restored=true raw=$oldData changed=${oldData == "0"}")
                    }
                }, {
                    pdb.runInTransaction { original.forEach { (key, row) -> if (row == null) dao.delete(key) else dao.put(row) } }
                    original.forEach { (key, before) -> val after = dao[key]; check(if (before == null) after == null else after != null && after.valueType == before.valueType && after.value.contentEquals(before.value)) { "Raw setting restore mismatch: $key" } }
                }, {
                    db.runInTransaction {
                        if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                        if (groupId != 0L) { db.proxyDao().deleteByGroup(groupId); db.groupDao().deleteById(groupId) }
                    }
                    if (ruleId != 0L) assertNull(db.rulesDao().getById(ruleId))
                    if (groupId != 0L) assertNull(db.groupDao().getById(groupId))
                }, {
                    fixture.diagnosticSnapshot().forEach { println("CONTROLLED_HANDOVER_FIXTURE reset=$reset $it") }
                }) } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        })
    }
}
