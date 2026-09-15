package io.nekohasekai.sagernet

import android.util.AtomicFile
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SubscriptionSchedule
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Three separate instrument invocations: prepare(old APK), verify(new APK), cleanup(new APK). */
@RunWith(AndroidJUnit4::class)
class WorkUpgradeNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    // Kotlin WorkManager 2.11 exposes a Companion, whereas the old Java 2.8 class does not.
    // Invoke the stable JVM static entry point so this same test APK can prepare the old APK.
    private val manager: WorkManager get() = WorkManager::class.java
        .getMethod("getInstance", android.content.Context::class.java)
        .invoke(null, context) as WorkManager
    private val remote get() = RemoteWorkManager.getInstance(context)
    private val checkpoint get() = AtomicFile(File(context.filesDir, "work-upgrade-fixture.json"))
    private var uniqueName = "SubscriptionUpdater"

    @Test fun stagedUpgrade() {
        val phase = InstrumentationRegistry.getArguments().getString("vialenWorkUpgrade")
        Assume.assumeTrue("Choose prepare, verify or cleanup on a physical device",
            phase in listOf("prepare", "verify", "cleanup"))
        check(DataStore.serviceState == BaseService.State.Idle || DataStore.serviceState == BaseService.State.Stopped) {
            "Stop VPN and leave user data unchanged throughout the staged upgrade"
        }
        val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:work-upgrade-test").apply { setReferenceCounted(false) }
        val startElapsed = android.os.SystemClock.elapsedRealtime()
        val startUptime = android.os.SystemClock.uptimeMillis()
        // Test-only: keep timeout clocks progressing while the physical device's screen is off.
        // The timeout is a backstop; every normal, failed, or cancelled phase releases in finally.
        wakeLock.acquire(120_000)
        try {
            when (phase) {
                "prepare" -> prepare()
                "verify" -> verify()
                "cleanup" -> cleanup()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            stage("phase clocks: elapsedMs=${android.os.SystemClock.elapsedRealtime() - startElapsed}, " +
                "uptimeMs=${android.os.SystemClock.uptimeMillis() - startUptime}")
        }
    }

    private fun save(value: JSONObject) {
        val output = checkpoint.startWrite()
        try {
            output.write(value.toString().toByteArray())
            checkpoint.finishWrite(output)
        } catch (e: Exception) { checkpoint.failWrite(output); throw e }
    }
    private fun load(): JSONObject = JSONObject(checkpoint.openRead().bufferedReader().use { it.readText() })
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    private fun prepare() {
        check(!checkpoint.baseFile.exists()) { "Existing fixture checkpoint: cleanup before preparing again" }
        val groups = SagerDatabase.groupDao.allGroups().filter { it.subscription != null }
        val state = JSONObject().put("groupId", -1L)
            .put("beforeWorkIds", JSONArray(manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
                .filterNot { it.state.isFinished }.map { it.id.toString() }))
            .put("subscriptions", JSONArray(groups.map { group ->
                JSONObject().put("id", group.id).put("bytes", encode(KryoConverters.serialize(group.subscription)))
            }))
            .put("config", JSONArray(PublicDatabase.kvPairDao.all().map { row ->
                JSONObject().put("key", row.key).put("type", row.valueType).put("bytes", encode(row.value))
            }))
            .put("receiverState", context.packageManager.getComponentEnabledSetting(
                android.content.ComponentName(context, BootReceiver::class.java)))
            .put("oldVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        // Persist recovery material before changing subscriptions or enqueuing anything.
        save(state)
        DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, false)
        DataStore.configurationStore.putBoolean("isAutoConnect", false)
        groups.forEach { group ->
            group.subscription!!.autoUpdate = false
            SagerDatabase.groupDao.updateGroup(group)
        }
        val fixture = ProxyGroup(name = "Work upgrade local fixture", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                autoUpdate = true; autoUpdateDelay = 1440
                lastUpdated = (System.currentTimeMillis() / 1000L).toInt()
                updateWhenConnectedOnly = false; forceResolve = false
                link = "http://127.0.0.1:1/not-due-before-verify"
            })
        fixture.id = SagerDatabase.groupDao.createGroup(fixture)
        state.put("groupId", fixture.id); save(state)
        // Real production worker class and real unique periodic WorkSpec in the old runtime.
        val request = PeriodicWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java, 1440, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        remote.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
            .get(10, TimeUnit.SECONDS)
        val live = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }
        assertEquals(1, live.size)
        state.put("periodicId", live.single().id.toString()).put("preparedState", live.single().state.name)
        save(state)
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("workUpgrade", "PREPARED periodicId=${live.single().id}; install new APK with -r, do not clear data")
        })
    }

    private fun stage(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("workUpgradeStage", message)
    })

    private fun fixtureRequestIds(state: JSONObject): Set<UUID> = buildSet {
        state.optString("oneTimeId").takeIf { it.isNotEmpty() }?.let { add(UUID.fromString(it)) }
        val previous = state.optJSONArray("priorOneTimeIds") ?: JSONArray()
        for (index in 0 until previous.length()) add(UUID.fromString(previous.getString(index)))
    }

    private fun cancelFixtureRequests(state: JSONObject) {
        val periodicId = state.optString("periodicId")
        fixtureRequestIds(state).forEach { id ->
            check(id.toString() != periodicId) { "Fixture cleanup must never cancel the preserved periodic work" }
            stage("cancel prior fixture request: $id")
            remote.cancelWorkById(id).get(10, TimeUnit.SECONDS)
        }
    }

    private fun verify() {
        val expectedProcess = "${context.packageName}:bg"
        for (name in listOf("androidx.work.multiprocess.RemoteWorkManagerService",
            "androidx.work.impl.background.systemjob.SystemJobService",
            "androidx.work.impl.foreground.SystemForegroundService")) {
            assertEquals("Work execution shares the live VPN state process", expectedProcess,
                context.packageManager.getServiceInfo(android.content.ComponentName(context, name), 0).processName)
        }
        assertEquals(expectedProcess, (context.applicationContext as SagerNet).workManagerConfiguration.defaultProcessName)
        stage("verify: load checkpoint")
        val state = load()
        stage("verify: cancel abandoned fixture requests")
        cancelFixtureRequests(state)
        val fixture = requireNotNull(SagerDatabase.groupDao.getById(state.getLong("groupId")))
        // A force-stopped prior verify may have left this subscription due. Normalize it
        // before UPDATE can schedule the preserved periodic WorkSpec in the new process.
        fixture.subscription!!.apply {
            autoUpdateDelay = 1440
            lastUpdated = (System.currentTimeMillis() / 1000L).toInt()
            link = "http://127.0.0.1:1/not-due-before-verify"
        }
        SagerDatabase.groupDao.updateGroup(fixture)
        val legacyId = UUID.fromString(state.getString("periodicId"))
        stage("verify: query old WorkSpec")
        val existing = manager.getWorkInfoById(legacyId).get(10, TimeUnit.SECONDS)
        assertNotNull("Old runtime WorkSpec survives replacement install", existing)
        assertTrue(existing!!.state == WorkInfo.State.ENQUEUED || existing.state == WorkInfo.State.CANCELLED)
        assertEquals("Old periodic already ran; a fresh old-runtime WorkSpec is required. Cleanup + UPDATE prepare does not reset its run count",
            0, periodicRunCount(legacyId))
        stage("verify: reconfigure begin")
        runBlocking { SubscriptionUpdater.reconfigureUpdaterOrThrow() }
        stage("verify: reconfigure complete")
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(legacyId).get(10, TimeUnit.SECONDS)!!.state)
        uniqueName = SubscriptionSchedule.name(fixture)
        var id = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS).single { !it.state.isFinished }.id
        state.put("currentWorkName", uniqueName).put("currentPeriodicId", id.toString()); save(state)
        runBlocking { SubscriptionUpdater.reconfigureUpdaterOrThrow() }
        assertEquals("Unchanged per-group UPDATE preserves identity", listOf(id),
            manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }.map { it.id })
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val served = List(2) { CountDownLatch(1) }
        val serverFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val serverThread = thread(name = "work-upgrade-local-http", isDaemon = true) {
            try {
                repeat(2) { attempt ->
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                        val name = if (attempt == 0) "WorkUpgradeFixture" else "WorkUpgradePeriodicFixture"
                        val body = "proxies:\n  - name: $name\n    type: socks5\n    server: 127.0.0.1\n    port: 1080\n".toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/yaml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                    served[attempt].countDown()
                }
            } catch (e: Throwable) { serverFailure.set(e); served.forEach { it.countDown() } }
        }
        // Save the previous ID before replacing it, so another force-stop remains recoverable.
        state.put("priorOneTimeIds", JSONArray(fixtureRequestIds(state).map { it.toString() }))
        save(state)
        var request: OneTimeWorkRequest? = null
        try {
            fixture.subscription!!.apply {
                link = "http://127.0.0.1:${server.localPort}/fixture"
                autoUpdateDelay = 15; lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            }
            SagerDatabase.groupDao.updateGroup(fixture)
            runBlocking { SubscriptionUpdater.reconfigureUpdaterOrThrow() }
            uniqueName = SubscriptionSchedule.name(fixture)
            id = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS).single { !it.state.isFinished }.id
            state.put("currentWorkName", uniqueName).put("currentPeriodicId", id.toString()); save(state)
            fixture.subscription!!.lastUpdated = 0
            SagerDatabase.groupDao.updateGroup(fixture)
            request = OneTimeWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java)
                .setInputData(workDataOf(SubscriptionSchedule.ID to fixture.id,
                    SubscriptionSchedule.CONFIG to SubscriptionSchedule.fingerprint(fixture.subscription!!))).build()
            state.put("oneTimeId", request.id.toString()); save(state)
            stage("verify: enqueue fixture")
            remote.enqueue(request).get(10, TimeUnit.SECONDS)
            stage("verify: await fixture worker")
            assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(request.id).state)
            assertTrue("Worker fetched local fixture", served[0].await(1, TimeUnit.SECONDS))
            serverFailure.get()?.let { throw AssertionError("Local server failed", it) }
            val node = SagerDatabase.proxyDao.getByGroup(fixture.id).single().requireBean()
            assertEquals("WorkUpgradeFixture", node.name)
            assertEquals("127.0.0.1", node.serverAddress)
            assertEquals(1080, node.serverPort)
            assertTrue(SagerDatabase.groupDao.getById(fixture.id)!!.subscription!!.lastUpdated > 0)
            assertFalse(manager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)!!.state.isFinished)

            // Only the preserved periodic remains runnable now: the one-time worker succeeded.
            assertEquals("First-run override requires a fresh unexecuted old-runtime WorkSpec; cleanup + UPDATE prepare cannot reset a used UUID",
                0, periodicRunCount(id))
            fixture.subscription!!.lastUpdated = 0
            SagerDatabase.groupDao.updateGroup(fixture)
            try {
                val periodic = PeriodicWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java,
                    15, TimeUnit.MINUTES)
                    .setInputData(workDataOf(SubscriptionSchedule.ID to fixture.id,
                        SubscriptionSchedule.CONFIG to SubscriptionSchedule.fingerprint(fixture.subscription!!)))
                    .addTag(SubscriptionSchedule.TAG).addTag(uniqueName)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setNextScheduleTimeOverride(System.currentTimeMillis()).build()
                remote.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, periodic)
                    .get(10, TimeUnit.SECONDS)
                val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
                stage("verify: await old periodic body id=$id")
                while (true) {
                    val info = manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)
                    assertNotNull("Migrated periodic UUID must survive UPDATE", info)
                    assertFalse("Periodic work must remain active", info!!.state.isFinished)
                    if (periodicRunCount(id) > 0 && info.state == WorkInfo.State.ENQUEUED) break
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Old periodic body did not complete" }
                    CountDownLatch(1).await(100, TimeUnit.MILLISECONDS)
                }
                probeMainThread()
                assertTrue("Migrated periodic fetched its distinct fixture", served[1].await(1, TimeUnit.SECONDS))
                serverFailure.get()?.let { throw AssertionError("Periodic HTTP fixture failed", it) }
                val periodicNode = SagerDatabase.proxyDao.getByGroup(fixture.id).single().requireBean()
                assertEquals("WorkUpgradePeriodicFixture", periodicNode.name)
                assertEquals("127.0.0.1", periodicNode.serverAddress)
                assertEquals(1080, periodicNode.serverPort)
                assertTrue(SagerDatabase.groupDao.getById(fixture.id)!!.subscription!!.lastUpdated > 0)
                val live = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
                    .filterNot { it.state.isFinished }
                assertEquals(listOf(id), live.map { it.id })
                assertEquals(WorkInfo.State.ENQUEUED, live.single().state)
                stage("verify: old periodic body completed id=$id period_count=${periodicRunCount(id)}")
            } finally {
                clearPeriodicOverride(id)
            }
            state.put("verified", true).put("newVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            save(state)
        } finally {
            try {
                request?.let { remote.cancelWorkById(it.id).get(10, TimeUnit.SECONDS) }
            } finally {
                server.close()
                serverThread.join(2000)
            }
        }
    }

    /** Read the actual migrated database without initializing another WorkManager runtime or writing SQL. */
    private fun periodicRunCount(id: UUID): Int {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        check(file.isFile) { "Migrated WorkManager database not found: $file" }
        return android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT period_count FROM WorkSpec WHERE id = ?", arrayOf(id.toString())).use { cursor ->
                check(cursor.moveToFirst()) { "Old periodic WorkSpec missing: $id" }
                cursor.getInt(0)
            }
        }
    }

    private fun clearPeriodicOverride(id: UUID) {
        val live = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
        check(live.map { it.id } == listOf(id)) { "Refuse to create or replace missing old periodic during recovery" }
        val builder = PeriodicWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java,
            1440, TimeUnit.MINUTES).setInitialDelay(1, TimeUnit.DAYS)
        if (uniqueName != SubscriptionSchedule.LEGACY) {
            val fixture = requireNotNull(SagerDatabase.groupDao.getById(load().getLong("groupId")))
            builder.setInputData(workDataOf(SubscriptionSchedule.ID to fixture.id,
                SubscriptionSchedule.CONFIG to SubscriptionSchedule.fingerprint(fixture.subscription!!)))
                .addTag(SubscriptionSchedule.TAG).addTag(uniqueName)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        }
        val request = builder.clearNextScheduleTimeOverride().build()
        remote.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
            .get(10, TimeUnit.SECONDS)
    }

    private fun dumpThreads(reason: String) {
        val destination = File(context.filesDir, "work-upgrade-threads-${System.currentTimeMillis()}.txt")
        try {
            val main = android.os.Looper.getMainLooper().thread
            destination.bufferedWriter().use { output ->
                output.appendLine("reason=$reason pid=${android.os.Process.myPid()}")
                Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
                    output.appendLine("\nthread=${thread.name} id=${thread.id} state=${thread.state} main=${thread === main}")
                    stack.forEach { output.appendLine("    at $it") }
                }
            }
            stage("diagnostic saved: ${destination.name}")
        } catch (error: Exception) {
            stage("diagnostic failed: ${error.javaClass.simpleName}")
        }
    }

    private fun probeMainThread() {
        stage("verify: main-thread probe posted")
        val responsive = CountDownLatch(1)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val probe = Runnable { responsive.countDown() }
        check(handler.post(probe)) { "Main looper rejected probe" }
        if (!responsive.await(2, TimeUnit.SECONDS)) {
            handler.removeCallbacks(probe)
            dumpThreads("main-thread probe did not execute within 2 seconds")
            throw AssertionError("Production main thread is unresponsive; worker success cannot count as PASS")
        }
        stage("verify: main-thread probe completed")
    }

    private fun awaitFinished(id: UUID): WorkInfo {
        // No runOnMainSync/LiveData registration: those can wait forever when the main
        // looper is the failure under test. Future queries are bounded independently.
        probeMainThread()
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        var lastState: WorkInfo.State? = null
        var nextProbe = android.os.SystemClock.elapsedRealtime() + 5_000
        stage("verify: bounded worker queries begin")
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val info = try {
                manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)
            } catch (error: Exception) {
                dumpThreads("bounded WorkInfo future failed: ${error.javaClass.simpleName}")
                throw error
            }
            if (info?.state != lastState) {
                lastState = info?.state
                stage("verify: bounded WorkInfo query state=$lastState")
            }
            if (info != null && info.state.isFinished) {
                probeMainThread()
                return info
            }
            if (android.os.SystemClock.elapsedRealtime() >= nextProbe) {
                probeMainThread()
                nextProbe = android.os.SystemClock.elapsedRealtime() + 5_000
            }
            // Bounded polling cadence on the instrumentation thread, not the UI thread.
            CountDownLatch(1).await(250, TimeUnit.MILLISECONDS)
        }
        dumpThreads("worker did not finish within 45 seconds; lastState=$lastState")
        throw AssertionError("Real Worker did not reach a terminal state; lastState=$lastState")
    }

    private fun cleanup() {
        val state = load()
        var recoveryFailure: Exception? = null
        fun recordFailure(error: Exception) {
            stage("cleanup: ${error.message}; continuing fixture and preference restoration")
            val previous = recoveryFailure
            if (previous == null) recoveryFailure = error else previous.addSuppressed(error)
        }
        uniqueName = state.optString("currentWorkName", SubscriptionSchedule.LEGACY)
        val periodicId = state.optString("currentPeriodicId", state.optString("periodicId")).takeIf { it.isNotEmpty() }?.let(UUID::fromString)
        var ownsPeriodic = false
        // A lost/cancelled/replaced UUID must not cause UPDATE to create or modify another
        // task, and must not prevent restoration of user data below.
        try {
            if (periodicId != null) {
                clearPeriodicOverride(periodicId) // Checks active unique identity before UPDATE.
                ownsPeriodic = true
            } else stage("cleanup: no recorded periodic UUID; leaving scheduling unchanged")
        } catch (error: Exception) { recordFailure(error) }
        try { cancelFixtureRequests(state) } catch (error: Exception) { recordFailure(error) }
        val fixtureId = state.getLong("groupId")
        if (fixtureId > 0) {
            SagerDatabase.proxyDao.deleteByGroup(fixtureId)
            SagerDatabase.groupDao.deleteById(fixtureId)
        }
        val subscriptions = state.getJSONArray("subscriptions")
        for (index in 0 until subscriptions.length()) {
            val row = subscriptions.getJSONObject(index)
            SagerDatabase.groupDao.getById(row.getLong("id"))?.let { group ->
                group.subscription = KryoConverters.subscriptionDeserialize(decode(row.getString("bytes")))
                SagerDatabase.groupDao.updateGroup(group)
            }
        }
        val config = state.getJSONArray("config")
        PublicDatabase.kvPairDao.reset()
        for (index in 0 until config.length()) {
            val row = config.getJSONObject(index)
            PublicDatabase.kvPairDao.put(KeyValuePair(row.getString("key")).also {
                it.valueType = row.getInt("type"); it.value = decode(row.getString("bytes"))
            })
        }
        if (ownsPeriodic) {
            try {
                // Recheck after data restoration: never reconfigure a replacement task.
                val live = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
                    .filterNot { it.state.isFinished }
                check(live.map { it.id } == listOf(periodicId)) {
                    "Old periodic disappeared or was replaced during cleanup; leaving scheduling unchanged"
                }
                runBlocking { SubscriptionUpdater.reconfigureUpdaterOrThrow() }
            } catch (error: Exception) { recordFailure(error) }
        }
        context.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(context, BootReceiver::class.java), state.getInt("receiverState"),
            android.content.pm.PackageManager.DONT_KILL_APP)
        // Retain recovery material if scheduling/cancellation still needs attention.
        recoveryFailure?.let { throw it }
        checkpoint.delete()
    }
}
