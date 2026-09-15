package io.nekohasekai.sagernet

import android.content.Context
import androidx.work.*
import androidx.work.testing.*
import io.nekohasekai.sagernet.bg.SubscriptionSchedule
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SubscriptionWorkConstraintsTest {
    @Test fun networkWaitsForConstraintsWhileDocumentRunsOfflineAndPeriodicRetryIsIsolated() {
        val context = RuntimeEnvironment.getApplication() as Context
        val calls = ConcurrentHashMap<Long, Int>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(c: Context, name: String, p: WorkerParameters) = object : Worker(c, p) {
                    override fun doWork(): Result {
                        val id = inputData.getLong(SubscriptionSchedule.ID, -1)
                        val n = calls.merge(id, 1, Int::plus)!!
                        return if (id == 1L && n == 1) Result.retry() else Result.success()
                    }
                }
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val manager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        fun request(id: Long, link: String) = SubscriptionSchedule.request(ProxyGroup(id = id, type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().applyDefaultValues().apply {
                this.link = link; autoUpdate = true; autoUpdateDelay = 15; lastUpdated = 0
            }), 10000)
        val network = request(1, "https://example.test/sub")
        val document = request(2, "content://fixture/document")
        try {
            manager.enqueue(listOf(network, document)).result.get(5, TimeUnit.SECONDS)
            assertNull(calls[1]); assertEquals(1, calls[2])
            driver.setAllConstraintsMet(network.id)
            assertEquals(1, calls[1]); assertEquals(1, calls[2])
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(network.id).get()!!.state)
            assertEquals(1, manager.getWorkInfoById(network.id).get()!!.runAttemptCount)
            // Periodic success also returns to ENQUEUED; it is not a terminal SUCCEEDED.
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(document.id).get()!!.state)
        } finally { manager.cancelAllWork().result.get(5, TimeUnit.SECONDS); WorkManagerTestInitHelper.closeWorkDatabase() }
    }
}
