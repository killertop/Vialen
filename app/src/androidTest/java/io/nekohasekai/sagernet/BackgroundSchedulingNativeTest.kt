package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.bg.SubscriptionSchedule
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real WorkManager/Room in the isolated package. Requests stay not-due; no VPN or network changes. */
@RunWith(AndroidJUnit4::class)
class BackgroundSchedulingNativeTest {
    @get:Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule()
    @Test fun legacyMigrationPreservesUnchangedIdsAndReplacesOnlyChangedSources() = runBlocking {
        check(BuildConfig.APPLICATION_ID.endsWith(".debug"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val remote = RemoteWorkManager.getInstance(context)
        val db = SagerDatabase.instance
        check(db.groupDao().subscriptions().none { it.subscription!!.autoUpdate }) { "Requires no existing automatic subscriptions" }
        fun active() = remote.getWorkInfos(WorkQuery.fromTags(listOf(SubscriptionSchedule.TAG))).get(10,TimeUnit.SECONDS).filterNot { it.state.isFinished }
        check(active().isEmpty()) { "Existing scheduled work must be preserved; fixture refused" }
        val old = PeriodicWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java, 1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        val ids = mutableListOf<Long>()
        fun group(link: String): ProxyGroup = ProxyGroup(name="schedule-fixture", type=GroupType.SUBSCRIPTION,
            subscription=SubscriptionBean().apply {
                initializeDefaultValues();this.link=link;autoUpdate=true;autoUpdateDelay=1440
                lastUpdated=(System.currentTimeMillis()/1000).toInt()
            }).apply { id=db.groupDao().createGroup(this);ids.add(id) }
        try {
            val web=group("https://example.test/sub")
            val document=group("content://synthetic/document")
            remote.enqueueUniquePeriodicWork(SubscriptionSchedule.LEGACY, ExistingPeriodicWorkPolicy.KEEP, old).get(10,TimeUnit.SECONDS)
            SubscriptionUpdater.reconfigureUpdaterOrThrow()
            assertEquals(WorkInfo.State.CANCELLED,remote.getWorkInfos(WorkQuery.fromIds(listOf(old.id))).get().single().state)
            val first=active();assertEquals(2,first.size)
            val webWork=first.single { SubscriptionSchedule.name(web) in it.tags }
            val documentWork=first.single { SubscriptionSchedule.name(document) in it.tags }
            assertEquals(NetworkType.CONNECTED,webWork.constraints.requiredNetworkType)
            assertEquals(NetworkType.NOT_REQUIRED,documentWork.constraints.requiredNetworkType)
            web.subscription!!.lastUpdated += 60 // Remote metadata must not move the WorkSpec schedule.
            db.groupDao().updateGroup(web)
            SubscriptionUpdater.reconfigureUpdaterOrThrow()
            assertEquals(first.associate { it.id to it.nextScheduleTimeMillis },
                active().associate { it.id to it.nextScheduleTimeMillis })
            web.subscription!!.link="content://synthetic/changed"
            db.groupDao().updateGroup(web)
            SubscriptionUpdater.reconfigureUpdaterOrThrow()
            val changed=active();assertEquals(2,changed.size)
            assertTrue(changed.all { it.constraints.requiredNetworkType==NetworkType.NOT_REQUIRED })
            assertTrue(changed.any { it.id==documentWork.id });assertTrue(changed.none { it.id==webWork.id })
            document.subscription!!.autoUpdate=false;db.groupDao().updateGroup(document)
            SubscriptionUpdater.reconfigureUpdaterOrThrow();assertEquals(1,active().size)
            db.groupDao().deleteById(web.id)
            SubscriptionUpdater.reconfigureUpdaterOrThrow();assertTrue(active().isEmpty())
        } finally {
            ids.forEach { db.groupDao().deleteById(it) }
            remote.cancelWorkById(old.id).get(10,TimeUnit.SECONDS)
            SubscriptionUpdater.reconfigureUpdaterOrThrow()
        }
    }
    @Test fun disablingRunningWorkCancelsNativeBodyAndPreventsCommit() = runBlocking {
        check(BuildConfig.APPLICATION_ID.endsWith(".debug"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val remote = RemoteWorkManager.getInstance(context)
        val db = SagerDatabase.instance
        check(db.groupDao().subscriptions().none { it.subscription!!.autoUpdate })
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val bodyStarted = java.util.concurrent.CountDownLatch(1)
        val bodyClosed = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val socketRef = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
        val serving = kotlin.concurrent.thread(name = "subscription-stop-fixture", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socketRef.set(socket)
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\nx".toByteArray())
                        flush()
                    }
                    bodyStarted.countDown()
                    check(socket.getInputStream().read() == -1) { "Cancelled request did not close body socket" }
                    bodyClosed.countDown()
                }
            } catch (error: Throwable) { failure.set(error); bodyStarted.countDown(); bodyClosed.countDown() }
        }
        val group = ProxyGroup(name = "cancel-fixture", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply {
                initializeDefaultValues(); autoUpdate = true; autoUpdateDelay = 15; lastUpdated = 0
                link = "http://127.0.0.1:${server.localPort}/held-body"
            }).apply { id = db.groupDao().createGroup(this) }
        val name = SubscriptionSchedule.name(group)
        val request = OneTimeWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java)
            .setInputData(workDataOf(SubscriptionSchedule.ID to group.id,
                SubscriptionSchedule.CONFIG to SubscriptionSchedule.fingerprint(group.subscription!!)))
            .addTag(SubscriptionSchedule.TAG).addTag(name)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        try {
            remote.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).get(10, TimeUnit.SECONDS)
            assertTrue("Real worker must reach held native body", bodyStarted.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
            group.subscription!!.autoUpdate = false
            db.groupDao().updateGroup(group)
            SubscriptionUpdater.reconfigureUpdaterOrThrow()
            assertTrue("WorkManager stop must reach native body", bodyClosed.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Cancellation fixture failed", it) }
            assertEquals(WorkInfo.State.CANCELLED,
                remote.getWorkInfos(WorkQuery.fromIds(listOf(request.id))).get(10, TimeUnit.SECONDS).single().state)
            assertEquals(0, db.groupDao().getById(group.id)!!.subscription!!.lastUpdated)
            assertTrue(db.proxyDao().getByGroup(group.id).isEmpty())
        } finally {
            remote.cancelWorkById(request.id).get(10, TimeUnit.SECONDS)
            socketRef.get()?.close(); server.close(); serving.join(2000)
            db.proxyDao().deleteByGroup(group.id); db.groupDao().deleteById(group.id)
            SubscriptionUpdater.reconfigureUpdaterOrThrow()
        }
    }

}
