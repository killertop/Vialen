package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Physical-device lifecycle checks; these do not establish scheduling/Worker success. */
@RunWith(AndroidJUnit4::class)
class BootReceiverLifecycleNativeTest {
    @get:Rule val foreground = BenchmarkForegroundRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val component get() = ComponentName(context, BootReceiver::class.java)
    private val pm get() = context.packageManager

    @Test fun orderedBroadcastWaitsForSchedulingLock() = exercise(deadline = false)
    @Test fun orderedBroadcastFinishesAtDeadlineWhileLockIsHeld() = exercise(deadline = true)

    private fun requireNoUserSchedule() {
        check(runBlocking { SagerDatabase.groupDao.subscriptions() }.none { it.subscription!!.autoUpdate }) {
            "Disable automatic subscriptions on a dedicated test device before this test; no user schedule was cancelled"
        }
        val manager = WorkManager.getInstance(context)
        val work = manager.getWorkInfosForUniqueWork("SubscriptionUpdater").get(5, TimeUnit.SECONDS)
        check(work.none { !it.state.isFinished }) {
            "Active SubscriptionUpdater work exists; finish staged upgrade/fixture cleanup before this test"
        }
        // Also reject independently enqueued copies of the production worker.
        val tagged = manager.getWorkInfosByTag("io.nekohasekai.sagernet.bg.SubscriptionUpdater\$UpdateTask")
            .get(5, TimeUnit.SECONDS)
        check(tagged.none { !it.state.isFinished }) { "Active subscription worker exists; refusing to interfere" }
    }

    private data class Delivery(val finished: CountDownLatch, val sentAt: Long, val finishedAt: AtomicLong)

    private fun send(): Delivery {
        pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP)
        val result = Delivery(CountDownLatch(1), SystemClock.uptimeMillis(), AtomicLong())
        context.sendOrderedBroadcast(Intent("${context.packageName}.test.BOOT_LIFECYCLE")
            .setComponent(component).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.finishedAt.set(SystemClock.uptimeMillis())
                    result.finished.countDown()
                }
            }, Handler(Looper.getMainLooper()), 0, null, null)
        return result
    }

    private fun awaitDisabled() {
        val until = SystemClock.uptimeMillis() + 3_000
        while (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            SystemClock.uptimeMillis() < until) CountDownLatch(1).await(25, TimeUnit.MILLISECONDS)
        assertEquals("Receiver must actually execute and disable itself with persist=false",
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component))
    }

    private fun exercise(deadline: Boolean) {
        requireNoUserSchedule()
        assertEquals("Receiver must run outside the instrumentation process", "${context.packageName}:bg",
            pm.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).processName)
        val dao = PublicDatabase.kvPairDao
        val before = dao[Key.PERSIST_ACROSS_REBOOT]?.let { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        }
        val componentBefore = pm.getComponentEnabledSetting(component)
        // Production uses app.filesDir, not a guessed device-protected storage directory.
        val file = File(app.filesDir, "subscription-schedule.lock")
        var held: FileLock? = null
        var sent = false
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        RandomAccessFile(file, "rw").channel.use { channel ->
            attempt {
                DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, false)
                held = channel.tryLock()
                check(held != null) { "Production scheduling lock is busy; retry only when device is idle" }
                requireNoUserSchedule()
                sent = true
                val delivery = send()
                awaitDisabled()
                assertFalse("Ordered broadcast completed before scheduling could acquire the lock",
                    delivery.finished.await(2, TimeUnit.SECONDS))
                if (deadline) {
                    assertTrue("8-second broadcast deadline did not finish within the bounded deadline wait",
                        delivery.finished.await(9, TimeUnit.SECONDS))
                    assertTrue("Test must retain production lock through deadline callback", held!!.isValid)
                    val elapsed = delivery.finishedAt.get() - delivery.sentAt
                    assertTrue("Deadline callback outside expected 7.5–9.5 second window: ${elapsed}ms",
                        elapsed in 7_500L..9_500L)
                } else {
                    held!!.release(); held = null
                    assertTrue("Ordered broadcast did not complete within 5 seconds after lock release",
                        delivery.finished.await(5, TimeUnit.SECONDS))
                    assertTrue("Completion hit deadline instead of completing scheduling",
                        delivery.finishedAt.get() - delivery.sentAt < 7_500)
                }
            }
            attempt { held?.release(); held = null }
            if (sent) attempt {
                // A final callback alone does not establish coroutine completion. This second
                // invocation uses the same :bg scheduling Mutex; completion before its own
                // deadline proves the previous invocation has left that critical section.
                val barrier = send()
                awaitDisabled()
                assertTrue("Cleanup scheduler barrier did not complete before its deadline; coroutine exit unverified",
                    barrier.finished.await(6, TimeUnit.SECONDS))
                assertTrue("Cleanup barrier reached deadline rather than draining scheduler",
                    barrier.finishedAt.get() - barrier.sentAt < 7_000)
                requireNoUserSchedule()
            }
            attempt {
                if (before == null) dao.delete(Key.PERSIST_ACROSS_REBOOT) else dao.put(before)
                val after = dao[Key.PERSIST_ACROSS_REBOOT]
                check(if (before == null) after == null else after != null &&
                    after.valueType == before.valueType && after.value.contentEquals(before.value)) {
                    "Raw persistAcrossReboot preference restoration failed"
                }
            }
            attempt {
                pm.setComponentEnabledSetting(component, componentBefore, PackageManager.DONT_KILL_APP)
                assertEquals("Receiver state restoration failed", componentBefore,
                    pm.getComponentEnabledSetting(component))
            }
        }
        failure?.let { throw it }
    }
}
