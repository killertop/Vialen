package io.nekohasekai.sagernet

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.security.MessageDigest

/** An outer rule: the snapshot surrounds @Before, the test, and @After. */
class ProfileSelectionStateRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val activitiesBefore = activities().toSet()
            withSnapshot(PublicDatabase.instance, description.displayName, {
                base.evaluate()
            }, {
                // Existing test teardown stops the service and disconnects its connection first.
                // Close only activities created by this case, including already-finishing owners.
                val owned = activities().filter { it !in activitiesBefore }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.runOnMainSync { owned.forEach { it.finish() } }
                val deadline = System.nanoTime() + 5_000_000_000L
                while (owned.any { !it.isDestroyed } && System.nanoTime() < deadline) Thread.sleep(50)
                check(owned.all { it.isDestroyed }) { "Test-owned Activities did not reach DESTROYED" }
                instrumentation.waitForIdleSync()
                // This also covers setup failures before a binder was acquired and async stop delivery.
                awaitNoStartedService()
                println("PROFILE_STATE cleanup activities_destroyed=${owned.size} started_service=false")
            })
        }
    }

    fun stopAndAwait(connection: SagerConnection) {
        SagerNet.stopService()
        val binder = connection.service ?: return // Outer rule still checks the system service dump.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (binder.state != BaseService.State.Stopped.ordinal && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        check(binder.state == BaseService.State.Stopped.ordinal) { "Service did not reach Stopped before disconnect" }
    }

    /** Inline try/finally tests must retain their original assertion if teardown also fails. */
    suspend fun preservingFailure(body: suspend () -> Unit, cleanup: suspend () -> Unit) {
        var failure: Throwable? = null
        try {
            body()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            cleanup()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    /** Complete independent teardown steps, retaining every failure. */
    suspend fun cleanupSteps(vararg steps: suspend () -> Unit) {
        var failure: Throwable? = null
        for (step in steps) {
            try { step() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun activities(): List<Activity> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result = emptyList<Activity>()
        instrumentation.runOnMainSync {
            result = Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filter {
                it.packageName == instrumentation.targetContext.packageName &&
                    (it is MainActivity || it is VpnRequestActivity)
            }.distinct()
        }
        return result
    }

    private fun awaitNoStartedService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun started(): Boolean {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("dumpsys activity services ${instrumentation.targetContext.packageName}"))
                .bufferedReader().use { it.readText() }
            check(dump.contains("ACTIVITY MANAGER SERVICES")) { "Cannot verify service cleanup" }
            return dump.contains("startRequested=true") || dump.contains("fgRequired=true") ||
                dump.contains("isForeground=true")
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (started() && System.nanoTime() < deadline) Thread.sleep(50)
        check(!started()) { "Started or foreground service remains after test teardown" }
    }

    companion object {
        internal val keys = listOf(Key.PROFILE_CURRENT, Key.PROFILE_GROUP, Key.PROFILE_ID)

        internal fun withSnapshot(db: PublicDatabase, label: String, body: () -> Unit, cleanup: () -> Unit = {}) {
            val dao = db.keyValuePairDao()
            val before = linkedMapOf<String, KeyValuePair?>()
            db.runInTransaction {
                keys.forEach { key ->
                    before[key] = dao[key]?.let { row ->
                        KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
                    }
                }
            }
            fun report(stage: String) {
                keys.forEach { key ->
                    val row = dao[key]
                    val hash = row?.value?.let { value ->
                        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
                    } ?: "absent"
                    println("PROFILE_STATE case=$label stage=$stage key=$key present=${row != null} type=${row?.valueType} sha256=$hash")
                }
            }
            report("before")
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            attempt(body)
            var cleanupPassed = true
            attempt {
                try { cleanup() } catch (error: Throwable) {
                    cleanupPassed = false
                    throw error
                }
            }
            // Restore even if cleanup fails; that failure remains a test failure, never a clean receipt.
            attempt {
                db.runInTransaction {
                    before.forEach { (key, row) -> if (row == null) dao.delete(key) else dao.put(row) }
                }
            }
            attempt {
                report("after")
                before.forEach { (key, expected) ->
                    val actual = dao[key]
                    check(if (expected == null) actual == null else actual != null &&
                        actual.valueType == expected.valueType && actual.value.contentEquals(expected.value)) {
                        "Profile selection restoration mismatch: $key"
                    }
                }
                println("PROFILE_STATE case=$label raw_restore_verified=true cleanup_passed=$cleanupPassed")
            }
            failure?.let { throw it }
        }
    }
}
