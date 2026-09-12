package io.nekohasekai.sagernet

import android.content.ComponentName
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.core.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real VPN/Binder and production Worker; explicit consent and an idle physical device are required. */
@RunWith(AndroidJUnit4::class)
class WorkConnectedOnlyNativeTest {
    private val profileState = ProfileSelectionStateRule()
    // This test owns the full preference snapshot. A generic outer restore must not run
    // after cancellation while a synchronous native update may still be writing.
    @get:Rule val foreground = BenchmarkForegroundRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SagerNet
    private val remote get() = RemoteWorkManager.getInstance(app)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private suspend fun await(label: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Timeout: $label" }
            delay(50)
        }
    }
    private suspend fun runWorker(
        ids: MutableList<UUID>, completed: MutableSet<UUID>, pid: String, checkpoint: () -> Unit
    ) {
        val request = OneTimeWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java).build()
        ids.add(request.id)
        checkpoint() // Persist ownership before enqueue can create work in the other process.
        remote.enqueue(request).get(10, TimeUnit.SECONDS)
        var result: WorkInfo? = null
        await("worker ${request.id}") {
            result = remote.getWorkInfos(WorkQuery.fromIds(listOf(request.id))).get(5, TimeUnit.SECONDS).singleOrNull()
            result?.state?.isFinished == true
        }
        assertEquals(WorkInfo.State.SUCCEEDED, result!!.state)
        completed.add(request.id)
        // Binder callbacks also update the UI process state. Only the actual WorkManager
        // completion record for this UUID proves execution in the expected :bg process.
        await("worker ${request.id} completed in :bg PID $pid") {
            shell("logcat -d --pid=$pid -s WM-WorkerWrapper:I")
                .contains("Worker result SUCCESS for Work [ id=${request.id},")
        }
    }

    @Test fun stoppedSkipsDueSubscriptionAndConnectedSameProcessFetchesIt() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("vialenWorkConnectedOnly") == "true") {
            "Explicit opt-in required: -e vialenWorkConnectedOnly true"
        }
        assertNull("VPN consent must already be granted; this test never changes app-ops", VpnService.prepare(app))
        check(SagerNet.connectivity.allNetworks.none {
            SagerNet.connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }) { "Refusing to replace an existing VPN network" }
        val initial = shell("dumpsys activity services ${app.packageName}")
        check(initial.contains("ACTIVITY MANAGER SERVICES") &&
            listOf("startRequested=true", "fgRequired=true", "isForeground=true").none { initial.contains(it) }) {
            "Refusing to interrupt an existing started service"
        }
        // Do not cancel or compete with the user's unique periodic worker.
        check(remote.getWorkInfos(WorkQuery.Builder.fromUniqueWorkNames(listOf("SubscriptionUpdater")).build())
            .get(10, TimeUnit.SECONDS).none { !it.state.isFinished }) {
            "Clean scheduling fixture required: an active SubscriptionUpdater periodic could race this test"
        }
        check(remote.getWorkInfos(WorkQuery.fromTags(listOf(SubscriptionUpdater.UpdateTask::class.java.name)))
            .get(10, TimeUnit.SECONDS).none { !it.state.isFinished }) {
            "An existing subscription Worker could race this test"
        }
        val db = SagerDatabase.instance
        check(db.groupDao().allGroups().none { it.subscription?.autoUpdate == true }) {
            "Disable automatic subscriptions before running this isolated fixture test"
        }
        val preferences = PublicDatabase.kvPairDao.all().map { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        }
        val subscriptions = db.groupDao().allGroups().filter { it.subscription != null }
            .associate { it.id to KryoConverters.serialize(it.subscription) }
        val recovery = File(app.filesDir, "work-connected-only-recovery.json")
        val recoveryFile = AtomicFile(recovery)
        check(!recovery.exists() && !File("${recovery.path}.bak").exists() &&
            !File("${recovery.path}.new").exists()) {
            "Previous fixture recovery is incomplete: ${recovery.name}"
        }
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        var bound = false
        val groups = mutableListOf<Long>()
        val rules = mutableListOf<Long>()
        val requests = mutableListOf<UUID>()
        val completedRequests = mutableSetOf<UUID>()
        var vpnNetwork: Network? = null
        var backgroundPid: String? = null
        var socks: LoopbackSocksFixture? = null
        var server: ServerSocket? = null
        var serverThread: Thread? = null
        val acceptedSocket = AtomicReference<Socket?>()
        val hits = AtomicInteger()
        val serverError = AtomicReference<Throwable?>()
        val nonce = "work-connected-${System.nanoTime()}"
        fun checkpoint() {
            fun bytes(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
            // App-private recovery material must stay on the test device; do not print values.
            val content = JSONObject().apply {
                put("nonce", nonce)
                put("backgroundPid", backgroundPid)
                put("groups", JSONArray(groups)); put("rules", JSONArray(rules))
                put("requests", JSONArray(requests.map(UUID::toString)))
                put("preferences", JSONArray(preferences.map { row -> JSONObject().apply {
                    put("key", row.key); put("type", row.valueType); put("value", bytes(row.value))
                } }))
                put("subscriptions", JSONArray(subscriptions.map { (id, value) -> JSONObject().apply {
                    put("id", id); put("value", bytes(value))
                } }))
            }.toString().toByteArray(Charsets.UTF_8)
            val output = recoveryFile.startWrite()
            try {
                output.write(content)
                recoveryFile.finishWrite(output)
            } catch (error: Throwable) {
                recoveryFile.failWrite(output)
                throw error
            }
        }
        checkpoint()
        profileState.preservingFailure({
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, false)
            subscriptions.keys.forEach { id ->
                db.groupDao().getById(id)!!.also { it.subscription!!.autoUpdate = false; db.groupDao().updateGroup(it) }
            }
            socks = LoopbackSocksFixture(nonce)
            server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            val http = checkNotNull(server)
            serverThread = thread(isDaemon = true, name = "connected-work-http") {
                try {
                    http.accept().use { socket ->
                        acceptedSocket.set(socket)
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        check(input.readLine() == "GET /$nonce HTTP/1.1")
                        while (!input.readLine().isNullOrEmpty()) { /* headers */ }
                        hits.incrementAndGet()
                        val body = "proxies:\n  - name: $nonce\n    type: socks5\n    server: 127.0.0.1\n    port: 1080\n".toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                } catch (error: Throwable) { if (!http.isClosed) serverError.set(error) }
                finally { acceptedSocket.set(null) }
            }
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"; DataStore.remoteDns = "local"
            DataStore.mixedPort = ServerSocket(0).use { it.localPort }
            DataStore.allowAccess = false
            DataStore.bypassLan = false; DataStore.bypassLanInCore = false
            DataStore.proxyApps = false; DataStore.enableFakeDns = false; DataStore.appendHttpProxy = false
            val vpnGroup = db.groupDao().createGroup(ProxyGroup(name = "$nonce-vpn")).also {
                groups.add(it); checkpoint()
            }
            DataStore.selectedProxy = db.proxyDao().addProxy(ProxyEntity(groupId = vpnGroup).putProfile(Profile(name = nonce, type = "socks", server = "127.0.0.1", port = checkNotNull(socks).port, socks = Profile.Socks())))
            rules.add(db.rulesDao().createRule(RuleEntity(name = nonce, userOrder = Long.MIN_VALUE,
                enabled = true, ip = "198.18.0.254/32", outbound = 0)))
            checkpoint()
            rules.add(db.rulesDao().createRule(RuleEntity(name = "$nonce-http", userOrder = Long.MIN_VALUE + 1,
                enabled = true, ip = "127.0.0.1/32", outbound = -1)))
            checkpoint()
            val group = ProxyGroup(name = "$nonce-subscription", type = GroupType.SUBSCRIPTION,
                subscription = SubscriptionBean().apply {
                    initializeDefaultValues(); autoUpdate = true; autoUpdateDelay = 15; lastUpdated = 0
                    updateWhenConnectedOnly = true; forceResolve = false
                    link = "http://127.0.0.1:${http.localPort}/$nonce"
                })
            group.id = db.groupDao().createGroup(group).also { groups.add(it); checkpoint() }
            val seed = ProxyEntity(groupId = group.id).putProfile(Profile(name = "unchanged-$nonce", type = "socks", server = "127.0.0.1", port = 1081, socks = Profile.Socks()))
            seed.id = db.proxyDao().addProxy(seed)
            val seedBytes = seed.requireProfile()
            bound = true
            connection.connect(app, object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit
                override fun onServiceConnected(service: ISagerNetService) = Unit
            })
            await("real service Binder Stopped") { connection.service?.state == BaseService.State.Stopped.ordinal }
            val pid = shell("pidof ${app.packageName}:bg").trim()
            check(pid.matches(Regex("[0-9]+")))
            backgroundPid = pid
            assertEquals("${app.packageName}:bg", app.packageManager.getServiceInfo(
                ComponentName(app, "androidx.work.multiprocess.RemoteWorkManagerService"), 0).processName)
            runWorker(requests, completedRequests, pid, ::checkpoint)
            assertEquals(BaseService.State.Stopped.ordinal, connection.service!!.state)
            assertEquals(0, hits.get())
            assertEquals(0, db.groupDao().getById(group.id)!!.subscription!!.lastUpdated)
            val unchanged = db.proxyDao().getByGroup(group.id).single()
            assertEquals(seed.id, unchanged.id)
            assertEquals(seedBytes, unchanged.requireProfile())
            SagerNet.startService()
            await("real VPN Connected") { connection.service?.state == BaseService.State.Connected.ordinal }
            assertEquals(pid, shell("pidof ${app.packageName}:bg").trim())
            await("created VPN becomes the active network") {
                SagerNet.connectivity.activeNetwork?.let { network ->
                    if (SagerNet.connectivity.getNetworkCapabilities(network)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        vpnNetwork = network
                        true
                    } else false
                } == true
            }
            val probe = URL("http://198.18.0.254/$nonce").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                probe.connectTimeout = 5000; probe.readTimeout = 5000
                assertEquals("RUST_VPN_E2E_$nonce", probe.inputStream.bufferedReader().use { it.readText() })
            } finally { probe.disconnect() }
            assertEquals(1, checkNotNull(socks).requests.get())
            runWorker(requests, completedRequests, pid, ::checkpoint)
            serverError.get()?.let { throw AssertionError("HTTP fixture failed", it) }
            assertEquals(1, hits.get())
            val updated = db.proxyDao().getByGroup(group.id).single().requireProfile()
            assertEquals(nonce, updated.name); assertEquals("127.0.0.1", updated.server); assertEquals(1080, updated.port)
            assertTrue(db.groupDao().getById(group.id)!!.subscription!!.lastUpdated > 0)
            assertEquals(BaseService.State.Connected.ordinal, connection.service!!.state)
            assertEquals(pid, shell("pidof ${app.packageName}:bg").trim())
            println("WORK_CONNECTED_ONLY stopped_skipped=true connected_nonce=true vpn_nonce=true same_bg_pid=$pid worker_ids=${requests.joinToString(",")}")
        }, {
            var serviceRemoved = false
            profileState.cleanupSteps({ checkpoint() }, {
                profileState.cleanupSteps(*requests.map { id -> suspend {
                    if (id !in completedRequests) {
                        // A natural SUCCESS proves doWork returned; CANCELLED alone does not.
                        if (remote.getWorkInfos(WorkQuery.fromIds(listOf(id))).get(5, TimeUnit.SECONDS)
                                .singleOrNull()?.state == WorkInfo.State.SUCCEEDED) {
                            completedRequests.add(id)
                        } else {
                            remote.cancelWorkById(id).get(10, TimeUnit.SECONDS)
                            await("cancelled fixture request $id") {
                                remote.getWorkInfos(WorkQuery.fromIds(listOf(id))).get(5, TimeUnit.SECONDS)
                                    .singleOrNull()?.state?.isFinished != false
                            }
                        }
                    }
                } }.toTypedArray())
            }, {
                profileState.cleanupSteps({ server?.close() }, { acceptedSocket.get()?.close() }, {
                    serverThread?.join(6000)
                    check(serverThread?.isAlive != true) { "HTTP fixture thread did not exit" }
                })
            }, {
                socks?.close()
            }, {
                if (bound) profileState.stopAndAwait(connection)
            }, {
                if (bound) connection.disconnect(app)
            }, {
                vpnNetwork?.let { network ->
                    await("created VPN network removed") {
                        SagerNet.connectivity.getNetworkCapabilities(network) == null
                    }
                }
                // Also covers a failed start before the Network was recorded above.
                await("no started service or VPN remains") {
                    val dump = shell("dumpsys activity services ${app.packageName}")
                    dump.contains("ACTIVITY MANAGER SERVICES") &&
                        listOf("startRequested=true", "fgRequired=true", "isForeground=true")
                            .none { dump.contains(it) } &&
                        SagerNet.connectivity.allNetworks.none {
                            SagerNet.connectivity.getNetworkCapabilities(it)
                                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                        }
                }
                serviceRemoved = true
            }, {
                check(serviceRemoved) { "Service cleanup unproven; recovery retained in ${recovery.name}" }
                check(requests.all { it in completedRequests }) {
                    "Worker exit unproven after cancellation; fixture and preferences retained. " +
                        "Resolve pending Work and stop its process before restoring ${recovery.name}."
                }
                // Restore only after every submitted Worker has actually returned. Any restore
                // error retains the journal so an external runner can recover the isolated app.
                profileState.cleanupSteps({
                    rules.forEach { db.rulesDao().deleteById(it) }
                    groups.forEach { db.proxyDao().deleteByGroup(it); db.groupDao().deleteById(it) }
                }, {
                    subscriptions.forEach { (id, bytes) -> db.groupDao().getById(id)?.let {
                        it.subscription = KryoConverters.subscriptionDeserialize(bytes); db.groupDao().updateGroup(it)
                    } }
                }, {
                    PublicDatabase.instance.runInTransaction {
                        PublicDatabase.kvPairDao.reset(); preferences.forEach { PublicDatabase.kvPairDao.put(it) }
                    }
                })
                check(recovery.delete()) { "Could not remove completed recovery journal" }
                println("WORK_CONNECTED_ONLY_CLEANUP workers_returned=true http_thread_exited=true vpn_removed=true preferences_restored=true recovery_removed=true")
            })
        })
    }
}
